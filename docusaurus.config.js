// CacheRelay docs site (Docusaurus 3, GitHub Pages project hosting).
// Source of truth is the existing root docs/ directory (9 pages with
// sidebar_position frontmatter); nothing is duplicated or generated.
const config = {
	title: 'CacheRelay',
	tagline: 'Enterprise-grade AI gateway',
	favicon: 'img/favicon.svg',
	url: 'https://kxng0109.github.io',
	baseUrl: '/CacheRelay/',
	trailingSlash: false,
	organizationName: 'kxng0109',
	projectName: 'CacheRelay',
	deploymentBranch: 'gh-pages',
	onBrokenLinks: 'throw',
	markdown: {
		mermaid: true,
		hooks: {
			onBrokenMarkdownLinks: 'warn',
		},
	},
	themes: ['@docusaurus/theme-mermaid'],
	i18n: {
		defaultLocale: 'en',
		locales: ['en'],
	},
	presets: [
		[
			'classic',
			{
				docs: {
					sidebarPath: './sidebars.js',
				},
				blog: false,
				theme: {
					customCss: './src/css/custom.css',
				},
			},
		],
	],
	themeConfig: {
		navbar: {
			title: 'CacheRelay',
			logo: {
				alt: 'CacheRelay home',
				src: 'img/logo.svg',
				width: 32,
				height: 32,
			},
			items: [
				{
					type: 'docSidebar',
					sidebarId: 'docs',
					position: 'left',
					label: 'Docs',
				},
				{
					href: 'https://github.com/kxng0109/CacheRelay',
					label: 'GitHub',
					position: 'right',
				},
			],
		},
		footer: {
			copyright: 'CacheRelay docs · MIT',
		},
	},
};

module.exports = config;
