import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { useOverlayFocus } from './useOverlayFocus.js'

/**
 * Minimal overlay harness: a background button (outside the dialog) plus a
 * body-portalled dialog with two stops.
 *
 * @returns Nothing; asserts through the screen.
 */
function Harness({
  onClose,
  label = 'probe dialog',
}: {
  onClose?: () => void
  label?: string
}): React.JSX.Element {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  useOverlayFocus(dialogRef)
  // Body portal mirrors production overlays: the dialog splits from the
  // background at the `body` child level, which is what `inert` scoping
  // relies on.
  return createPortal(
    <div ref={dialogRef} role="dialog" aria-label={label}>
      <button type="button">first stop</button>
      <button type="button">second stop</button>
      {onClose === undefined ? null : (
        <button
          type="button"
          onClick={() => {
            onClose()
          }}
        >
          close overlay
        </button>
      )}
    </div>,
    document.body,
  )
}

/**
 * Overlay without stops: exercises the bare-container focus fallback.
 */
function EmptyHarness(): React.JSX.Element {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  useOverlayFocus(dialogRef)
  return createPortal(
    <div ref={dialogRef} role="dialog" aria-label="empty dialog">
      <p>no stops here</p>
    </div>,
    document.body,
  )
}

describe('useOverlayFocus', () => {
  it('moves initial focus inside and inerts the background', async () => {
    renderApp(
      <div data-testid="background">
        <button type="button">background action</button>
        <Harness />
      </div>,
    )
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /first stop/i })).toHaveFocus()
    })
    const background = screen.getByTestId('background')
    // `inert` lands on the body-level container; the background sits inside
    // an inert subtree while the portalled dialog stays outside it.
    expect(background.closest('[inert]')).not.toBeNull()
    expect(screen.getByRole('dialog').closest('[inert]')).toBeNull()
  })

  it('pulls stray focus back inside on Tab', () => {
    renderApp(
      <div data-testid="background">
        <button type="button">background action</button>
        <Harness />
      </div>,
    )
    const first = screen.getByRole('button', { name: /first stop/i })
    expect(first).toHaveFocus()
    // Escape the dialog without the hook noticing, then Tab: the trap
    // pulls focus back to the first stop instead of letting it wander.
    ;(document.activeElement as HTMLElement | null)?.blur()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(first).toHaveFocus()
  })

  it('holds Tab on an empty dialog instead of wandering', () => {
    renderApp(
      <div data-testid="background">
        <EmptyHarness />
      </div>,
    )
    const dialog = screen.getByRole('dialog', { name: /empty dialog/i })
    expect(dialog).toHaveFocus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(dialog).toHaveFocus()
  })

  it('traps Tab inside and returns focus to the origin on release', async () => {
    const user = userEvent.setup()
    function Wrapper(): React.JSX.Element {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button">origin action</button>
          <button
            type="button"
            onClick={() => {
              setOpen(true)
            }}
          >
            open overlay
          </button>
          {open ? <Harness /> : null}
        </>
      )
    }
    renderApp(<Wrapper />)
    const origin = screen.getByRole('button', { name: /origin action/i })
    origin.focus()
    await user.click(screen.getByRole('button', { name: /open overlay/i }))
    const first = await screen.findByRole('button', { name: /first stop/i })
    const second = screen.getByRole('button', { name: /second stop/i })
    expect(first).toHaveFocus()
    await user.tab()
    expect(second).toHaveFocus()
    await user.tab()
    expect(first).toHaveFocus()
    await user.tab({ shift: true })
    expect(second).toHaveFocus()
  })

  it('focuses a bare container when nothing inside is stoppable', async () => {
    renderApp(
      <div data-testid="background">
        <EmptyHarness />
      </div>,
    )
    const dialog = await screen.findByRole('dialog', { name: /empty dialog/i })
    await waitFor(() => {
      expect(dialog).toHaveFocus()
    })
    expect(dialog).toHaveAttribute('tabindex', '-1')
  })

  it('returns nothing when the opener is gone', async () => {
    const user = userEvent.setup()
    function Wrapper(): React.JSX.Element {
      const [open, setOpen] = useState(false)
      const [gone, setGone] = useState(false)
      return (
        <>
          {gone ? null : (
            <button
              type="button"
              onClick={() => {
                setOpen(true)
              }}
            >
              open overlay
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              setGone(true)
            }}
          >
            remove opener
          </button>
          {open ? (
            <Harness
              onClose={() => {
                setOpen(false)
              }}
            />
          ) : null}
        </>
      )
    }
    renderApp(<Wrapper />)
    await user.click(screen.getByRole('button', { name: /open overlay/i }))
    await screen.findByRole('button', { name: /first stop/i })
    await user.click(screen.getByRole('button', { name: /remove opener/i }))
    await user.click(screen.getByRole('button', { name: /close overlay/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    // The opener is detached: focus falls back to the body, never throws.
    expect(document.activeElement === document.body).toBe(true)
  })

  it('keeps the lower background inert when a nested overlay closes', async () => {
    const user = userEvent.setup()
    function Stack(): React.JSX.Element {
      const [inner, setInner] = useState(false)
      return (
        <>
          <Harness label="outer dialog" />
          <button
            type="button"
            onClick={() => {
              setInner(true)
            }}
          >
            open inner
          </button>
          {inner ? (
            <Harness
              label="inner dialog"
              onClose={() => {
                setInner(false)
              }}
            />
          ) : null}
        </>
      )
    }
    renderApp(
      <div data-testid="background">
        <button type="button">background action</button>
        <Stack />
      </div>,
    )
    const background = screen.getByTestId('background')
    // Outer overlay open: its background is inert.
    expect(background.closest('[inert]')).not.toBeNull()
    await user.click(screen.getByRole('button', { name: /open inner/i }))
    await screen.findByRole('dialog', { name: /inner dialog/i })
    // Close the top overlay: the still-open lower overlay's background
    // must stay inert — closing the top must not un-inert it (DEF-10).
    await user.click(screen.getByRole('button', { name: /close overlay/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /inner dialog/i })).not.toBeInTheDocument()
    })
    expect(screen.getByRole('dialog', { name: /outer dialog/i })).toBeInTheDocument()
    expect(background.closest('[inert]')).not.toBeNull()
  })
})
