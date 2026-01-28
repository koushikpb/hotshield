// Shared pieces for the milestone 4 scenarios. Every script reads the same env vars, so two runs
// are comparable only if RATE, DURATION, WARMUP, SEED and MAX_ID match.
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Must match the seeded row count, or 404s get mixed into the latency numbers.
export const MAX_ID = Number(__ENV.MAX_ID || 1000000);
export const SEED = Number(__ENV.SEED || 1);
export const RATE = Number(__ENV.RATE || 200);
export const DURATION = __ENV.DURATION || '30s';
export const WARMUP = __ENV.WARMUP || '10s';

// k6's default timeout is 60s. 5s sits above Hikari's 2s connection-timeout, so a request stuck
// waiting for a pooled connection comes back as a 5xx from the server instead of being cut off by
// the client, and the failure shows up as what it is.
const TIMEOUT = '5s';

export const READ_TAGS = { tags: { name: 'GET /items/{id}' }, timeout: TIMEOUT };
export const WRITE_TAGS = {
  headers: { 'Content-Type': 'application/json' },
  tags: { name: 'PUT /items/{id}' },
  timeout: TIMEOUT,
};

// Open loop: requests start on schedule even when responses are slow, so latency isn't hidden
// by the generator waiting (coordinated omission).
// VUs scale with RATE: by Little's law in-flight requests are RATE * latency. Preallocate for
// 100 ms and let k6 add VUs when they are busy. Each VU keeps its own keep-alive connection, so
// the old RATE * 2.5 meant up to 10,000 connections at 4000 req/s, above Tomcat's
// max-connections (8192); those runs had thousands of 5 s client timeouts and the new sizing has
// none. maxVUs stays under the limit, so a server that
// keeps 8,000 requests in flight makes k6 drop iterations rather than open more connections.
// Scenarios that run beside these (herd's burst) add their own VUs on top.
function arrivals(duration, startTime, extra = {}) {
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration,
    startTime,
    preAllocatedVUs: Math.ceil(RATE * 0.1),
    maxVUs: Math.min(Math.ceil(RATE * 5), 8000),
    ...extra,
  };
}

// Warmup runs the same traffic as the measured phase and is left out of the reported numbers.
// extraScenarios lets herd.js add its one-off PUT; extraThresholds adds per-name submetrics.
export function buildOptions(extraScenarios = {}, extraThresholds = {}) {
  return {
    scenarios: {
      // Without gracefulStop: '0s', warmup iterations still in flight would overlap the start of
      // the measured phase and add load the measured rate doesn't account for.
      warmup: arrivals(WARMUP, '0s', { gracefulStop: '0s' }),
      measured: arrivals(DURATION, WARMUP),
      ...extraScenarios,
    },
    thresholds: {
      // A generator that falls behind is no longer open loop; fail the run instead of reporting it.
      // The per-scenario entries only make the summary say which phase dropped.
      dropped_iterations: ['count==0'],
      'dropped_iterations{scenario:warmup}': ['count==0'],
      'dropped_iterations{scenario:measured}': ['count==0'],
      // Only the measured scenario counts; these entries make the summary print it separately.
      // Achieved req/s is the measured http_reqs count divided by DURATION.
      'http_req_duration{scenario:measured}': ['p(99)>=0'],
      'http_reqs{scenario:measured}': ['count>=0'],
      'http_req_failed{scenario:measured}': ['rate>=0'],
      'checks{scenario:measured}': ['rate>=0'],
      ...extraThresholds,
    },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  };
}

// Scenario start times count from the end of setup(), so this timestamp plus WARMUP is when the measured
// phase starts. bench.sh reads it to cut the server-side counters to the same window as the k6 numbers,
// without a per-request --out file that would cost the generator CPU on the same machine.
export function setup() {
  console.log(`test_start_ms=${Date.now()}`);
}

// Accepts k6-style durations ("500ms", "20s", "1m30s"). Needed to place herd's PUT at
// WARMUP + HERD_AT, since k6 only takes startTime as a single string.
export function seconds(duration) {
  const units = { ms: 0.001, s: 1, m: 60, h: 3600 };
  const parts = String(duration).match(/\d+(?:\.\d+)?(?:ms|s|m|h)/g);
  if (!parts || parts.join('') !== String(duration)) {
    throw new Error(`bad duration: ${duration}`);
  }
  return parts.reduce((total, part) => {
    const [, n, unit] = part.match(/(\d+(?:\.\d+)?)(ms|s|m|h)/);
    return total + Number(n) * units[unit];
  }, 0);
}

function mix32(x) {
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d);
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b);
  return (x ^ (x >>> 16)) >>> 0;
}

// Uniform float in [0,1), keyed on the global iteration number rather than the VU, because VU
// assignment under an arrival-rate executor changes between runs. Same SEED and rate give the
// same sequence. `stream` separates independent draws in one iteration (e.g. read/write choice
// vs key). Warmup gets its own stream so the measured phase doesn't open by replaying exactly
// the ids warmup just cached.
export function uniform01(stream = 0) {
  const phase = exec.scenario.name === 'warmup' ? 1 : 0;
  let x = Math.imul(exec.scenario.iterationInTest + 1, 0x9e3779b1);
  // Math.imul keeps the products in 32-bit integer arithmetic; a plain * is a float multiply and
  // loses low bits once the product passes 2^53.
  const salt = Math.imul(SEED, 0x85ebca6b) + Math.imul(stream, 0xc2b2ae35)
    + Math.imul(phase, 0x27d4eb2f);
  x ^= mix32(salt | 0);
  return mix32(x) / 4294967296;
}

export function uniformId(stream = 0) {
  return 1 + Math.floor(uniform01(stream) * MAX_ID);
}

const HEAD = 4096;

function gcd(a, b) {
  return b === 0 ? a : gcd(b, a % b);
}

// Builds a Zipf(s) sampler over MAX_ID keys. Call it from init code only: SharedArray can't be
// created inside a VU iteration.
//
// The CDF is computed once per k6 process and shared read-only across VUs; a plain array would be
// 8 MB per VU. Sampling is a binary search, about 20 element reads for 1M keys, instead of a
// linear scan.
export function zipfSampler(s = Number(__ENV.ZIPF_S || 1.1)) {
  const cdf = new SharedArray(`zipf-cdf-${MAX_ID}-${s}`, () => {
    const weights = new Array(MAX_ID);
    let total = 0;
    for (let k = 1; k <= MAX_ID; k++) {
      total += Math.pow(k, -s);
      weights[k - 1] = total;
    }
    for (let i = 0; i < MAX_ID; i++) {
      weights[i] /= total;
    }
    return weights;
  });

  // Rank 1 is not id 1: if the hot keys were ids 1..k they would sit in the same few heap and
  // index pages, which flatters the direct-mode baseline through Postgres' buffer cache. An
  // affine map (rank * A + B) mod MAX_ID is a bijection whenever gcd(A, MAX_ID) == 1, so every
  // id still has exactly one rank. A near MAX_ID / golden ratio puts consecutive ranks far apart
  // (a small A, or one close to MAX_ID, would leave them a few ids apart). B depends on SEED so a
  // new seed moves the hot set too.
  let a = Math.floor(MAX_ID * 0.6180339887) || 1;
  while (gcd(a, MAX_ID) !== 1) {
    a++;
  }
  const b = mix32(SEED) % MAX_ID;

  // Every SharedArray element read deserializes that element, which made a full binary search
  // cost tens of microseconds per sample. Each VU keeps a plain copy of the first HEAD entries
  // (32 KB): with s=1.1 most draws land there and never touch the SharedArray.
  const head = [];
  for (let i = 0; i < Math.min(HEAD, MAX_ID); i++) {
    head.push(cdf[i]);
  }

  function search(arr, lo, hi, u) {
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (arr[mid] > u) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  function rank(u) {
    if (u < head[head.length - 1]) {
      return search(head, 0, head.length - 1, u);
    }
    return search(cdf, head.length, MAX_ID - 1, u);
  }

  return function zipfId(stream = 0) {
    return 1 + ((rank(uniform01(stream)) * a + b) % MAX_ID);
  };
}
