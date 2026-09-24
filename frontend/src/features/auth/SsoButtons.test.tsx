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

  it('greys out with the hover message when SSO is not configured', () => {
    vi.stubEnv('VITE_SSO_PROVIDERS', '')
    try {
      renderApp(<SsoButtons />)
      const button = screen.getByRole('button', { name: /continue with sso/i })
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('title', 'SSO not enabled')
      expect(screen.getByText('SSO not enabled')).toBeInTheDocument()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    } finally {
      vi.unstubAllEnvs()
    }
  })
})
