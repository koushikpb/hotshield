output "alb_url" {
  value = "http://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "docker_push_commands" {
  value = <<-EOT
    aws ecr get-login-password --region ${var.region} | docker login --username AWS --password-stdin ${split("/", aws_ecr_repository.app.repository_url)[0]}
    docker build --platform linux/${lower(var.cpu_architecture) == "arm64" ? "arm64" : "amd64"} -t ${aws_ecr_repository.app.repository_url}:${var.image_tag} .
    docker push ${aws_ecr_repository.app.repository_url}:${var.image_tag}
  EOT
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "peers" {
  value = local.peers
}

output "deregistration_delay" {
  description = "Recorded with the milestone 11 kill experiment."
  value       = var.deregistration_delay
}

output "health_check_interval" {
  description = "Recorded with the milestone 11 kill experiment."
  value       = var.health_check_interval
}
