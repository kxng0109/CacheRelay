import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { Toasts } from '../../shared/components/Toasts.js'
import { useToastStore } from '../../shared/toast/store.js'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ApprovalsPage } from './page.js'

const PENDING = {
  approvals: [
    {
      approvalId: 'a1',
      toolName: 'send-email',
      requestedAt: '2026-09-17T00:00:00Z',
      requestedBy: 'agent',
    },
  ],
}

beforeEach(() => {
  useToastStore.getState().clear()
})

/**
 * Renders the board with the toast viewport, mirroring the shell layout.
 *
 * @returns The render result.
 */
function renderBoard() {
  return renderApp(
    <>
      <ApprovalsPage />
      <Toasts />
    </>,
    { adminSession: true },
  )
}

describe('ApprovalsPage', () => {
  it('mounts the board without a session (router guards access)', () => {
    renderApp(<ApprovalsPage />)
    expect(screen.getByText(/loading pending approvals/i)).toBeInTheDocument()
  })

  it('shows the empty queue', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json({ approvals: [] })),
    )
    renderApp(<ApprovalsPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/queue is empty/i)).toBeInTheDocument()
    })
  })

  it('approves a pending tool call', async () => {
    const user = userEvent.setup()
    let calls = 1
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () =>
        HttpResponse.json({ approvals: calls === 0 ? [] : PENDING.approvals }),
      ),
      http.post('*/v1/admin/mcp/approvals/:id/approve', () => {
        calls = 0
        return new HttpResponse(null, { status: 200 })
      }),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    await waitFor(() => {
      expect(screen.getByText(/a1: approved/i)).toBeInTheDocument()
    })
  })

  it('attributes the decision to the session username', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post('*/v1/admin/mcp/approvals/:id/approve', async ({ request }) => {
        body = await request.json()
        return new HttpResponse(null, { status: 200 })
      }),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    await waitFor(() => {
      expect(screen.getByText(/a1: approved/i)).toBeInTheDocument()
    })
    expect(body).toMatchObject({ decidedBy: 'test-admin' })
  })

  it('rejects a pending tool call', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post(
        '*/v1/admin/mcp/approvals/:id/reject',
        () => new HttpResponse(null, { status: 200 }),
      ),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^reject$/i }))
    await waitFor(() => {
      expect(screen.getByText(/a1: rejected/i)).toBeInTheDocument()
    })
  })

  it('holds the decision buttons while the call is in flight', async () => {
    const user = userEvent.setup()
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post('*/v1/admin/mcp/approvals/:id/approve', async () => {
        await gate
        return new HttpResponse(null, { status: 200 })
      }),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    // Both decision buttons hold while the call is in flight.
    const working = await screen.findAllByRole('button', { name: /working/i })
    expect(working).toHaveLength(2)
    for (const button of working) {
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('aria-busy', 'true')
    }
    release()
    await waitFor(() => {
      expect(screen.getByText(/a1: approved/i)).toBeInTheDocument()
    })
  })

  it('reports decision failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post(
        '*/v1/admin/mcp/approvals/:id/approve',
        () => new HttpResponse('x', { status: 409 }),
      ),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    await waitFor(() => {
      expect(screen.getByText(/a1:.*HTTP 409/)).toBeInTheDocument()
    })
  })

  it('reports queue fetch failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<ApprovalsPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })
})
