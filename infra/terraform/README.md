# AWS infrastructure (milestones 8–11)

**Run `terraform destroy` at the end of every session, even if you plan to run again tomorrow.**
These bill by the hour for as long as they exist, idle or not:

- RDS instance (`db.m7g.large` by default) and its gp3 storage
- Application Load Balancer
- Three Fargate tasks (1 vCPU / 2 GB each)
- Public IPv4 addresses (one per task, plus the ALB's)

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

## Deploy

```sh
cd infra/terraform
terraform init
terraform apply -target=aws_ecr_repository.app -var run=<label>   # repo first, so there's somewhere to push
terraform output -raw docker_push_commands                         # run these from the repo root
terraform apply -var run=<label>
```

Useful variables: `hotshield_mode`, `db_instance_class`, `enable_tracing` (adds an ADOT sidecar exporting
to X-Ray; leave off for benchmark runs), `allowed_cidr` (narrow it to the k6 instance's IP).

## Destroy and verify

```sh
terraform destroy -var run=<label>
aws resourcegroupstaggingapi get-resources --region us-east-1 \
  --tag-filters Key=Project,Values=hotshield --query 'ResourceTagMappingList[].ResourceARN'
```

The second command should print `[]`. The tagging API can list a deleted resource for a while after it is
gone, so if something shows up, check it directly (e.g. `aws rds describe-db-instances`) before worrying.
Also check for anything created by hand outside Terraform, like the k6 EC2 instance.
