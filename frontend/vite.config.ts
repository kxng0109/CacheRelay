import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import type { Plugin } from 'vite'

// CSP nonce strategy (production, per frontend-security §3):
// - `html.cspNonce` is a BUILD-TIME literal placeholder, never a real nonce.
// - The serving edge generates a cryptographically random nonce per HTTP
//   response (e.g. `crypto.randomUUID()`), substitutes BOTH placeholders
//   `CSP_NONCE_PLACEHOLDER` and `__CSP_NONCE__` in the served HTML, and emits
//   `Content-Security-Policy: script-src 'nonce-<value>' 'strict-dynamic' ...`.
// - Never cache HTML with a substituted nonce; inject at the edge per request.
// - `build.assetsInlineLimit: 0` keeps assets as separate files so CSP does
//   not need `data:` allowances.
/**
 * Adds the per-response nonce placeholder to script tags lacking one.
 *
 * The edge replaces `__CSP_NONCE__` with the per-request nonce value.
 *
 * @returns The CSP nonce placeholder plugin.
 */
function cspNoncePlaceholder(): Plugin {
  return {
    name: 'cacherelay-csp-nonce',
    transformIndexHtml(html: string): string {
      return html.replace(/<script(?![^>]*\bnonce=)/g, '<script nonce="__CSP_NONCE__"')
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss(), cspNoncePlaceholder()],
  // ECharts is route-split by design: no global `echarts` import anywhere.
  // Lazy per-route `import('echarts/...')` lands in `echarts-vendor`.
  build: {
    target: 'baseline-widely-available',
    assetsInlineLimit: 0,
    sourcemap: false,
    cssCodeSplit: true,
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: (id: string): string | undefined => {
          if (
            id.includes('node_modules/echarts') ||
            id.includes('node_modules/echarts-for-react') ||
            id.includes('node_modules/zrender')
          ) {
            return 'echarts-vendor'
          }
          return undefined
        },
      },
    },
  },
  server: {
    host: 'localhost',
    port: 3000,
  },
  html: {
    cspNonce: 'CSP_NONCE_PLACEHOLDER',
  },
})
