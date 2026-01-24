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
- `HOTSHIELD_DB_DELAY=50ms`, an artificial delay before every `findById` (default 0, herd runs
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
| `BURST_DELAY` | `10ms` | herd, how long after the PUT starts the burst fires |

## Rules

- Runs that are compared use the same `RATE`, `DURATION`, `WARMUP`, `SEED` and `MAX_ID`, and the
  same `ZIPF_S` / `FLASH_*` / `WRITE_SHARE` / `HERD_AT` where they apply. Only `hotshield.mode` changes.
- The id sequence comes from a hash of the iteration number and `SEED`, never `Math.random` or the
  VU number, so the same settings replay the same requests.
- Reset `pg_stat_statements` before each run, and record pool size, `hikaricp.connections.pending`
  and `dropped_iterations` with the results.
- Restart the app between modes. Counters such as `cache.gets` are cumulative, and a warm cache
  from the previous mode would leak into the next one.
- `mixed.js` and `herd.js` write rows. Reseed (`loadtest/seed.sql`) after them, or keep a fixed run order so every
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

`staleness.js` can't see the commit, so it reports two bounds: `staleness_ms` from when the PUT returned
and `staleness_from_sent_ms` from when it was sent. The PUT returns only after the invalidation broadcast
finishes or times out, so in a healthy run `staleness_ms` is about 0 by construction. The interesting run is
the fault pass: `docker pause hotshield-node-b-1` for a few seconds mid-run, then `docker unpause`, so
invalidations to node-b are lost. The pollers add about `WRITE_RATE * 1000 / POLL_MS` req/s on top of
`RATE`; report it with the run. Run it with `HOTSHIELD_CACHE_TTL=10m`, so the TTL cannot hide a lost
invalidation, and record `stale_reads`, `poll_reads`, and the `staleness_ms` percentiles.

## bench.sh

`bench.sh` does one measured run end to end: it restarts the app in the given mode, resets
`pg_stat_statements`, samples the server counters every 200ms, runs k6 (`grafana/k6:2.3.0`) and prints a
`docs/EXPERIMENTS.md`-shaped block cut to the measured window. Run `loadtest/bench.sh` with no arguments for
the options. It refuses to start while the machine is busy (load average above 2.5, under 30% free memory,
or containers from other projects running), because those numbers would measure the machine.

Before the first run of a session: `./gradlew bootJar`, `docker compose up -d`, seed once with
`docker compose exec -T postgres psql -U hotshield hotshield < loadtest/seed.sql`, and close what you can.

## Benchmark plan

The order matters: each step's rate comes from the step before it, and every comparison within a step
uses the same rate, duration, warmup and seed. `bench.sh` restarts the app and prewarms the table before
every run, so no separate warmup pass is needed.

1. **Direct capacity.** Run `loadtest/bench.sh cap-direct-<rate> direct zipf.js <rate>` at 500, 1000, 1500
   and 2000. `RATE` is the highest rate where direct mode had 0 dropped iterations and under 1% errors, so
   direct mode is loaded but still valid and cache and coalesce have something to improve on.
2. **Modes at the same rate (milestones 2–4).** For each of `uniform.js`, `zipf.js` and `flash.js`, run
   `direct`, `cache` and `coalesce`: `loadtest/bench.sh <scenario>-<mode> <mode> <scenario>.js $RATE`.
   Run `direct` first for each scenario: uniform reads colder pages than zipf, so direct may not hold
   `$RATE` there. If it drops iterations, rerun all three modes of that scenario at a lower rate.
3. **Herd (milestone 4).** `APP_ENV="HOTSHIELD_CACHE_TTL=10m HOTSHIELD_DB_DELAY=50ms" RESEED=1` with `herd.js` at 300 req/s, in `cache` and `coalesce`. Not `direct`:
   without a cache there is no herd, every read is already a query. The burst has to land between the PUT
   returning and the refill finishing, a window about one `DB_DELAY` long. With 20ms and a
   50ms `BURST_DELAY` the key was already back in the cache when the burst fired. Check the printed PUT
   latency is under `BURST_DELAY`. Don't raise `DB_DELAY` much further: at 200ms the ordinary misses
   alone keep all 10 pool connections busy.
4. **Cluster (milestone 5).** `TARGET=cluster` with `zipf.js` and `flash.js` at `$RATE`, in `coalesce`
   (three independent caches behind nginx) and `distributed`. The number to compare is DB queries per GET.
5. **Owner down (milestone 5).** Node-a owns `FLASH_ID=42` for nodes a, b and c. Send traffic to node-b
   only and pause node-a, so every forward for 42 has to time out and fall back:
   `TARGET=cluster K6_BASE_URL=http://host.docker.internal:8082 APP_ENV="HOTSHIELD_CACHE_TTL=2s"
   PAUSE_NODE=node-a PAUSE_AT=20 PAUSE_FOR=10` with `flash.js` at 300 req/s in `distributed`. The short TTL
   makes node-b miss 42 every 2s; with the default 30s it might not miss at all during the pause. Through
   nginx a third of requests would go to the paused node itself and show up as client timeouts, which is a
   different failure than the one this step measures. Record forward fallbacks, errors and latency.
6. **Staleness (milestone 6).** `TARGET=cluster RESEED=1 APP_ENV="HOTSHIELD_CACHE_TTL=10m"
   K6_ENV="-e WRITE_RATE=5"` with `staleness.js` at 300 req/s in `distributed`, once healthy and once
   with `PAUSE_AT=20 PAUSE_FOR=5` (node-b by default).

Copy each printed block into `docs/EXPERIMENTS.md` and add the hypothesis before the run, not after.
`DB queries per GET` counts every GET the servers saw in the window, including herd's burst and the
staleness pollers; `Cache hit % per lookup` is not the same thing, because a coalesce leader checks the
cache twice and a forwarded miss is looked up on two nodes.
