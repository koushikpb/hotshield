# Experiment log

One entry per run. Numbers come from real runs only.

## Template

### YYYY-MM-DD — <name>
- Commit:
- Mode: direct | cache | coalesce | distributed
- Environment: local docker / AWS (task size, RDS class)
- Load: k6 scenario, arrival rate, duration, warmup, seed, key distribution
- Config: DB pool size, cache max-size and ttl, node restarted before the run (cache.gets is cumulative),
  tracing on/off and TRACE_RATIO
- Hypothesis (written before the run):

| Metric | Value |
|---|---|
| Achieved req/s | |
| DB queries/s | |
| p50 / p95 / p99 (ms) | |
| Error % | |
| Cache hit % (measured phase only) | |
| Pool pending (max) | |
| k6 dropped_iterations | |

- What happened vs the hypothesis:
- Next change:

## Runs

### 2026-10-23 — index vs no index on the category query
- Commit: baa8029
- Mode: n/a (psql against the compose Postgres)
- Environment: local docker, Apple M2 8 GB, Docker Desktop VM 4 GB, `postgres:16-alpine` default config.
  Other workloads were running on the machine (load average about 10, 6 GB swap in use). The plan shape
  doesn't depend on that; the absolute times may.
- Data: `loadtest/seed.sql`, 1,000,000 rows, 50 categories of 20,000 rows, `analyze` after the seed
- Query: `ItemRepository.findByCategory` with `category = 'cat-07'`, `price_cents >= 50000`, `limit 50`.
  "Without index" drops `items_category_price_idx` inside a transaction and rolls back.
- Hypothesis (written before the run):

| Run | With index (ms) | Without index (ms) |
|---|---|---|
| 1 | 0.259 | 25.745 |
| 2 | 0.222 | 26.320 |
| 3 | 0.221 | 27.481 |

With index (run 3):
```
QUERY PLAN                                                                   
-----------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.42..140.89 rows=50 width=60) (actual time=0.019..0.191 rows=50 loops=1)
   Buffers: shared hit=54
   ->  Index Scan using items_category_price_idx on items  (cost=0.42..27924.94 rows=9940 width=60) (actual time=0.018..0.187 rows=50 loops=1)
         Index Cond: ((category = 'cat-07'::text) AND (price_cents >= 50000))
         Buffers: shared hit=54
 Planning:
   Buffers: shared hit=146
 Planning Time: 0.365 ms
 Execution Time: 0.221 ms
(9 rows)
```

Without index (run 3):
```
QUERY PLAN                                                              
--------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=18750.62..18756.45 rows=50 width=60) (actual time=25.953..27.449 rows=50 loops=1)
   Buffers: shared hit=11435
   ->  Gather Merge  (cost=18750.62..19717.15 rows=8284 width=60) (actual time=25.952..27.445 rows=50 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         Buffers: shared hit=11435
         ->  Sort  (cost=17750.59..17760.95 rows=4142 width=60) (actual time=24.707..24.709 rows=40 loops=3)
               Sort Key: price_cents
               Sort Method: top-N heapsort  Memory: 36kB
               Buffers: shared hit=11435
               Worker 0:  Sort Method: top-N heapsort  Memory: 36kB
               Worker 1:  Sort Method: top-N heapsort  Memory: 35kB
               ->  Parallel Seq Scan on items  (cost=0.00..17613.00 rows=4142 width=60) (actual time=0.017..24.170 rows=3340 loops=3)
                     Filter: ((price_cents >= 50000) AND (category = 'cat-07'::text))
                     Rows Removed by Filter: 329993
                     Buffers: shared hit=11363
 Planning:
   Buffers: shared hit=5
 Planning Time: 0.044 ms
 Execution Time: 27.481 ms
(20 rows)
```

- What happened vs the hypothesis:
- Next change:

### 2026-10-23 — one request traced across two nodes (milestone 7)
- Commit: 4c752ae (traces are unchanged by the uncommitted harness work)
- Mode: distributed (cluster), `TRACING=-javaagent:/app/opentelemetry-javaagent.jar`, `TRACE_RATIO=1.0`
- Environment: local docker, same busy machine as the index entry. These were the first requests after the
  nodes started, so the durations include cold-JVM time and say nothing about steady-state latency.
- Request: `GET /items/101` sent straight to node-a (port 8081). Rendezvous hashing makes node-c the owner.

Span tree from Jaeger (operation [node] duration attributes):
```
GET /items/{id} [node-a] 564.7ms {'http.response.status_code': 200, 'url.path': '/items/101'}
  cache.lookup [node-a] 53.2ms {'item.id': 101}
  singleflight.forward [node-a] 373.6ms {'hotshield.owner': 'node-c', 'hotshield.singleflight.role': 'leader', 'item.id': 101}
    cache.lookup [node-a] 0.0ms {'item.id': 101}
    owner.forward [node-a] 351.0ms {'hotshield.owner': 'node-c', 'item.id': 101}
      GET [node-a] 233.3ms {'http.response.status_code': 200}
        GET /internal/items/{id} [node-c] 117.4ms {'http.response.status_code': 200, 'url.path': '/internal/items/101'}
          cache.lookup [node-c] 33.6ms {'item.id': 101}
          singleflight.load [node-c] 48.9ms {'hotshield.singleflight.role': 'leader', 'item.id': 101}
            cache.lookup [node-c] 0.0ms {'item.id': 101}
            item.read_db [node-c] 36.9ms {'item.id': 101}
              hotshield [node-c] 0.8ms {'db.statement': ''}
              SELECT hotshield.items [node-c] 14.7ms {'db.statement': 'select id, sku, name, category, price_cents, stock, version, updated_at from items where id = ? '}
```

### 2026-10-24 — direct mode capacity on zipf (benchmark plan step 1)
- Commit: 9345afe
- Mode: direct
- Environment: local, Apple M2 8 GB. k6 (`grafana/k6:2.3.0`), Postgres (`postgres:16-alpine`, default config)
  and the poller in the Docker Desktop VM; the app jar on the host. Chrome and other projects closed, every
  run passed the bench.sh gate (load1 under 2.0, 44–69% free). About 0.9–3 GB of old swap was still in use.
- Load: `zipf.js`, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000. The first run reseeded; every run
  prewarmed `items` and `items_pkey`.
- Config: DB pool size 10, app restarted before every run, tracing off.
- Hypothesis (written before the run):

| RATE | Achieved req/s | DB queries/s | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped |
|---|---|---|---|---|---|---|---|
| 500 | 500.0 | 499.6 | 0.88 / 1.54 / 4.11 | 24.2 | 0.000 | 0 | 0 |
| 750 | 750.0 | 750.9 | 0.75 / 1.54 / 5.07 | 81.6 | 0.000 | 0 | 0 |
| 1000 | 990.7 | 918.1 | 0.70 / 1967.88 / 2873.90 | 3870.0 | 4.620 | 1848 | 559 |
| 1500 | 1061.0 | 686.1 | 132.15 / 2435.24 / 2756.25 | 6199.1 | 37.051 | 2144 | 25542 |
| 2000 | 912.0 | 880.1 | 724.76 / 1833.20 / 2473.30 | 4552.1 | 4.161 | 779 | 41513 |

- The full bench.sh blocks are in `loadtest/results/cap-direct-<rate>/report.md` (not committed).
- 750 was probed after the planned four, because 500 left direct mode at a 4 ms p99. At 750 the poller
  missed eight 500 ms samples in the last 10 s of the measured phase; at 1000 the pool queue reached 1848.
- `RATE` for steps 2 and 4: 750.
- In the failing runs, "GETs served without a DB read" is not meaningful (it reads 14% at 1500 in direct
  mode): a request that times out waiting for a connection counts as a GET but never runs a query.
- What happened vs the hypothesis:
- Next change:

### 2026-10-25 — direct mode capacity on zipf, EC2 VM (benchmark plan step 1)
- Commit: a5c60fc
- Mode: direct
- Environment: AWS EC2 `c7i.2xlarge` (8 vCPU, 16 GiB), us-east-2, Ubuntu 24.04, Docker Engine 29.1.3,
  OpenJDK 21.0.12. k6, Postgres (`postgres:16-alpine`, default config) and the poller in containers; the app
  jar on the host. Nothing else running; every run passed the gate (load1 under 1.0, 93–94% free, no swap).
  This machine replaces the Mac for steps 2–6, so `RATE` comes from here.
- Load: `zipf.js`, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000. The first run reseeded; every run
  prewarmed `items` and `items_pkey`.
- Config: DB pool size 10, app restarted before every run, tracing off.
- Hypothesis (written before the run):

| RATE | Achieved req/s | DB queries/s | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped |
|---|---|---|---|---|---|---|---|
| 1000 | 1000.0 | 1000.5 | 0.56 / 1.11 / 1.68 | 37.2 | 0.000 | 0 | 0 |
| 2000 | 2000.0 | 1999.8 | 0.41 / 0.84 / 1.64 | 76.9 | 0.000 | 89 | 0 |
| 3000 | 3000.0 | 2996.2 | 0.34 / 0.74 / 1.74 | 21.9 | 0.000 | 0 | 0 |
| 4000 | 4000.0 | not sampled | 0.35 / 0.75 / 3.81 | 5008.0 | 6.375 | not sampled | 2434 |

- At 4000 the poller could not reach the app for the whole measured window, so the server-side rows are
  missing. From the app log and the kernel counters of that run: Hikari's 10 connections were all busy,
  up to 8,111 requests waited for one, and 14,914 gave up after the 2 s `connection-timeout`
  (`SQLTransientConnectionException`). `TcpExtListenDrops` was 167,773: the accept queue overflowed, so
  some connections were never accepted and k6 hit its 5 s timeout. The median stayed at 0.35 ms throughout.
- `RATE` for steps 2 and 4: 3000.
- What happened vs the hypothesis:
- Next change:

### 2026-10-25 — direct vs cache vs coalesce on uniform, zipf and flash (benchmark plan step 2)
- Commit: 939f87c
- Mode: direct, cache, coalesce (one run each per scenario, direct first)
- Environment: the EC2 VM from the step 1 entry (c7i.2xlarge, us-east-2). Every run passed the gate
  (load1 under 1.0, 94% free, no swap).
- Load: RATE=3000, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000; `zipf.js` and `flash.js` with their
  defaults (ZIPF_S=1.1; FLASH_ID=42, FLASH_SHARE=0.5). No reseed (these scenarios only read); every run
  prewarmed `items` and `items_pkey`.
- Config: DB pool size 10, cache defaults (`HOTSHIELD_CACHE_TTL` 30s), app restarted before every run,
  tracing off.
- Hypothesis (written before the run): none was written before these runs.

| Scenario | Mode | Achieved req/s | DB queries/s | DB queries per GET | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped |
|---|---|---|---|---|---|---|---|---|---|
| uniform | direct | 3000.0 | 2998.0 | 1.0000 | 0.35 / 0.76 / 1.74 | 37.4 | 0.000 | 0 | 0 |
| uniform | cache | 3000.0 | 2756.5 | 0.9205 | 0.35 / 0.78 / 2.92 | 29.4 | 0.000 | 0 | 0 |
| uniform | coalesce | 3000.0 | 2756.4 | 0.9207 | 0.36 / 0.78 / 2.49 | 97.9 | 0.000 | 1 | 0 |
| zipf | direct | 3000.0 | 3008.1 | 1.0000 | 0.34 / 0.72 / 1.69 | 27.1 | 0.000 | 0 | 0 |
| zipf | cache | 3000.0 | 674.8 | 0.2251 | 0.22 / 0.66 / 1.29 | 17.9 | 0.000 | 0 | 0 |
| zipf | coalesce | 3000.0 | 674.7 | 0.2250 | 0.22 / 0.64 / 1.18 | 18.7 | 0.000 | 0 | 0 |
| flash | direct | 3000.0 | 3010.3 | 1.0000 | 0.35 / 0.77 / 1.90 | 39.7 | 0.000 | 0 | 0 |
| flash | cache | 3000.1 | 391.8 | 0.1304 | 0.21 / 0.62 / 1.18 | 12.4 | 0.000 | 0 | 0 |
| flash | coalesce | 3000.0 | 392.5 | 0.1305 | 0.21 / 0.63 / 1.34 | 74.8 | 0.000 | 0 | 0 |

- Cache hit % per lookup: uniform 7.95 (cache) / 4.13 (coalesce), zipf 77.49 / 63.27, flash 86.96 / 76.92.
  It is lower in coalesce mode because the leader looks the key up a second time before it loads.
- pg_stat_statements findById calls (whole run, warmup included): uniform 224275 / 208667 / 207770,
  zipf 224119 / 53424 / 53247, flash 223916 / 30811 / 30717 (direct / cache / coalesce).
- The full bench.sh blocks are in `loadtest/results/<scenario>-<mode>-3000/report.md` on the VM (not committed).
- What happened vs the hypothesis:
- Next change:
