output "alb_url" {
  value = "http://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

# Tags the image with the current commit. Pass the same value as -var image_tag=... on apply.
output "docker_push_commands" {
  value = <<-EOT
    TAG=$(git rev-parse --short HEAD)
    aws ecr get-login-password --region ${var.region} | docker login --username AWS --password-stdin ${split("/", aws_ecr_repository.app.repository_url)[0]}
    docker build --platform linux/${lower(var.cpu_architecture) == "arm64" ? "arm64" : "amd64"} -t ${aws_ecr_repository.app.repository_url}:$TAG .
    docker push ${aws_ecr_repository.app.repository_url}:$TAG
  EOT
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "peers" {
  value = local.peers
}

output "region" {
  value = var.region
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "runner_instance_id" {
  value = var.enable_runner ? aws_instance.runner[0].id : null
}

output "runner_ssm_command" {
  value = var.enable_runner ? "aws ssm start-session --region ${var.region} --target ${aws_instance.runner[0].id}" : null
}

# Everything that changes a measurement without changing the code. Copy it into docs/EXPERIMENTS.md with
# each run: terraform output -json run_config
output "run_config" {
  value = {
    image_tag                 = var.image_tag
    hotshield_mode            = var.hotshield_mode
    cache_ttl                 = var.cache_ttl
    tombstone_ttl             = var.tombstone_ttl
    db_delay                  = var.db_delay
    db_pool_size              = var.db_pool_size
    forward_timeout           = var.forward_timeout
    enable_tracing            = var.enable_tracing
    trace_ratio               = var.enable_tracing ? var.trace_ratio : 0
    task_cpu                  = var.task_cpu
    task_memory               = var.task_memory
    db_instance_class         = var.db_instance_class
    runner_instance_type      = var.enable_runner ? var.runner_instance_type : null
    health_check_interval     = var.health_check_interval
    health_check_grace_period = local.health_check_grace_period
    deregistration_delay      = var.deregistration_delay
  }
}
