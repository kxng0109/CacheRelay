import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { PlaygroundPage } from './page.js'

const STREAM = 'data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: [DONE]\n\n'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PlaygroundPage', () => {
  it('prefills the key from the memory store', () => {
    renderApp(<PlaygroundPage />, { gatewayKey: 'gw-seeded' })
    expect(screen.getByLabelText(/api key/i)).toHaveValue('gw-seeded')
  })

  it('validates the prompt form before streaming', async () => {
    const user = userEvent.setup()
    renderApp(<PlaygroundPage />)
    await user.click(screen.getByRole('button', { name: /stream completion/i }))
    await waitFor(() => {
      expect(screen.getByText(/prompt is required/i)).toBeInTheDocument()
    })
  })

  it('streams a completion into the log region', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(STREAM))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i), 'Say hello')
    await user.click(screen.getByRole('button', { name: /stream completion/i }))
    await waitFor(
      () => {
        expect(screen.getByRole('log')).toHaveTextContent('Hello')
      },
      { timeout: 5000 },
    )
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
  })

  it('falls back to non-streaming completions when the flag is off', async () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/chat/completions', () =>
        HttpResponse.json({
          choices: [{ message: { role: 'assistant', content: 'static hi' } }],
          model: 'gpt-4o-mini',
        }),
      ),
    )
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    await waitFor(() => {
      expect(screen.getByRole('log')).toHaveTextContent('static hi')
    })
    expect(screen.queryByText(/phase:/i)).not.toBeInTheDocument()
  })

  it('reports non-streaming failures as alerts', async () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    const user = userEvent.setup()
    server.use(http.post('*/v1/chat/completions', () => new HttpResponse('x', { status: 503 })))
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })
})
