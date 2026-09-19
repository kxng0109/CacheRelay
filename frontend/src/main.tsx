import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import '@fontsource-variable/fraunces/wght.css'
import '@fontsource-variable/inter/wght.css'
import '@fontsource-variable/martian-mono/wght.css'
import './index.css'
import { Providers } from './app/providers.tsx'
import { router } from './app/router.tsx'
import { restoreSession } from './shared/auth/session.js'

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

// Restores a cookie-backed session without blocking first paint.
void restoreSession()
