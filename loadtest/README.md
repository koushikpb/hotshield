# Load scenarios

Milestone 4. Use k6's `constant-arrival-rate` executor (open loop) so slow responses don't hide latency.

- `uniform.js` — keys uniform over 1M ids
- `zipf.js` — Zipf(s≈1.1) over 1M ids
- `flash.js` — ~50% of requests to one id, rest Zipf
- `mixed.js` — 95/5 read/write on Zipf keys (invalidation experiments)

Log every run in `docs/EXPERIMENTS.md`.
