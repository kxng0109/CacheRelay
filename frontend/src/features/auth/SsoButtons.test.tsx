import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { SsoButtons } from './SsoButtons.js'

describe('SsoButtons', () => {
  it('links every configured provider to its authorization entry', () => {
    vi.stubEnv('VITE_SSO_PROVIDERS', 'google,github')
    try {
      renderApp(<SsoButtons />)
      const google = screen.getByRole('link', { name: /continue with google/i })
      expect(google).toHaveAttribute(
        'href',
        expect.stringContaining('/oauth2/authorization/google'),
      )
      expect(screen.getByRole('link', { name: /continue with github/i })).toHaveAttribute(
        'href',
        expect.stringContaining('/oauth2/authorization/github'),
      )
    } finally {
      vi.unstubAllEnvs()
    }
  })

  it('renders one muted line when SSO is not configured', () => {
    vi.stubEnv('VITE_SSO_PROVIDERS', '')
    try {
      renderApp(<SsoButtons />)
      expect(screen.getByText(/single sign-on is not enabled/i)).toBeInTheDocument()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
    } finally {
      vi.unstubAllEnvs()
    }
  })
})
