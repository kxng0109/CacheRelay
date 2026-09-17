import { Suspense, lazy } from 'react'
import { createBrowserRouter } from 'react-router'
import { Layout } from './layout.js'

const PlaygroundPage = lazy(() =>
  import('../features/playground/page.js').then((m) => ({ default: m.PlaygroundPage })),
)
const CircuitsPage = lazy(() =>
  import('../features/circuits/page.js').then((m) => ({ default: m.CircuitsPage })),
)
const KeysPage = lazy(() =>
  import('../features/keys/page.js').then((m) => ({ default: m.KeysPage })),
)
const LedgerPage = lazy(() =>
  import('../features/ledger/page.js').then((m) => ({ default: m.LedgerPage })),
)
const CachePage = lazy(() =>
  import('../features/cache/page.js').then((m) => ({ default: m.CachePage })),
)
const EmbeddingsPage = lazy(() =>
  import('../features/embeddings/page.js').then((m) => ({ default: m.EmbeddingsPage })),
)
const ApprovalsPage = lazy(() =>
  import('../features/approvals/page.js').then((m) => ({ default: m.ApprovalsPage })),
)
const McpPage = lazy(() => import('../features/mcp/page.js').then((m) => ({ default: m.McpPage })))
const ObservabilityPage = lazy(() =>
  import('../features/observability/page.js').then((m) => ({ default: m.ObservabilityPage })),
)

/**
 * Wraps a lazy route element in a suspense boundary.
 *
 * @param element - The lazy route element.
 * @returns The element inside a loading fallback.
 */
function suspend(element: React.JSX.Element): React.JSX.Element {
  return <Suspense fallback={<p role="status">Loading screen…</p>}>{element}</Suspense>
}

/**
 * Data router: one lazy route per console screen under the shell layout.
 *
 * @remarks Every feature chunk loads on demand so the initial JS stays in
 * the 150–200 kB gzipped budget.
 */
export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: suspend(<PlaygroundPage />) },
      { path: 'circuits', element: suspend(<CircuitsPage />) },
      { path: 'keys', element: suspend(<KeysPage />) },
      { path: 'ledger', element: suspend(<LedgerPage />) },
      { path: 'cache', element: suspend(<CachePage />) },
      { path: 'embeddings', element: suspend(<EmbeddingsPage />) },
      { path: 'approvals', element: suspend(<ApprovalsPage />) },
      { path: 'mcp', element: suspend(<McpPage />) },
      { path: 'observability', element: suspend(<ObservabilityPage />) },
    ],
  },
])
