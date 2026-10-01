import React from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';

export default function Home() {
	return (
		<Layout title="CacheRelay" description="Enterprise-grade AI gateway docs">
			<main style={{ padding: '4rem 2rem', textAlign: 'center' }}>
				<h1>CacheRelay</h1>
				<p>Enterprise-grade AI gateway — operator and integration docs.</p>
				<p>
					<Link className="button button--primary button--lg" to="/docs/getting-started">
						Read the docs
					</Link>
				</p>
			</main>
		</Layout>
	);
}
