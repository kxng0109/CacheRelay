import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { useUiStore } from '../../shared/store.js'
import { renderApp } from '../../test/utils.js'
import LatencyChart from './LatencyChart.js'

const { mockChart, mockInit } = vi.hoisted(() => {
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

const SCRAPE = [
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="0.5"} 40',
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="+Inf"} 41',
  'http_server_requests_seconds_count{uri="/v1/chat/completions"} 41',
].join('\n')

const SCRAPE2 = [
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="0.5"} 140',
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="+Inf"} 141',
  'http_server_requests_seconds_count{uri="/v1/chat/completions"} 141',
].join('\n')

interface ChartSeries {
  data: [number, number | null][]
}

interface ChartOption {
  series: ChartSeries[]
  tooltip: { valueFormatter: (value: unknown) => string }
}

function lastOption(): ChartOption {
  const calls = mockChart.setOption.mock.calls
  const last = calls[calls.length - 1]
  if (last?.[0] === undefined) throw new Error('setOption never called')
  return last[0] as ChartOption
}

function mockScrape(status: number, body: string): void {
  server.use(
    http.get(
      '*/actuator/prometheus',
      () => new HttpResponse(body, { status, headers: { 'Content-Type': 'text/plain' } }),
    ),
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  if (useUiStore.getState().dark) useUiStore.getState().toggleDark()
})

afterEach(() => {
  if (useUiStore.getState().dark) useUiStore.getState().toggleDark()
})

describe('LatencyChart', () => {
  it('collects samples before two scrapes exist', async () => {
    mockScrape(200, SCRAPE)
    renderApp(<LatencyChart />)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/collecting latency samples/i)
    })
    expect(mockInit).toHaveBeenCalledTimes(1)
  })

  it('surfaces scrape failures as alerts', async () => {
    mockScrape(500, 'x')
    renderApp(<LatencyChart />)
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/metrics scrape failed/i)
    })
  })

  it('stays in collecting state when the scrape carries no histogram', async () => {
    let calls = 0
    server.use(
      http.get('*/actuator/prometheus', () => {
        calls += 1
        return new HttpResponse('cacherelay_tokens_total{provider="x"} 5\n', {
          headers: { 'Content-Type': 'text/plain' },
        })
      }),
    )
    renderApp(<LatencyChart />)
    await waitFor(() => {
      expect(calls).toBeGreaterThan(0)
    })
    await act(async () => {
      await Promise.resolve()
    })
    expect(mockInit).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent(/collecting latency samples/i)
  })

  it('reports unknown latency when samples never resolve finitely', async () => {
    const unbounded = [
      'http_server_requests_seconds_bucket{uri="/v1/x",le="+Inf"} 141',
      'http_server_requests_seconds_count{uri="/v1/x"} 141',
    ].join('\n')
    let calls = 0
    server.use(
      http.get('*/actuator/prometheus', () => {
        calls += 1
        const body = calls === 1 ? SCRAPE : unbounded
        return new HttpResponse(body, { headers: { 'Content-Type': 'text/plain' } })
      }),
    )
    renderApp(<LatencyChart pollMs={50} />)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/P50 unknown/)
    })
  })

  it('pushes options to the chart and disposes on unmount', async () => {
    mockScrape(200, SCRAPE)
    const { unmount } = renderApp(<LatencyChart />)
    await waitFor(() => {
      expect(mockChart.setOption).toHaveBeenCalled()
    })
    expect(mockChart.setTheme).toHaveBeenCalledWith('default')
    unmount()
    expect(mockChart.dispose).toHaveBeenCalledTimes(1)
  })

  it('follows the app theme without re-initializing', async () => {
    mockScrape(200, SCRAPE)
    useUiStore.getState().toggleDark()
    try {
      renderApp(<LatencyChart />)
      await waitFor(() => {
        expect(mockChart.setTheme).toHaveBeenCalledWith('dark')
      })
      expect(mockInit).toHaveBeenCalledTimes(1)
    } finally {
      useUiStore.getState().toggleDark()
    }
  })

  it('accumulates a second scrape into live points', async () => {
    let calls = 0
    server.use(
      http.get('*/actuator/prometheus', () => {
        calls += 1
        const body = calls === 1 ? SCRAPE : SCRAPE2
        return new HttpResponse(body, { headers: { 'Content-Type': 'text/plain' } })
      }),
    )
    renderApp(<LatencyChart pollMs={50} />)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/P50 500 ms/)
    })
    const option = lastOption()
    expect(option.series[0]?.data.length).toBeGreaterThan(0)
    expect(option.tooltip.valueFormatter(12)).toBe('12 ms')
  })

  it('resizes the chart when the container changes', async () => {
    const observed: (() => void)[] = []
    const ro = vi.fn(
      class {
        constructor(callback: () => void) {
          observed.push(callback)
        }
        observe = vi.fn()
        unobserve = vi.fn()
        disconnect = vi.fn()
      },
    )
    vi.stubGlobal('ResizeObserver', ro)
    try {
      mockScrape(200, SCRAPE)
      renderApp(<LatencyChart />)
      await waitFor(() => {
        expect(mockInit).toHaveBeenCalledTimes(1)
      })
      expect(observed).toHaveLength(1)
      act(() => {
        observed[0]?.()
      })
      expect(mockChart.resize).toHaveBeenCalledTimes(1)
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
