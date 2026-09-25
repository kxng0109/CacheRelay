import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
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

// Provider inventory fires on every ModelsPage render; the envelope default
// keeps alias-focused tests isolated (scenario tests override with data).
// Shape mirrors the backend `{ providers }` envelope, never a bare array.
function providersOk() {
  return http.get('*/v1/admin/providers', () =>
    HttpResponse.json({
      providers: [
        {
          name: 'anthropic',
          type: 'ANTHROPIC',
          baseUrl: null,
          keyConfigured: true,
          connectTimeoutSeconds: 5,
          requestTimeoutSeconds: 60,
          embeddingSingleAsString: false,
          circuitState: 'CLOSED',
          aliasReferences: 0,
          validationStatus: 'AUTH_REACHABLE',
        },
        {
          name: 'openai',
          type: 'OPENAI',
          baseUrl: null,
          keyConfigured: true,
          connectTimeoutSeconds: 5,
          requestTimeoutSeconds: 60,
          embeddingSingleAsString: false,
          circuitState: 'CLOSED',
          aliasReferences: 0,
          validationStatus: 'LIVE_VERIFIED',
        },
      ],
    }),
  )
}

beforeEach(() => {
  server.use(providersOk())
})

describe('ModelsPage', () => {
  it('lists aliases with source pills and file rows read only', async () => {
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('file-gpt')
    expect(table).toHaveTextContent('db-fast')
    expect(
      screen.getByText(/2 aliases · 1 file bound \(read only\) · 1 database-managed/i),
    ).toBeInTheDocument()
    const fileRow = within(table).getByText('file-gpt').closest('tr')
    expect(fileRow).not.toBeNull()
    if (fileRow !== null) {
      expect(fileRow).toHaveTextContent('read only')
      expect(within(fileRow).getByTitle(/file bound aliases are read only/i)).toBeInTheDocument()
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
    await selectOption(user, /provider 1/i, 'openai · CLOSED')
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

  it('closes the create dialog on Escape', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await screen.findByRole('dialog', { name: /new alias/i })
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /new alias/i })).not.toBeInTheDocument()
    })
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
    await selectOption(user, /provider 1/i, 'openai · CLOSED')
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

  it('marks file bound inspectors read only with no replace action', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('file-gpt'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    expect(inspector).toHaveTextContent(/file bound aliases are read only/i)
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
        () => new HttpResponse(JSON.stringify({ message: 'plan rejected' }), { status: 409 }),
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
    await user.click(editorScope.getByRole('combobox', { name: /^strategy$/i }))
    await user.click(screen.getByRole('option', { name: 'SEQUENTIAL' }))
    await user.click(editorScope.getByRole('button', { name: /^replace plan$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/db-fast replaced/i)
    })
    expect(body).toMatchObject({ strategy: 'SEQUENTIAL' })
  })

  it('requires a provider choice when creating', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'no-provider')
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/name and at least one/i)
  })

  it('keeps stored providers selected in the replace editor', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    await screen.findByRole('heading', { name: /replace plan:/i })
    expect(screen.getByRole('combobox', { name: /provider 1/i })).toHaveTextContent(/openai/)
    expect(screen.getByRole('combobox', { name: /provider 2/i })).toHaveTextContent(/anthropic/)
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
    await user.click(screen.getByRole('combobox', { name: /^strategy$/i }))
    await user.click(screen.getByRole('option', { name: 'RACE' }))
    await selectOption(user, /provider 1/i, 'openai · CLOSED')
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/strat-one created/i)
    })
    expect(body).toMatchObject({ name: 'strat-one', strategy: 'RACE' })
  })

  it('reports alias-list failures as alerts', async () => {
    server.use(http.get('*/v1/admin/models', () => new HttpResponse('x', { status: 500 })))
    renderApp(<ModelsPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('selects and deselects rows on click', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('db-fast'))
    expect(await screen.findByRole('complementary')).toHaveTextContent('db-fast')
    await user.click(within(table).getByText('db-fast'))
    await waitFor(() => {
      expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    })
  })

  it('selects rows from the keyboard', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table).getByText('db-fast').closest('tr')?.focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('complementary')).toHaveTextContent('db-fast')
    within(table).getByText('db-fast').closest('tr')?.focus()
    await user.keyboard('{ }')
    await waitFor(() => {
      expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    })
  })

  it('ignores non-action keys on rows', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table).getByText('db-fast').closest('tr')?.focus()
    await user.keyboard('a')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  })

  it('seeds the replace editor with a blank step for empty chains', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/models', () =>
        HttpResponse.json({
          models: [{ name: 'empty-db', chain: [], strategy: 'SEQUENTIAL', source: 'database' }],
        }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    expect(within(table).getByText('empty-db').closest('tr')).toHaveTextContent('none')
    await user.click(within(table).getByText('empty-db'))
    await user.click(await screen.findByRole('button', { name: /^replace plan$/i }))
    await waitFor(() => {
      expect(screen.getByText(/replace plan:/i)).toHaveTextContent('empty-db')
    })
    expect(screen.getByRole('combobox', { name: /provider 1/i })).toHaveTextContent(
      /select provider/i,
    )
  })

  it('omits the actions column when no database alias is visible', async () => {
    server.use(
      http.get('*/v1/admin/models', () =>
        HttpResponse.json({
          models: [{ name: 'file-only', chain: [], strategy: 'SEQUENTIAL', source: 'file' }],
        }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    expect(within(table).queryByText(/actions/i)).not.toBeInTheDocument()
    expect(within(table).getByText('file-only')).toBeInTheDocument()
  })

  it('clears overrides back to null instead of sending blanks', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      listOk(),
      http.post('*/v1/admin/models', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          { name: 'null-override', chain: [], strategy: 'SEQUENTIAL', source: 'database' },
          { status: 201 },
        )
      }),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.type(screen.getByLabelText(/name \(lowercase slug\)/i), 'null-override')
    await selectOption(user, /provider 1/i, 'openai · CLOSED')
    await user.type(screen.getByLabelText(/model override/i), 'claude-x')
    await user.clear(screen.getByLabelText(/model override/i))
    await user.click(screen.getByRole('button', { name: /create alias/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/null-override created/i)
    })
    expect(body).toMatchObject({ chain: [{ providerName: 'openai', modelOverride: null }] })
  })

  it('lists live providers with circuit state and picks without typing', async () => {
    const user = userEvent.setup()
    server.use(listOk())
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await user.click(screen.getByRole('combobox', { name: /provider 1/i }))
    expect(screen.getByRole('option', { name: 'openai · CLOSED' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'anthropic · CLOSED' }))
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /provider 1/i })).toHaveTextContent('anthropic')
    })
    expect(document.body.textContent).not.toContain('Select provideranthropic')
  })

  it('blocks submit with Retry when the provider inventory fails', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.get('*/v1/admin/providers', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    const dialog = await screen.findByRole('dialog', { name: /new alias/i })
    const scope = within(dialog)
    expect(scope.getByRole('alert')).toHaveTextContent(/providers unavailable/i)
    expect(scope.getByRole('combobox', { name: /provider 1/i })).toBeDisabled()
    expect(scope.getByRole('button', { name: /create alias/i })).toBeDisabled()
    expect(scope.queryByRole('textbox', { name: /provider 1/i })).not.toBeInTheDocument()
    await user.click(scope.getByRole('button', { name: /^retry$/i }))
    expect(scope.getByRole('combobox', { name: /provider 1/i })).toBeDisabled()
  })

  it('blocks submit when no providers are configured', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.get('*/v1/admin/providers', () => HttpResponse.json({ providers: [] })),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new alias/i }))
    await waitFor(() => {
      expect(screen.getByText(/no providers configured/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('combobox', { name: /provider 1/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /create alias/i })).toBeDisabled()
  })

  it('keeps a retired stored provider visible as removed', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/models', () =>
        HttpResponse.json({
          models: [
            {
              name: 'legacy',
              chain: [{ providerName: 'retired', modelOverride: null }],
              strategy: 'SEQUENTIAL',
              source: 'database',
            },
          ],
        }),
      ),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('legacy'))
    const inspector = screen.getByRole('complementary', { name: /alias inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /^replace plan$/i }))
    await screen.findByRole('heading', { name: /replace plan:/i })
    expect(screen.getByRole('combobox', { name: /provider 1/i })).toHaveTextContent(
      /retired \(removed\)/i,
    )
  })
})
