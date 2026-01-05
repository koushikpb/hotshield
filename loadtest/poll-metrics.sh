#!/usr/bin/env bash
# Prints "epoch_seconds,count" once a second for the findById query counter until killed.
# Prometheus scrapes every 15s by default, which smears a herd spike that lasts well under a
# second; per-second deltas of this output show it.
#
# Pass every node's own URL, not the load balancer: through round robin each sample would come
# from a different node's counter. The count is the sum across nodes.
#   NODE_URLS="http://localhost:8081 http://localhost:8082 http://localhost:8083" loadtest/poll-metrics.sh
set -euo pipefail

NODE_URLS="${NODE_URLS:-http://localhost:8080}"

while true; do
  total=0
  for url in $NODE_URLS; do
    # A failed poll makes the whole sample empty instead of killing the loop, so a node restart
    # mid-run shows up as a gap in the CSV rather than a misleading partial sum.
    count=$(curl -s --max-time 0.5 "$url/actuator/metrics/db.queries?tag=op:findById" \
      | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))' \
      2>/dev/null || true)
    if [[ -z "$count" ]]; then
      total=""
      break
    fi
    total=$((total + count))
  done
  echo "$(date +%s),$total"
  sleep 1
done
