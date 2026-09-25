import { screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { ModelSelect } from './ModelSelect.js'

function Harness({
  token,
  actAsKey,
  initial,
}: {
  token: string
  actAsKey?: string
  initial?: string
}) {
  const [value, setValue] = useState(initial ?? '')
  const onSelect = vi.fn((v: string) => {
    setValue(v)
  })
  return (
    <ModelSelect
      token={token}
      {...(actAsKey === undefined ? {} : { actAsKey })}
      id="model-probe"
      value={value}
      onSelect={onSelect}
      invalid={false}
    />
  )
}

describe('ModelSelect', () => {
  it('stays disabled with a guide until a key exists', () => {
    renderApp(<Harness token="" />)
    const select = screen.getByRole('combobox', { name: /model/i })
    expect(select).toBeDisabled()
    expect(screen.getByText(/paste a key to list models/i)).toBeInTheDocument()
  })

  it('lists catalog ids and reports the choice', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/models', () => HttpResponse.json({ data: [{ id: 'alpha' }, { id: 'beta' }] })),
    )
    renderApp(<Harness token="gw-test" />)
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent(/select a model/i)
    })
    await selectOption(user, /model/i, 'beta')
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent('beta')
    })
  })

  it('keeps a saved choice visible when the catalog no longer lists it', async () => {
    server.use(http.get('*/v1/models', () => HttpResponse.json({ data: [{ id: 'alpha' }] })))
    renderApp(<Harness token="gw-test" initial="retired-model" />)
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent(/retired-model/)
    })
  })

  it('offers reload instead of free text when the catalog fails', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/models', () => new HttpResponse('x', { status: 500 })))
    renderApp(<Harness token="gw-test" />)
    const reload = await screen.findByRole('button', { name: /reload/i })
    expect(screen.getByRole('combobox', { name: /model/i })).toBeDisabled()
    await user.click(reload)
    expect(screen.getByRole('combobox', { name: /model/i })).toBeDisabled()
  })

  it('names credential rejections with a paste fallback', async () => {
    server.use(http.get('*/v1/models', () => new HttpResponse('x', { status: 401 })))
    renderApp(<Harness token="gw-test" />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/rejected this credential \(401\)/i)
    expect(alert).toHaveTextContent(/paste a key instead/i)
  })

  it('sends the pasted key instead of the session for paste-mode catalogs', async () => {
    const user = userEvent.setup()
    let seenAuth = ''
    server.use(
      http.get('*/v1/models', ({ request }) => {
        seenAuth = request.headers.get('Authorization') ?? ''
        return HttpResponse.json({ data: [{ id: 'alpha' }] })
      }),
    )
    renderApp(<Harness token="gw-pasted" />, { nonAdminSession: true })
    await waitFor(() => {
      expect(seenAuth).toBe('Bearer gw-pasted')
    })
    await selectOption(user, /model/i, 'alpha')
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent('alpha')
    })
  })

  it('refetches with the new credential after a key switch', async () => {
    const user = userEvent.setup()
    const seen: string[] = []
    server.use(
      http.get('*/v1/models', ({ request }) => {
        seen.push(request.headers.get('Authorization') ?? '')
        return HttpResponse.json({ data: [{ id: 'alpha' }] })
      }),
    )
    function Switchable() {
      const [token, setToken] = useState('gw-first')
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setToken('gw-second')
            }}
          >
            switch key
          </button>
          <Harness token={token} />
        </>
      )
    }
    renderApp(<Switchable />)
    await waitFor(() => {
      expect(seen).toEqual(['Bearer gw-first'])
    })
    await user.click(screen.getByRole('button', { name: /switch key/i }))
    await waitFor(() => {
      expect(seen).toEqual(['Bearer gw-first', 'Bearer gw-second'])
    })
  })

  it('lists models through act-as-self with the session bearer', async () => {
    const user = userEvent.setup()
    let seenActAs: string | null = null
    let seenAuth = ''
    server.use(
      http.get('*/v1/models', ({ request }) => {
        seenActAs = request.headers.get('X-Act-As-Key')
        seenAuth = request.headers.get('Authorization') ?? ''
        return HttpResponse.json({ data: [{ id: 'alpha' }] })
      }),
    )
    renderApp(<Harness token="" actAsKey={'k'.repeat(64)} />, { nonAdminSession: true })
    await waitFor(() => {
      expect(seenActAs).toBe('k'.repeat(64))
    })
    expect(seenAuth).toBe('Bearer test-user-jwt')
    await selectOption(user, /model/i, 'alpha')
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /model/i })).toHaveTextContent('alpha')
    })
  })
})
