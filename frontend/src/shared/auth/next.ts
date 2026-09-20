/** Maximum accepted post-login destination length (generous, bounded). */
const MAX_NEXT_LENGTH = 2048

/**
 * Validates a post-login destination from `?next=` against an allow-list shape.
 *
 * @remarks Only same-origin absolute paths pass: a single leading slash, no
 * backslashes (no `\\` UNC or escape tricks), bounded length. Login and
 * redeem bounce to `/` so a stale bookmark cannot loop the auth screens.
 * Everything else — external URLs, protocol-relative `//host`, auth screens —
 * falls back to `/`.
 *
 * @param raw - Untrusted `next` query value, or null when absent.
 * @returns A safe in-app path, defaulting to `/`.
 */
export function resolveNext(raw: string | null): string {
  if (raw === null || raw.length === 0 || raw.length > MAX_NEXT_LENGTH) return '/'
  if (!raw.startsWith('/')) return '/'
  if (raw.startsWith('//')) return '/'
  if (raw.includes('\\')) return '/'
  if (
    raw === '/login' ||
    raw.startsWith('/login?') ||
    raw === '/redeem' ||
    raw.startsWith('/redeem?')
  )
    return '/'
  return raw
}
