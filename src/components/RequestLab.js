import React, { useRef, useState } from 'react';

/**
 * Request lab: a client-side simulation of the cache-then-relay path.
 * Nothing here touches a gateway: every number comes from your own clicks in
 * this session. Cooldown matches production (30 s), not the 8 s shortcut of
 * the throwaway prototype.
 */

const PROMPTS = [
	{ t: 'What is the capital of France?', g: 'fr' },
	{ t: "Tell me France's capital city", g: 'fr' },
	{ t: 'Enable notifications', g: 'nt', p: 1 },
	{ t: 'Disable notifications', g: 'nt', p: -1 },
];

const SEED_PROVIDERS = [
	{ n: 'openai', up: true, f: 0, t: 0 },
	{ n: 'anthropic', up: true, f: 0, t: 0 },
	{ n: 'ollama', up: true, f: 0, t: 0 },
];

const COOLDOWN_MS = 30_000;
const open = (r) => r.f >= 3 && Date.now() - r.t < COOLDOWN_MS;

export default function RequestLab() {
	const [sel, setSel] = useState(0);
	const [temp, setTemp] = useState(0);
	const [providers, setProviders] = useState(SEED_PROVIDERS);
	const [lanes, setLanes] = useState({ l0: '', l2: '', rl: '' });
	const [out, setOut] = useState(
		'$ response: pending\nSend a prompt to see where it is answered.',
	);
	const [stats, setStats] = useState({ r: 0, h: 0, f: 0 });
	const store = useRef([]);

	const lane = (id, cls, text) =>
		setLanes((prev) => ({ ...prev, [id]: cls === '' ? '' : `${cls} ${text}` }));

	const send = () => {
		setLanes({ l0: '', l2: '', rl: '' });
		const p = PROMPTS[sel];
		const lines = [];
		const tried = [];
		let code = 200;
		let xc = 'MISS';
		let hitNow = 0;
		let bad = false;
		let req = stats.r + 1;
		let fails = stats.f;

		const provs = providers.map((r) => ({ ...r }));
		const relay = () => {
			let win = null;
			for (const r of provs) {
				if (open(r)) {
					tried.push(`${r.n}:circuit-open`);
					continue;
				}
				const probe = r.f >= 3;
				if (!r.up) {
					r.f += 1;
					if (r.f >= 3) r.t = Date.now();
					tried.push(r.n + (probe ? ':probe-failed' : ':503'));
					continue;
				}
				r.f = 0;
				tried.push(r.n + (probe ? ':probe-ok' : ':200'));
				win = r;
				break;
			}
			if (win) {
				lane('rl', 'hit', `answered by ${win.n}`);
				if (tried.length > 1) fails += 1;
				if (temp <= 0.1) store.current = [...store.current, p];
			} else {
				lane('rl', 'bad', 'all providers failed');
				code = 502;
				bad = true;
			}
			lines.push(
				`X-CacheRelay-Provider: ${win === null ? 'none' : win.n}`,
				`X-CacheRelay-Tried: ${tried.join(', ')}`,
			);
		};

		if (temp > 0.1) {
			xc = `BYPASS (temperature ${temp.toFixed(1)} is above 0.1)`;
			lane('l0', 'skip', '');
			lane('l2', 'skip', '');
			relay();
		} else if (store.current.some((s) => s.t === p.t)) {
			xc = 'HIT (L0/L1 exact)';
			hitNow = 1;
			lane('l0', 'hit', 'served from memory');
			lane('l2', 'skip', '');
			lane('rl', 'skip', 'no upstream spend');
		} else {
			lane('l0', 'bad', 'miss');
			const c = store.current.find((s) => s.g === p.g);
			if (c !== undefined && c.p !== undefined && p.p !== undefined && c.p !== p.p) {
				lane('l2', 'bad', 'polarity guard rejected');
				lines.push(`cache guard: "${c.t}" has the opposite intent`);
				relay();
			} else if (c !== undefined) {
				xc = 'HIT (L2 semantic, similarity 0.87)';
				hitNow = 1;
				lane('l2', 'hit', `matched "${c.t}"`);
				lane('rl', 'skip', 'no upstream spend');
			} else {
				lane('l2', 'bad', 'miss');
				relay();
			}
		}
		setProviders(provs);
		setStats({ r: req, h: stats.h + hitNow, f: fails });
		setOut(
			bad
				? `HTTP 502\ncause: every provider in the chain failed\ntried: ${tried.join(', ')}\nnext: bring a provider up, then send again`
				: [`HTTP ${String(code)}`, `X-Cache: ${xc}`, ...lines].join('\n'),
		);
	};

	const purge = () => {
		store.current = [];
		setLanes({ l0: '', l2: '', rl: '' });
		setOut('cache purged\nL0, L1 and L2 are empty.');
	};

	const hitRate = stats.r === 0 ? 0 : Math.round((stats.h / stats.r) * 100);

	return (
		<section aria-label="Request lab (simulation)" style={{ marginTop: '3rem' }}>
			<h2>Request lab</h2>
			<p>
				Simulation: runs entirely in your browser against the rules above. No gateway
				is involved, and the breaker cools down in 30 s like production.
			</p>
			<div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
				{PROMPTS.map((p, i) => (
					<button
						key={p.t}
						type="button"
						aria-pressed={i === sel}
						onClick={() => setSel(i)}
						className="button button--secondary button--sm"
						style={i === sel ? { borderColor: 'var(--ifm-color-primary)' } : undefined}
					>
						{p.t}
					</button>
				))}
			</div>
			<label style={{ display: 'block', marginBottom: 12 }}>
				temperature{' '}
				<input
					type="range"
					min="0"
					max="1"
					step="0.1"
					value={temp}
					onChange={(e) => setTemp(Number(e.target.value))}
					aria-label="temperature"
				/>{' '}
				<output>{temp.toFixed(1)}</output>
			</label>
			<div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
				{providers.map((r, i) => (
					<button
						key={r.n}
						type="button"
						aria-pressed={r.up}
						onClick={() =>
							setProviders((prev) => prev.map((x, j) => (j === i ? { ...x, up: !x.up } : x)))
						}
						className="button button--secondary button--sm"
					>
						{r.n}: {r.up ? 'up' : 'down'} · breaker {open(r) ? 'OPEN' : 'closed'}
					</button>
				))}
			</div>
			<div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8, marginBottom: 12 }}>
				{[
					['l0', 'L0 / L1'],
					['l2', 'L2'],
					['rl', 'Relay'],
				].map(([id, label]) => (
					<div
						key={id}
						style={{
							border: '1px solid var(--cr-hair)',
							borderRadius: 12,
							padding: '8px 12px',
							fontSize: 13,
						}}
					>
						<strong>{label}</strong>
						<div>{lanes[id] === '' ? '—' : lanes[id]}</div>
					</div>
				))}
			</div>
			<div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
				<button type="button" onClick={send} className="button button--primary">
					Send request
				</button>
				<button type="button" onClick={purge} className="button button--secondary">
					Purge cache
				</button>
			</div>
			<pre aria-live="polite">
				<code>{out}</code>
			</pre>
			<p style={{ fontFamily: 'var(--ifm-font-family-monospace)', fontSize: 12 }}>
				requests {stats.r} · hit rate {hitRate}% · failovers {stats.f}
			</p>
		</section>
	);
}
