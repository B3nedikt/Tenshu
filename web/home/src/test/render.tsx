import type { ReactNode } from 'react'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from '../i18n'
import { LiveProvider } from '../lib/live'

// Renders under a fresh, REAL QueryClient (no retries, like `mountApp`) and the app's LiveProvider,
// in English unless a test switches language.
export function renderWithProviders(ui: ReactNode) {
  void i18n.changeLanguage('en')
  return renderWithQueryClient(<LiveProvider>{ui}</LiveProvider>)
}
