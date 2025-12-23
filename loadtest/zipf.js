// Reads skewed by Zipf(ZIPF_S, default 1.1), so a handful of ids take most of the traffic.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 loadtest/zipf.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, READ_TAGS, buildOptions, zipfSampler } from './lib.js';

const zipfId = zipfSampler();

export const options = buildOptions();

export default function () {
  const res = http.get(`${BASE_URL}/items/${zipfId()}`, READ_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}
