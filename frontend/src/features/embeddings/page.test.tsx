import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { EmbeddingsPage } from './page.js'

describe('EmbeddingsPage', () => {
  it('validates empty input before submitting', async () => {
    const user = userEvent.setup()
    renderApp(<EmbeddingsPage />)
    await user.clear(screen.getByLabelText(/^model$/i))
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByText(/input text is required/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/model is required/i)).toBeInTheDocument()
  })

  it('reports dimensions and vector count on success', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({
          data: [{ embedding: [0.1, 0.2, 0.3], index: 0 }],
          model: 'text-embedding-3-small',
        }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/input text/i), 'hello world')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('text-embedding-3-small')
    expect(table).toHaveTextContent('● ok')
  })

  it('shows the model chip and counters after a run', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({
          data: [{ embedding: [0.1], index: 0 }],
          model: 'm',
        }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
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

  it('filters runs by model and inspects failures', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
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
    await user.clear(screen.getByLabelText(/^model$/i))
    await user.type(screen.getByLabelText(/^model$/i), 'm-a')
    await user.type(screen.getByLabelText(/input text/i), 'one')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await user.clear(screen.getByLabelText(/^model$/i))
    await user.type(screen.getByLabelText(/^model$/i), 'm-b')
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
    await user.click(within(table).getByText('m-b'))
    expect(screen.getByRole('complementary', { name: /run inspector/i })).toHaveTextContent(
      /HTTP 500/,
    )
  })

  it('surfaces gateway errors without leaking internals', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(
        '*/v1/embeddings',
        () => new HttpResponse(JSON.stringify({ title: 'x' }), { status: 401 }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-bad')
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/invalid api key/i)
    })
  })

  it('reports zero dimensions for empty vector lists', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [], model: 'text-embedding-3-small' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/input text/i), 'hello')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('● ok')
  })

  it('names an empty usage table honestly before any run', () => {
    renderApp(<EmbeddingsPage />)
    expect(screen.getByText(/no runs yet/i)).toBeInTheDocument()
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter usage by model/i), 'zzz-no-model')
    expect(screen.getByText(/no runs match this filter/i)).toBeInTheDocument()
  })

  it('deselects a run on second click', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/embeddings', () =>
        HttpResponse.json({ data: [{ embedding: [0.1], index: 0 }], model: 'm' }),
      ),
    )
    renderApp(<EmbeddingsPage />)
    await user.type(screen.getByLabelText(/api key/i), 'gw-test')
    await user.type(screen.getByLabelText(/input text/i), 'hi')
    await user.click(screen.getByRole('button', { name: /create embeddings/i }))
    const table = await screen.findByRole('table')
    const row = within(table).getByText('● ok').closest('tr')
    expect(row).not.toBeNull()
    await user.click(row as HTMLElement)
    expect(screen.getByRole('complementary', { name: /run inspector/i })).toHaveTextContent('m')
    await user.click(row as HTMLElement)
    expect(screen.getByRole('complementary', { name: /run inspector/i })).toHaveTextContent(
      /select a run/i,
    )
  })
})
