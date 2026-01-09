// Milestone 6: how long after a PUT returns can a read still see the older version?
// Background readers keep a small hot set cached on every node. Writers PUT a hot key, then poll it through
// the load balancer for WINDOW and record the last time a read came back with a version older than the one
// the PUT returned. Polling for the whole window (not stopping at the first fresh read) matters: with round
// robin, one node can be fresh while another still serves the old copy.
// Run against the compose cluster with a long TTL so the TTL doesn't hide lost invalidations:
// HOTSHIELD_CACHE_TTL=10m docker compose --profile cluster up -d --build
// k6 run -e RATE=300 -e WRITE_RATE=5 -e DURATION=60s -e SEED=1 loadtest/staleness.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, READ_TAGS, WRITE_TAGS, buildOptions, seconds, uniform01 } from './lib.js';

const HOT_KEYS = Number(__ENV.HOT_KEYS || 50);
// Writers use a smaller set so concurrent writes to the same key actually happen.
const WRITE_KEYS = Number(__ENV.WRITE_KEYS || 5);
const WRITE_RATE = Number(__ENV.WRITE_RATE || 5);
const WINDOW = __ENV.WINDOW || '1s';
const POLL_MS = Number(__ENV.POLL_MS || 5);

// Two bounds on commit-to-fresh time: k6 can't see the commit, which happens somewhere between sending the
// PUT and getting its response.
const staleness = new Trend('staleness_ms', true);
const stalenessFromSent = new Trend('staleness_from_sent_ms', true);
const staleReads = new Counter('stale_reads');
const pollReads = new Counter('poll_reads');

function hotId(stream) {
  return 1 + Math.floor(uniform01(stream) * HOT_KEYS);
}

export const options = buildOptions({
  writes: {
    executor: 'constant-arrival-rate',
    exec: 'write',
    rate: WRITE_RATE,
    timeUnit: '1s',
    duration: __ENV.DURATION || '30s',
    startTime: __ENV.WARMUP || '10s',
    // Each iteration polls for WINDOW, so it needs about WRITE_RATE * WINDOW VUs at once.
    preAllocatedVUs: Math.ceil(WRITE_RATE * seconds(WINDOW) * 2) + 2,
    maxVUs: Math.ceil(WRITE_RATE * seconds(WINDOW) * 4) + 4,
  },
}, {
  'staleness_ms': ['p(99)>=0'],
  'staleness_from_sent_ms': ['p(99)>=0'],
});

export default function () {
  const res = http.get(`${BASE_URL}/items/${hotId(0)}`, READ_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function write() {
  const id = 1 + Math.floor(uniform01(1) * WRITE_KEYS);
  const body = JSON.stringify({ name: `item ${id}`, priceCents: 1000 + Math.floor(uniform01(2) * 9000), stock: 10 });
  const sentAt = Date.now();
  const put = http.put(`${BASE_URL}/items/${id}`, body, WRITE_TAGS);
  if (!check(put, { 'put 200': (r) => r.status === 200 })) {
    return;
  }
  const written = put.json('version');
  const returnedAt = Date.now();
  let lastStaleAt = null;

  while (Date.now() - returnedAt < seconds(WINDOW) * 1000) {
    const read = http.get(`${BASE_URL}/items/${id}`, { ...READ_TAGS, tags: { name: 'GET /items/{id} poll' } });
    pollReads.add(1);
    if (read.status === 200 && read.json('version') < written) {
      lastStaleAt = Date.now();
      staleReads.add(1);
    }
    sleep(POLL_MS / 1000);
  }
  // Zero when every poll was fresh. Bounded below by POLL_MS resolution and above by WINDOW: a value near
  // WINDOW means the copy stayed stale for the whole window (likely a lost invalidation).
  staleness.add(lastStaleAt === null ? 0 : lastStaleAt - returnedAt);
  stalenessFromSent.add(lastStaleAt === null ? returnedAt - sentAt : lastStaleAt - sentAt);
}
