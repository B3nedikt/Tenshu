import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '../i18n'
import i18n from '../i18n'
import { LiveProvider } from '../lib/live'

// Renders under a fresh QueryClient (no retries, like main.tsx) and the app's LiveProvider, in
// English unless a test switches language.
export function renderWithProviders(ui: ReactNode) {
  void i18n.changeLanguage('en')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const result = render(
    <QueryClientProvider client={client}>
      <LiveProvider>{ui}</LiveProvider>
    </QueryClientProvider>,
  )
  return { ...result, client }
}
