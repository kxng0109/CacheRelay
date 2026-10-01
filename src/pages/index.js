import React from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';

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
			<main style={{ maxWidth: 960, margin: '0 auto', padding: '4rem 1.5rem' }}>
				<p style={{ fontFamily: 'var(--ifm-font-family-monospace)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--ifm-color-primary)' }}>
					$ cacherelay · docs
				</p>
				<h1>Cache, then relay.</h1>
				<p style={{ fontSize: 18, maxWidth: 640 }}>
					One OpenAI-compatible endpoint in front of every model provider. Answers from
					cache when it can, relays with failover when it can&apos;t.
				</p>
				<div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, margin: '2rem 0' }}>
					{STATS.map((s) => (
						<div key={s.label} style={{ border: '1px solid var(--cr-hair)', borderRadius: 16, padding: '1rem 1.25rem' }}>
							<div style={{ fontFamily: 'var(--ifm-font-family-monospace)', fontSize: 22 }}>{s.value}</div>
							<div style={{ fontSize: 12, opacity: 0.75 }}>{s.label}</div>
						</div>
					))}
				</div>
				<pre><code>{`curl -N http://localhost:8080/v1/chat/completions \\
  -H "Authorization: Bearer $GW_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}'`}</code></pre>
				<div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginTop: '2rem' }}>
					{CARDS.map((c) => (
						<Link key={c.to} to={c.to} style={{ border: '1px solid var(--cr-hair)', borderRadius: 16, padding: '1.25rem', textDecoration: 'none', color: 'inherit' }}>
							<strong>{c.title}</strong>
							<div style={{ fontSize: 13, opacity: 0.75 }}>{c.text}</div>
						</Link>
					))}
				</div>
			</main>
		</Layout>
	);
}
