// Minimal open-loop read load, used from milestone 1 until the full scenarios exist (milestone 4).
// k6 run -e RATE=500 -e DURATION=60s loadtest/baseline.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_ID = Number(__ENV.MAX_ID || 1000000);

export const options = {
  scenarios: {
    reads: {
      // Open loop: requests start on schedule even when responses are slow, so latency isn't hidden
      // by the generator waiting (coordinated omission).
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const id = 1 + Math.floor(Math.random() * MAX_ID);
  const res = http.get(`${BASE_URL}/items/${id}`, { tags: { name: 'GET /items/{id}' } });
  check(res, { 'status 200': (r) => r.status === 200 });
}
