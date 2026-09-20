import { Navigate, useLocation } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { useAuthStore } from '../shared/auth/store.js'

interface RequireAuthProps {
  /** Screen content visible to any live session (admin or not). */
  children: React.JSX.Element
}

/**
 * Renders children for authenticated sessions, bouncing guests to login.
 *
 * @remarks Guests keep no trace of the guarded screen: they land on
 * `/login?next=<original>` and return after signing in. Non-admin sessions
 * pass — privilege separation stays in `RequireAdmin`.
 *
 * @param props - Gated children.
 * @returns Children or a login redirect preserving the destination.
 */
export function RequireAuth({ children }: RequireAuthProps): React.JSX.Element {
  const session = useAuthStore(useShallow((s) => s.session))
  const { pathname, search } = useLocation()
  if (session !== null) return children
  const next = encodeURIComponent(`${pathname}${search}`)
  return <Navigate to={`/login?next=${next}`} replace />
}
