import { screen, waitFor } from '@testing-library/react'
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
    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()
    })
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
    await waitFor(() => {
      expect(screen.getAllByText('0')).toHaveLength(2)
    })
  })
})
