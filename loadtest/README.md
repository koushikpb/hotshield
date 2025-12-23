# Load scenarios

Milestone 4. Every script uses k6's `constant-arrival-rate` executor (open loop) so slow responses
don't hide latency. Each run has a `warmup` scenario that is discarded and a `measured` scenario
that the summary reports separately (`{scenario:measured}` lines). A run fails if k6 drops any
iteration, because at that point it has stopped being open loop.

- `uniform.js` — keys uniform over all ids
- `zipf.js` — Zipf(s≈1.1) over all ids
- `flash.js` — `FLASH_SHARE` of requests to one id, rest Zipf
- `mixed.js` — 95/5 read/write on Zipf keys (invalidation experiments)
- `herd.js` — flash traffic plus one PUT to the flash id mid-run, so its cached copy is dropped
  while many reads for it are in flight
- `lib.js` — shared options, seeded random draws, Zipf sampler
- `baseline.js` — the milestone 1 script, kept so earlier runs stay reproducible

## Running

With Docker, from the repo root (on Docker Desktop for Mac, `--network host` may not reach the
host; use `-e BASE_URL=http://host.docker.internal:8080` instead):

```
docker run --rm -i --network host -v "$PWD/loadtest:/scripts" grafana/k6:latest run \
  -e RATE=500 -e DURATION=60s -e WARMUP=10s -e SEED=1 -e MAX_ID=1000000 /scripts/zipf.js
```

Swap `zipf.js` for any other scenario. To look at latency over time (the herd spike is invisible
in a whole-run p99), mount a directory outside the repo
(`-v /tmp/k6:/out`), add `--out csv=/out/herd.csv`, and plot `http_req_duration`.

## Environment variables

| Variable | Default | Used by |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | all |
| `RATE` | `200` | all, requests per second |
| `DURATION` | `30s` | all, measured phase |
| `WARMUP` | `10s` | all, discarded phase before it |
| `SEED` | `1` | all, fixes the id sequence and which ids are hot |
| `MAX_ID` | `1000000` | all, must match the seeded row count or 404s skew latency |
| `ZIPF_S` | `1.1` | zipf, flash, mixed, herd |
| `FLASH_ID` | `42` | flash, herd |
| `FLASH_SHARE` | `0.5` | flash, herd |
| `WRITE_SHARE` | `0.05` | mixed |
| `HERD_AT` | `20s` | herd, offset of the PUT into the measured phase (must be < `DURATION`) |

## Rules

- Runs that are compared use the same `RATE`, `DURATION`, `WARMUP`, `SEED` and `MAX_ID`, and the
  same `ZIPF_S` / `FLASH_*` / `WRITE_SHARE` / `HERD_AT` where they apply. Only `hotshield.mode` changes.
- The id sequence comes from a hash of the iteration number and `SEED`, never `Math.random` or the
  VU number, so the same settings replay the same requests.
- Reset `pg_stat_statements` before each run, and record pool size, `hikaricp.connections.pending`
  and `dropped_iterations` with the results.
- The Zipf CDF is built once per k6 process at startup; expect a short pause before the first
  request at 1M ids.

Log every run in `docs/EXPERIMENTS.md`.
