import { Navigate, useLocation } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { useAuthStore } from '../shared/auth/store.js'
import { NotFound } from './NotFound.js'

interface RequireAdminProps {
  /** Screen content visible to admin sessions only. */
  children: React.JSX.Element
}

/**
 * Renders children for admin sessions only.
 *
 * @remarks Two distinct outcomes protect the route: logged-out visitors
 * bounce to `/login?next=<original>` and learn nothing about the screen;
 * a logged-in non-admin sees the shared `NotFound` screen, byte-identical
 * to an unknown route, leaking neither the route's existence nor the
 * reason. Anything that hides admin surfaces from users stays in here.
 *
 * @param props - Gated children.
 * @returns Children, a login redirect, or the missing page.
 */
export function RequireAdmin({ children }: RequireAdminProps): React.JSX.Element {
  const session = useAuthStore(useShallow((s) => s.session))
  const { pathname, search } = useLocation()
  if (session === null) {
    const next = encodeURIComponent(`${pathname}${search}`)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  if (!session.admin) return <NotFound />
  return children
}
