"""Prints the numbers for one bench.sh or cloud_bench.sh run directory.

k6 numbers come from its summary (measured scenario only). Server counters come from the poller, cut to the
measured window: setup() logs the test start, and the measured phase runs from start + WARMUP for DURATION.
Both timestamps come from one clock: the Docker VM's for bench.sh, the runner's for cloud_bench.sh.
"""
import json
import re
import shlex
import sys
from pathlib import Path


def seconds(duration):
    units = {'ms': 0.001, 's': 1, 'm': 60, 'h': 3600}
    return sum(float(n) * units[u] for n, u in re.findall(r'(\d+(?:\.\d+)?)(ms|s|m|h)', duration))


def meta(out):
    values = {}
    for line in (out / 'meta.env').read_text().splitlines():
        key, _, value = line.partition('=')
        values[key] = ' '.join(shlex.split(value))
    return values


def k6_env(meta_values, key, default):
    match = re.search(rf'-e {key}=(\S+)', meta_values['K6_ENV'])
    return match.group(1) if match else default


def samples(out):
    return [json.loads(line) for line in (out / 'poll.jsonl').read_text().splitlines() if line]


def at(rows, t):
    """The last sample at or before t, so a delta never includes counts from outside the window."""
    before = [row for row in rows if row['t'] <= t]
    return before[-1] if before else rows[0]


def delta(rows, key, start, end):
    return at(rows, end).get(key, 0) - at(rows, start).get(key, 0)


def metric(summary, name):
    return summary['metrics'].get(name, {})


def node_name(url):
    """The port locally (8081-8083), the host on AWS, where every node listens on 8080."""
    host, port = url.split('//', 1)[1].rsplit(':', 1)
    return host.split('.')[0] if port == '8080' and host != 'host.docker.internal' else port


def down_intervals(rows, start):
    """Spans of samples where some node didn't answer, relative to the measured start."""
    spans, current = [], None
    for row in rows:
        if row['down'] and current is None:
            current = [row['t'], row['t'], row['down']]
        elif row['down']:
            current[1] = row['t']
        elif current is not None:
            spans.append(current)
            current = None
    if current is not None:
        spans.append(current)
    return [f"{','.join(node_name(u) for u in nodes)} down {a - start:+.1f}s to {b - start:+.1f}s"
            for a, b, nodes in spans]


def main(out):
    m = meta(out)
    summary = json.loads((out / 'summary.json').read_text())
    k6_text = (out / 'k6.txt').read_text()
    start_ms = re.search(r'test_start_ms=(\d+)', k6_text)
    if not start_ms:
        sys.exit('no test_start_ms in k6.txt: did the scenario export setup from lib.js?')
    start = int(start_ms.group(1)) / 1000 + seconds(m['WARMUP'])
    duration = seconds(m['DURATION'])
    end = start + duration
    rows = samples(out)

    if m.get('FAULT'):
        fault = out / 'fault.txt'
        lines = fault.read_text().split('\n') if fault.exists() else []
        if not any(line.startswith('unpaused') for line in lines):
            sys.exit(f"FAULT DID NOT RUN ({m['FAULT']}); fault.txt: {lines}. Don't record this run.")

    k6_reqs = metric(summary, 'http_reqs{scenario:measured}').get('count', 0)
    latency = metric(summary, 'http_req_duration{scenario:measured}')
    failed = metric(summary, 'http_req_failed{scenario:measured}').get('value', 0)
    # The warmup is discarded, so only measured drops break open loop; warmup drops (a cold JVM) are listed
    # beside them because k6's exit code counts both.
    dropped = metric(summary, 'dropped_iterations{scenario:measured}').get('count', 0)
    warmup_dropped = metric(summary, 'dropped_iterations{scenario:warmup}').get('count', 0)
    # Scenarios such as staleness.js' writers can drop too; they aren't the measured reads but k6 fails on them.
    other_dropped = metric(summary, 'dropped_iterations').get('count', 0) - dropped - warmup_dropped
    # Server-side GETs in the window, from every scenario: herd's burst and staleness' pollers read too, and
    # their misses are in the query counter, so they belong in the denominator.
    gets = delta(rows, 'gets', start, end)
    queries = delta(rows, 'findById', start, end)
    hits = delta(rows, 'hit', start, end)
    misses = delta(rows, 'miss', start, end)
    live = [row for row in rows if start <= row['t'] <= end]
    pending = max((row.get('pending', 0) for row in live), default=0)
    pool = max((row.get('pool_max', 0) for row in rows), default=0)
    exit_note = re.search(r'k6_exit=(\d+)', k6_text)

    print(f"### {m['NAME']}")
    print(f"- Commit: {m['COMMIT']}")
    where = ('k6 on the runner, three Fargate tasks, RDS' if m['TARGET'] == 'aws'
             else 'k6, the app and Postgres share one machine')
    print(f"- Mode: {m['MODE']} ({m['TARGET']}; {where})")
    print(f"- Load: {m['SCENARIO']}, RATE={m['RATE']}, DURATION={m['DURATION']}, WARMUP={m['WARMUP']}, SEED=1, "
          f"MAX_ID=1000000 {m['K6_ENV']}".rstrip())
    if m.get('K6_BASE_URL'):
        print(f"- k6 target: {m['K6_BASE_URL']} (one node, not the load balancer)")
    print(f"- App env: {m['APP_ENV'] or '(defaults)'}; DB pool size {pool:.0f}; reseeded before run: "
          f"{m['RESEED'] == '1'}; table prewarmed")
    print(f"- {'; '.join((out / 'gate.txt').read_text().split(chr(10))).strip('; ')}")
    if m.get('FAULT'):
        print(f"- Fault: {m['FAULT']}; seen by the poller as: {'; '.join(down_intervals(rows, start)) or 'none'}")
    elif down_intervals(rows, start):
        print(f"- Nodes not answering the poller: {'; '.join(down_intervals(rows, start))}")
    if exit_note:
        print(f"- k6 exited {exit_note.group(1)} (99 = a threshold failed, usually dropped iterations)")
    print()
    print('| Metric | Value |')
    print('|---|---|')
    print(f"| Achieved req/s (k6, measured scenario) | {k6_reqs / duration:.1f} |")
    if abs(gets - k6_reqs) > 0.02 * max(k6_reqs, 1):
        print(f"| Server GET req/s in window (all scenarios) | {gets / duration:.1f} |")
    print(f"| DB queries/s (findById) | {queries / duration:.1f} |")
    if gets:
        print(f"| DB queries per GET | {queries / gets:.4f} |")
        print(f"| GETs served without a DB read % | {100 * (1 - queries / gets):.2f} |")
    print(f"| p50 / p95 / p99 (ms) | {latency.get('p(50)', 0):.2f} / {latency.get('p(95)', 0):.2f} / "
          f"{latency.get('p(99)', 0):.2f} |")
    print(f"| Max (ms) | {latency.get('max', 0):.1f} |")
    print(f"| Error % | {100 * failed:.3f} |")
    if hits + misses:
        # Not "requests served from cache": a coalesce leader checks the cache again before loading, and a
        # forwarded miss is looked up on two nodes. Use the row above for that.
        print(f"| Cache hit % per lookup | {100 * hits / (hits + misses):.2f} |")
    print(f"| Pool pending (max) | {pending:.0f} |")
    print(f"| k6 dropped_iterations (measured; warmup) | {dropped}; {warmup_dropped} |")
    if other_dropped:
        print(f"| k6 dropped_iterations in other scenarios | {other_dropped} |")
    print(f"| pg_stat_statements findById calls (whole run) | {(out / 'pgss.txt').read_text().strip()} |")
    forwards = delta(rows, 'forward_ok', start, end), delta(rows, 'forward_fallback', start, end)
    if any(forwards):
        print(f"| Owner forwards ok / fallback | {forwards[0]:.0f} / {forwards[1]:.0f} |")

    if m['SCENARIO'] == 'herd.js':
        herd(m, summary, rows, start)
    if m['SCENARIO'] == 'staleness.js':
        staleness(summary)


def herd(m, summary, rows, start):
    put_at = start + seconds(k6_env(m, 'HERD_AT', '20s'))
    burst = metric(summary, 'http_req_duration{scenario:burst}')
    put = metric(summary, 'http_req_duration{scenario:invalidate}')
    print()
    print('Herd: findById queries per poller sample around the PUT (t=0 is when the PUT was scheduled)')
    print()
    print('| t (s) | queries |')
    print('|---|---|')
    window = [row for row in rows if put_at - 1 <= row['t'] <= put_at + 2]
    for previous, row in zip(window, window[1:]):
        print(f"| {row['t'] - put_at:+.1f} | {row.get('findById', 0) - previous.get('findById', 0):.0f} |")
    print()
    print(f"- Queries from PUT to PUT+1s: {delta(rows, 'findById', put_at, put_at + 1):.0f}")
    put_ms = f"{put['max']:.1f} ms" if 'max' in put else 'n/a (not in this summary)'
    print(f"- PUT latency: {put_ms} (BURST_DELAY={k6_env(m, 'BURST_DELAY', '10ms')})")
    print(f"- Burst reads: {metric(summary, 'http_reqs{scenario:burst}').get('count', 0)}, "
          f"p50/p99 {burst.get('p(50)', 0):.1f} / {burst.get('p(99)', 0):.1f} ms")


def staleness(summary):
    print()
    for name in ('staleness_ms', 'staleness_from_sent_ms'):
        trend = metric(summary, name)
        print(f"- {name}: p50 {trend.get('p(50)', 0):.1f}, p95 {trend.get('p(95)', 0):.1f}, "
              f"p99 {trend.get('p(99)', 0):.1f}, max {trend.get('max', 0):.1f}")
    print(f"- stale_reads: {metric(summary, 'stale_reads').get('count', 0)}, "
          f"poll_reads: {metric(summary, 'poll_reads').get('count', 0)}")


if __name__ == '__main__':
    main(Path(sys.argv[1]))
