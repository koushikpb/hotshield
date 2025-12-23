// Thundering herd: steady flash-key traffic (same mix as flash.js), then one PUT to FLASH_ID at
// HERD_AT into the measured phase. The PUT invalidates the cached row, so every read of FLASH_ID
// that arrives before the next fill is a concurrent miss. Without coalescing each one is a DB
// query; with it they should collapse to one.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 -e HERD_AT=20s loadtest/herd.js
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL, DURATION, READ_TAGS, WARMUP, WRITE_TAGS,
  buildOptions, seconds, uniform01, zipfSampler,
} from './lib.js';

const FLASH_ID = Number(__ENV.FLASH_ID || 42);
const FLASH_SHARE = Number(__ENV.FLASH_SHARE || 0.5);
const HERD_AT = __ENV.HERD_AT || '20s';
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
    startTime: `${seconds(WARMUP) + seconds(HERD_AT)}s`,
    maxDuration: '10s',
  },
}, {
  'checks{scenario:invalidate}': ['rate==1'],
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
