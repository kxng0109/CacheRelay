/**
 * URL safety helpers: every `href`/`src` the SPA renders from dynamic data
 * must pass through {@link isSafeUrl} first.
 */

const ALLOWED_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:'])

/**
 * Checks whether a URL is safe to render as `href`/`src`.
 *
 * @param raw - Candidate URL (absolute, or site-relative starting with `/` or `#`).
 * @returns True for `http`/`https`/`mailto`/`tel` and site-relative URLs; false for `javascript:`, `data:`, `vbscript:`, and unparseable input.
 */
export function isSafeUrl(raw: string): boolean {
  const value = raw.trim()
  if (value.length === 0) return false
  if (value.startsWith('/') || value.startsWith('#')) return true
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    return false
  }
  return ALLOWED_PROTOCOLS.has(parsed.protocol)
}
