// Metric vs imperial for every distance on screen — the web twin of native `LocalMeasurementSystem`
// (`ui-core/.../MeasurementSystemLocal.kt`): one ambient value, read by the formatter, never
// threaded through the tree.
//
// ⚠⚠ The value SHOULD be the realm-wide `OwnerPreferences.Units` (`timeline-app.md` §Web scope 4f),
// but the web SDK has no read for it yet: run A exposed no preference surface for Timeline, and
// this run may not edit `tenshu-sdk-js`. Until a `units` read lands, the app root provides METRIC —
// the same default native gives a shell with no proxy behind it (`TimelineRepository.units()`).
// This is a recorded gap (the PR's "Needs a decision"), not a silent choice: wiring the read is one
// `UnitsContext.Provider value=…` in `App.tsx`; every formatter already takes the system.
import { createContext, useContext } from 'react'

export type MeasurementSystem = 'metric' | 'imperial'

// Mirrors `MeasurementSystem.token` on the Kotlin side.
export const DEFAULT_UNITS: MeasurementSystem = 'metric'

export const UnitsContext = createContext<MeasurementSystem>(DEFAULT_UNITS)

export function useUnits(): MeasurementSystem {
  return useContext(UnitsContext)
}
