import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { scrolledIntoView } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { PlaygroundPage } from './page.js'

const STREAM = 'data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: [DONE]\n\n'

afterEach(() => {
  vi.unstubAllGlobals()
  // Env stubs leak across tests otherwise: a `VITE_FEATURE_STREAMING=false`
  // left behind silently flips every later test onto the static path.
  vi.unstubAllEnvs()
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
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
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
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
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
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })

  it('names an empty non-streaming completion honestly', async () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/chat/completions', () =>
        HttpResponse.json({ choices: [], model: 'gpt-4o-mini' }),
      ),
    )
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    await waitFor(() => {
      expect(screen.getByRole('log')).toHaveTextContent(/empty completion/i)
    })
  })

  it('requires a model before sending', async () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    const user = userEvent.setup()
    renderApp(<PlaygroundPage />)
    await user.clear(screen.getByLabelText(/model/i))
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    await waitFor(() => {
      expect(screen.getByText(/model is required/i)).toBeInTheDocument()
    })
  })

  it('records runs and reloads them into the prompt', async () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/chat/completions', () =>
        HttpResponse.json({ choices: [{ message: { content: 'hi' } }], model: 'gpt-4o-mini' }),
      ),
    )
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
    await user.click(screen.getByRole('button', { name: /send completion/i }))
    const entry = await screen.findByRole('button', { name: /run #1/i })
    expect(entry).toHaveTextContent('Say hello')
    await user.clear(screen.getByLabelText(/prompt/i, { selector: 'textarea' }))
    await user.click(entry)
    expect(screen.getByLabelText(/prompt/i, { selector: 'textarea' })).toHaveValue('Say hello')
  })

  it('sends from the keyboard without leaving the prompt', async () => {
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
    const prompt = screen.getByLabelText(/prompt/i, { selector: 'textarea' })
    await user.type(prompt, 'Say hello')
    await user.keyboard('{Control>}{Enter}{/Control}')
    await waitFor(
      () => {
        expect(screen.getByRole('log')).toHaveTextContent('Hello')
      },
      { timeout: 5000 },
    )
    expect(screen.getByRole('region', { name: /run 1: gpt-56-luna/i })).toBeInTheDocument()
  })

  it('fills the sanctioned sample without sending', async () => {
    const user = userEvent.setup()
    renderApp(<PlaygroundPage />)
    await user.click(screen.getByRole('button', { name: /try sample prompt/i }))
    expect(screen.getByLabelText(/prompt/i, { selector: 'textarea' })).toHaveValue(
      'Summarize the three cache outcomes (HIT, MISS, STALE) in one sentence each.',
    )
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
  })

  it('disables send past the prompt limit', async () => {
    renderApp(<PlaygroundPage />)
    fireEvent.change(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), {
      target: { value: 'x'.repeat(8001) },
    })
    await waitFor(() => {
      expect(screen.getByText(/over limit/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /stream completion/i })).toBeDisabled()
  })

  it('offers a copyable terminal example', async () => {
    const user = userEvent.setup()
    renderApp(<PlaygroundPage />)
    await user.click(screen.getByText(/run it from a terminal instead/i))
    expect(screen.getByText(/chat\/completions/i)).toBeInTheDocument()
  })

  it('reruns the submitted run as a fresh block and scrolls to it', async () => {
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
    scrolledIntoView.length = 0
    renderApp(<PlaygroundPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/prompt/i, { selector: 'textarea' }), 'Say hello')
    await user.click(screen.getByRole('button', { name: /stream completion/i }))
    await waitFor(
      () => {
        expect(screen.getByRole('log')).toHaveTextContent('Hello')
      },
      { timeout: 5000 },
    )
    expect(scrolledIntoView.length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: /^rerun$/i }))
    await waitFor(() => {
      expect(screen.getByRole('region', { name: /run 2: gpt-56-luna/i })).toBeInTheDocument()
    })
  })
})
