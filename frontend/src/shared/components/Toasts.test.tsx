import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useToastStore } from '../toast/store.js'
import { renderApp } from '../../test/utils.js'
import { Toasts } from './Toasts.js'

beforeEach(() => {
  useToastStore.getState().clear()
})

describe('Toasts viewport', () => {
  it('renders nothing on an empty stack', () => {
    renderApp(<Toasts />)
    expect(screen.queryByLabelText('Notifications')).not.toBeInTheDocument()
  })

  it('announces successes politely and errors assertively', () => {
    renderApp(<Toasts />)
    act(() => {
      useToastStore.getState().push('success', 'a1: approved.')
      useToastStore.getState().push('error', 'a1: network down.')
    })
    // Live regions announce content changes, not accessible names (status
    // and alert prohibit naming), so assert role counts plus exact text.
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByText('a1: approved.')).toBeInTheDocument()
    expect(screen.getByText('a1: network down.')).toBeInTheDocument()
  })

  it('dismisses one toast on demand', async () => {
    const user = userEvent.setup()
    renderApp(<Toasts />)
    act(() => {
      useToastStore.getState().push('success', 'first notice.')
      useToastStore.getState().push('success', 'second notice.')
    })
    await user.click(screen.getByRole('button', { name: /dismiss: first notice/i }))
    await waitFor(() => {
      expect(screen.queryByText('first notice.')).not.toBeInTheDocument()
    })
    expect(screen.getByText('second notice.')).toBeInTheDocument()
  })

  it('clears every toast on Escape from anywhere on the page', async () => {
    const user = userEvent.setup()
    renderApp(
      <>
        <button type="button">outside action</button>
        <Toasts />
      </>,
    )
    act(() => {
      useToastStore.getState().push('success', 'first notice.')
      useToastStore.getState().push('error', 'second notice.')
    })
    // Focus stays outside the stack: the global ladder entry still clears.
    screen.getByRole('button', { name: /outside action/i }).focus()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByLabelText('Notifications')).not.toBeInTheDocument()
    })
  })

  it('times each toast from its own push, never starving early ones', async () => {
    vi.useFakeTimers()
    try {
      renderApp(<Toasts />)
      act(() => {
        useToastStore.getState().push('success', 'first notice.')
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      act(() => {
        useToastStore.getState().push('success', 'second notice.')
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2100)
      })
      expect(screen.queryByText('first notice.')).not.toBeInTheDocument()
      expect(screen.getByText('second notice.')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('auto-dismisses the oldest toast after four seconds', async () => {
    vi.useFakeTimers()
    try {
      renderApp(<Toasts />)
      act(() => {
        useToastStore.getState().push('success', 'aging notice.')
      })
      expect(screen.getByText('aging notice.')).toBeInTheDocument()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4000)
      })
      expect(screen.queryByText('aging notice.')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('caps the stack instead of growing without bound', () => {
    renderApp(<Toasts />)
    act(() => {
      for (let i = 0; i < 8; i += 1)
        useToastStore.getState().push('success', `notice ${String(i)}.`)
    })
    expect(useToastStore.getState().toasts).toHaveLength(5)
    expect(screen.getByText('notice 7.')).toBeInTheDocument()
    expect(screen.queryByText('notice 0.')).not.toBeInTheDocument()
  })
})
