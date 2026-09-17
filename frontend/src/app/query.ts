import { QueryClient } from '@tanstack/react-query'

/**
 * Shared React Query client.
 *
 * @remarks
 * `gcTime` (v5 rename of `cacheTime`) keeps inactive admin snapshots for
 * five minutes; retries stay at one so 401/403/429 surfaces reach the UI
 * instead of being retried behind the user's back.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      gcTime: 5 * 60 * 1000,
      staleTime: 5 * 1000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})
