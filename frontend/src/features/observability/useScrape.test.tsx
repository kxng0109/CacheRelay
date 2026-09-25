import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { screen, waitFor } from '@testing-library/react'
import { act, useEffect } from 'react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { usePrometheusScrape } from './useScrape.js'

const SCRAPE = `# HELP notes
# TYPE notes gauge
http_server_requests_seconds_count{status="200"} 10
http_server_requests_seconds_sum{status="200"} 1.5
process_uptime_seconds 100
jvm_memory_used_bytes{area="heap"} 1000
jvm_memory_max_bytes{area="heap"} 2000
tomcat_threads_current_threads 5
`

/**
 * Captures the test query client for invalidation.
 *
 * @returns Nothing; the client escapes through the holder property
 * (module bindings are never reassigned, per the lint rule).
 */
const capture: { client: QueryClient | null } = { client: null }

function Capture(): React.JSX.Element {
  const client = useQueryClient()
  useEffect(() => {
    capture.client = client
  }, [client])
  return <></>
}

/**
 * Minimal scrape consumer behind a length readout.
 *
 * @param props - Test id for the readout.
 * @returns The scrape length, or nothing while pending.
 */
function Consumer({ testId }: { testId: string }): React.JSX.Element {
  const scrape = usePrometheusScrape(15_000, true, (s) => s.text.length)
  if (scrape.error instanceof Error)
    return <p data-testid={testId}>failed: {scrape.error.message}</p>
  return <p data-testid={testId}>{scrape.data ?? 'pending'}</p>
}

describe('usePrometheusScrape', () => {
  it('serves every consumer from one request per interval', async () => {
    let calls = 0
    server.use(
      http.get('*/actuator/prometheus', () => {
        calls += 1
        return HttpResponse.text(SCRAPE)
      }),
    )
    useAuthStore.getState().clear()
    renderApp(
      <>
        <Capture />
        <Consumer testId="a" />
        <Consumer testId="b" />
      </>,
    )
    // Two observers mount together: React Query dedupes to one fetch.
    await waitFor(() => {
      expect(screen.getByTestId('a')).not.toHaveTextContent('pending')
      expect(screen.getByTestId('b')).not.toHaveTextContent('pending')
    })
    expect(calls).toBe(1)
    // One invalidation re-scrapes once for every consumer, never per view.
    await act(async () => {
      await capture.client?.invalidateQueries({ queryKey: ['prometheus-scrape'] })
    })
    await waitFor(() => {
      expect(calls).toBe(2)
    })
  })

  it('reports scrape failures with the shared message', async () => {
    server.use(http.get('*/actuator/prometheus', () => new HttpResponse('x', { status: 503 })))
    renderApp(<Consumer testId="solo" />)
    await waitFor(() => {
      expect(screen.getByTestId('solo')).toHaveTextContent(/HTTP 503/)
    })
  })

  it('reads null bodies as empty instead of crashing', async () => {
    server.use(http.get('*/actuator/prometheus', () => new HttpResponse(null, { status: 200 })))
    renderApp(<Consumer testId="nullbody" />)
    await waitFor(() => {
      expect(screen.getByTestId('nullbody')).toHaveTextContent('0')
    })
  })

  it('refuses bodies past the byte budget instead of buffering them', async () => {
    server.use(
      http.get('*/actuator/prometheus', () => {
        // 100 MB with no delimiter: the client must stop at the budget,
        // never buffer the whole body.
        const flood = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('x'.repeat(100_000_000)))
            ctrl.close()
          },
        })
        return new HttpResponse(flood, { headers: { 'content-type': 'text/plain' } })
      }),
    )
    renderApp(<Consumer testId="flood" />)
    await waitFor(() => {
      expect(screen.getByTestId('flood')).toHaveTextContent(/8 MB budget/)
    })
  })
})
