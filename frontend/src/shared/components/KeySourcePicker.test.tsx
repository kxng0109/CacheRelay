import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { KeySourcePicker, type KeySource } from './KeySourcePicker.js'

function Harness() {
  const [source, setSource] = useState<KeySource>('account')
  const [keyId, setKeyId] = useState('')
  const onSource = vi.fn((s: KeySource) => {
    setSource(s)
  })
  const onKey = vi.fn((id: string) => {
    setKeyId(id)
  })
  return (
    <>
      <KeySourcePicker
        source={source}
        onSourceChange={onSource}
        selectedKeyId={keyId}
        onSelectKeyId={onKey}
        idPrefix="ks"
      />
      <p data-testid="probe">{`${source}:${keyId}`}</p>
    </>
  )
}

function keysOk() {
  return http.get('*/v1/me/keys', () =>
    HttpResponse.json([
      { keyId: 'a'.repeat(64), name: 'dev', allowedModels: [], enabled: true },
      { keyId: 'b'.repeat(64), name: 'old', allowedModels: [], enabled: false },
    ]),
  )
}

describe('KeySourcePicker', () => {
  it('renders nothing for guests', () => {
    renderApp(<Harness />)
    expect(screen.queryByText(/key source/i)).not.toBeInTheDocument()
  })

  it('auto-selects the first enabled owned key', async () => {
    server.use(keysOk())
    renderApp(<Harness />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByTestId('probe')).toHaveTextContent('account')
    })
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /owned key/i })).toHaveTextContent('dev')
    })
  })

  it('switches sources on toggle', async () => {
    const user = userEvent.setup()
    server.use(keysOk())
    renderApp(<Harness />, { nonAdminSession: true })
    await screen.findByRole('combobox', { name: /owned key/i })
    await user.click(screen.getByLabelText(/paste a key/i))
    expect(screen.queryByRole('combobox', { name: /owned key/i })).not.toBeInTheDocument()
  })

  it('selects owned keys without displaying secrets', async () => {
    const user = userEvent.setup()
    server.use(keysOk())
    renderApp(<Harness />, { nonAdminSession: true })
    await screen.findByRole('combobox', { name: /owned key/i })
    await selectOption(user, /owned key/i, 'old (disabled)')
    await waitFor(() => {
      expect(screen.getByTestId('probe')).toHaveTextContent('b'.repeat(64))
    })
    expect(document.body.textContent).not.toContain('gw-')
  })

  it('names key-list failures with a paste fallback, never red', async () => {
    server.use(http.get('*/v1/me/keys', () => new HttpResponse('x', { status: 500 })))
    renderApp(<Harness />, { nonAdminSession: true })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/paste a key instead/i)
    expect(alert.innerHTML).not.toContain('text-danger')
  })

  it('names empty inventories with guidance', async () => {
    server.use(http.get('*/v1/me/keys', () => HttpResponse.json([])))
    renderApp(<Harness />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no owned keys yet/i)).toBeInTheDocument()
    })
  })

  it('falls back to the first key when all are disabled', async () => {
    server.use(
      http.get('*/v1/me/keys', () =>
        HttpResponse.json([
          { keyId: 'd'.repeat(64), name: 'old', allowedModels: [], enabled: false },
        ]),
      ),
    )
    renderApp(<Harness />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /owned key/i })).toHaveTextContent('old')
    })
  })

  it('ignores blank selections without crashing', () => {
    server.use(keysOk())
    renderApp(<Harness />, { nonAdminSession: true })
    fireEvent.change(screen.getByLabelText(/paste a key/i), { target: { value: 'x' } })
    expect(screen.getByText(/key source/i)).toBeInTheDocument()
  })
})
