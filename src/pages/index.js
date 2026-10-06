import React from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import RequestLab from '../components/RequestLab.js';

const STATS = [
	{ value: 'L0 / L1 / L2', label: 'cache tiers' },
	{ value: '3 → 30s', label: 'breaker trips → cooldown' },
	{ value: '51', label: 'Grafana panels' },
	{ value: '20', label: 'Prometheus alert rules' },
];

const CARDS = [
	{ to: '/docs/getting-started', title: 'Getting started', text: 'Compose profiles, first chat call.' },
	{ to: '/docs/failover-circuits', title: 'Failover', text: 'Chains, classification, breakers.' },
	{ to: '/docs/mcp-a2a', title: 'MCP and A2A', text: 'Governed tools and agents.' },
	{ to: '/docs/operations', title: 'Operations', text: 'Config, security, gates.' },
];

export default function Home() {
	return (
		<Layout title="CacheRelay" description="Cache, then relay — operator and integration docs">
			<main className="cr-hero">
				<p style={{ fontFamily: 'var(--ifm-font-family-monospace)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--ifm-color-primary)' }}>
					$ cacherelay · docs<span className="cr-caret" aria-hidden="true">▊</span>
				</p>
				<h1>Cache, then relay.</h1>
				<p className="cr-lede">
					One OpenAI-compatible endpoint in front of every model provider. Answers from
					cache when it can, relays with failover when it can&apos;t.
				</p>
				<div className="cr-stats">
					{STATS.map((s) => (
						<div key={s.label} className="cr-stat">
							<b>{s.value}</b>
							<span>{s.label}</span>
						</div>
					))}
				</div>
				<p className="cr-tick">docs · 9 pages · version next</p>
				<div className="cr-term">
					<div className="cr-termbar">$ first request</div>
					<pre className="cr-pre"><code>{`curl -N http://localhost:8080/v1/chat/completions \\
  -H "Authorization: Bearer $GW_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}'`}</code></pre>
				</div>
				<div className="cr-cards">
					{CARDS.map((c) => (
						<Link key={c.to} to={c.to} className="cr-card">
							<strong>{c.title}</strong>
							<span>{c.text}</span>
						</Link>
					))}
				</div>
				<RequestLab />
			</main>
		</Layout>
	);
}
