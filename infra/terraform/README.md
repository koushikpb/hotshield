# AWS infrastructure (milestones 8–11)

Planned modules: network (VPC, 2 public + 2 private subnets, SGs), alb, ecs (cluster, task def, service of 3 Fargate tasks),
rds (PostgreSQL 16, single-AZ, smallest class that doesn't throttle the benchmark), observability (CloudWatch log groups, ADOT collector sidecar).

Always run `terraform destroy` after a benchmark session.
