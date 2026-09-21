import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import '@fontsource-variable/fraunces/wght.css'
import '@fontsource-variable/inter/wght.css'
import '@fontsource-variable/martian-mono/wght.css'
import './index.css'
import { Providers } from './app/providers.tsx'
import { router } from './app/router.tsx'
import { restoreSession, shouldDeferRestore } from './shared/auth/session.js'

const root = document.getElementById('root')
// Boot guard: index.html always provides #root; the throw is unreachable by construction.
/* v8 ignore if -- @preserve */
if (root === null) throw new Error('Missing #root element.')

createRoot(root).render(
  <StrictMode>
    <Providers>
      <RouterProvider router={router} />
    </Providers>
  </StrictMode>,
)

// Restores a cookie-backed session without blocking first paint. Public
// routes wait for first input so cold loads stay clean when the gateway is
// unreachable; authed deep links restore immediately to avoid a login bounce.
if (shouldDeferRestore(window.location.pathname)) {
  const restoreOnce = (): void => {
    window.removeEventListener('pointerdown', restoreOnce)
    window.removeEventListener('keydown', restoreOnce)
    void restoreSession()
  }
  window.addEventListener('pointerdown', restoreOnce, { once: true })
  window.addEventListener('keydown', restoreOnce, { once: true })
} else {
  void restoreSession()
}
