locals {
  k6_version = "v2.3.0"
}

# Resolved at plan time, so each apply gets the current patched AL2023. The AMI and the k6 download below
# are arm64, so runner_instance_type has to stay a Graviton (g) type.
data "aws_ssm_parameter" "al2023_arm64" {
  count = var.enable_runner ? 1 : 0

  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

# Session Manager instead of SSH: no key pair to manage and no inbound port open to the internet.
resource "aws_iam_role" "runner" {
  count = var.enable_runner ? 1 : 0

  name               = "hotshield-runner"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "runner_ssm" {
  count = var.enable_runner ? 1 : 0

  role       = aws_iam_role.runner[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# So psql on the runner can fetch the DB password itself. Same aws/ssm key note as the execution role.
resource "aws_iam_role_policy" "runner_db_password" {
  count = var.enable_runner ? 1 : 0

  name = "read-db-password"
  role = aws_iam_role.runner[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = aws_ssm_parameter.db_password.arn
    }]
  })
}

resource "aws_iam_instance_profile" "runner" {
  count = var.enable_runner ? 1 : 0

  name = "hotshield-runner"
  role = aws_iam_role.runner[0].name
}

resource "aws_security_group" "runner" {
  count = var.enable_runner ? 1 : 0

  name   = "hotshield-runner"
  vpc_id = aws_vpc.main.id
}

# Outbound covers the SSM agent, GitHub and dnf for setup, the ALB, the nodes and RDS. No ingress rules.
resource "aws_vpc_security_group_egress_rule" "runner_all" {
  count = var.enable_runner ? 1 : 0

  security_group_id = aws_security_group.runner[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# The ALB is internet-facing, so the runner reaches it through its public IP and the ALB sees that address,
# not the runner's security group. A security-group reference would never match here.
resource "aws_vpc_security_group_ingress_rule" "alb_from_runner" {
  count = var.enable_runner ? 1 : 0

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "${aws_instance.runner[0].public_ip}/32"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

# Same AZ as the nodes and the DB (public subnet 0), so the generator adds the same network distance to
# every node.
resource "aws_instance" "runner" {
  count = var.enable_runner ? 1 : 0

  ami                    = data.aws_ssm_parameter.al2023_arm64[0].value
  instance_type          = var.runner_instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.runner[0].id]
  iam_instance_profile   = aws_iam_instance_profile.runner[0].name

  metadata_options {
    http_tokens = "required"
  }

  user_data_replace_on_change = true
  user_data                   = <<-EOT
    #!/bin/bash
    set -euo pipefail
    dnf install -y git postgresql16
    curl -fsSL https://github.com/grafana/k6/releases/download/${local.k6_version}/k6-${local.k6_version}-linux-arm64.tar.gz \
      | tar -xz -C /usr/local/bin --strip-components=1 k6-${local.k6_version}-linux-arm64/k6
  EOT

  tags = { Name = "hotshield-runner" }
}
