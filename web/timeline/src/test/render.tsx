import type { ReactNode } from 'react'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from '../i18n'
import { LiveProvider } from '../lib/live'
import { UnitsContext, type MeasurementSystem } from '../lib/units'

// Renders under a fresh QueryClient (no retries, like `mountApp`) and the app's LiveProvider, in
// English unless a test switches language, in the units given (metric by default).
export function renderWithProviders(ui: ReactNode, options: { units?: MeasurementSystem } = {}) {
  void i18n.changeLanguage('en')
  return renderWithQueryClient(
    <UnitsContext.Provider value={options.units ?? 'metric'}>
      <LiveProvider>{ui}</LiveProvider>
    </UnitsContext.Provider>,
  )
}
