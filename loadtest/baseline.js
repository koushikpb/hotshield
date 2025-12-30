// Minimal open-loop read load, used from milestone 1 until the full scenarios exist (milestone 4).
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 loadtest/baseline.js
// MAX_ID must match the seeded row count, or 404s get mixed into the latency numbers.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_ID = Number(__ENV.MAX_ID || 1000000);
const SEED = Number(__ENV.SEED || 1);
const RATE = Number(__ENV.RATE || 200);
const WARMUP = __ENV.WARMUP || '10s';

// Open loop: requests start on schedule even when responses are slow, so latency isn't hidden
// by the generator waiting (coordinated omission).
function arrivals(duration, startTime) {
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration,
    startTime,
    preAllocatedVUs: 100,
    maxVUs: 1000,
  };
}

export const options = {
  scenarios: {
    warmup: arrivals(WARMUP, '0s'),
    reads: arrivals(__ENV.DURATION || '30s', WARMUP),
  },
  thresholds: {
    // A generator that falls behind is no longer open loop; fail the run instead of reporting it.
    dropped_iterations: ['count==0'],
    // Only the measured scenario counts; these entries make the summary print it separately.
    'http_req_duration{scenario:reads}': ['p(99)>=0'],
    'checks{scenario:reads}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Keyed on the global iteration number, not the VU, because VU assignment under an arrival-rate
// executor changes between runs. Same SEED and rate give the same id sequence.
function idFor(iteration) {
  // Math.imul instead of *, so the mix stays integer math past 2^53 (about 2M iterations).
  let x = (Math.imul(iteration + 1, 0x9e3779b1) + Math.imul(SEED, 0x85ebca6b)) | 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d);
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b);
  x = (x ^ (x >>> 16)) >>> 0;
  return 1 + (x % MAX_ID);
}

export default function () {
  const id = idFor(exec.scenario.iterationInTest);
  const res = http.get(`${BASE_URL}/items/${id}`, { tags: { name: 'GET /items/{id}' } });
  check(res, { 'status 200': (r) => r.status === 200 });
}
