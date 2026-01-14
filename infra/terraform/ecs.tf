locals {
  nodes = toset(["node-a", "node-b", "node-c"])

  peers = join(",", [for n in sort(tolist(local.nodes)) : "${n}=http://${n}.${aws_service_discovery_private_dns_namespace.main.name}:8080"])

  # The JVM caches successful DNS lookups for 30s by default, on top of the Cloud Map TTL, so a replaced
  # owner would stay unreachable for up to ~40s. networkaddress.cache.ttl is a security property, not a
  # system property, so -Dnetworkaddress.cache.ttl=5 is silently ignored; sun.net.inetaddr.ttl is its
  # command-line equivalent. This only affects new connections: a pooled keep-alive connection to the
  # old IP fails on use and is then re-resolved.
  java_tool_options = join(" ", compact([
    "-Dsun.net.inetaddr.ttl=5",
    var.enable_tracing ? "-javaagent:/app/opentelemetry-javaagent.jar" : "",
  ]))

  tracing_env = {
    OTEL_SERVICE_NAME           = "hotshield"
    OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318"
    OTEL_EXPORTER_OTLP_PROTOCOL = "http/protobuf"
    OTEL_METRICS_EXPORTER       = "none"
    OTEL_LOGS_EXPORTER          = "none"
    OTEL_TRACES_SAMPLER         = "parentbased_traceidratio"
    OTEL_TRACES_SAMPLER_ARG     = tostring(var.trace_ratio)
  }

  # The OpenTelemetry agent instruments classes as they load, which slows startup.
  health_check_grace_period = var.enable_tracing ? 120 : var.health_check_grace_period

  # The JVM sizes its heap with MaxRAMPercentage against the cgroup limit it sees. Without a container-level
  # limit that is the whole task, so with the sidecar running the heap would be sized for memory the collector
  # also uses. Giving each container its own limit makes the app's share explicit.
  collector_memory = 256
  app_memory       = var.enable_tracing ? var.task_memory - local.collector_memory : var.task_memory

  # Ships the collector's own config that receives OTLP and exports traces to X-Ray only, so tracing
  # doesn't also start writing EMF metrics to CloudWatch.
  collector = {
    name      = "adot-collector"
    image     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.45.1"
    essential = false
    memory    = local.collector_memory
    command   = ["--config=/etc/ecs/ecs-xray.yaml"]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "adot"
      }
    }
  }
}

resource "aws_ecs_cluster" "main" {
  name = "hotshield"

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/hotshield"
  retention_in_days = 7
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Used by the ECS agent before the app starts: pull the image, create log streams, read the DB password.
resource "aws_iam_role" "execution" {
  name               = "hotshield-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# The parameter uses the AWS-managed aws/ssm key, so no kms:Decrypt grant is needed.
resource "aws_iam_role_policy" "execution_db_password" {
  name = "read-db-password"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = aws_ssm_parameter.db_password.arn
    }]
  })
}

# Used by the running containers. Empty unless tracing is on.
resource "aws_iam_role" "task" {
  name               = "hotshield-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "task_xray" {
  count = var.enable_tracing ? 1 : 0

  role       = aws_iam_role.task.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_ecs_task_definition" "node" {
  for_each = local.nodes

  family                   = "hotshield-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode(concat([
    {
      name         = "app"
      image        = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
      essential    = true
      memory       = local.app_memory
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment = [for k, v in merge({
        NODE_ID                  = each.key
        PEERS                    = local.peers
        HOTSHIELD_MODE           = var.hotshield_mode
        HOTSHIELD_CACHE_TTL      = var.cache_ttl
        HOTSHIELD_DB_DELAY       = var.db_delay
        HOTSHIELD_TOMBSTONE_TTL  = var.tombstone_ttl
        DB_POOL_SIZE             = tostring(var.db_pool_size)
        FORWARD_TIMEOUT          = var.forward_timeout
        DB_URL                   = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}"
        DB_USER                  = aws_db_instance.main.username
        JAVA_TOOL_OPTIONS        = local.java_tool_options
        OTEL_RESOURCE_ATTRIBUTES = "service.instance.id=${each.key}"
      }, var.enable_tracing ? local.tracing_env : {}) : { name = k, value = v }]
      secrets = [{ name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.region
          awslogs-stream-prefix = each.key
        }
      }
    }
  ], var.enable_tracing ? [local.collector] : []))
}

# One service per node rather than one service with three tasks: each needs its own NODE_ID and its own
# DNS name, and ECS replaces a killed task under the same name.
resource "aws_ecs_service" "node" {
  for_each = local.nodes

  name                  = each.key
  cluster               = aws_ecs_cluster.main.id
  task_definition       = aws_ecs_task_definition.node[each.key].arn
  desired_count         = 1
  launch_type           = "FARGATE"
  propagate_tags        = "SERVICE"
  wait_for_steady_state = true

  health_check_grace_period_seconds = local.health_check_grace_period

  # Stop the old task before starting the new one. Two copies of a node would both register under the same
  # Cloud Map name and in the ALB target group, so during a deploy that changes hotshield_mode the ALB would
  # serve two modes at once and peers would resolve the node name to either copy.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    # One AZ for all nodes, the DB and the runner keeps node-to-node and node-to-DB latency equal, so
    # per-node p99s can be compared. A cross-AZ hop would add latency to some nodes and not others.
    subnets          = [aws_subnet.public[0].id]
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  service_registries {
    registry_arn = aws_service_discovery_service.node[each.key].arn
  }

  depends_on = [aws_lb_listener.http]
}
