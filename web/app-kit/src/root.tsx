// The app's React root: StrictMode → a root error boundary → the query client → the app.
import { StrictMode, type ComponentType, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { createAppQueryClient } from './query'

export interface MountAppOptions {
  // The app's own boundary (it carries the app's crash copy) — the root catch-all.
  readonly boundary: ComponentType<{ children: ReactNode }>
  readonly queryClient?: QueryClient
  readonly element?: HTMLElement
}

export function mountApp(App: ComponentType, options: MountAppOptions): void {
  const { boundary: Boundary } = options
  const queryClient = options.queryClient ?? createAppQueryClient()
  createRoot(options.element ?? document.getElementById('root')!).render(
    <StrictMode>
      <Boundary>
        <QueryClientProvider client={queryClient}>
          <App />
        </QueryClientProvider>
      </Boundary>
    </StrictMode>,
  )
}
