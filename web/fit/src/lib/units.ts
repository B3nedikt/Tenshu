// Metric vs imperial for every value on screen — the web twin of native `LocalMeasurementSystem`:
// one ambient value, read by the formatters, never threaded through the tree.
//
// The value is the synced `OwnerPreferences.MeasurementSystem`, read through `fit.profile` (App.tsx
// provides it). Until that read answers, METRIC — native's first-run default, never a guess from the
// browser's locale (fit-app.md §Web scope 6a).
import { createContext, useContext } from 'react'
import type { FitUnits } from '@tenshu/sdk/fit'

export type MeasurementSystem = FitUnits

export const DEFAULT_UNITS: MeasurementSystem = 'metric'

export const UnitsContext = createContext<MeasurementSystem>(DEFAULT_UNITS)

export function useUnits(): MeasurementSystem {
  return useContext(UnitsContext)
}
