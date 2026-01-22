// Thundering herd: steady flash-key traffic (same mix as flash.js), then one PUT to FLASH_ID at
// HERD_AT into the measured phase. The PUT invalidates the cached row, so every read of FLASH_ID
// that arrives before the next fill is a concurrent miss. Without coalescing each one is a DB
// query; with it they should collapse to one.
//
// The steady traffic alone is a weak herd: at RATE * FLASH_SHARE req/s only a few reads land in
// the miss window. So BURST_VUS extra reads of FLASH_ID fire together BURST_DELAY after the PUT
// starts, and their latency is reported on its own. The burst is placed on the clock, not on the
// PUT's response, so a slow PUT can push the invalidation past the burst; check the PUT's latency
// in the summary.
// Run the app with HOTSHIELD_CACHE_TTL and HOTSHIELD_DB_DELAY set as described in README.md.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 -e HERD_AT=20s loadtest/herd.js
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL, DURATION, READ_TAGS, WARMUP, WRITE_TAGS,
  buildOptions, seconds, uniform01, zipfSampler,
} from './lib.js';
export { setup } from './lib.js';

const FLASH_ID = Number(__ENV.FLASH_ID || 42);
const FLASH_SHARE = Number(__ENV.FLASH_SHARE || 0.5);
const HERD_AT = __ENV.HERD_AT || '20s';
const BURST_VUS = Number(__ENV.BURST_VUS || 200);
const BURST_DELAY = __ENV.BURST_DELAY || '50ms';
const BURST_TAGS = { ...READ_TAGS, tags: { name: 'GET /items/{id} burst' } };
const putAt = seconds(WARMUP) + seconds(HERD_AT);
const zipfId = zipfSampler();

if (seconds(HERD_AT) >= seconds(DURATION)) {
  throw new Error(`HERD_AT (${HERD_AT}) must fall inside DURATION (${DURATION})`);
}

export const options = buildOptions({
  invalidate: {
    executor: 'shared-iterations',
    exec: 'invalidate',
    vus: 1,
    iterations: 1,
    startTime: `${putAt.toFixed(3)}s`,
    maxDuration: '10s',
  },
  burst: {
    executor: 'per-vu-iterations',
    exec: 'burst',
    vus: BURST_VUS,
    iterations: 1,
    startTime: `${(putAt + seconds(BURST_DELAY)).toFixed(3)}s`,
    maxDuration: '10s',
  },
}, {
  'checks{scenario:invalidate}': ['rate==1'],
  // Listed only so the summary export carries it: the burst lands in the miss window only if the PUT
  // returned before BURST_DELAY and the refill (one DB_DELAY) hadn't finished yet.
  'http_req_duration{scenario:invalidate}': ['max>=0'],
  'http_req_duration{scenario:burst}': ['p(99)>=0'],
  'http_reqs{scenario:burst}': ['count>=0'],
  'checks{scenario:burst}': ['rate>=0'],
});

export default function () {
  const id = uniform01(1) < FLASH_SHARE ? FLASH_ID : zipfId();
  const res = http.get(`${BASE_URL}/items/${id}`, READ_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function invalidate() {
  const body = JSON.stringify({ name: `item ${FLASH_ID} herd`, priceCents: 4200, stock: 42 });
  const res = http.put(`${BASE_URL}/items/${FLASH_ID}`, body, WRITE_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function burst() {
  const res = http.get(`${BASE_URL}/items/${FLASH_ID}`, BURST_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}
