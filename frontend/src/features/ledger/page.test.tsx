import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LedgerPage } from './page.js'

function summary(body: Record<string, unknown>) {
  return http.get('*/v1/admin/ledger/summary', () =>
    HttpResponse.json({
      totalPromptTokens: 0,
      totalCompletionTokens: 0,
      totalTokens: 0,
      totalCostUsd: '0.000000',
      byOwner: [],
      byModel: [],
      byProvider: [],
      ...body,
    }),
  )
}

function entry(requestId: string) {
  return {
    requestId,
    model: 'gpt-4o-mini',
    costUsdMicros: 12,
    createdAt: '2026-09-17T00:00:00Z',
  }
}

function pageOf(requestIds: string[], hasNext: boolean, page = 0) {
  return {
    content: requestIds.map((id) => entry(id)),
    page,
    size: 25,
    totalElements: requestIds.length,
    totalPages: hasNext ? 2 : 1,
    hasNext,
  }
}

function receiptOf(requestId: string) {
  return {
    requestId,
    ownerId: 'tenant-corp',
    provider: 'openai',
    model: 'gpt-4o-mini',
    promptTokens: 8,
    completionTokens: 4,
    totalTokens: 12,
    costUsdMicros: 12,
    durationMs: 41,
    cached: false,
    cacheTier: null,
    createdAt: '2026-09-21T00:00:00Z',
  }
}

describe('LedgerPage', () => {
  it('mounts the board without a session (router guards access)', () => {
    renderApp(<LedgerPage />)
    expect(screen.getByText(/loading summary/i)).toBeInTheDocument()
  })

  it('renders summary totals and paginated entries', async () => {
    server.use(
      summary({ totalRequests: 42, totalCostUsdMicros: 9000, averageDurationMs: 123.45 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r1'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('r1')).toBeInTheDocument()
    })
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('$0.009')).toBeInTheDocument()
    expect(screen.getByText('123.5ms')).toBeInTheDocument()
  })

  it('counts filter matches and clears the filter', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 2, totalCostUsdMicros: 2, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r1', 'r2'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await screen.findByRole('table')
    expect(screen.getByText('2 on this page')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/filter audit log/i), 'r1')
    expect(screen.getByText('1 of 2 match')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^clear$/i }))
    expect(screen.getByLabelText(/filter audit log/i)).toHaveValue('')
    expect(screen.getByText('2 on this page')).toBeInTheDocument()
  })

  it('jumps to a page number within range', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 50, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', ({ request }) => {
        const url = new URL(request.url)
        const p = Number.parseInt(url.searchParams.get('page') ?? '0', 10)
        return HttpResponse.json(pageOf([`r${String(p + 1)}`], p === 0, p))
      }),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/jump to page/i), '2')
    await user.click(screen.getByRole('button', { name: /^go$/i }))
    await waitFor(() => {
      expect(screen.getByText('r2')).toBeInTheDocument()
    })
  })

  it('selects a row with the Enter key', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      http.get('*/v1/admin/ledger/entries/:id', () => HttpResponse.json(receiptOf('r9'))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    // FE-22: narrow viewports scroll the table region, never the page.
    expect(table.closest('.overflow-x-auto')).not.toBeNull()
    within(table)
      .getByRole('button', { name: /inspect receipt r9/i })
      .focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('dialog')).toHaveTextContent(/r9|gpt-4o-mini/)
  })

  it('ignores an empty jump instead of paging', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 50, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', ({ request }) => {
        const url = new URL(request.url)
        const p = Number.parseInt(url.searchParams.get('page') ?? '0', 10)
        return HttpResponse.json(pageOf([`r${String(p + 1)}`], p === 0, p))
      }),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /^go$/i }))
    expect(screen.getByText('r1')).toBeInTheDocument()
  })

  it('selects a row with the Space key', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      http.get('*/v1/admin/ledger/entries/:id', () => HttpResponse.json(receiptOf('r9'))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect receipt r9/i })
      .focus()
    await user.keyboard('{ }')
    expect(await screen.findByRole('dialog')).toHaveTextContent(/r9|gpt-4o-mini/)
  })

  it('shows the empty state without traffic', async () => {
    server.use(
      summary({ totalRequests: 0, totalCostUsdMicros: 0, averageDurationMs: 0 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf([], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no entries yet/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /open playground/i })).toHaveAttribute(
      'href',
      '/playground',
    )
  })

  it('pages forward and back on the backend hasNext signal', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 30, totalCostUsdMicros: 1, averageDurationMs: 2 }),
      http.get('*/v1/admin/ledger/entries', ({ request }) => {
        const page = new URL(request.url).searchParams.get('page')
        return HttpResponse.json(
          page === '1' ? pageOf(['p1-r0'], false, 1) : pageOf(['p0-r0'], true, 0),
        )
      }),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /next/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 2/i)).toBeInTheDocument()
    })
    expect(screen.getByText('p1-r0')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /previous/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 1/i)).toBeInTheDocument()
    })
  })

  it('disables next when the backend reports no further page', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r1'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    expect(await screen.findByRole('button', { name: /next/i })).toBeDisabled()
  })

  it('inspects a row receipt without leaving the table', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      http.get('*/v1/admin/ledger/entries/:id', () => HttpResponse.json(receiptOf('r9'))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    const inspect = within(table).getByRole('button', { name: /inspect receipt r9/i })
    await user.click(inspect)
    const inspector = await screen.findByRole('dialog')
    expect(inspector).toHaveTextContent(/r9|gpt-4o-mini/)
    await user.click(inspect)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect/i)).toBeInTheDocument()
  })

  it('filters rows by request id without refetching', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 2, totalCostUsdMicros: 2, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json(pageOf(['keep-1', 'drop-2'], false)),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter audit log/i), 'keep')
    expect(screen.getByText('keep-1')).toBeInTheDocument()
    expect(screen.queryByText('drop-2')).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText(/filter audit log/i))
    await user.type(screen.getByLabelText(/filter audit log/i), 'zzz-no-match')
    expect(screen.getByText(/no entries match this filter/i)).toBeInTheDocument()
  })

  it('copies the inspected receipt as one line', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (s: string): Promise<void> => {
          writes.push(s)
          return Promise.resolve()
        },
      },
    })
    try {
      server.use(
        summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
        http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      )
      renderApp(<LedgerPage />, { adminSession: true })
      const table = await screen.findByRole('table')
      await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
      await user.click(screen.getByRole('button', { name: /copy receipt/i }))
      expect(writes.length).toBe(1)
      const first = writes.at(0)
      expect(first).toBeDefined()
      if (first !== undefined) expect(first).toContain('r9')
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    }
  })

  it('selects a row from the keyboard', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      http.get('*/v1/admin/ledger/entries/:id', () => HttpResponse.json(receiptOf('r9'))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect receipt r9/i })
      .focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('dialog', { name: /receipt inspector/i })).toHaveTextContent(
      'gpt-4o-mini',
    )
  })

  it('copies the inspected receipt as markdown with a heading and fence', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (s: string): Promise<void> => {
          writes.push(s)
          return Promise.resolve()
        },
      },
    })
    try {
      server.use(
        summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
        http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      )
      renderApp(<LedgerPage />, { adminSession: true })
      const table = await screen.findByRole('table')
      await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
      await user.click(screen.getByRole('button', { name: /copy markdown/i }))
      expect(writes.length).toBe(1)
      const first = writes.at(0)
      expect(first).toBeDefined()
      if (first !== undefined) {
        expect(first.startsWith('# Receipt r9')).toBe(true)
        expect(first).toContain('```json')
        expect(first.toLowerCase()).not.toContain('bearer')
      }
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    }
  })

  it('reports clipboard absence inline instead of failing silently', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await user.click(screen.getByRole('button', { name: /copy receipt/i }))
    expect(screen.getByText(/copy unavailable in this browser/i)).toBeInTheDocument()
  })

  it('reports clipboard rejection inline', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: (): Promise<void> => Promise.reject(new Error('denied')) },
    })
    try {
      server.use(
        summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
        http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
      )
      renderApp(<LedgerPage />, { adminSession: true })
      const table = await screen.findByRole('table')
      await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
      await user.click(screen.getByRole('button', { name: /copy markdown/i }))
      expect(await screen.findByText(/copy failed. select the text manually/i)).toBeInTheDocument()
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    }
  })

  it('omits the page total when the backend reports none', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json({ ...pageOf(['r1'], false), totalPages: 0 }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const pager = await screen.findByText(/page 1/i)
    expect(pager).toHaveTextContent('Page 1')
    expect(pager).not.toHaveTextContent('of')
  })

  it('reports summary failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => new HttpResponse('x', { status: 500 })),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf([], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('reports log failures as alerts', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })
})
