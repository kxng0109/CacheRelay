import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ModelSelect } from './ModelSelect.js'

function Harness({ token, initial }: { token: string; initial?: string }) {
  const { register } = useForm<{ model: string }>({ defaultValues: { model: initial ?? '' } })
  const [value, setValue] = useState(initial ?? '')
  return (
    <>
      <label htmlFor="model-probe">Model</label>
      <ModelSelect
        token={token}
        id="model-probe"
        registration={register('model', {
          onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
            setValue(e.target.value)
          },
        })}
        value={value}
        invalid={false}
      />
    </>
  )
}

describe('ModelSelect', () => {
  it('stays disabled with a guide until a key exists', () => {
    renderApp(<Harness token="" />)
    const select = screen.getByLabelText(/model/i)
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
      expect(screen.getByRole('option', { name: 'beta' })).toBeInTheDocument()
    })
    await user.selectOptions(screen.getByLabelText(/model/i), 'beta')
    expect(screen.getByLabelText(/model/i)).toHaveValue('beta')
  })

  it('keeps a saved choice visible when the catalog no longer lists it', async () => {
    server.use(http.get('*/v1/models', () => HttpResponse.json({ data: [{ id: 'alpha' }] })))
    renderApp(<Harness token="gw-test" initial="retired-model" />)
    await waitFor(() => {
      expect(screen.getByRole('option', { name: /retired-model \(saved\)/i })).toBeInTheDocument()
    })
  })

  it('offers reload instead of free text when the catalog fails', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/models', () => new HttpResponse('x', { status: 500 })))
    renderApp(<Harness token="gw-test" />)
    const reload = await screen.findByRole('button', { name: /reload/i })
    expect(screen.getByLabelText(/model/i)).toBeDisabled()
    await user.click(reload)
    expect(screen.getByLabelText(/model/i)).toBeDisabled()
  })
})
