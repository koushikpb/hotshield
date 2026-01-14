# Peers address each other by node name, not task IP. A replaced task gets a new IP but keeps its name,
# so rendezvous ownership (which hashes the node id) does not move when ECS replaces a task in milestone 11.
resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "hotshield.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "node" {
  for_each = local.nodes

  name = each.key

  # Destroy can race ECS deregistering the last task's instance; without this, deleting a service that still
  # has an instance fails and leaves the namespace behind.
  force_destroy = true

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"

    # Short TTL so a replacement task's IP is picked up within seconds. Also see the JVM DNS cache note in ecs.tf.
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}
