# AWS infrastructure (milestones 8–11)

**Run `terraform destroy` at the end of every session, even if you plan to run again tomorrow.**
These bill by the hour for as long as they exist, idle or not:

- RDS instance (`db.m7g.large` by default) and its gp3 storage
- Application Load Balancer
- Three Fargate tasks (1 vCPU / 2 GB each)
- The runner EC2 instance (`c7g.large` by default, `enable_runner=false` to skip it) and its EBS volume
- Public IPv4 addresses (one per task, the runner's, plus the ALB's)

These bill monthly, small or free: the SSM SecureString (standard tier, free), ECR image storage
(per GB-month, the repo is force-deleted on destroy), CloudWatch log storage (7-day retention).

There is no NAT gateway on purpose: tasks sit in public subnets with public IPs so they can reach ECR,
CloudWatch Logs and SSM. Their security group only admits the ALB and the other tasks.

## Layout

| File | What |
|---|---|
| `network.tf` | VPC, 2 public subnets (ALB, tasks), 2 private subnets (RDS, no internet route), security groups |
| `rds.tf` | PostgreSQL 16 single-AZ, `pg_stat_statements` preloaded, password in SSM |
| `ecr.tf` | Image repository |
| `discovery.tf` | Cloud Map namespace `hotshield.local`, one A record per node |
| `ecs.tf` | Cluster, log group, IAM roles, one task definition and one service per node |
| `alb.tf` | ALB, target group shared by all three nodes, `/internal/*` blocked |
| `runner.tf` | EC2 load generator in the nodes' AZ with k6, `psql` and git; reached via SSM Session Manager |

## Deploy

`allowed_cidr` and `image_tag` have no defaults. Use your own IP as `allowed_cidr` (`curl -s https://checkip.amazonaws.com`
plus `/32`) and the short git SHA as `image_tag`. ECR tags are immutable, so commit before you build: a tag is pushed once.

```sh
cd infra/terraform
terraform init
TAG=$(git rev-parse --short HEAD)
VARS="-var run=<label> -var allowed_cidr=<your-ip>/32 -var image_tag=$TAG"
terraform apply -target=aws_ecr_repository.app $VARS   # repo first, so there's somewhere to push
terraform output -raw docker_push_commands              # run these from the repo root
terraform apply $VARS
```

Useful variables: `hotshield_mode`, `db_instance_class`, `db_pool_size`, `forward_timeout`, `tombstone_ttl`,
`enable_tracing` (adds an ADOT sidecar exporting to X-Ray; leave off for benchmark runs) and `trace_ratio`.
Record `terraform output -json run_config` with every run in `docs/EXPERIMENTS.md`.

Changing `hotshield_mode` stops each node's old task before starting the new one, so expect a short gap
per node; `apply` waits until all three services are steady again.

## Running load from the runner

The nodes, RDS and the runner share one AZ so network distance is the same for every node. Run k6 from the
runner, not your laptop: the internet path adds latency and variance the experiment isn't about.

```sh
$(terraform output -raw runner_ssm_command)    # needs the Session Manager plugin for the AWS CLI
sudo su - ec2-user
git clone <your repo url> hotshield && cd hotshield
k6 run -e BASE_URL=<alb_url> loadtest/<scenario>.js
```

For a recorded run, use `loadtest/cloud_bench.sh` instead of plain k6: it resets `pg_stat_statements`, polls the
nodes and prints the same report as `bench.sh`. Pass the node names, not task IPs, so the poller follows a
replaced task:

```sh
BASE_URL=<alb_url> PGHOST=<rds_endpoint> COMMIT=<sha> \
  NODE_URLS="http://node-a.hotshield.local:8080 http://node-b.hotshield.local:8080 http://node-c.hotshield.local:8080" \
  loadtest/cloud_bench.sh aws-zipf-distributed-500 distributed zipf.js 500
```

The poller sums counters across nodes, so a replaced task's counters (starting at 0) make the sums drop. For a
run with a task replacement, count only the positive steps between samples. That gives lower bounds: it loses
what a node served while the poller couldn't reach it and the other nodes' counts in the step that was dropped.

If the commits you want to run aren't pushed, the runner can't clone them. `aws ssm send-command` with a
base64 `git archive HEAD loadtest | gzip` works for the scripts (about 27 KB), and SSM returns at most 24,000
characters of output per command, so copy results back in chunks.

The runner can also reach each node directly on `:8080` (via its task IP) and RDS on 5432. To seed or to reset
`pg_stat_statements` between runs, run `loadtest/seed.sql` from the runner with `psql -f`, using `rds_endpoint`,
user `hotshield` and the password from SSM:

```sh
export PGPASSWORD=$(aws ssm get-parameter --region <region> --name /hotshield/db-password \
  --with-decryption --query Parameter.Value --output text)
psql -h <rds_endpoint> -U hotshield hotshield
```

## Destroy and verify

Read the region and VPC id before destroying; the outputs are gone afterwards.

```sh
REGION=$(terraform output -raw region)
VPC=$(terraform output -raw vpc_id)
terraform destroy $VARS

aws resourcegroupstaggingapi get-resources --region $REGION \
  --tag-filters Key=Project,Values=hotshield --query 'ResourceTagMappingList[].ResourceARN'
aws ecs list-tasks --region $REGION --cluster hotshield
aws ec2 describe-network-interfaces --region $REGION --filters Name=vpc-id,Values=$VPC \
  --query 'NetworkInterfaces[].NetworkInterfaceId'
aws ec2 describe-instances --region $REGION --filters Name=tag:Project,Values=hotshield \
  Name=instance-state-name,Values=pending,running,stopping,stopped --query 'Reservations[].Instances[].InstanceId'
```

The tagging query and the instance query should print `[]`. `list-tasks` should fail with
`ClusterNotFoundException` (the cluster is gone) or print no task ARNs. The network-interface query should print
`[]`: a leftover interface means a task, the ALB or RDS is still there, and it keeps its public IPv4 billing.
The tagging API can list a deleted resource for a while after it is gone, so if something shows up, check it
directly (e.g. `aws rds describe-db-instances --region $REGION`) before worrying.
