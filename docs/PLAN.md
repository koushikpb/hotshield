# HotShield — build plan

Goal: show, with measured numbers, how request coalescing and a distributed hot-key cache change database load,
tail latency, and failure behavior for a PostgreSQL-backed service on AWS.

Rough budget: 25–40 implementation hours plus 14–20 hours of my own study, about 7–11 days alongside recruiting.
Build the single-node cache + coalescing experiment first. Distribution comes second.

## Data model

`items(id bigint pk, sku text unique, name text, category text, price_cents int, stock int, version bigint, updated_at timestamptz)`
Seed 1M rows. Traffic follows a Zipf distribution so a handful of keys get most of the reads, plus a "flash" mode
where one key gets ~50% of all requests.

## API
- `GET /items/{id}` — read through whichever mode is active
- `PUT /items/{id}` — write to Postgres, bump `version`, invalidate
- `GET /items?category=&minPrice=` — a query that needs an index (for the EXPLAIN ANALYZE milestone)
- `GET /internal/items/{id}` — owner-node fetch (milestone 5)
- `/actuator/metrics`, `/actuator/health`

## Milestones

| # | Milestone | Done when | My study task |
|---|-----------|-----------|---------------|
| 0 | Skeleton: Gradle, Spring Boot, Docker Compose Postgres, Flyway, health check | `./gradlew test` green, app talks to DB | Draw the request path. Review HikariCP pool sizing and Postgres transactions. |
| 1 | Baseline: direct DB reads/writes, seed script, category query | Seed 1M rows; `direct` mode works | Write the SQL. Run EXPLAIN ANALYZE before and after adding an index on `(category, price_cents)`. Record both plans. |
| 2 | Local cache (`cache` mode): Caffeine, size bound, TTL, cache-aside | Hit/miss metrics exposed | Explain hit/miss, eviction, TTL, stale reads without notes. |
| 3 | Single-flight (`coalesce` mode) | Race test: 1,000 concurrent misses on one key → exactly 1 DB query | **I write `SingleFlight`.** Explain the race, what happens if the leader's query fails or is cancelled. |
| 4 | Benchmark harness: k6 open-loop (constant-arrival-rate) scenarios: uniform, zipf, flash | First results table for direct / cache / coalesce | Throughput, p50/p95/p99, open vs closed loop, coordinated omission. |
| 5 | Three nodes (`distributed` mode): rendezvous hashing picks owner; non-owners forward misses to owner | Same key always hits the same owner; DB queries per logical request measured | **I write rendezvous hashing.** Compare with consistent hashing on a ring. |
| 6 | Invalidation: on PUT, owner drops key and broadcasts invalidate; versions prevent stale overwrite | Staleness window measured under concurrent writes | Write-through vs write-back vs cache-aside vs TTL. What can still go stale and for how long? |
| 7 | OpenTelemetry: Java agent + custom spans for cache lookup, single-flight wait, forward, DB | One request traced across two nodes | Read a trace by hand and name every parent/child span. |
| 8 | Terraform: VPC, subnets, SGs, ALB, ECS Fargate (3 tasks), RDS Postgres, CloudWatch | `terraform plan` clean | Draw VPC/subnet/SG/ALB/ECS/RDS from memory. |
| 9 | Deploy to AWS | Load test hits ALB, metrics in CloudWatch | Inspect every created resource and say what it's for. |
| 10 | Cloud benchmark | Results table from AWS runs | Predict the bottleneck before running; compare. |
| 11 | Failure experiment: kill one ECS task mid-load | Error rate + recovery time recorded | What failed, what retried, what state survived? Why? |
| 12 | Debug/perf pass | One real bottleneck found and fixed with before/after | Read traces and plans myself before asking for conclusions. |
| 13 | README + results + 3-minute demo video | README opens with diagram + results table | 15-minute mock deep-dive with no repo open. |

## Experiments (fill `docs/EXPERIMENTS.md` as you go)

| Experiment | Measure |
|---|---|
| Direct PostgreSQL baseline | req/s, DB queries/s, p50/p95/p99 |
| Cache only | same |
| Cache + coalescing | same |
| Flash hot key | DB queries per logical request |
| Concurrent writes | staleness duration |
| Kill one task | error rate, recovery time |
| Index vs no index | plan + runtime |
| AWS run | CPU, memory, req rate, errors, latency |

## Stretch (only after milestone 13)
Automatic hot-key detection and replication to non-owners, Postgres LISTEN/NOTIFY invalidation,
stale-while-revalidate, adaptive admission (TinyLFU tuning), autoscaling.

## Interview questions this must prepare me for
- How is coalescing different from caching? What race happens on two simultaneous misses?
- What if the request that owns the DB lookup is cancelled or times out?
- Why this index? What does EXPLAIN ANALYZE show?
- Why p99 and not average?
- How do you invalidate safely? What's the worst-case staleness?
- What happens if the owner node dies?
- Why ECS and not Lambda or EKS? Why RDS and not DynamoDB?
- How would this scale to 10x traffic?
