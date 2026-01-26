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
- Hypothesis (written before the run): none was written before the run.

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

- What happened :
  With the index the planner walks `items_category_price_idx` from the first row of `cat-07` at
  `price_cents >= 50000` and stops after 50 rows: 54 buffers, 0.22–0.26 ms. Without it, it scans all 1M rows
  in parallel (329,993 removed by the filter per worker, 11,435 buffers) and top-N sorts what is left:
  25.7–27.5 ms, about 100x slower. The index helps twice: the equality and range conditions find the rows,
  and its order already matches `order by price_cents`, so `limit` ends the scan early. The times come from
  a busy laptop; the buffer counts don't depend on that.
- Next change : None. The index stays (`V2__index_items_category_price.sql`).

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
- Hypothesis (written before the run): none was written before the run.

| RATE | Achieved req/s | DB queries/s | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped (measured; warmup) |
|---|---|---|---|---|---|---|---|
| 500 | 500.0 | 499.6 | 0.88 / 1.54 / 4.11 | 24.2 | 0.000 | 0 | 0; 0 |
| 750 | 750.0 | 750.9 | 0.75 / 1.54 / 5.07 | 81.6 | 0.000 | 0 | 0; 0 |
| 1000 | 990.7 | 918.1 | 0.70 / 1967.88 / 2873.90 | 3870.0 | 4.620 | 1848 | 559; 0 |
| 1500 | 1061.0 | 686.1 | 132.15 / 2435.24 / 2756.25 | 6199.1 | 37.051 | 2144 | 16711; 8831 |
| 2000 | 912.0 | 880.1 | 724.76 / 1833.20 / 2473.30 | 4552.1 | 4.161 | 779 | 25573; 15940 |

- The full bench.sh blocks are in `loadtest/results/cap-direct-<rate>/report.md` (not committed).
- 750 was probed after the planned four, because 500 left direct mode at a 4 ms p99. At 750 the poller
  missed eight 500 ms samples in the last 10 s of the measured phase; at 1000 the pool queue reached 1848.
- `RATE` for steps 2 and 4: 750.
- In the failing runs, "GETs served without a DB read" is not meaningful (it reads 14% at 1500 in direct
  mode): a request that times out waiting for a connection counts as a GET but never runs a query.
- What happened :
  On the busy Mac, direct mode was clean at 750 req/s (p99 5.07 ms) and broke at 1000: 559 measured
  drops, 4.6% errors, up to 1848 requests waiting for one of the 10 pool connections. The fall is a cliff,
  not a slope: p99 went from 5 ms to 2.9 s between the two rates. The poller missing samples at 750 showed
  the app was already near its limit there, and the laptop's background load kept changing between runs.
- Next change : Moved the benchmarks to a dedicated EC2 VM (next entry), so the numbers measure HotShield and not the laptop.

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
- Hypothesis (written before the run): none was written before the run.

| RATE | Achieved req/s | DB queries/s | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped (measured; warmup) |
|---|---|---|---|---|---|---|---|
| 1000 | 1000.0 | 1000.5 | 0.56 / 1.11 / 1.68 | 37.2 | 0.000 | 0 | 0; 0 |
| 2000 | 2000.0 | 1999.8 | 0.41 / 0.84 / 1.64 | 76.9 | 0.000 | 89 | 0; 0 |
| 3000 | 3000.0 | 2996.2 | 0.34 / 0.74 / 1.74 | 21.9 | 0.000 | 0 | 0; 0 |
| 4000 | 4000.0 | not sampled | 0.35 / 0.75 / 3.81 | 5008.0 | 6.375 | not sampled | 0; 2434 |

- At 4000 the poller could not reach the app for the whole measured window, so the server-side rows are
  missing. From the app log and the kernel counters of that run: Hikari's 10 connections were all busy,
  up to 8,173 requests waited for one, and 14,914 gave up after the 2 s `connection-timeout`
  (`SQLTransientConnectionException`). `TcpExtListenDrops` was 167,773 (read with `nstat` on the VM after the run, not saved): the accept queue overflowed, so
  some connections were never accepted and k6 hit its 5 s timeout. The median stayed at 0.35 ms throughout.
- `RATE` for steps 2 and 4: 3000.
- What happened :
  Direct mode held 3000 req/s with p99 under 2 ms and failed at 4000, again as a cliff. The 4000 run
  failed in two layers: all 10 pool connections were busy, up to 8,173 requests queued for one and 14,914
  gave up after 2 s; and the kernel's accept queue overflowed, so some connections were never accepted and
  k6 waited its full 5 s. The median stayed at 0.35 ms: most requests were fast and a tail hung completely.
  Nothing limits how many requests wait for the pool, because every request gets its own virtual thread.
  These runs don't show why the connections were held longer at 4000 (k6, the JVM and Postgres share
  8 vCPUs, so CPU is a likely cause, but it wasn't measured).
  Corrected in part by the milestone 12 entry: the pool saturated only in the warmup; the measured-phase
  errors were k6 client timeouts, most likely from more k6 connections than Tomcat accepts.
- Next change : Milestone 12: put a bound in front of the pool (for example a limit on concurrent DB calls or on
  Tomcat connections) so excess requests fail fast instead of queueing, and rerun 4000 req/s to compare the
  error rate and the tail.

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
- The full bench.sh blocks are in `loadtest/results/vm/<scenario>-<mode>-3000/report.md`, copied from the VM (not committed).
- What happened :
  The cache pays off in proportion to how skewed the keys are. DB queries per request fell from 1.0 to
  0.225 on zipf (-77.5%) and to 0.130 on flash (-87.0%), and p50 fell from about 0.35 ms to 0.22 ms. On uniform
  keys it barely helped (0.92): 1M keys drawn evenly repeat too rarely within the 30 s TTL. There the cache
  only added work: p99 rose from 1.74 ms to 2.92 ms (cache) and 2.49 ms (coalesce). That is one run each, so
  a repeat should confirm it before it counts. Coalesce matched cache to four decimals on every mix: with a
  0.3 ms database read, two misses on the same key almost never overlap, so there is nothing to collapse.
  Coalescing can only matter when misses are slow and the key is hot, which is what the herd tests.
- Next change : The herd scenario (step 3), with a slower database read so concurrent misses overlap.

### 2026-10-25 — herd, first attempt: the burst missed the window (benchmark plan step 3)
- Commit: fc68c62
- Mode: cache, coalesce
- Environment: the EC2 VM from the step 1 entry. Both runs passed the gate.
- Load: `herd.js`, RATE=300, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000, HERD_AT=20s, FLASH_ID=42,
  FLASH_SHARE=0.5, BURST_VUS=200, BURST_DELAY=50ms. Reseeded before each run.
- Config: `HOTSHIELD_CACHE_TTL=10m`, `HOTSHIELD_DB_DELAY=20ms`, DB pool size 10, app restarted before every run.

| Metric | cache | coalesce |
|---|---|---|
| Achieved req/s | 300.0 | 300.0 |
| DB queries/s | 50.4 | 50.4 |
| p50 / p95 / p99 (ms) | 0.53 / 22.01 / 22.60 | 0.54 / 22.03 / 22.55 |
| Error % | 0.000 | 0.000 |
| Queries from PUT to PUT+1s | 62 | 58 |
| Burst reads, p50 / p99 (ms) | 200, 2.8 / 5.8 | 200, 7.2 / 13.4 |

- Neither run shows a herd: the second after the PUT has about the same number of queries as any other
  second. The PUT has no artificial delay and returns within a few ms; the next read of 42 refills the
  cache one `DB_DELAY` (20 ms) later; the burst fired 50 ms after the PUT started, so it found 42 cached
  again. These runs don't test coalescing. The setup was changed (DB_DELAY=50ms, BURST_DELAY=10ms, PUT
  latency now reported) and the runs repeated; see the next herd entry.

### 2026-10-25 — herd, second attempt: the PUT was slower than the burst delay (benchmark plan step 3)
- Commit: 84a3e57
- Same as the first attempt except `HOTSHIELD_DB_DELAY=50ms` and BURST_DELAY=10ms. Reseeded before each run.

| Metric | cache | coalesce |
|---|---|---|
| Achieved req/s | 300.0 | 300.0 |
| DB queries/s | 50.6 | 50.3 |
| p50 / p95 / p99 (ms) | 0.54 / 52.07 / 52.64 | 0.54 / 52.18 / 52.81 |
| Error % | 0.000 | 0.000 |
| Queries from PUT to PUT+1s | 68 | 59 |
| PUT latency (ms) | 25.1 | 26.9 |
| Burst reads, p50 / p99 (ms) | 200, 6.1 / 12.7 | 200, 6.6 / 9.1 |

- Still no herd. The PUT took 25–27 ms, longer than BURST_DELAY, so the burst read 42 before the PUT
  invalidated it. The warmup sends only GETs, so this PUT was the JVM's first. `herd.js` now sends 20 PUTs
  to FLASH_ID+1 during the warmup (commit d55f9e5).

### 2026-10-25 — cluster at RATE=3000 with 1 vCPU per node: overloaded (benchmark plan step 4)
- Commit: 84a3e57
- Mode: coalesce, distributed; TARGET=cluster (three nodes behind nginx, each `cpus: 1`, `mem_limit: 2g`)
- Load: RATE=3000, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000. Reseeded before the first run.

| Scenario | Mode | Achieved req/s | p50 / p95 / p99 (ms) | Error % | Dropped (measured; warmup) | Pool pending (max) | Forwards ok / fallback |
|---|---|---|---|---|---|---|---|
| zipf | coalesce | 3000.0 | 0.39 / 1167.90 / 4998.51 | 7.655 | 0; 8424 | 899 | – |
| zipf | distributed | 2256.8 | 230.40 / 4877.42 / 5433.64 | 30.090 | 44595; 17367 | 1913 | 15094 / 12873 |
| flash | coalesce | 3000.0 | 0.32 / 398.18 / 4999.91 | 2.783 | 0; 1180 | 513 | – |
| flash | distributed | 2787.5 | 144.35 / 4720.07 / 5190.18 | 24.841 | 12751; 10058 | 615 | 10442 / 7920 |

- None is a valid comparison: both distributed runs dropped measured iterations, and all four had over 1%
  errors. The poller lost one or more nodes from the start of the warmup for 16.6 s (zipf coalesce),
  17.1 s (flash coalesce), 27.9 s (zipf distributed) and 38.6 s (flash distributed), into the measured
  phase: one CPU per JVM compiles slowly, and a 15 s warmup wasn't enough. Step 4 is repeated at a lower rate with WARMUP=30s.

### 2026-10-25 — herd, cache vs coalesce (benchmark plan step 3)
- Commit: d55f9e5
- Mode: cache, coalesce
- Environment: the EC2 VM from the step 1 entry. Both runs passed the gate.
- Load: `herd.js`, RATE=300, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000, HERD_AT=20s, FLASH_ID=42,
  FLASH_SHARE=0.5, BURST_VUS=200, BURST_DELAY=10ms; 20 PUTs to id 43 during the warmup. Reseeded before
  each run.
- Config: `HOTSHIELD_CACHE_TTL=10m`, `HOTSHIELD_DB_DELAY=50ms`, DB pool size 10, app restarted before every run.
- Hypothesis (written before the run): none was written before the run.

| Metric | cache | coalesce |
|---|---|---|
| Achieved req/s | 300.0 | 300.0 |
| DB queries/s (whole measured phase) | 53.8 | 50.4 |
| p50 / p95 / p99 (ms) | 0.54 / 52.13 / 52.93 | 0.56 / 52.21 / 52.81 |
| Error % | 0.000 | 0.000 |
| k6 dropped_iterations | 0 | 0 |
| PUT latency (ms) | 3.6 | 4.8 |
| Queries from PUT to PUT+1s | 152 | 63 |
| Burst reads, p50 / p99 (ms) | 200, 557.6 / 1064.8 | 200, 51.8 / 55.8 |

findById queries per 200 ms poller sample around the PUT (t=0 is the PUT):

| t (s) | cache | | t (s) | coalesce |
|---|---|---|---|---|
| -0.6 | 9 | | -0.6 | 11 |
| -0.4 | 12 | | -0.4 | 11 |
| -0.2 | 13 | | -0.2 | 13 |
| -0.0 | 15 | | +0.0 | 15 |
| +0.2 | 32 | | +0.2 | 8 |
| +0.4 | 40 | | +0.4 | 12 |
| +0.6 | 40 | | +0.6 | 12 |
| +0.8 | 40 | | +0.9 | 16 |
| +1.0 | 40 | | +1.1 | 4 |
| +1.2 | 41 | | +1.3 | 15 |
| +1.4 | 40 | | +1.5 | 13 |
| +1.6 | 27 | | +1.7 | 10 |
| +1.8 | 10 | | +1.9 | 13 |

- The PUT returned in under BURST_DELAY in both runs, so the burst landed in the miss window.
- 40 queries per 200 ms is 200 queries/s, which is 10 pool connections each holding one 50 ms query.
- What happened :
  Coalescing removes the herd. In cache mode the PUT on the hot key was followed by roughly one query per
  burst read: about 40 queries per 200 ms for 1.4 s, which is exactly 10 connections each doing one 50 ms
  query, so the pool was the limit and the burst waited 557.6 ms at p50. In coalesce mode the queries stayed
  at the background rate, and the burst p50 was 51.8 ms, about one DB_DELAY: all 200 reads waited for the
  same single load. The 50 ms delay is artificial; it stands in for a slow query or a remote database. It
  took two failed attempts to get the burst inside the miss window, which is itself the lesson: a herd only
  exists while a miss is in flight.
- Next change : None for the single node. Step 4 tests the same idea across three nodes.

### 2026-10-25 — cluster: coalesce vs distributed on zipf and flash (benchmark plan step 4)
- Commit: ce34e77
- Mode: coalesce (three independent caches behind nginx), distributed (rendezvous owner per key)
- Environment: the EC2 VM from the step 1 entry; TARGET=cluster, three nodes with `cpus: 1` and
  `mem_limit: 2g` each, nginx in front. Every run passed the gate.
- Load: RATE=1000, DURATION=60s, WARMUP=30s, SEED=1, MAX_ID=1000000. Reseeded before the first run.
  RATE is 1000, not the single-node 3000: with 1 vCPU per node, distributed zipf held 1000 with 0 measured
  drops and 0 errors, and failed at 1500 (20.538% errors; its 4008 drops were all in the warmup; `cap-cluster-dist-1500`).
  WARMUP is 30s because one CPU per JVM compiles slowly; drops during the warmup are listed separately.
- Config: DB pool size 10 per node, `HOTSHIELD_CACHE_TTL` 30s, nodes recreated before every run, tracing off.
- Hypothesis (written before the run): none was written before the run.

| Scenario | Mode | Achieved req/s | DB queries/s | DB queries per GET | p50 / p95 / p99 (ms) | Max (ms) | Error % | Dropped (measured; warmup) | Forwards ok / fallback |
|---|---|---|---|---|---|---|---|---|---|
| zipf | coalesce | 1000.0 | 346.5 | 0.3464 | 0.48 / 1.05 / 2.60 | 14.1 | 0.000 | 0; 0 | – |
| zipf | distributed | 1000.0 | 279.2 | 0.2794 | 0.37 / 1.79 / 13.64 | 70.8 | 0.000 | 0; 1305 | 13738 / 0 |
| flash | coalesce | 1000.0 | 196.3 | 0.1962 | 0.43 / 1.02 / 2.51 | 27.0 | 0.000 | 0; 0 | – |
| flash | distributed | 1000.0 | 160.0 | 0.1600 | 0.33 / 1.61 / 2.37 | 14.0 | 0.000 | 0; 0 | 7839 / 0 |

- pg_stat_statements findById calls (whole run, warmup included): zipf 31821 / 24781, flash 17935 / 15634
  (coalesce / distributed).
- In the zipf distributed run the poller couldn't reach any node from -28.8 s to -10.4 s (warmup only).
- What happened :
  Routing each key to one owner cut DB queries per request by about a fifth compared with three
  independent caches: 0.3464 to 0.2794 on zipf (-19%) and 0.1962 to 0.1600 on flash (-18%). With
  independent caches every node misses each key on its own; with an owner, one copy serves all three. The
  cost is a forward hop: 13,738 forwards on zipf and 7,839 on flash, and the cluster held 1000 req/s but not
  1500 with 1 vCPU per node. On flash the p99 stayed the same (2.37 vs 2.51 ms). On zipf distributed it rose
  to 13.64 ms against 2.60 ms, but that run's nodes were still unreachable until 10 s before the measured
  phase, so part of that tail may be a late warm-up rather than forwarding. These cluster numbers don't
  compare directly with the single-node table: at 1000 req/s fewer requests repeat a key within the TTL.
- Next change : Repeat zipf distributed with a longer warmup before deciding whether HTTP forwarding costs enough to try
  gRPC (the stack rule: only if the overhead shows up in measurements).

### 2026-10-25 — owner down: forward timeouts fall back to the database (benchmark plan step 5)
- Commit: ce34e77
- Mode: distributed; TARGET=cluster as in step 4
- Load: `flash.js` (FLASH_ID=42, owned by node-a), RATE=300, DURATION=60s, WARMUP=30s, SEED=1,
  MAX_ID=1000000, sent to node-b directly (`K6_BASE_URL=http://host.docker.internal:8082`), not through nginx.
- Fault: `docker pause hotshield-node-a-1` at measured+20s for 10s; the poller saw node-a down from +20.1 s
  to +29.4 s.
- Config: `HOTSHIELD_CACHE_TTL=2s` so node-b misses 42 every 2 s during the pause; forward timeout 500 ms.
- Hypothesis (written before the run): none was written before the run.

| Metric | Value |
|---|---|
| Achieved req/s | 300.0 |
| DB queries/s | 90.6 |
| p50 / p95 / p99 (ms) | 0.56 / 3.90 / 501.21 |
| Max (ms) | 505.5 |
| Error % | 0.000 |
| Pool pending (max) | 24 |
| Dropped (measured; warmup) | 0; 0 |
| Owner forwards ok / fallback | 3197 / 305 |

- What happened :
  Losing the owner did not cause errors: with node-a paused for 10 s, node-b answered every request
  (0.000% errors, 0 drops). 305 forwards timed out and fell back to the database. Each fallback paid the full
  500 ms forward timeout first, which is the p99 of 501.21 ms and the max of 505.5 ms. Pool pending reached 24
  while the fallbacks read the database. The failure is slow, not wrong.
- Next change : Optional: after a few timeouts, mark the owner as down for a short time and go straight to the database,
  so later misses don't each wait 500 ms. Measure it with this same run.

### 2026-10-25 — staleness under concurrent writes, healthy and with a paused node (benchmark plan step 6)
- Commit: ce34e77
- Mode: distributed; TARGET=cluster as in step 4, k6 through nginx
- Load: `staleness.js`, RATE=300, WRITE_RATE=5, DURATION=60s, WARMUP=30s, SEED=1, MAX_ID=1000000.
  Reseeded before each run. The pollers add GETs on top of RATE (server GET req/s in the window: 1112.7
  healthy, 1047.8 fault).
- Fault pass: `docker pause hotshield-node-b-1` at measured+20s for 5s; the poller saw node-b down from
  +20.1 s to +24.4 s.
- Config: `HOTSHIELD_CACHE_TTL=10m`, so only invalidation removes a stale copy.
- Hypothesis (written before the run): none was written before the run.

| Metric | healthy | node-b paused 5 s |
|---|---|---|
| Achieved req/s (measured reads) | 300.0 | 300.0 |
| DB queries/s | 5.1 | 5.0 |
| p50 / p95 / p99 (ms) | 0.44 / 0.89 / 2.40 | 0.45 / 1.27 / 4080.98 |
| Max (ms) | 26.7 | 5000.4 |
| Error % | 0.000 | 0.022 |
| Owner forwards ok / fallback | 600 / 0 | 562 / 8 |
| staleness_ms p50 / p95 / p99 / max | 0.0 / 0.0 / 0.0 / 0.0 | 0.0 / 0.0 / 0.0 / 4120.0 |
| staleness_from_sent_ms p50 / p95 / p99 / max | 6.0 / 13.1 / 21.0 / 32.0 | 6.0 / 504.0 / 2615.1 / 4625.0 |
| stale_reads / poll_reads | 0 / 49200 | 2 / 45574 |

- Fault pass: k6 exited 99 because the `write` scenario dropped 9 of its scheduled PUTs while node-b was
  paused; the measured reads dropped none. 4 of 27001 checks failed (`status 200`).
- What happened :
  When every node is healthy, no read returned an old version: 0 stale reads in 49,200 polls. That is
  by construction: a PUT returns only after the invalidation reached every node, so `staleness_ms` is 0, and
  `staleness_from_sent_ms` (max 32 ms) is the PUT's own duration. With node-b paused for 5 s, 2 polls saw an
  old version, the worst 4,120 ms after the PUT returned; the PUTs waited for the 500 ms broadcast timeout
  (from-sent p95 504 ms) and 9 scheduled writes were dropped. Requests nginx sent to the paused node waited
  until k6's 5 s timeout (p99 4080.98 ms, 0.022% errors). The stale reads stopped within about 4 s even
  with a 10-minute TTL; these numbers don't show which mechanism ended them (a later write's invalidation or
  the version check on a forward). The node logs weren't saved.
- Next change : Save the node logs with cluster runs, so a fault pass shows how each stale copy was cleared. If a
  lost invalidation must never last, retry failed invalidations until they land, and repeat this run.

### 2026-10-25 — why direct mode failed at 4000 req/s (milestone 12)
- Commit: ce34e77 (diagnostic runs), bb78c69 (after runs)
- Mode: direct, single node; the EC2 VM from step 1; every run passed the gate (load1 under 1.0, 94–95%
  free, no swap)
- Load: `zipf.js`, DURATION=60s, WARMUP=15s, SEED=1, MAX_ID=1000000; RATE as in the tables. No reseed;
  every run prewarmed `items` and `items_pkey`.
- Config: DB pool size 10, Hikari `connection-timeout` 2s, Tomcat defaults (`max-connections` 8192).
- Hypothesis: none was written before the diagnostic runs. Before the after runs: with the new VU sizing,
  4000 req/s runs with 0 errors.

Diagnostic runs at 4000 req/s, before the change. `diag-direct-4000` kept the app running afterwards so its
metrics could be read; `diag-direct-4000-top` sampled `top -b -d 5` (saved as `top-diag.txt`).

| Measurement | diag-direct-4000 | diag-direct-4000-top | Source |
|---|---|---|---|
| Error % (measured scenario) | 6.374 | 6.452 | k6 summary |
| Failed checks, whole run | 22,088 | 28,721 | k6 summary |
| of which k6 `request timeout` (5 s) | 17,105 | 17,755 | k6 log |
| Pool timeouts (HTTP 500) | 4,983 | not read | `hikaricp_connections_timeout_total` |
| Most waiters in a pool timeout message | 6,029 | 8,172 | app log (`waiting=`) |
| Pool timeout messages with `active=10, idle=0` | 2,505 | 8,761 | app log |
| Time a connection was held, mean over the run | 0.21 ms (57.995 s over 276,777 uses) | not read | `hikaricp_connections_usage_seconds`, read live, not saved |
| Time to get a connection, mean over the run | 90 ms (25,324 s over 281,760 gets) | not read | `hikaricp_connections_acquire_seconds`, read live, not saved |
| Query time in Postgres, mean / max | 0.024 / 22.3 ms | not read | `pg_stat_statements`, read live, not saved |
| k6 VUs, most active / initialized | 6,189 / 10,000 | 10,081 / 10,098 | k6 `vus`, `vus_max` |
| `http_req_connecting` p99 / max | 1.30 / 3,079 ms | 1,015 / 4,138 ms | k6 summary |

- When the failures happened: in `diag-direct-4000-top` every pool timeout in the app log is between 2 s
  and 10 s after k6 started, inside the warmup. The `request timeout` failures came in bursts about every
  7 s through the whole run, in both runs.
- CPU in `diag-direct-4000-top` (5 s samples; k6 started at 03:14:08.8 and ended at about 03:15:24; the
  capture stopped at 03:15:05):

| Sample end | User / idle % | k6 | java | postgres |
|---|---|---|---|---|
| 03:14:00, 03:14:05 (before k6 started the test) | 91.9–93.6 / 0.0–0.9 | 755, 775 | 0, 14 | 0, 0 |
| 03:14:10 to 03:14:20 | 59.4–90.5 / 1.3–26.7 | 142–568 | 196–554 | 3–38 |
| 03:14:25 to 03:15:05 | 18.2–31.1 / 59.2–73.3 | 103–202 | 81–93 | 25–28 |

After the change (`m12-direct-*`, one run each):

| RATE | Achieved req/s | DB queries/s | p50 / p95 / p99 (ms) | Max (ms) | Error % | Pool pending (max) | Dropped (measured; warmup) | k6 VUs (max) |
|---|---|---|---|---|---|---|---|---|
| 3000 | 3000.0 | 2997.3 | 0.32 / 0.58 / 1.61 | 18.1 | 0.000 | 0 | 0; 1207 | 333 |
| 4000 | 4000.0 | 3990.2 | 0.30 / 0.51 / 2.30 | 31.5 | 0.000 | 1 | 0; 3176 | 501 |
| 5000 | 5000.0 | 4996.8 | 0.32 / 0.48 / 2.75 | 45.2 | 0.000 | 0 | 0; 5745 | 640 |
| 6000 | 6000.1 | 5995.0 | 0.35 / 0.88 / 4.80 | 53.3 | 0.000 | 11 | 0; 10651 | 851 |

- After the change: 0 `request timeout` failures in every run. Pool timeouts: 0, 0, 1 and 15, all in the
  warmup. `http_req_connecting` p99 0.047–0.062 ms; max 7.1–18.9 ms, except 1,059 ms in the 6000 run.
- What happened :
  The 4000 req/s runs failed in two ways at two times. In the warmup, the pool saturated: all 10
  connections checked out and up to 8,172 requests waiting, and thousands timed out after 2 s. The CPU was
  fully busy then. Before the test started, k6 used 755–775% while it started 10,000 VUs; in the first
  seconds of traffic the JVM used up to 554%. Postgres was not the cause (0.024 ms per query on average),
  and over the whole run a connection was held for only 0.21 ms on average, so the saturation was a warmup
  event. Most likely, the threads that held or waited for connections didn't get CPU.
  In the measured phase, the pool didn't time out, the CPU was 59–73% idle, and requests still failed: about
  17,000 per run hit k6's 5 s timeout, in bursts about every 7 s. The likely cause is the connection count.
  `lib.js` preallocated `RATE * 2.5` VUs, 10,000 at 4000 req/s; each k6 VU keeps its own keep-alive
  connection, so up to 10,000 connections could be open (an idle VU keeps its connection too, which is how
  `diag-direct-4000` could have passed 8,192 with at most 6,189 active VUs). Tomcat accepts 8,192. The connection count
  wasn't captured (no `ss`), so this is inferred, not measured. At 3000 req/s the old sizing gave 7,500 VUs,
  under the limit.
  The fix supports it: with `RATE * 0.1` VUs preallocated (at most 8,000), the client timeouts went to 0 at
  4000, 5000 and 6000 req/s, and the warmup pool timeouts nearly went away (at most 15), likely because k6
  no longer starts thousands of VUs at once. p99 rose from 2.30 to 4.80 ms between 4000 and 6000. The 3000 run
  matches step 2 (p99 1.61 vs 1.69 ms), so the change didn't shift the earlier results. 6000 req/s is the
  highest rate tried, not a measured limit.
  This corrects the step 1 entry in part: the pool did saturate, but only in the warmup, and the
  measured-phase errors were client timeouts.
- What stays: k6 drops warmup iterations at every rate now (1,207 to 10,651), because it adds VUs while the
  cold JVM is slow, so k6 exits 99. The old sizing dropped warmup iterations too in one of the two runs
  (2,180). The warmup is discarded and the measured phase dropped none. More preallocated VUs would remove
  the drops but bring back thousands of connections.
- Not rerun: the cluster at 3000 req/s (step 4) used the old sizing, 7,500 VUs through nginx. Its overload
  was put down to 1 vCPU per node; these runs don't show whether the VU count added to it.
- Next change : Capture `ss -s` during a run to confirm the connection count, and rerun the cluster
  at 3000 req/s with the new sizing to separate the two causes.
