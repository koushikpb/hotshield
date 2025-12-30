#!/usr/bin/env bash
# Prints "epoch_seconds,count" once a second for the findById query counter until killed.
# Prometheus scrapes every 15s by default, which smears a herd spike that lasts well under a
# second; per-second deltas of this output show it.
# BASE_URL=http://localhost:8080 loadtest/poll-metrics.sh > /tmp/herd-queries.csv
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
URL="$BASE_URL/actuator/metrics/db.queries?tag=op:findById"

while true; do
  # A failed poll prints an empty count instead of killing the loop, so a restart mid-run shows
  # up as a gap in the CSV.
  count=$(curl -s "$URL" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["measurements"][0]["value"])' \
    2>/dev/null || true)
  echo "$(date +%s),$count"
  sleep 1
done
