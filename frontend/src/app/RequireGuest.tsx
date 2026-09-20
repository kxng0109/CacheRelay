import { Navigate, useSearchParams } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { resolveNext } from '../shared/auth/next.js'
import { useAuthStore } from '../shared/auth/store.js'

interface RequireGuestProps {
  /** Screen content visible only while logged out (login, redeem). */
  children: React.JSX.Element
}

/**
 * Renders children for logged-out visitors, sending sessions home.
 *
 * @remarks An already-authenticated visit to `/login` (or a stale bookmark)
 * is a no-op round-trip: the session jumps to its `?next=` destination when
 * valid, else `/`. No auth state is exposed beyond the redirect itself.
 *
 * @param props - Guest-only children.
 * @returns Children or a homeward redirect.
 */
export function RequireGuest({ children }: RequireGuestProps): React.JSX.Element {
  const session = useAuthStore(useShallow((s) => s.session))
  const [params] = useSearchParams()
  if (session === null) return children
  return <Navigate to={resolveNext(params.get('next'))} replace />
}
