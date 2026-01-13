variable "region" {
  type    = string
  default = "us-east-1"
}

variable "run" {
  description = "Label for this benchmark session, copied into the Run tag on every resource."
  type        = string
  default     = "dev"
}

variable "allowed_cidr" {
  description = "Who may reach the ALB. Narrow this to your k6 instance or your own IP (x.x.x.x/32)."
  type        = string
  default     = "0.0.0.0/0"
}

variable "hotshield_mode" {
  description = "direct | cache | coalesce | distributed"
  type        = string
  default     = "distributed"

  validation {
    condition     = contains(["direct", "cache", "coalesce", "distributed"], var.hotshield_mode)
    error_message = "hotshield_mode must be one of direct, cache, coalesce, distributed."
  }
}

variable "cache_ttl" {
  type    = string
  default = "30s"
}

variable "db_delay" {
  description = "Artificial DB delay for herd runs only, see loadtest/README.md."
  type        = string
  default     = "0ms"
}

variable "image_tag" {
  type    = string
  default = "latest"
}

# A Mac with Apple Silicon builds arm64 images, and Graviton Fargate is also the cheaper option.
# Switch to X86_64 only together with `docker build --platform linux/amd64`.
variable "cpu_architecture" {
  type    = string
  default = "ARM64"
}

# 1 vCPU / 2 GB is still below the JVM's "server class" threshold (2 CPUs), so the JVM would pick
# SerialGC here. It runs G1 only because the Dockerfile pins -XX:+UseG1GC.
variable "task_cpu" {
  type    = number
  default = 1024
}

variable "task_memory" {
  type    = number
  default = 2048
}

variable "health_check_grace_period" {
  description = "Seconds ECS ignores ALB health checks after a task starts, so Spring Boot and Flyway can come up."
  type        = number
  default     = 60
}

variable "health_check_interval" {
  type    = number
  default = 10
}

variable "deregistration_delay" {
  description = "Seconds the ALB keeps draining a stopping task. Recorded in the milestone 11 kill experiment."
  type        = number
  default     = 30
}

# Non-burstable on purpose: a t4g spends CPU credits during a long run and then gets throttled to its
# baseline, so later runs in a session would measure the credit balance instead of the database.
variable "db_instance_class" {
  type    = string
  default = "db.m7g.large"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "enable_tracing" {
  description = "Adds the ADOT collector sidecar and the OpenTelemetry agent. Leave off for benchmark runs."
  type        = bool
  default     = false
}
