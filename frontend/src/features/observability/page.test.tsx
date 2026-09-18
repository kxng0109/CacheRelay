import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ObservabilityPage } from './page.js'

beforeEach(() => {
  server.use(
    http.get(
      '*/actuator/prometheus',
      () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
    ),
  )
})

describe('ObservabilityPage', () => {
  it('reports gateway liveness', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
  })

  it('reports degraded status honestly', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'DOWN' })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway reports down/i)).toBeInTheDocument()
    })
  })

  it('surfaces probe failures as alerts', async () => {
    server.use(http.get('*/actuator/health', () => new HttpResponse('x', { status: 500 })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/health probe failed/i)
    })
  })
})
