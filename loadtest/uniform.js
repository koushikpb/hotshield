// Reads spread evenly over all ids: the worst case for a cache, since almost nothing repeats.
// k6 run -e RATE=500 -e DURATION=60s -e SEED=1 loadtest/uniform.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, READ_TAGS, buildOptions, uniformId } from './lib.js';
export { setup } from './lib.js';

export const options = buildOptions();

export default function () {
  const res = http.get(`${BASE_URL}/items/${uniformId()}`, READ_TAGS);
  check(res, { 'status 200': (r) => r.status === 200 });
}
