import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { useDriftStore } from '../drift/store.js'
import { DriftNotice } from './DriftNotice.js'

beforeEach(() => {
  useDriftStore.getState().clear()
})

describe('DriftNotice', () => {
  it('stays hidden while no payload was hidden', () => {
    renderApp(<DriftNotice />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('names the endpoint and dismisses', async () => {
    const user = userEvent.setup()
    useDriftStore.getState().note('keys')
    renderApp(<DriftNotice />)
    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent(/hid unfamiliar data from keys/i)
    await user.click(screen.getByRole('button', { name: /dismiss drift notice/i }))
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })
})
