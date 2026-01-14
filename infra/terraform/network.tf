data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "main" {
  cidr_block           = "10.40.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "hotshield" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

# Tasks live here with public IPs so they can pull from ECR and ship logs without a NAT gateway,
# which bills by the hour. Their security group still only admits the ALB and each other.
resource "aws_subnet" "public" {
  count = 2

  vpc_id                  = aws_vpc.main.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  map_public_ip_on_launch = true

  tags = { Name = "hotshield-public-${count.index}" }
}

# No route to the internet at all. RDS needs two subnets in different AZs even when single-AZ.
resource "aws_subnet" "private" {
  count = 2

  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, 10 + count.index)

  tags = { Name = "hotshield-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "alb" {
  name   = "hotshield-alb"
  vpc_id = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = var.allowed_cidr
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_security_group" "tasks" {
  name   = "hotshield-tasks"
  vpc_id = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Non-owners forward misses to the owner on /internal/items/{id}.
resource "aws_vpc_security_group_ingress_rule" "tasks_from_peers" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Open egress covers ECR, CloudWatch Logs, SSM, X-Ray, peers and RDS.
resource "aws_vpc_security_group_egress_rule" "tasks_all" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "rds" {
  name   = "hotshield-rds"
  vpc_id = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_tasks" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# The runner polls each node's /actuator endpoints and can hit a node directly, bypassing the ALB.
resource "aws_vpc_security_group_ingress_rule" "tasks_from_runner" {
  count = var.enable_runner ? 1 : 0

  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.runner[0].id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Seeding and pg_stat_statements_reset() between runs. The DB has no public address, so this is the way in.
resource "aws_vpc_security_group_ingress_rule" "rds_from_runner" {
  count = var.enable_runner ? 1 : 0

  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.runner[0].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
