"""Samples the server counters every 200ms and prints one JSON line per sample, summed across nodes.

bench.sh runs this in a container, so its timestamps come from the same Docker VM clock as k6's
test_start_ms; on the host the two clocks could disagree. cloud_bench.sh runs it on the runner, beside k6. A node that doesn't answer contributes its last
known counters and is listed under "down", so a paused node shows up in the data instead of as a gap.
"""
import json
import re
import sys
import time
import urllib.request

# Greedy up to the last '}': label values such as uri="/items/{id}" contain braces.
LINE = re.compile(r'^([a-z_]+)\{(.*)\} (\S+)$', re.M)
WANTED = {
    ('db_queries_total', ('op="findById"',)): 'findById',
    ('db_queries_total', ('op="update"',)): 'update',
    ('cache_gets_total', ('result="hit"',)): 'hit',
    ('cache_gets_total', ('result="miss"',)): 'miss',
    ('owner_forwards_total', ('result="ok"',)): 'forward_ok',
    ('owner_forwards_total', ('result="fallback"',)): 'forward_fallback',
    # Client-facing reads only; /internal forwards between nodes are not logical requests.
    ('http_server_requests_seconds_count', ('method="GET"', 'uri="/items/{id}"')): 'gets',
}
GAUGES = {'hikaricp_connections_pending': 'pending', 'hikaricp_connections_max': 'pool_max'}


def sample(url):
    body = urllib.request.urlopen(url + '/actuator/prometheus', timeout=0.5).read().decode()
    values = {}
    for name, labels, value in LINE.findall(body):
        if name in GAUGES:
            values[GAUGES[name]] = float(value)
            continue
        for (wanted_name, wanted_labels), key in WANTED.items():
            if name == wanted_name and all(label in labels for label in wanted_labels):
                # http_server_requests has one series per status and outcome; add them up.
                values[key] = values.get(key, 0) + float(value)
    return values


def main(urls):
    last = {url: {} for url in urls}
    while True:
        t = time.time()
        down = []
        for url in urls:
            try:
                last[url] = sample(url)
            except Exception:
                down.append(url)
        total = {'t': round(t, 3), 'down': down}
        for url in urls:
            for key, value in last[url].items():
                if key in ('pending', 'pool_max'):
                    # A gauge per pool; the worst node is what shows overload. A down node's last value is
                    # stale, so it doesn't count.
                    if url not in down:
                        total[key] = max(total.get(key, 0), value)
                else:
                    total[key] = total.get(key, 0) + value
        print(json.dumps(total), flush=True)
        time.sleep(0.2)


if __name__ == '__main__':
    main(sys.argv[1:])
