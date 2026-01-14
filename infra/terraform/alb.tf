resource "aws_lb" "main" {
  name               = "hotshield"
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}

# All three node services register here, so the ALB round-robins like nginx does locally and a request
# for any key can land on a non-owner.
resource "aws_lb_target_group" "app" {
  name                 = "hotshield"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = var.deregistration_delay

  health_check {
    # Liveness leaves the DB out. With /actuator/health, DB pressure during a herd run would fail the check
    # and ECS would replace healthy tasks in the middle of the measurement.
    path                = "/actuator/health/liveness"
    matcher             = "200"
    interval            = var.health_check_interval
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# Same as infra/nginx.conf: the owner-to-owner path is for nodes only. This rule alone is not enough,
# because the ALB matches the raw path and does not normalize it, so //internal/items/1 slips past and
# Spring may still route it. The app also rejects /internal requests carrying X-Forwarded-For, which the
# ALB always adds and a direct peer call never has.
resource "aws_lb_listener_rule" "block_internal" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    path_pattern {
      values = ["/internal/*"]
    }
  }

  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      status_code  = "404"
    }
  }
}
