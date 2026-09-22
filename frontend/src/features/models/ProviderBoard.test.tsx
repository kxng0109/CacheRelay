import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ModelsPage } from './page.js'
import { validationBadge } from './ProviderBoard.js'

function providers() {
  return http.get('*/v1/admin/providers', () =>
    HttpResponse.json([
      {
        name: 'openai',
        type: 'OPENAI',
        baseUrl: 'https://api.openai.com',
        keyConfigured: true,
        connectTimeoutSeconds: 5,
        requestTimeoutSeconds: 60,
        embeddingSingleAsString: false,
        circuitState: 'CLOSED',
        aliasReferences: 2,
        validationStatus: 'AUTH_REACHABLE',
      },
      {
        name: 'mystery',
        type: 'CUSTOM',
        baseUrl: null,
        keyConfigured: false,
        connectTimeoutSeconds: 5,
        requestTimeoutSeconds: 60,
        embeddingSingleAsString: false,
        circuitState: 'CLOSED',
        aliasReferences: 0,
        validationStatus: 'SOMEDAY_MAYBE',
      },
    ]),
  )
}

describe('validationBadge', () => {
  it('maps the four known depths and degrades unknowns to grey', () => {
    expect(validationBadge('CONTRACT_CHECKED').label).toContain('Contract')
    expect(validationBadge('AUTH_REACHABLE').label).toContain('Reachable')
    expect(validationBadge('LIVE_VERIFIED').label).toContain('Live')
    expect(validationBadge('UNVERIFIED').label).toContain('Unverified')
    expect(validationBadge('SOMEDAY_MAYBE').label).toContain('Unknown')
  })
})

describe('ProviderBoard', () => {
  it('lists validation depth without exposing key material', async () => {
    server.use(
      http.get('*/v1/admin/models', () => HttpResponse.json({ models: [] })),
      providers(),
    )
    renderApp(<ModelsPage />, { adminSession: true })
    const section = await screen.findByRole('region', { name: /^providers$/i })
    await waitFor(() => {
      expect(section).toHaveTextContent('Reachable')
    })
    expect(section).toHaveTextContent('set')
    expect(section).toHaveTextContent('Unknown')
    expect(section).not.toHaveTextContent('sk-')
  })
})
