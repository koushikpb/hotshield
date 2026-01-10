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
