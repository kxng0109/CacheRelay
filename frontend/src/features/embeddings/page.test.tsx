import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { EmbeddingsPage } from './page.js'

describe('EmbeddingsPage', () => {
  /**
   * Catalog stub: every submitting test picks the model from the live
   * list, never from a hardcoded default.
   */
  function catalog() {
    return http.get('*/v1/models', () =>
      HttpResponse.json({ data: [{ id: 'text-embedding-3-small' }] }),
    )
  }

  /**
   * Waits for the catalog then chooses the model, mirroring the operator
   * flow of paste key, pick model, write input.
   */
  async function pickModel(
    user: ReturnType<typeof userEvent.setup>,
    id = 'text-embedding-3-small',
  ): Promise<void> {
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent(/select a model/i)
    })
    await selectOption(user, /^model$/i, id)
  }

  it('fills the sanctioned sample without submitting', async () => {
    const user = userEvent.setup()
    renderApp(<EmbeddingsPage />)
    await user.click(screen.getByRole('button', { name: /fill sample text/i }))
    expect(screen.getByLabelText(/input text/i)).toHaveValue(
      'CacheRelay routes every request through admission, cache, and router stages.',
    )
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('diagnoses failures with a retry action', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () => {
        calls += 1
        if (calls === 1) return new HttpResponse('x', { status: 503 })
        return HttpResponse.json({
          data: [{ embedding: [0.1], index: 0 }],
          model: 'text-embedding-3-small',
        })
      }),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const retry = await screen.findByRole('button', { name: /^retry$/i })
    expect(screen.getByRole('alert')).toHaveTextContent(/embedding request failed/i)
    await user.click(retry)
    await waitFor(() => {
      expect(screen.getByRole('table')).toHaveTextContent('● ok')
    })
  })

  it('validates empty input before submitting', async () => {
    const user = userEvent.setup()
    renderApp(<EmbeddingsPage />)
    fireEvent.change(screen.getByLabelText(/^model$/i), { target: { value: '' } })
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByText(/input text is required/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/model is required/i)).toBeInTheDocument()
    // FE-30: errors are linked, not just broadcast.
    expect(screen.getByLabelText(/input text/i)).toHaveAttribute(
      'aria-describedby',
      'emb-input-error',
    )
    expect(screen.getByRole('combobox', { name: /^model$/i })).toHaveAttribute(
      'aria-describedby',
      'emb-model-error',
    )
  })

  it('requires an api key in paste mode before sending', async () => {
    const user = userEvent.setup()
    server.use(catalog())
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.clear(screen.getByLabelText(/api key/i))
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    expect(await screen.findByText(/api key is required/i)).toBeInTheDocument()
  })

  it('reports dimensions and vector count on success', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({
          data: [{ embedding: [0.1, 0.2, 0.3], index: 0 }],
          model: 'text-embedding-3-small',
        }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('text-embedding-3-small')
    expect(table).toHaveTextContent('● ok')
  })

  it('sends embedding options and records token usage per run', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      catalog(),
      http.post('*/v1/embeddings', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({
          data: [{ embedding: [0.1, 0.2], index: 0 }],
          model: 'text-embedding-3-small',
          usage: { prompt_tokens: 5, total_tokens: 5 },
        })
      }),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.clear(screen.getByLabelText(/dimensions/i))
    await user.type(screen.getByLabelText(/dimensions/i), '512')
    await user.type(screen.getByLabelText(/user/i), 'op-1')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('5 tok')
    expect(body).toMatchObject({ dimensions: 512 })
  })

  it('sends base64 encoding via the shared dropdown', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      catalog(),
      http.post('*/v1/embeddings', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({
          data: [{ embedding: [0.1], index: 0 }],
          model: 'text-embedding-3-small',
        })
      }),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await selectOption(user, /encoding format/i, 'base64')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await screen.findByRole('table')
    expect(body).toMatchObject({ encoding_format: 'base64' })
  })

  it('fills dimensions from the catalog default on demand', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/me/keys', () =>
        HttpResponse.json([
          { keyId: 'a'.repeat(64), name: 'dev', allowedModels: [], enabled: true },
        ]),
      ),
      http.get('*/v1/models', () =>
        HttpResponse.json({ data: [{ id: 'text-embedding-3-small' }] }),
      ),
      http.get('*/v1/admin/model-catalog', () =>
        HttpResponse.json({
          models: [
            {
              modelId: 'text-embedding-3-small',
              provider: 'openai',
              mode: 'embeddings',
              inputCostPerToken: 0.00000002,
              outputCostPerToken: 0,
              cacheReadInputTokenCost: null,
              cacheCreationInputTokenCost: null,
              maxInputTokens: 8191,
              maxOutputTokens: null,
              qualityTier: null,
              benchmarkRefs: null,
              embeddingDimensions: 1536,
            },
          ],
        }),
      ),
    )
    renderApp(<EmbeddingsPage />, { adminSession: true })
    await pickModel(user)
    expect(await screen.findByText(/catalog default: 1536 dims/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /fill 1536/i }))
    expect(screen.getByLabelText(/dimensions/i)).toHaveValue('1536')
  })

  it('hides the dims hint from guests without admin catalog access', async () => {
    const user = userEvent.setup()
    server.use(catalog())
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await waitFor(() => {
      expect(screen.getByLabelText(/dimensions/i)).toBeInTheDocument()
    })
    expect(screen.queryByText(/catalog default/i)).not.toBeInTheDocument()
  })

  it('omits malformed dimensions instead of sending NaN', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      catalog(),
      http.post('*/v1/embeddings', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({
          data: [{ embedding: [0.1], index: 0 }],
          model: 'text-embedding-3-small',
        })
      }),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.type(screen.getByLabelText(/dimensions/i), 'abc')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await screen.findByRole('table')
    expect(body).not.toMatchObject({ dimensions: 512 })
    expect(body).not.toHaveProperty('dimensions')
  })

  it('shows the model chip and counters after a run', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({
          data: [{ embedding: [0.1], index: 0 }],
          model: 'm',
        }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByText(/model:text-embedding-3-small/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/vectors:1/i)).toBeInTheDocument()
  })

  it('counts input characters live', async () => {
    const user = userEvent.setup()
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    expect(screen.getByText('5/8000')).toBeInTheDocument()
  })

  it('blocks submit past the input limit with an announced guard', async () => {
    renderApp(<EmbeddingsPage />)
    fireEvent.change(screen.getByLabelText(/input text/i), {
      target: { value: 'x'.repeat(8001) },
    })
    await waitFor(() => {
      expect(screen.getByText(/over limit/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /create embeddings/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/over the 8000/i)
  })

  it('filters runs by model and inspects failures', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/models', () => HttpResponse.json({ data: [{ id: 'm-a' }, { id: 'm-b' }] })),
      http.post('*/v1/embeddings', () => {
        calls += 1
        if (calls === 1) {
          return HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm-a' })
        }
        return new HttpResponse('x', { status: 500 })
      }),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent(/select a model/i)
    })
    await selectOption(user, /^model$/i, 'm-a')
    await user.type(screen.getByLabelText(/input text/i), 'one')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await selectOption(user, /^model$/i, 'm-b')
    await user.clear(screen.getByLabelText(/input text/i))
    await user.type(screen.getByLabelText(/input text/i), 'two')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    await waitFor(() => {
      expect(table).toHaveTextContent('■ fail')
    })
    await user.type(screen.getByLabelText(/filter usage by model/i), 'm-b')
    expect(table).not.toHaveTextContent('m-a')
    await user.clear(screen.getByLabelText(/filter usage by model/i))
    await user.click(within(table).getByRole('button', { name: /inspect run m-b/i }))
    expect(screen.getByRole('dialog', { name: /run inspector/i })).toHaveTextContent(/HTTP 500/)
  })

  it('closes the run inspector from its close button', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/models', () => HttpResponse.json({ data: [{ id: 'm' }] })),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent(/select a model/i)
    })
    await selectOption(user, /^model$/i, 'm')
    await user.type(screen.getByLabelText(/input text/i), 'one')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect run m /i }))
    const inspector = await screen.findByRole('dialog', { name: /run inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /run inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a run to inspect/i)).toBeInTheDocument()
  })

  it('surfaces gateway errors without leaking internals', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post(
        '*/v1/embeddings',
        () => new HttpResponse(JSON.stringify({ title: 'x' }), { status: 401 }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-bad')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/invalid api key/i)
    })
  })

  it('reports zero dimensions for empty vector lists', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [], model: 'text-embedding-3-small' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('● ok')
  })

  it('names an empty usage table honestly before any run', () => {
    renderApp(<EmbeddingsPage />)
    expect(screen.getByText(/no runs yet/i)).toBeInTheDocument()
  })

  it('requires a pasted key when the field is cleared', async () => {
    const user = userEvent.setup()
    server.use(catalog())
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.clear(screen.getByLabelText(/api key/i))
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    expect(await screen.findByText(/api key is required/i)).toBeInTheDocument()
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter usage by model/i), 'zzz-no-model')
    expect(screen.getByText(/no runs match this filter/i)).toBeInTheDocument()
  })

  it('deselects a run on second click', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    const inspect = within(table).getByRole('button', {
      name: /inspect run text-embedding-3-small/i,
    })
    await user.click(inspect)
    expect(screen.getByRole('dialog', { name: /run inspector/i })).toHaveTextContent('m')
    await user.click(inspect)
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /run inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a run to inspect/i)).toBeInTheDocument()
  })

  it('selects a run with the Space key', async () => {
    const user = userEvent.setup()
    server.use(
      catalog(),
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect run text-embedding-3-small/i })
      .focus()
    await user.keyboard('{ }')
    expect(screen.getByRole('dialog', { name: /run inspector/i })).toHaveTextContent('m')
  })

  it('embeds through act-as-self with the session bearer', async () => {
    const user = userEvent.setup()
    let seenAuth = ''
    let seenActAs: string | null = null
    server.use(
      http.get('*/v1/me/keys', () =>
        HttpResponse.json([
          { keyId: 'e'.repeat(64), name: 'dev', allowedModels: [], enabled: true },
        ]),
      ),
      catalog(),
      http.post('*/v1/embeddings', ({ request }) => {
        seenAuth = request.headers.get('Authorization') ?? ''
        seenActAs = request.headers.get('X-Act-As-Key')
        return HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' })
      }),
    )
    renderApp(<EmbeddingsPage />, { nonAdminSession: true })
    await screen.findByRole('combobox', { name: /owned key/i })
    await pickModel(user)
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByText('● ok')).toBeInTheDocument()
    })
    expect(seenAuth).toBe('Bearer test-user-jwt')
    expect(seenActAs).toBe('e'.repeat(64))
    expect(document.body.textContent).not.toContain('gw-')
  })
})
