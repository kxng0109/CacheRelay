import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { UsagePage } from './page.js'

function summary(overrides: Record<string, unknown> = {}) {
  return {
    totalRequests: 7,
    totalPromptTokens: 700,
    totalCompletionTokens: 300,
    totalTokens: 1000,
    totalCostUsdMicros: 1500,
    totalCostUsd: '0.001500',
    averageDurationMs: 42.5,
    byOwner: [],
    byModel: [],
    byProvider: [],
    ...overrides,
  }
}

function usageOk(body: Record<string, unknown> = {}) {
  return http.get('*/v1/me/usage', () =>
    HttpResponse.json(summary(body), {
      headers: { 'X-Dashboard-Generated-At': '2026-09-24T10:00:00Z' },
    }),
  )
}

describe('UsagePage', () => {
  it('loads the past week by default', async () => {
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summary(), {
          headers: { 'X-Dashboard-Generated-At': '2026-09-24T10:00:00Z' },
        })
      }),
    )
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument()
    })
    expect(screen.getByRole('combobox', { name: /range/i })).toHaveTextContent('Past 7 days')
    expect(seenUrl).toContain('from=')
    expect(seenUrl).toContain('to=')
    expect(screen.getByText(/updated .* ago/i)).toBeInTheDocument()
  })

  it('renders the empty trio on empty windows', async () => {
    server.use(usageOk({ totalRequests: 0, averageDurationMs: 0 }))
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no usage in range/i)).toBeInTheDocument()
    })
  })

  it('switches presets without a submit click', async () => {
    const user = userEvent.setup()
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summary())
      }),
    )
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    await selectOption(user, /range/i, 'Today')
    await waitFor(() => {
      expect(seenUrl).toContain('from=')
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('rejects reversed windows client-side without fetching', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/me/usage', () => {
        calls += 1
        return HttpResponse.json(summary())
      }),
    )
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    await selectOption(user, /range/i, 'Custom range')
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-24' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-01' } })
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot be after/i)
    expect(calls).toBe(1)
  })

  it('rejects oversize windows with the narrow-the-window copy', async () => {
    const user = userEvent.setup()
    server.use(usageOk())
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    await selectOption(user, /range/i, 'Custom range')
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-01-01' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-24' } })
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/narrow the window/i)
  })

  it('applies valid custom windows to the query', async () => {
    const user = userEvent.setup()
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summary())
      }),
    )
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    await selectOption(user, /range/i, 'Custom range')
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-20' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-24' } })
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    await waitFor(() => {
      expect(seenUrl).toContain('from=2026-09-20')
    })
    expect(seenUrl).toContain('to=2026-09-24')
  })

  it('surfaces backend 400 with narrow-the-window guidance', async () => {
    server.use(http.get('*/v1/me/usage', () => new HttpResponse('x', { status: 400 })))
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/narrow the window/i)
    })
  })

  it('hints IdP disablement on 401', async () => {
    server.use(http.get('*/v1/me/usage', () => new HttpResponse('x', { status: 401 })))
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/IdP account may be disabled/i)
    })
  })

  it('backs off with slow-down copy on 429', async () => {
    server.use(http.get('*/v1/me/usage', () => new HttpResponse('x', { status: 429 })))
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(
      () => {
        expect(screen.getByRole('alert')).toHaveTextContent(/slow down/i)
      },
      { timeout: 8000 },
    )
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('hides custom dates until the custom range is picked', async () => {
    server.use(usageOk())
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    expect(screen.queryByLabelText(/^from$/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^apply$/i })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('submits blank custom dates as backend defaults', async () => {
    const user = userEvent.setup()
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summary())
      }),
    )
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await screen.findByText('7')
    await selectOption(user, /range/i, 'Custom range')
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    await waitFor(() => {
      expect(seenUrl.endsWith('/v1/me/usage')).toBe(true)
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('names unreachable gateways without a status code', async () => {
    server.use(http.get('*/v1/me/usage', () => HttpResponse.error()))
    renderApp(<UsagePage />, { route: '/usage', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/network unreachable/i)
    })
  })
})
