// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view: a failed READ is a failure on the web, because there is
// no reachability banner to explain a blank list (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { MapsFailure } from '@tenshu/sdk/maps'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = MapsFailure['error']

// `MapsErrorKind.sourceSilent` — a relation read never answered within the SDK's bound. ⚠ Spelled
// out rather than imported: `MapsErrorKind` is a VALUE in `@tenshu/sdk/maps`, and a value import
// drags the Kotlin runtime into this bundle. It reads as "still loading / couldn't load", with a
// retry — the data is not gone, it is late.
export const SOURCE_SILENT = 'error_timeline_source_silent'

// The map's kinds (`RegionErrorKind` in `@tenshu/map-kit`, `MapRegionErrors` in Kotlin). ⚠ Spelled
// as data too, so this table can be read without the map-kit: `errors.test.ts` pins them equal.
export const MAP_ERROR_KINDS = [
  'error_map_transport_unconfigured',
  'error_map_transport_failed',
  'error_map_catalog_unverified',
  'error_map_unknown_region',
] as const

// Every `error_…` key the catalogue can name: the generic service keys, the Timeline keys the place
// reads and the rename mint (`MapsErrorKind` and the delegated `Timeline.renamePlace`), and the
// map's. Anything else falls back to `error_generic`, never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  'error_timeline_place_not_found',
  'error_timeline_blank_name',
  SOURCE_SILENT,
  ...MAP_ERROR_KINDS,
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
