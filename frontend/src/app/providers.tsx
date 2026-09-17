import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { queryClient } from './query.js'

interface ProvidersProps {
  children: ReactNode
}

/**
 * Global providers: server-state cache only. Auth stays in the memory-only
 * zustand store and is passed explicitly to the gateway client per call.
 *
 * @param props - Wrapped application tree.
 * @returns The tree wrapped in all global providers.
 */
export function Providers({ children }: ProvidersProps): ReactNode {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
