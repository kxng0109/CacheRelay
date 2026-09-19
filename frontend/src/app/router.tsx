import { Suspense, lazy } from 'react'
import { createBrowserRouter } from 'react-router'
import { Layout } from './layout.js'
import { NotFound } from './NotFound.js'
import { RequireAdmin } from './RequireAdmin.js'

const OverviewPage = lazy(() =>
  import('../features/overview/page.js').then((m) => ({ default: m.OverviewPage })),
)
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
const LoginPage = lazy(() =>
  import('../features/auth/LoginPage.js').then((m) => ({ default: m.LoginPage })),
)
const RedeemPage = lazy(() =>
  import('../features/auth/RedeemPage.js').then((m) => ({ default: m.RedeemPage })),
)

/**
 * Wraps an admin screen in the stealth guard.
 *
 * @param element - Admin screen element.
 * @returns Guarded element (missing page for non-admins).
 */
function guard(element: React.JSX.Element): React.JSX.Element {
  return <RequireAdmin>{suspend(element)}</RequireAdmin>
}

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
      { index: true, element: suspend(<OverviewPage />) },
      { path: 'playground', element: suspend(<PlaygroundPage />) },
      { path: 'login', element: suspend(<LoginPage />) },
      { path: 'redeem', element: suspend(<RedeemPage />) },
      { path: 'circuits', element: guard(<CircuitsPage />) },
      { path: 'keys', element: guard(<KeysPage />) },
      { path: 'ledger', element: guard(<LedgerPage />) },
      { path: 'cache', element: guard(<CachePage />) },
      { path: 'embeddings', element: suspend(<EmbeddingsPage />) },
      { path: 'approvals', element: guard(<ApprovalsPage />) },
      { path: 'mcp', element: suspend(<McpPage />) },
      { path: 'observability', element: suspend(<ObservabilityPage />) },
      { path: '*', element: <NotFound /> },
    ],
  },
])
