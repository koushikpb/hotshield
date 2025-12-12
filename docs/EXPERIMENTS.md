# Experiment log

One entry per run. Numbers come from real runs only.

## Template

### YYYY-MM-DD — <name>
- Commit:
- Mode: direct | cache | coalesce | distributed
- Environment: local docker / AWS (task size, RDS class)
- Load: k6 scenario, arrival rate, duration, key distribution
- Hypothesis (written before the run):

| Metric | Value |
|---|---|
| Achieved req/s | |
| DB queries/s | |
| p50 / p95 / p99 (ms) | |
| Error % | |
| Cache hit % | |

- What happened vs the hypothesis:
- Next change:
