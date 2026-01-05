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
  while many reads for it are in flight, then a burst of `BURST_VUS` reads of the flash id
  `BURST_DELAY` after the PUT (reported as `{scenario:burst}`)
- `lib.js` — shared options, seeded random draws, Zipf sampler
- `baseline.js` — the milestone 1 script, kept so earlier runs stay reproducible
- `poll-metrics.sh` — samples the `findById` query counter once a second (for herd runs)
- `staleness.js` — concurrent writes plus reads on a small hot set; reports how long reads keep returning an older version after a PUT returns (milestone 6)

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

Achieved throughput is the `http_reqs{scenario:measured}` count divided by `DURATION`, not the
whole-run `http_reqs` rate, which also counts warmup.

Every request has a 5s client timeout, above Hikari's 2s connection-timeout, so pool exhaustion
shows up as server 5xx in `http_req_failed{scenario:measured}` rather than as client timeouts.

### Herd runs

Start the app with two extra settings:

- `HOTSHIELD_CACHE_TTL=10m`, so a TTL expiry on the flash id can't land near the PUT and blur
  which event caused the misses.
- `HOTSHIELD_DB_DELAY=20ms`, an artificial delay before every `findById` (default 0, herd runs
  only). A local primary-key lookup returns so quickly that concurrent
  misses barely overlap, which leaves nothing to coalesce.

Record both values in the `docs/EXPERIMENTS.md` entry; results with a different delay aren't
comparable.

The Prometheus default 15s scrape is too coarse to see a spike that lasts under a second. Run
`poll-metrics.sh` alongside k6 and read the per-second deltas of the count (it needs `python3`):

```
# single node:
loadtest/poll-metrics.sh > /tmp/k6/herd-queries.csv &
# compose cluster: each node directly, summed (nginx would hand each sample to a different node)
NODE_URLS="http://localhost:8081 http://localhost:8082 http://localhost:8083" loadtest/poll-metrics.sh > /tmp/k6/herd-queries.csv &
# ... k6 run herd.js ...
kill %1
```

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
| `BURST_VUS` | `200` | herd, reads of the flash id fired together after the PUT |
| `BURST_DELAY` | `50ms` | herd, how long after the PUT starts the burst fires |

## Rules

- Runs that are compared use the same `RATE`, `DURATION`, `WARMUP`, `SEED` and `MAX_ID`, and the
  same `ZIPF_S` / `FLASH_*` / `WRITE_SHARE` / `HERD_AT` where they apply. Only `hotshield.mode` changes.
- The id sequence comes from a hash of the iteration number and `SEED`, never `Math.random` or the
  VU number, so the same settings replay the same requests.
- Reset `pg_stat_statements` before each run, and record pool size, `hikaricp.connections.pending`
  and `dropped_iterations` with the results.
- Restart the app between modes. Counters such as `cache.gets` are cumulative, and a warm cache
  from the previous mode would leak into the next one.
- `mixed.js` and `herd.js` write rows. Reseed after them, or keep a fixed run order so every
  mode sees the same data.
- Postgres' buffer cache carries over between runs, so the second mode tested reads from warmer
  pages than the first. Either run one discarded warmup pass before the measured runs or restart
  Postgres between them; pick one and record which in the experiment entry.
- When the app runs in a container, `DB_URL` must point at `postgres:5432`, not `localhost`
  (`docker-compose.yml` sets it).
- The Zipf CDF is built once per k6 process at startup; expect a short pause before the first
  request at 1M ids.

Log every run in `docs/EXPERIMENTS.md`.

## Staleness (milestone 6)

`staleness.js` times staleness from when the PUT *returns*, not from the commit, because k6 can't see the
commit. The PUT returns only after the invalidation broadcast finishes or times out, so this number is a
lower bound on commit-to-fresh time. Run it with `HOTSHIELD_CACHE_TTL=10m`, so the TTL cannot hide a lost
invalidation, and record `stale_reads`, `poll_reads`, and the `staleness_ms` percentiles.
