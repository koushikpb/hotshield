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

### 2026-01-17 — index vs no index on the category query
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
