/*
 * 40-breakpoint: gateway ceiling hunt WITHOUT touching completions.
 *
 * Probes GET /v1/models with the load key: full gateway work per request
 * (TLS, auth lookup, alias listing, JSON) with zero upstream spend, zero
 * Ollama involvement, zero ledger/rate side effects beyond the lookup itself.
 * That isolates the gateway ceiling from provider and iGPU limits, so the
 * break point is the gateway's (or the generator's) — never Ollama's.
 *
 * Twenty arrival-rate steps, 100 rps apart (BASE_RPS + 100 -> BASE_RPS + 2000,
 * 30s each; default BASE_RPS=0 reproduces the 100 -> 2000 shakedown):
 * the summary attributes the first break (failed/dropped surge, app restart,
 * host distress) to an exact step. k6's end-of-run summary carries the full
 * latency distribution natively: avg (mean), min, med (p50), p(90), p(95),
 * p(99), max. The last step with checks ~100% and failed < 1% is the maximum
 * sustainable rate. This route is UNMETERED by design (ModelController does
 * its own auth lookup, no rate Lua, no budget hold), so there is no limiter
 * knee anywhere on this ramp — any break is gateway, generator, or host.
 * Generator-vs-gateway discipline: client-side timeouts and dropped iterations
 * mean the GENERATOR saturated first (single-box k6 did exactly this at
 * vus_max 10,000 in the 32 overload hunt) — the maximum is fiction unless
 * http_req_failed stays transport-clean. No failing thresholds on purpose
 * except the abort below: at the top steps saturation is the EXPECTED outcome
 * under test, recorded — not asserted.
 *
 * Run: k6 run loadtest/k6/40-breakpoint.js
 *      k6 run --out csv=results-40.csv loadtest/k6/40-breakpoint.js
 *      BASE_RPS=2900 k6 run --out csv=results-40-high.csv loadtest/k6/40-breakpoint.js
 * Env:  BASE_URL (default http://localhost:8080)
 *       LOAD_KEY (any valid key — the models path never rate-limits)
 *       BASE_RPS (default 0; resume/extend with e.g. 2900 for 3000 -> 4900)
 * Noisy-box warning: run on quiet iron for record numbers; a concurrent
 * coverage/test run on the same host reads low and proves wiring only.
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || 'gw-0123456789abcdef0123456789abcdef';

const STEP_RPS = 100;
const STEPS = 20;
const STEP_DURATION = '30s';
const BASE_RPS = Number(__ENV.BASE_RPS || 0);

function step(index, rate, startSeconds) {
    return {
        executor: 'ramping-arrival-rate',
        startRate: rate,
        timeUnit: '1s',
        preAllocatedVUs: Math.ceil(rate / 10),
        maxVUs: Math.ceil(rate / 2),
        stages: [{duration: STEP_DURATION, target: rate}],
        startTime: startSeconds + 's',
        exec: 'models',
        tags: {phase: 'break' + rate},
    };
}

export const options = {
    scenarios: Object.fromEntries(
        Array.from({length: STEPS}, (_, i) => {
            const rate = BASE_RPS + (i + 1) * STEP_RPS;
            return ['break' + rate, step(i, rate, i * 35)];
        })
    ),
    thresholds: {
        // Abort the ramp when transport failures sustain above 1%: anything
        // past the abort is generator distress, not gateway signal.
        http_req_failed: [{threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '30s'}],
    },
    discardResponseBodies: true,
};

export function models() {
    const res = http.get(BASE + '/v1/models', {
        headers: {Authorization: 'Bearer ' + KEY},
        timeout: '10s',
        tags: {op: 'models-list'},
    });
    check(res, {
        'models 200': (r) => r.status === 200,
        'rate not exceeded loudly': (r) => r.status !== 429 || r.headers['Retry-After'] !== undefined,
    });
}
