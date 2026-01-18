// Reads and writes on the same Zipf keys (WRITE_SHARE default 0.05), for the invalidation and
// staleness experiments. Writes land on hot keys too, which is where stale reads would show.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 loadtest/mixed.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { BASE_URL, READ_TAGS, WRITE_TAGS, buildOptions, uniform01, zipfSampler } from './lib.js';
export { setup } from './lib.js';

const WRITE_SHARE = Number(__ENV.WRITE_SHARE || 0.05);
const zipfId = zipfSampler();

export const options = buildOptions({}, {
  // Reads and writes have very different latencies; one combined p99 would hide both.
  'http_req_duration{scenario:measured,name:GET /items/{id}}': ['p(99)>=0'],
  'http_req_duration{scenario:measured,name:PUT /items/{id}}': ['p(99)>=0'],
});

export default function () {
  const id = zipfId();
  if (uniform01(1) >= WRITE_SHARE) {
    const res = http.get(`${BASE_URL}/items/${id}`, READ_TAGS);
    check(res, { 'status 200': (r) => r.status === 200 });
    return;
  }
  // Values derive from the iteration so a write always changes the row and replays identically.
  const n = exec.scenario.iterationInTest;
  const body = JSON.stringify({ name: `item ${id} w${n}`, priceCents: 100 + (n % 10000), stock: n % 500 });
  const res = http.put(`${BASE_URL}/items/${id}`, body, WRITE_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}
