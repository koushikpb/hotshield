# HotShield

A distributed load shield for PostgreSQL under hot-key traffic. Concurrent cache misses for the same key are
coalesced into one query, popular keys are owned by one node in a small cluster via rendezvous hashing, and writes
invalidate cached copies. Deployed on AWS (ECS Fargate, RDS, ALB) with Terraform, traced with OpenTelemetry.

> Work in progress. Results below get filled in from real benchmark runs.

## Results

| Mode | DB queries/s | p50 | p99 | Error % |
|---|---|---|---|---|
| Direct PostgreSQL | | | | |
| Cache only | | | | |
| Cache + coalescing | | | | |
| Distributed | | | | |

## Running locally

```bash
docker compose up -d
./gradlew bootRun
```

Java 21 and a running Docker daemon required (tests use Testcontainers). Compose publishes Postgres on host port
5434 (override with `PG_HOST_PORT`) so a locally installed Postgres on 5432 is never used by mistake.
