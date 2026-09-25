/** Maximum accepted post-login destination length (generous, bounded). */
const MAX_NEXT_LENGTH = 2048

/**
 * Validates a post-login destination from `?next=` against an allow-list shape.
 *
 * @remarks Only same-origin absolute paths pass: a single leading slash, no
 * backslashes, no ASCII control characters (the URL parser strips tab and
 * newline, turning a smuggled tab into a cross-origin navigation), bounded
 * length. The candidate is resolved against the current origin and only the
 * same-origin `pathname + search + hash` is returned — then the returned
 * string is guarded itself, because the WHATWG parser shortens dot segments
 * (`/..//evil.example` → `//evil.example`) while staying same-origin.
 * Login and redeem bounce to `/` so a stale bookmark cannot loop the auth
 * screens. Everything else — external URLs, protocol-relative `//host`,
 * auth screens — falls back to `/`.
 *
 * @param raw - Untrusted `next` query value, or null when absent.
 * @returns A safe in-app path, defaulting to `/`.
 */
export function resolveNext(raw: string | null): string {
  if (raw === null || raw.length === 0 || raw.length > MAX_NEXT_LENGTH) return '/'
  if (!raw.startsWith('/')) return '/'
  if (raw.startsWith('//')) return '/'
  if (raw.includes('\\')) return '/'
  for (let i = 0; i < raw.length; i += 1) {
    const code = raw.charCodeAt(i)
    if (code <= 0x1f || code === 0x7f) return '/'
  }
  let baseOrigin: string
  let url: URL
  try {
    const loc = (globalThis as { location?: { origin?: unknown } }).location
    const base =
      typeof loc?.origin === 'string' && loc.origin.length > 0 ? loc.origin : 'http://localhost'
    baseOrigin = new URL(base).origin
    url = new URL(raw, base)
    if (url.origin !== baseOrigin) return '/'
  } catch {
    return '/'
  }
  const safe = `${url.pathname}${url.search}${url.hash}`
  // Guard the returned string, not just the input: dot-segment shortening
  // can turn a same-origin resolve into a `//host` pathname (DEF-01).
  if (safe.startsWith('//')) return '/'
  try {
    if (new URL(safe, baseOrigin).origin !== baseOrigin) return '/'
  } catch {
    return '/'
  }
  if (
    safe === '/login' ||
    safe.startsWith('/login?') ||
    safe === '/redeem' ||
    safe.startsWith('/redeem?')
  )
    return '/'
  if (safe.length > MAX_NEXT_LENGTH) return '/'
  return safe
}
