#!/usr/bin/env bash
# One measured run: restart the app in MODE, reset pg_stat_statements, poll the server counters, run k6,
# then print the numbers for a docs/EXPERIMENTS.md entry. Results go to loadtest/results/NAME (gitignored).
#
#   loadtest/bench.sh NAME MODE SCENARIO RATE
#   loadtest/bench.sh zipf-direct-800 direct zipf.js 800
#   APP_ENV="HOTSHIELD_CACHE_TTL=10m HOTSHIELD_DB_DELAY=20ms" loadtest/bench.sh herd-coalesce coalesce herd.js 300
#   TARGET=cluster loadtest/bench.sh zipf-dist-800 distributed zipf.js 800
#
# TARGET=single runs build/libs/app.jar on the host (:8080). TARGET=cluster recreates the three compose nodes;
# there APP_ENV can only set what docker-compose.yml passes through (HOTSHIELD_CACHE_TTL, HOTSHIELD_DB_DELAY,
# TRACING, TRACE_RATIO). K6_ENV adds k6 -e flags ("-e FLASH_SHARE=0.5"). RESEED=1 reseeds first, for runs after
# mixed.js, herd.js or staleness.js. DURATION and WARMUP default to 60s and 15s. KEEP_APP=1 leaves the app
# running afterwards (to look at traces or metrics). With TARGET=cluster, PAUSE_AT=N pauses PAUSE_NODE
# (default node-b) N seconds into the measured phase for PAUSE_FOR seconds (default 5). K6_BASE_URL points k6
# at one node instead of the load balancer (http://host.docker.internal:8082 is node-b).
set -euo pipefail

if [[ $# -ne 4 ]]; then
  sed -n '2,16p' "$0"
  exit 2
fi
NAME=$1 MODE=$2 SCENARIO=$3 RATE=$4
TARGET=${TARGET:-single}
DURATION=${DURATION:-60s}
WARMUP=${WARMUP:-15s}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/loadtest/results/$NAME
POLLER=hotshield-bench-poller
cd "$ROOT"

if [[ -e $OUT ]]; then
  echo "results/$NAME already exists; pick a new name so no run is overwritten" >&2
  exit 1
fi

# Numbers from a machine that is swapping or busy with something else measure the machine. Refuse to start
# rather than produce them; BENCH_FORCE=1 overrides and the override is recorded with the run.
gate() {
  local load free others
  load=$(sysctl -n vm.loadavg | awk '{print $2}')
  free=$(memory_pressure | awk -F': ' '/free percentage/ {print $2+0}')
  others=$(docker ps --format '{{.Names}}' | grep -v '^hotshield-' || true)
  echo "gate: load1=$load free=${free}% swap=$(sysctl -n vm.swapusage | awk '{print $6}') other_containers=[${others//$'\n'/ }]"
  if awk -v l="$load" 'BEGIN {exit !(l > 2.5)}' || (( free < 30 )) || [[ -n $others ]]; then
    if [[ ${BENCH_FORCE:-0} != 1 ]]; then
      echo "gate: machine is busy (need load1 <= 2.5, free >= 30%, no other containers); BENCH_FORCE=1 to run anyway" >&2
      return 1
    fi
    echo "gate: FORCED"
  fi
}

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -sf "$1/actuator/health" >/dev/null && return 0
    sleep 1
  done
  echo "app at $1 did not become healthy" >&2
  exit 1
}

seconds() {
  python3 -c "import re, sys; print(sum(float(n) * {'ms': 0.001, 's': 1, 'm': 60, 'h': 3600}[u]
    for n, u in re.findall(r'(\d+(?:\.\d+)?)(ms|s|m|h)', sys.argv[1])))" "$1"
}

psql_q() {
  docker compose exec -T postgres psql -qAtU hotshield hotshield -v ON_ERROR_STOP=1 "$@"
}

# Both targets serve :8080. Waits for the port to close, because a JVM that is still shutting down
# gracefully would otherwise answer the health check for the new run.
stop_app() {
  pkill -f 'build/libs/app.jar' || true
  docker compose --profile cluster stop node-a node-b node-c lb jaeger >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    lsof -ti tcp:8080 -sTCP:LISTEN >/dev/null || return 0
    sleep 1
  done
  echo "something still listens on :8080 (a ./gradlew bootRun?): $(lsof -ti tcp:8080 -sTCP:LISTEN | xargs ps -o command= -p)" >&2
  exit 1
}

cleanup() {
  docker rm -f "$POLLER" >/dev/null 2>&1 || true
  # Each line ends in "|| true": under set -e a failing command here would end the trap before stop_app.
  [[ -z ${FAULT_PID:-} ]] || kill "$FAULT_PID" 2>/dev/null || true
  # A fault subshell killed mid-pause would leave the node frozen for the next run.
  [[ -z ${PAUSE_AT:-} ]] || docker unpause "hotshield-${PAUSE_NODE:-node-b}-1" >/dev/null 2>&1 || true
  [[ ${KEEP_APP:-0} == 1 ]] || stop_app
}

gate_line=$(gate)
mkdir -p "$OUT"
echo "$gate_line" | tee "$OUT/gate.txt"
docker compose up -d --wait postgres >/dev/null 2>&1

if [[ ${RESEED:-0} == 1 ]]; then
  psql_q < loadtest/seed.sql >/dev/null
  # A fresh table has no visibility map and leaves autovacuum and a checkpoint to land mid-run.
  psql_q -c 'vacuum analyze items' -c 'checkpoint' >/dev/null
fi
# Every run starts from the same buffer state: the table and its primary key loaded, as far as they fit.
psql_q -c 'create extension if not exists pg_prewarm' \
  -c "select pg_prewarm('items'), pg_prewarm('items_pkey')" >/dev/null

trap cleanup EXIT
stop_app
if [[ $TARGET == single ]]; then
  # No daemon: an idle Gradle daemon would hold about 400 MB through the measured run.
  ./gradlew bootJar -q --no-daemon
  # shellcheck disable=SC2086
  env HOTSHIELD_MODE="$MODE" ${APP_ENV:-} java -jar build/libs/app.jar > "$OUT/app.log" 2>&1 &
  APP_PID=$!
  wait_healthy http://localhost:8080
  if [[ $(lsof -ti tcp:8080 -sTCP:LISTEN) != "$APP_PID" ]]; then
    echo "the process on :8080 is not the one this run started" >&2
    exit 1
  fi
  NODE_URLS="http://host.docker.internal:8080"
else
  # shellcheck disable=SC2086
  if ! env HOTSHIELD_MODE="$MODE" ${APP_ENV:-} docker compose --profile cluster up -d --build --force-recreate \
      --wait node-a node-b node-c lb > "$OUT/compose.log" 2>&1; then
    tail -5 "$OUT/compose.log" >&2
    exit 1
  fi
  for port in 8081 8082 8083; do wait_healthy "http://localhost:$port"; done
  NODE_URLS="http://host.docker.internal:8081 http://host.docker.internal:8082 http://host.docker.internal:8083"
fi

psql_q -c 'select pg_stat_statements_reset()' >/dev/null

git status --porcelain > "$OUT/git-status.txt"
git diff HEAD > "$OUT/git-diff.patch"
cat > "$OUT/meta.env" <<EOF
NAME=$NAME
MODE=$MODE
SCENARIO=$SCENARIO
RATE=$RATE
TARGET=$TARGET
DURATION=$DURATION
WARMUP=$WARMUP
APP_ENV="${APP_ENV:-}"
K6_ENV="${K6_ENV:-}"
K6_BASE_URL="${K6_BASE_URL:-}"
RESEED=${RESEED:-0}
FAULT="${PAUSE_AT:+pause ${PAUSE_NODE:-node-b} at measured+${PAUSE_AT}s for ${PAUSE_FOR:-5}s}"
COMMIT=$(git rev-parse --short HEAD)$(if [[ -s $OUT/git-status.txt ]]; then echo -dirty; fi)
EOF

# shellcheck disable=SC2086
docker run --rm --name "$POLLER" --add-host=host.docker.internal:host-gateway \
  -v "$ROOT/loadtest:/scripts:ro" python:3.12.7-alpine python /scripts/bench_poll.py $NODE_URLS \
  > "$OUT/poll.jsonl" 2>/dev/null &

# The fault pass freezes one node so the invalidations sent to it are lost. Timed from k6's own
# test_start_ms line, not from launch, because k6 takes a variable second or two to boot. The report takes
# the real pause from the poller's "down" samples; fault.txt (host clock) is only a log.
if [[ -n ${PAUSE_AT:-} ]]; then
  pause_after=$(python3 -c "print($(seconds "$WARMUP") + $PAUSE_AT)")
  (
    for _ in $(seq 1 1200); do
      grep -q test_start_ms "$OUT/k6.txt" 2>/dev/null && break
      sleep 0.05
    done
    start_ms=$(grep -o 'test_start_ms=[0-9]*' "$OUT/k6.txt" | cut -d= -f2) || exit 1
    python3 -c "import time; time.sleep(max(0, $start_ms / 1000 + $pause_after - time.time()))"
    node=hotshield-${PAUSE_NODE:-node-b}-1
    docker pause "$node" >/dev/null
    echo "paused $node at $(python3 -c 'import time; print(round(time.time(), 1))')" >> "$OUT/fault.txt"
    sleep "${PAUSE_FOR:-5}"
    docker unpause "$node" >/dev/null
    echo "unpaused $node at $(python3 -c 'import time; print(round(time.time(), 1))')" >> "$OUT/fault.txt"
  ) &
  FAULT_PID=$!
fi

# k6 exits 99 when a threshold fails (dropped iterations); keep going so the report says what happened.
# shellcheck disable=SC2086
docker run --rm -i -v "$ROOT/loadtest:/scripts:ro" -v "$OUT:/out" grafana/k6:2.3.0 run -q \
  -e BASE_URL="${K6_BASE_URL:-http://host.docker.internal:8080}" -e RATE="$RATE" -e DURATION="$DURATION" -e WARMUP="$WARMUP" \
  -e SEED=1 -e MAX_ID=1000000 ${K6_ENV:-} --summary-export /out/summary.json "/scripts/$SCENARIO" \
  > "$OUT/k6.txt" 2>&1 || echo "k6_exit=$?" >> "$OUT/k6.txt"
sleep 1
[[ -z ${FAULT_PID:-} ]] || wait "$FAULT_PID" 2>/dev/null || true

psql_q -c "select coalesce(sum(calls), 0) from pg_stat_statements where query like '%from items%where id = \$1%'" \
  > "$OUT/pgss.txt"

python3 loadtest/bench_report.py "$OUT" | tee "$OUT/report.md"
