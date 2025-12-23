// One id takes FLASH_SHARE of all reads (default 0.5); the rest follow Zipf. This is the
// "DB queries per logical request" experiment: coalescing should collapse misses on FLASH_ID.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 loadtest/flash.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, READ_TAGS, buildOptions, uniform01, zipfSampler } from './lib.js';

const FLASH_ID = Number(__ENV.FLASH_ID || 42);
const FLASH_SHARE = Number(__ENV.FLASH_SHARE || 0.5);
const zipfId = zipfSampler();

export const options = buildOptions();

export default function () {
  const id = uniform01(1) < FLASH_SHARE ? FLASH_ID : zipfId();
  const res = http.get(`${BASE_URL}/items/${id}`, READ_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}
