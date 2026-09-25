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
    expect(screen.getByText(/probes:down/i)).toBeInTheDocument()
  })

  it('surfaces probe failures as alerts', async () => {
    server.use(http.get('*/actuator/health', () => new HttpResponse('x', { status: 500 })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/health probe failed/i)
    })
    expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
  })

  it('keeps answered HTTP failures red', async () => {
    server.use(http.get('*/actuator/health', () => new HttpResponse('x', { status: 503 })))
    renderApp(<ObservabilityPage />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/health probe failed/i)
    expect(alert).toHaveTextContent(/HTTP 503/)
    expect(alert.innerHTML).toContain('text-danger')
  })

  it('mutes unreachable probes instead of alarming', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.error()))
    renderApp(<ObservabilityPage />)
    const alert = await screen.findByRole('alert')
    await waitFor(() => {
      expect(alert).toHaveTextContent(/fetch failed|failed to fetch|network|unreachable/i)
    })
    expect(alert.innerHTML).toContain('text-ink-soft')
    expect(alert.innerHTML).not.toContain('text-danger')
    expect(screen.getByText(/probes:down/i)).toBeInTheDocument()
  })

  it('names a missing health content type instead of guessing', async () => {
    server.use(
      http.get('*/actuator/health', () => {
        const res = HttpResponse.json({ status: 'UP' })
        res.headers.delete('content-type')
        return res
      }),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/without a content type/i)).toBeInTheDocument()
    })
  })

  it('names a missing metrics content type instead of claiming a clean scrape', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get('*/actuator/prometheus', () => {
        const res = new HttpResponse('process_uptime_seconds 10')
        res.headers.delete('content-type')
        return res
      }),
    )
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/without a content type/i)).toBeInTheDocument()
    })
  })

  it('names a non-JSON health answer instead of leaking parser text', async () => {
    server.use(
      http.get(
        '*/actuator/health',
        () => new HttpResponse('<html></html>', { headers: { 'Content-Type': 'text/html' } }),
      ),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/not JSON/i)
    })
  })

  it('accepts Spring Boot vendor JSON suffixes as JSON', async () => {
    server.use(
      http.get('*/actuator/health', () =>
        HttpResponse.json(
          { status: 'UP' },
          { headers: { 'Content-Type': 'application/vnd.spring-boot.actuator.v3+json' } },
        ),
      ),
    )
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('names a non-text metrics answer instead of claiming a clean scrape', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('<html></html>', { headers: { 'Content-Type': 'text/html' } }),
      ),
    )
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/not Prometheus text/i)).toBeInTheDocument()
    })
  })

  it('shows metrics scrape state and probes chip for admins', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/probes:up/i)).toBeInTheDocument()
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
      expect(screen.getByText(/probes:up/i)).toBeInTheDocument()
    })
    const rail = await screen.findByRole('region', { name: /signal rail/i })
    expect(rail).toHaveTextContent(/probes up/i)
    expect(rail).toHaveTextContent(/metrics live/i)
  })

  it('hides the metrics signal from non-admin sessions', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    const rail = await screen.findByRole('region', { name: /signal rail/i })
    expect(rail).toHaveTextContent(/probes up/i)
    expect(rail).not.toHaveTextContent(/metrics/i)
  })

  it('inspects an endpoint without leaving the list', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /metrics.*prometheus/i }))
    const inspector = screen.getByRole('complementary', { name: /endpoint inspector/i })
    expect(inspector).toHaveTextContent(/read by your monitoring scraper/i)
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
    // One failed scrape surfaces in every consumer (probe card and chart),
    // each owning its own message.
    await waitFor(() => {
      const alerts = screen.getAllByRole('alert')
      expect(alerts.length).toBeGreaterThan(0)
      expect(alerts.some((a) => a.textContent.includes('HTTP 500'))).toBe(true)
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

  it('keeps header docs out of the endpoint section', async () => {
    server.use(http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })))
    renderApp(<ObservabilityPage />)
    await waitFor(() => {
      expect(screen.getByText(/gateway is up/i)).toBeInTheDocument()
    })
    expect(screen.queryByText(/reading cache headers on a stream/i)).not.toBeInTheDocument()
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
        return new HttpResponse(stream, { headers: { 'Content-Type': 'text/plain' } })
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
      /read by your monitoring scraper/i,
    )
    await user.click(trigger)
    expect(screen.getByRole('complementary', { name: /endpoint inspector/i })).toHaveTextContent(
      /select an endpoint/i,
    )
  })
})
