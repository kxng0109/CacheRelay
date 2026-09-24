import { Suspense, lazy } from 'react'
import { createBrowserRouter } from 'react-router'
import { Layout } from './layout.js'
import { NotFound } from './NotFound.js'
import { RequireAdmin } from './RequireAdmin.js'
import { RequireAuth } from './RequireAuth.js'
import { RequireGuest } from './RequireGuest.js'

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
const ModelsPage = lazy(() =>
  import('../features/models/page.js').then((m) => ({ default: m.ModelsPage })),
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
const UsagePage = lazy(() =>
  import('../features/usage/page.js').then((m) => ({ default: m.UsagePage })),
)
const TeamsPage = lazy(() =>
  import('../features/teams/page.js').then((m) => ({ default: m.TeamsPage })),
)
const UserLedgerPage = lazy(() =>
  import('../features/ledger/UserPage.js').then((m) => ({ default: m.UserLedgerPage })),
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
 * Wraps a screen requiring any live session (admin or not).
 *
 * @param element - Session-gated screen element.
 * @returns Guarded element (login redirect for guests).
 */
function authed(element: React.JSX.Element): React.JSX.Element {
  return <RequireAuth>{suspend(element)}</RequireAuth>
}

/**
 * Wraps a logged-out-only screen (login, redeem).
 *
 * @param element - Guest-only screen element.
 * @returns Guarded element (homeward redirect for sessions).
 */
function guest(element: React.JSX.Element): React.JSX.Element {
  return <RequireGuest>{suspend(element)}</RequireGuest>
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
      { index: true, element: authed(<OverviewPage />) },
      { path: 'playground', element: suspend(<PlaygroundPage />) },
      { path: 'usage', element: authed(<UsagePage />) },
      { path: 'teams', element: authed(<TeamsPage />) },
      { path: 'login', element: guest(<LoginPage />) },
      { path: 'redeem', element: guest(<RedeemPage />) },
      { path: 'circuits', element: guard(<CircuitsPage />) },
      { path: 'models', element: guard(<ModelsPage />) },
      { path: 'keys', element: guard(<KeysPage />) },
      { path: 'ledger', element: guard(<LedgerPage />) },
      { path: 'ledger/user/:userId', element: guard(<UserLedgerPage />) },
      { path: 'cache', element: guard(<CachePage />) },
      { path: 'embeddings', element: suspend(<EmbeddingsPage />) },
      { path: 'approvals', element: guard(<ApprovalsPage />) },
      { path: 'mcp', element: authed(<McpPage />) },
      { path: 'observability', element: authed(<ObservabilityPage />) },
      { path: '*', element: <NotFound /> },
    ],
  },
])
