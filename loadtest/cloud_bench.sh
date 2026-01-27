#!/usr/bin/env bash
# One measured run on AWS, from the runner instance: reset pg_stat_statements on RDS, poll the three nodes,
# run k6 against the ALB, then print the same report as bench.sh. Terraform owns the app and the database,
# so this script starts and stops nothing; apply the mode you want before the run.
#
#   BASE_URL=http://<alb> NODE_URLS="http://node-a.hotshield.local:8080 ..." PGHOST=<rds> COMMIT=<sha> \
#     loadtest/cloud_bench.sh NAME MODE SCENARIO RATE
#
# MODE and COMMIT are recorded, not checked: pass what `terraform output -json run_config` and git say. Use the
# Cloud Map names in NODE_URLS, not task IPs, so the poller follows a replaced task. PGPASSWORD comes from SSM if
# it isn't set. DURATION, WARMUP, K6_ENV and RESEED work as in bench.sh.
set -euo pipefail

if [[ $# -ne 4 ]]; then
  sed -n '2,11p' "$0"
  exit 2
fi
NAME=$1 MODE=$2 SCENARIO=$3 RATE=$4
DURATION=${DURATION:-60s}
WARMUP=${WARMUP:-15s}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/loadtest/results/$NAME
: "${BASE_URL:?}" "${NODE_URLS:?}" "${PGHOST:?}" "${COMMIT:?}"
export PGHOST PGUSER=hotshield PGDATABASE=hotshield
if [[ -z ${PGPASSWORD:-} ]]; then
  PGPASSWORD=$(aws ssm get-parameter --region "${AWS_REGION:-us-east-2}" --name /hotshield/db-password \
    --with-decryption --query Parameter.Value --output text)
  export PGPASSWORD
fi
cd "$ROOT"

if [[ -e $OUT ]]; then
  echo "results/$NAME already exists; pick a new name so no run is overwritten" >&2
  exit 1
fi
mkdir -p "$OUT"

# Only recorded: the runner runs k6 and the poller and nothing else, so there's nothing to refuse on.
echo "runner: load1=$(cut -d' ' -f1 /proc/loadavg) $(nproc) vCPU" > "$OUT/gate.txt"

psql_q() {
  psql -qAt -v ON_ERROR_STOP=1 "$@"
}

# An apply returns before every replaced task answers, and a run that starts then measures a node that is
# still starting.
for url in $NODE_URLS; do
  for _ in $(seq 1 90); do
    curl -sf "$url/actuator/health" >/dev/null && continue 2
    sleep 2
  done
  echo "$url did not become healthy" >&2
  exit 1
done

if [[ ${RESEED:-0} == 1 ]]; then
  psql_q -f loadtest/seed.sql >/dev/null
  psql_q -c 'vacuum analyze items' -c 'checkpoint' >/dev/null
fi
psql_q -c 'create extension if not exists pg_prewarm' \
  -c "select pg_prewarm('items'), pg_prewarm('items_pkey')" >/dev/null
psql_q -c 'select pg_stat_statements_reset()' >/dev/null

cat > "$OUT/meta.env" <<EOF
NAME=$NAME
MODE=$MODE
SCENARIO=$SCENARIO
RATE=$RATE
TARGET=aws
DURATION=$DURATION
WARMUP=$WARMUP
APP_ENV=""
K6_ENV="${K6_ENV:-}"
K6_BASE_URL=""
BASE_URL=$BASE_URL
NODE_URLS="$NODE_URLS"
RESEED=${RESEED:-0}
FAULT=""
COMMIT=$COMMIT
EOF

# k6 and the poller share the runner's clock, which is what the report's window cut needs.
# shellcheck disable=SC2086
python3 loadtest/bench_poll.py $NODE_URLS > "$OUT/poll.jsonl" 2>/dev/null &
POLL_PID=$!
trap 'kill $POLL_PID 2>/dev/null || true' EXIT

# shellcheck disable=SC2086
k6 run -q -e BASE_URL="$BASE_URL" -e RATE="$RATE" -e DURATION="$DURATION" -e WARMUP="$WARMUP" \
  -e SEED=1 -e MAX_ID=1000000 ${K6_ENV:-} --summary-export "$OUT/summary.json" "loadtest/$SCENARIO" \
  > "$OUT/k6.txt" 2>&1 || echo "k6_exit=$?" >> "$OUT/k6.txt"
sleep 1

psql_q -c "select coalesce(sum(calls), 0) from pg_stat_statements where query like '%from items%where id = \$1%'" \
  > "$OUT/pgss.txt"

python3 loadtest/bench_report.py "$OUT" | tee "$OUT/report.md"
