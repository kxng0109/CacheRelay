import { useShallow } from 'zustand/react/shallow'
import { useAuthStore } from '../shared/auth/store.js'
import { NotFound } from './NotFound.js'

interface RequireAdminProps {
  /** Screen content visible to admin sessions only. */
  children: React.JSX.Element
}

/**
 * Renders children for admin sessions, the missing page otherwise.
 *
 * @remarks The forbidden branch is the shared `NotFound` screen —
 * indistinguishable from an unknown route, leaking neither the route's
 * existence nor the reason. Non-admin sessions and logged-out visitors
 * see exactly the same pixels.
 *
 * @param props - Gated children.
 * @returns Children or the missing page.
 */
export function RequireAdmin({ children }: RequireAdminProps): React.JSX.Element {
  const session = useAuthStore(useShallow((s) => s.session))
  if (!session?.admin) return <NotFound />
  return children
}
