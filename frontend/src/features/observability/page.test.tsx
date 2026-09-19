import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { ObservabilityPage } from './page.js'

const { mockInit } = vi.hoisted(() => {
  const mockChart = {
    setOption: vi.fn(),
    setTheme: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  }
  return { mockChart, mockInit: vi.fn(() => mockChart) }
})

vi.mock('../../shared/echarts/setup.js', () => ({
  echarts: { init: mockInit },
}))

beforeEach(() => {
  vi.clearAllMocks()
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

  it('shows metrics scrape state and gateway chip', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway:up/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/probe:15s/i)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText(/scrape ok/i)).toBeInTheDocument()
    })
  })

  it('inspects an endpoint without leaving the list', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    await user.click(await screen.findByRole('button', { name: /metrics.*prometheus/i }))
    const inspector = screen.getByRole('complementary', { name: /endpoint inspector/i })
    expect(inspector).toHaveTextContent(/do not render secrets/i)
    expect(inspector.querySelector('a')).toHaveAttribute('target', '_blank')
  })

  it('retries all probes on demand', async () => {
    const user = userEvent.setup()
    let healthCalls = 0
    server.use(
      http.get('*/actuator/health', () => {
        healthCalls += 1
        return HttpResponse.json({ status: 'UP' })
      }),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    expect(healthCalls).toBe(1)
    await user.click(screen.getByRole('button', { name: /retry/i }))
    await waitFor(() => {
      expect(healthCalls).toBeGreaterThan(1)
    })
  })

  it('reports metrics failures honestly', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/metrics probe failed/i)).toBeInTheDocument()
    })
  })

  it('reads scrape bodies that fail mid-stream as empty', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.error(new Error('truncated'))
          },
        })
        return new HttpResponse(stream)
      }),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/scrape ok/i)).toBeInTheDocument()
    })
  })

  it('deselects an endpoint on second click', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    const trigger = await screen.findByRole('button', { name: /metrics.*prometheus/i })
    await user.click(trigger)
    expect(screen.getByRole('complementary', { name: /endpoint inspector/i })).toHaveTextContent(
      /do not render secrets/i,
    )
    await user.click(trigger)
    expect(screen.getByRole('complementary', { name: /endpoint inspector/i })).toHaveTextContent(
      /select an endpoint/i,
    )
  })
})
