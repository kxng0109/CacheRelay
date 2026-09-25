import { useCallback, useEffect, useRef } from 'react'
import type { RefObject } from 'react'

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Lists keyboard stops inside an overlay in DOM order.
 *
 * @param root - Overlay container.
 * @returns Focusable descendants.
 */
function stops(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE))
}

/**
 * Marks every `body` child outside the overlay as inert. Attribute form,
 * so tests and assistive tech observe the same state the browser enforces.
 *
 * @remarks Nested overlays each record what they set (see
 * `useOverlayFocus`): release restores only its own marks, so closing an
 * upper overlay never strips a lower overlay's background inert (DEF-10).
 *
 * @param dialog - Overlay container whose background to mark.
 * @returns The marked elements with their previous `inert` presence.
 */
function markBackgroundInert(dialog: HTMLElement): { el: Element; had: boolean }[] {
  // `body.children` holds elements only: no narrowing needed, every child
  // takes or keeps the mark.
  const touched: { el: Element; had: boolean }[] = []
  for (const child of Array.from(document.body.children)) {
    if (child === dialog || child.contains(dialog)) continue
    touched.push({ el: child, had: child.hasAttribute('inert') })
    child.setAttribute('inert', '')
  }
  return touched
}

/**
 * Restores elements marked by {@link markBackgroundInert}: only elements
 * this overlay set (no prior mark) are cleared; pre-existing marks from
 * lower overlays are kept.
 *
 * @param touched - Marks recorded when the overlay opened.
 */
function restoreBackgroundInert(touched: { el: Element; had: boolean }[]): void {
  for (const { el, had } of touched) {
    if (!had) el.removeAttribute('inert')
  }
}

export interface OverlayFocus {
  /**
   * Releases the overlay immediately: drops background inert and returns
   * focus to the origin. Overlays call this when a close starts so focus
   * never sits inside the exit animation; unmount repeats it as a fallback.
   */
  release: () => void
}

/**
 * Shared overlay focus contract: origin capture, initial focus, Tab trap,
 * background `inert`, and focus return.
 *
 * @remarks Single implementation for Modal, InspectorShell, and
 * ShortcutSheet (FE-09). Assumes body-portalled containers (so `body`
 * children cleanly split into overlay vs background). Each overlay tracks
 * exactly the elements it marked inert and restores only those on
 * release, so stacked overlays compose (DEF-10). Initial focus prefers `initialRef` (drawer headings), else the first
 * stop, else the container itself; an `autoFocus` caller that already
 * landed inside wins over all three.
 *
 * @param dialogRef - Body-portalled overlay container.
 * @param initialRef - Preferred initial stop, if any.
 * @returns The release handle for close paths.
 */
export function useOverlayFocus(
  dialogRef: RefObject<HTMLElement | null>,
  initialRef?: RefObject<HTMLElement | null>,
): OverlayFocus {
  const originRef = useRef<HTMLElement | null>(null)
  const releasedRef = useRef(false)
  /**
   * This overlay's own inert marks. Restoring only these (never clearing
   * everywhere) keeps nested overlays from un-inerting each other.
   */
  const touchedRef = useRef<{ el: Element; had: boolean }[]>([])

  const release = useCallback((): void => {
    if (releasedRef.current) return
    releasedRef.current = true
    restoreBackgroundInert(touchedRef.current)
    touchedRef.current = []
    if (originRef.current?.isConnected === true) originRef.current.focus()
  }, [])

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) return
    originRef.current = document.activeElement as HTMLElement | null
    if (!dialog.contains(document.activeElement)) {
      const initial = initialRef?.current
      if (initial !== undefined && initial !== null && dialog.contains(initial)) {
        initial.focus()
      } else {
        const [first] = stops(dialog)
        if (first !== undefined) first.focus()
        else {
          dialog.setAttribute('tabindex', '-1')
          dialog.focus()
        }
      }
    }
    touchedRef.current = markBackgroundInert(dialog)
    const onKey = (event: KeyboardEvent): void => {
      if (event.key !== 'Tab') return
      const current = dialogRef.current
      if (current === null) return
      const items = stops(current)
      if (items.length === 0) {
        event.preventDefault()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      if (first === undefined || last === undefined) return
      if (!current.contains(document.activeElement)) {
        event.preventDefault()
        first.focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('keydown', onKey, true)
      release()
    }
  }, [dialogRef, initialRef, release])

  return { release }
}
