// Sidebar groups mirror the console rail: task-first eyebrows (Start, Run,
// Guard, Reference) over the existing root docs/ pages. Doc IDs omit the
// numeric filename prefixes; URLs are unchanged.
module.exports = {
	docs: [
		{
			type: 'category',
			label: 'Start',
			collapsed: false,
			items: ['getting-started'],
		},
		{
			type: 'category',
			label: 'Run',
			collapsed: false,
			items: ['request-flow', 'failover-circuits', 'caching'],
		},
		{
			type: 'category',
			label: 'Guard',
			collapsed: false,
			items: ['keys-budgets', 'mcp-a2a', 'guardrails-sovereignty'],
		},
		{
			type: 'category',
			label: 'Reference',
			collapsed: false,
			items: ['admin-reference', 'operations'],
		},
	],
};
