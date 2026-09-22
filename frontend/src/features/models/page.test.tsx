import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ModelsPage } from './page.js'

const ALIASES = {
  models: [
    {
      name: 'file-gpt',
      chain: [{ providerName: 'openai', modelOverride: null }],
      strategy: 'SEQUENTIAL',
      source: 'file',
    },
    {
      name: 'db-fast',
      chain: [
        { providerName: 'openai', modelOverride: null },
        { providerName: 'anthropic', modelOverride: 'claude-x' },
      ],
      strategy: 'RACE',
      source: 'database',
    },
  ],
}

function listOk() {
  return http.get('*/v1/admin/models', () => HttpResponse.json(ALIASES))
}

// ProviderBoard fires on every ModelsPage render; the empty default keeps
// alias-focused tests isolated (scenario tests override with data).
beforeEach(() => {
  server.use(http.get('*/v1/admin/providers', () => HttpResponse.json([])))
})

describe('ModelsPage', () => {
  it('lists aliases with source pills and file rows read-only', async () => {
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('file-gpt')
    expect(table).toHaveTextContent('db-fast')
    expect(
      screen.getByText(/2 aliases · 1 file-bound \(read-only\) · 1 database-managed/i),
    ).toBeInTheDocument()
    const fileRow = within(table).getByText('file-gpt').closest('tr')
    expect(fileRow).not.toBeNull()
    if (fileRow !== null) {
      expect(fileRow).toHaveTextContent('—')
      expect(within(fileRow).getByTitle(/file-bound aliases are read-only/i)).toBeInTheDocument()
    }
  })

  it('creates an alias and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.post('*/v1/admin/models', () =>
        HttpResponse.json(
          { name: 'new-one', chain: [], strategy: 'SEQUENTIAL', source: 'database' },
          { status: 201 },
        ),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'new-one')
    await user.type(screen.getByLabelText(/provider 1/i), 'openai')
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/new-one created/i)
    })
  })

  it('requires a name and a provider step before creating', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/name and at least one/i)
  })

  it('surfaces duplicate names honestly', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.post(
        '*/v1/admin/models',
        () => new HttpResponse(JSON.stringify({ message: 'alias exists' }), { status: 409 }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'db-fast')
    await user.type(screen.getByLabelText(/provider 1/i), 'openai')
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('replaces the plan of a database alias from the inspector', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.put('*/v1/admin/models/db-fast', () =>
        HttpResponse.json({ ...ALIASES.models[1], strategy: 'SEQUENTIAL' }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    const editor = await screen.findByRole('heading', { name: /replace plan:/i })
    const editorCard = editor.closest('div')
    expect(editorCard).not.toBeNull()
    if (editorCard instanceof HTMLElement) {
      await user.click(within(editorCard).getByRole('button', { name: /^replace plan$/i }))
    }
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/db-fast replaced/i)
    })
  })

  it('deletes with inline confirm and keeps file rows actionless', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.delete('*/v1/admin/models/db-fast', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/db-fast deleted/i)
    })
  })

  it('names an empty catalog honestly', async () => {
    server.use(http.get('*/v1/admin/models', () => HttpResponse.json({ models: [] })))
    renderApp(<ModelsPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no aliases yet/i)).toBeInTheDocument()
    })
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter aliases/i), 'zzz-no-alias')
    expect(screen.getByText(/no aliases match this filter/i)).toBeInTheDocument()
  })

  it('adds and removes chain steps within the backend maximum', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.click(screen.getByRole('button', { name: /add step/i }))
    expect(screen.getByLabelText(/provider 2/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /remove step 2/i }))
    expect(screen.queryByLabelText(/provider 2/i)).not.toBeInTheDocument()
  })

  it('caps chain steps at the backend maximum', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    for (let i = 0; i < 7; i += 1) {
      await user.click(screen.getByRole('button', { name: /add step/i }))
    }
    expect(screen.getByLabelText(/provider 8/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /add step/i })).not.toBeInTheDocument()
  })

  it('selects an alias with the Space key', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table).getByText('db-fast').closest('tr')?.focus()
    await user.keyboard('{ }')
    expect(screen.getByRole('complementary', { name: /alias inspector/i })).toHaveTextContent(
      'db-fast',
    )
    await user.click(
      within(screen.getByRole('complementary', { name: /alias inspector/i })).getByRole('button', {
        name: /close inspector/i,
      }),
    )
    await waitFor(() => {
      expect(
        screen.queryByRole('complementary', { name: /alias inspector/i }),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect an alias/i)).toBeInTheDocument()
  })

  it('selects an alias with the Space key', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table).getByText('db-fast').closest('tr')?.focus()
    await user.keyboard('{ }')
    expect(screen.getByRole('complementary', { name: /alias inspector/i })).toHaveTextContent(
      'db-fast',
    )
  })

  it('clears the inspector when its alias is deleted', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.delete('*/v1/admin/models/:name', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    expect(screen.getByRole('complementary', { name: /alias inspector/i })).toHaveTextContent(
      'db-fast',
    )
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(
        screen.queryByRole('complementary', { name: /alias inspector/i }),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect an alias/i)).toBeInTheDocument()
  })

  it('cancels creation without calling the gateway', async () => {
    const user = userEvent.setup()
    let posts = 0
    server.use(
      listOk(),
      http.post('*/v1/admin/models', () => {
        posts += 1
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'abandoned')
    await user.click(screen.getByRole('button', { name: /^cancel$/i }))
    expect(posts).toBe(0)
    expect(screen.queryByLabelText(/name \(lowercase slug\)/i)).not.toBeInTheDocument()
  })

  it('cancels deletion without calling the gateway', async () => {
    const user = userEvent.setup()
    let deletes = 0
    server.use(
      listOk(),
      http.delete('*/v1/admin/models/:name', () => {
        deletes += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^no$/i }))
    expect(deletes).toBe(0)
  })

  it('marks file-bound inspectors read-only with no replace action', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('file-gpt'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    expect(inspector).toHaveTextContent(/file-bound aliases are read-only/i)
    expect(
      within(inspector).queryByRole('button', { name: /^replace plan$/i }),
    ).not.toBeInTheDocument()
  })

  it('cancels replacement without calling the gateway', async () => {
    const user = userEvent.setup()
    let puts = 0
    server.use(
      listOk(),
      http.put('*/v1/admin/models/:name', () => {
        puts += 1
        return HttpResponse.json({})
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    const editor = await screen.findByRole('heading', { name: /replace plan:/i })
    const editorCard = editor.closest('div')
    expect(editorCard).not.toBeNull()
    if (editorCard instanceof HTMLElement) {
      await user.click(within(editorCard).getByRole('button', { name: /^cancel$/i }))
    }
    expect(puts).toBe(0)
    expect(screen.queryByRole('heading', { name: /replace plan:/i })).not.toBeInTheDocument()
  })

  it('reports replacement failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.put(
        '*/v1/admin/models/db-fast',
        () => new HttpResponse(JSON.stringify({ message: 'file-bound' }), { status: 409 }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    const editor = await screen.findByRole('heading', { name: /replace plan:/i })
    const editorCard = editor.closest('div')
    expect(editorCard).not.toBeNull()
    if (editorCard instanceof HTMLElement) {
      await user.click(within(editorCard).getByRole('button', { name: /^replace plan$/i }))
    }
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('edits overrides and strategy in the replace editor', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      listOk(),
      http.put('*/v1/admin/models/:name', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({})
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    const editor = await screen.findByRole('heading', { name: /replace plan:/i })
    const editorCard = editor.closest('div')
    expect(editorCard).not.toBeNull()
    if (!(editorCard instanceof HTMLElement)) return
    const editorScope = within(editorCard)
    const overrides = editorScope.getAllByLabelText(/model override \(optional\)/i)
    const firstOverride = overrides.at(0)
    expect(firstOverride).toBeDefined()
    if (firstOverride instanceof HTMLElement) {
      await user.clear(firstOverride)
      await user.type(firstOverride, 'gpt-x')
    }
    await user.selectOptions(editorScope.getByLabelText(/^strategy$/i), 'SEQUENTIAL')
    await user.click(editorScope.getByRole('button', { name: /^replace plan$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/db-fast replaced/i)
    })
    expect(body).toMatchObject({ strategy: 'SEQUENTIAL' })
  })

  it('requires a provider step when replacing', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    const editor = await screen.findByRole('heading', { name: /replace plan:/i })
    const editorCard = editor.closest('div')
    expect(editorCard).not.toBeNull()
    if (!(editorCard instanceof HTMLElement)) return
    const editorScope = within(editorCard)
    await user.clear(editorScope.getByLabelText(/provider 1/i))
    await user.clear(editorScope.getByLabelText(/provider 2/i))
    await user.click(editorScope.getByRole('button', { name: /^replace plan$/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/at least one provider step/i)
  })

  it('reports deletion failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.delete('*/v1/admin/models/db-fast', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('submits the chosen strategy with the new alias', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      listOk(),
      http.post('*/v1/admin/models', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          { name: 'strat-one', chain: [], strategy: 'RACE', source: 'database' },
          { status: 201 },
        )
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'strat-one')
    await user.selectOptions(screen.getByLabelText(/^strategy$/i), 'RACE')
    await user.type(screen.getByLabelText(/provider 1/i), 'openai')
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/strat-one created/i)
    })
    expect(body).toMatchObject({ name: 'strat-one', strategy: 'RACE' })
  })
})
