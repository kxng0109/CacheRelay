import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { PulseStrip } from './PulseStrip.js'

const PULSE = [
  'process_uptime_seconds 7200.0',
  'http_server_requests_seconds_count{status="200",uri="/v1/chat/completions"} 100',
  'http_server_requests_seconds_count{status="500",uri="/v1/chat/completions"} 5',
  'jvm_memory_used_bytes{area="heap"} 536870912',
  'jvm_memory_max_bytes{area="heap"} 2147483648',
  'sse_connection_active 3',
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="0.1"} 50',
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="0.5"} 104',
  'http_server_requests_seconds_bucket{uri="/v1/chat/completions",le="+Inf"} 105',
].join('\n')

describe('PulseStrip', () => {
  it('stays hidden for regular sessions and never fetches', async () => {
    let scraped = false
    server.use(
      http.get('*/actuator/prometheus', () => {
        scraped = true
        return new HttpResponse(PULSE)
      }),
    )
    renderApp(<PulseStrip />)
    await new Promise((resolve) => {
      setTimeout(resolve, 0)
    })
    expect(screen.queryByLabelText(/gateway pulse/i)).not.toBeInTheDocument()
    expect(scraped).toBe(false)
  })

  it('renders aggregate gauges from the scrape for admins', async () => {
    server.use(http.get('*/actuator/prometheus', () => new HttpResponse(PULSE)))
    renderApp(<PulseStrip />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('2h 0m')).toBeInTheDocument()
    })
    expect(screen.getByText('105')).toBeInTheDocument()
    expect(screen.getByText('4.76%')).toBeInTheDocument()
    expect(screen.getByText('500 ms')).toBeInTheDocument()
    expect(screen.getByText('512 MB')).toBeInTheDocument()
    expect(screen.getByText(/of 2\.0 GB/)).toBeInTheDocument()
    const strip = screen.getByLabelText(/gateway pulse/i)
    expect(strip).toHaveTextContent('3')
    expect(strip).toHaveTextContent('●')
  })

  it('shows em dashes while loading, never zeros', () => {
    server.use(http.get('*/actuator/prometheus', () => new HttpResponse(PULSE)))
    renderApp(<PulseStrip />, { adminSession: true })
    const strip = screen.getByLabelText(/gateway pulse/i)
    expect(strip).toHaveTextContent('—')
    expect(strip).not.toHaveTextContent('0')
  })

  it('stays silent when the scrape fails', async () => {
    let called = false
    server.use(
      http.get('*/actuator/prometheus', () => {
        called = true
        return new HttpResponse('x', { status: 500 })
      }),
    )
    renderApp(<PulseStrip />, { adminSession: true })
    await waitFor(() => {
      expect(called).toBe(true)
    })
    await waitFor(() => {
      expect(screen.queryByLabelText(/gateway pulse/i)).not.toBeInTheDocument()
    })
  })
})
