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
    expect(screen.getByText(/gateway:down/i)).toBeInTheDocument()
  })

  it('surfaces probe failures as alerts', async () => {
    server.use(http.get('*/actuator/health', () => new HttpResponse('x', { status: 500 })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/health probe failed/i)
    })
  })

  it('shows metrics scrape state and gateway chip for admins', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/gateway:up/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/probe:15s/i)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText(/scrape ok/i)).toBeInTheDocument()
    })
  })

  it('keeps operator metrics behind the admin session', async () => {
    let scraped = false
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () => {
        scraped = true
        return new HttpResponse('')
      }),
    )
    renderApp(<ObservabilityPage />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    expect(screen.queryByLabelText(/gateway pulse/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/request latency/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/metrics scrape/i)).not.toBeInTheDocument()
    // The metrics endpoint row is operator tooling too; docs rows stay.
    expect(screen.queryByRole('button', { name: /metrics.*prometheus/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /api reference/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /api docs/i })).toBeInTheDocument()
    await new Promise((resolve) => {
      setTimeout(resolve, 0)
    })
    expect(scraped).toBe(false)
  })

  it('reads the signal rail from the live probes', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () =>
        HttpResponse.json({ status: 'UP' }, { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/gateway:up/i)).toBeInTheDocument()
    })
    const rail = await screen.findByRole('region', { name: /signal rail/i })
    expect(rail).toHaveTextContent(/gateway up/i)
    expect(rail).toHaveTextContent(/metrics live/i)
  })

  it('hides the metrics signal from non-admin sessions', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    const rail = await screen.findByRole('region', { name: /signal rail/i })
    expect(rail).toHaveTextContent(/gateway up/i)
    expect(rail).not.toHaveTextContent(/metrics/i)
  })

  it('inspects an endpoint without leaving the list', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { adminSession: true })
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

  it('reports metrics failures honestly for admins', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('retries one probe without disturbing the other', async () => {
    const user = userEvent.setup()
    let healthCalls = 0
    server.use(
      http.get('*/actuator/health', () => {
        healthCalls += 1
        if (healthCalls === 1) return new HttpResponse('x', { status: 503 })
        return HttpResponse.json({ status: 'UP' })
      }),
    )
    renderApp(<ObservabilityPage />)
    const retry = await screen.findByRole('button', { name: /^retry probe$/i })
    await user.click(retry)
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    expect(healthCalls).toBeGreaterThan(1)
  })

  it('explains cache headers without a backend call', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    await user.click(screen.getByText(/reading cache headers on a stream/i))
    expect(screen.getByText(/L0-Memory/i)).toBeInTheDocument()
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
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/scrape ok/i)).toBeInTheDocument()
    })
  })

  it('deselects an endpoint on second click', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { adminSession: true })
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
