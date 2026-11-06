import type { ComponentType, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createAppQueryClient } from '../query'

/**
 * Renders under a fresh QueryClient with the app's defaults (no retries, like `mountApp`), inside
 * the app's own providers when given (e.g. its live provider).
 */
export function renderWithQueryClient(
  ui: ReactNode,
  options: { readonly wrapper?: ComponentType<{ children: ReactNode }> } = {},
) {
  const client = createAppQueryClient()
  const Wrapper = options.wrapper
  const result = render(
    <QueryClientProvider client={client}>{Wrapper ? <Wrapper>{ui}</Wrapper> : ui}</QueryClientProvider>,
  )
  return { ...result, client }
}
