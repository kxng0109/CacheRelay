import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { Toasts } from '../../shared/components/Toasts.js'
import { useToastStore } from '../../shared/toast/store.js'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ApprovalsPage } from './page.js'

const TOKEN = '9f8e7d6c5b4a32109f8e7d6c5b4a3210'

const PENDING = {
  approvals: [
    {
      tokenId: TOKEN,
      toolName: 'send-email',
      serverName: 'mail',
      ownerId: 'tenant-corp',
      keyName: 'production-key',
      createdAt: '2026-09-17T00:00:00Z',
      expiresAt: '2026-09-17T00:05:00Z',
    },
  ],
}

function approvedReceipt() {
  return HttpResponse.json({
    status: 'APPROVED',
    tokenId: TOKEN,
    message: 'Tool invocation approved. Client may resume execution.',
  })
}

function rejectedReceipt() {
  return HttpResponse.json({
    status: 'REJECTED',
    tokenId: TOKEN,
    message: 'Tool invocation rejected and purged.',
  })
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

  it('renders admin unavailable on stealth 404', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<ApprovalsPage />, { adminSession: true })
    expect(await screen.findByText(/admin unavailable/i)).toBeInTheDocument()
  })

  it('states the approval lifetime windows', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json({ approvals: [] })),
    )
    renderApp(<ApprovalsPage />, { adminSession: true })
    expect(await screen.findByText(/approvals hold 5 minutes/i)).toBeInTheDocument()
  })

  it('approves a pending tool call with the server receipt', async () => {
    const user = userEvent.setup()
    let calls = 1
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () =>
        HttpResponse.json({ approvals: calls === 0 ? [] : PENDING.approvals }),
      ),
      http.post('*/v1/admin/mcp/approvals/:id/approve', () => {
        calls = 0
        return approvedReceipt()
      }),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    await waitFor(() => {
      expect(screen.getByText(/client may resume execution/i)).toBeInTheDocument()
    })
  })

  it('sends the rationale with the decision', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post('*/v1/admin/mcp/approvals/:id/approve', async ({ request }) => {
        body = await request.json()
        return approvedReceipt()
      }),
    )
    renderBoard()
    await screen.findByRole('button', { name: /^approve$/i })
    await user.type(screen.getByLabelText(/rationale/i), 'Looks safe')
    await user.click(await screen.findByRole('button', { name: /^approve$/i }))
    await waitFor(() => {
      expect(screen.getByText(/client may resume execution/i)).toBeInTheDocument()
    })
    expect(body).toMatchObject({ reason: 'Looks safe', decidedBy: 'test-admin' })
  })

  it('inspects decrypted args before deciding', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.get('*/v1/admin/mcp/approvals/:id', () =>
        HttpResponse.json({ tokenId: TOKEN, toolName: 'send-email', args: { to: 'ops@x.com' } }),
      ),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /inspect args/i }))
    expect(await screen.findByText(/ops@x\.com/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /hide args/i }))
    expect(screen.queryByText(/ops@x\.com/)).not.toBeInTheDocument()
  })

  it('reports args hydration failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.get('*/v1/admin/mcp/approvals/:id', () => new HttpResponse('x', { status: 500 })),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /inspect args/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/HTTP 500/)
  })

  it('names gone invocations honestly when the detail is empty', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.get('*/v1/admin/mcp/approvals/:id', () => HttpResponse.json(null, { status: 200 })),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /inspect args/i }))
    expect(await screen.findByText(/no args returned/i)).toBeInTheDocument()
  })

  it('warns against approving undecryptable args', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.get('*/v1/admin/mcp/approvals/:id', () =>
        HttpResponse.json({ tokenId: TOKEN, toolName: 'send-email', args: '***undecryptable***' }),
      ),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /inspect args/i }))
    expect(await screen.findByText(/out-of-band verification/i)).toBeInTheDocument()
  })

  it('rejects a pending tool call', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json(PENDING)),
      http.post('*/v1/admin/mcp/approvals/:id/reject', () => rejectedReceipt()),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /^reject$/i }))
    await waitFor(() => {
      expect(screen.getByText(/rejected and purged/i)).toBeInTheDocument()
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
        return approvedReceipt()
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
      expect(screen.getByText(/client may resume execution/i)).toBeInTheDocument()
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
      expect(
        screen.getByText(new RegExp(`${TOKEN.slice(0, 8)}.*conflict`, 'i')),
      ).toBeInTheDocument()
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
