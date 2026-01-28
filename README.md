# HotShield

A Java service in front of PostgreSQL that protects it from hot-key traffic. Concurrent cache misses for one key
collapse into a single query, each key is owned by one node in a small cluster (rendezvous hashing), and writes
invalidate cached copies. `hotshield.mode` switches the same build between `direct`, `cache`, `coalesce` and
`distributed`.

## Results

Single runs, open-loop load, 60 s measured after a discarded warmup. Every run is logged in
[docs/EXPERIMENTS.md](docs/EXPERIMENTS.md).

One node on an EC2 `c7i.2xlarge` (k6, app and PostgreSQL 16 on the same machine), zipf reads over 1M rows,
3000 req/s:

| Mode | DB queries/s | DB queries per request | p50 (ms) | p99 (ms) | Error % |
|---|---|---|---|---|---|
| Direct PostgreSQL | 3008.1 | 1.0000 | 0.34 | 1.69 | 0.000 |
| Cache only | 674.8 | 0.2251 | 0.22 | 1.29 | 0.000 |
| Cache + coalescing | 674.7 | 0.2250 | 0.22 | 1.18 | 0.000 |

Thundering herd: a PUT drops the hot key, then 200 reads of it arrive together (50 ms artificial DB delay):

| Mode | Queries in the second after the PUT | Burst p50 / p99 (ms) |
|---|---|---|
| Cache only | 152 | 557.6 / 1064.8 |
| Cache + coalescing | 63 | 51.8 / 55.8 |

Three nodes (1 vCPU each) behind nginx on the same machine, zipf, 1000 req/s:

| Mode | DB queries/s | DB queries per request | p50 (ms) | p99 (ms) | Error % |
|---|---|---|---|---|---|
| Three independent caches | 346.5 | 0.3464 | 0.48 | 2.60 | 0.000 |
| Distributed (one owner per key) | 279.2 | 0.2794 | 0.37 | 13.64 | 0.000 |

AWS: three Fargate tasks (1 vCPU each) behind an ALB, RDS `db.m7g.large`, k6 in the same AZ, zipf, 500 req/s:

| Mode | DB queries/s | DB queries per request | p50 (ms) | p99 (ms) | Error % |
|---|---|---|---|---|---|
| Direct PostgreSQL | 500.3 | 1.0000 | 1.89 | 14.27 | 0.000 |
| Three independent caches | 195.0 | 0.3891 | 1.78 | 9.06 | 0.000 |
| Distributed (one owner per key) | 159.5 | 0.3198 | 1.92 | 19.69 | 0.000 |

Stopping one of the three tasks at 500 req/s caused no failed requests; the other nodes read the database for
its keys for about 172 s until the replacement was registered.

## Setup

Java 21 and Docker. Postgres is published on host port 5434 (`PG_HOST_PORT` to change it).

```bash
docker compose up -d
./gradlew bootRun   # Flyway creates the table on startup
docker compose exec -T postgres psql -U hotshield hotshield < loadtest/seed.sql   # 1M rows
./gradlew test      # unit and Testcontainers tests
```

Three nodes behind nginx: `docker compose --profile cluster up -d --build`. Load tests: [loadtest/README.md](loadtest/README.md).
AWS: [infra/terraform/README.md](infra/terraform/README.md).
