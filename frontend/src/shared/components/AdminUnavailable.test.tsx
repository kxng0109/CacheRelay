import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { AdminUnavailable } from './AdminUnavailable.js'

describe('AdminUnavailable', () => {
  it('renders one ambiguous copy for stealth 404', () => {
    renderApp(<AdminUnavailable path="/v1/admin/keys" status={404} />)
    expect(screen.getByRole('alert')).toHaveTextContent(/admin unavailable/i)
    expect(screen.getByText(/no access or no route/i)).toBeInTheDocument()
    expect(screen.getByText(/\/v1\/admin\/keys/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to overview/i })).toHaveAttribute('href', '/')
  })

  it('never distinguishes auth failure from a missing route', () => {
    renderApp(<AdminUnavailable path="/v1/admin/teams?org=acme" status={404} />)
    expect(screen.queryByText(/unauthorized|forbidden|login/i)).not.toBeInTheDocument()
  })
})
