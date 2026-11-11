// F.4 load test: a fixed rate of caller lines at POST /v1/utterance, from the 14 android scripts.
// Start with run_load.sh, which builds lines.json and measures CPU and memory per mode.
//   k6 run -e MODE=cascade -e RATE=20 -e DURATION=60s -e OUT=out.json load_test.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const lines = new SharedArray('lines', () => JSON.parse(open(__ENV.LINES || './lines.json')));
const BASE = (__ENV.GATEWAY_URL || 'http://localhost:8080').replace(/\/$/, '');
const RATE = Number(__ENV.RATE || 20);
const RUN = __ENV.RUN || 'run';
const SLOTS = 20; // calls in progress at once
const SPREAD = 7; // so the slots aren't all on the same line of the same script

const bertStatus = {};
for (const s of ['ok', 'skipped', 'no_context', 'timeout', 'error', 'off']) bertStatus[s] = new Counter(`bert_${s}`);
const badReply = new Counter('bad_reply');

export const options = {
  scenarios: {
    lines: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const n = exec.scenario.iterationInTest;
  const slot = n % SLOTS;
  const pos = Math.floor(n / SLOTS) + slot * SPREAD;
  const l = lines[pos % lines.length];
  const round = Math.floor(pos / lines.length);
  const res = http.post(
    `${BASE}/v1/utterance`,
    JSON.stringify({
      call_id: `load-${RUN}-${slot}-${round}-${l.call}`,
      seq: l.seq,
      speaker: 'caller',
      text: l.text,
      lang: l.lang,
      t_ms: 20000 + 5000 * l.seq, // past the warm-up, as in eval_cascade.py
    }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '5s' },
  );
  if (res.status !== 200) {
    badReply.add(1);
    return;
  }
  const c = bertStatus[res.json('bert_status')];
  if (c) c.add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = (k) => (m[k] ? m[k].values.count : 0);
  const d = m.http_req_duration.values;
  const out = {
    mode: __ENV.MODE,
    rate: RATE,
    requests: count('http_reqs'),
    dropped: count('dropped_iterations'),
    bad_replies: count('bad_reply'),
    p50_ms: d.med,
    p95_ms: d['p(95)'],
    p99_ms: d['p(99)'],
    max_ms: d.max,
    bert: Object.fromEntries(Object.keys(bertStatus).map((s) => [s, count(`bert_${s}`)])),
  };
  return { [__ENV.OUT || 'load_out.json']: JSON.stringify(out, null, 1), stdout: JSON.stringify(out) + '\n' };
}
