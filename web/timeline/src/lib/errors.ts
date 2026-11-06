// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view: a failed READ is a failure on the web, because there is
// no reachability banner to explain a blank timeline (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { TimelineFailure } from '@tenshu/sdk/timeline'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = TimelineFailure['error']

// `TimelineErrorKind.sourceSilent` — a relation read never answered within the SDK's bound
// (run A's `SOURCE_SILENCE_BOUND`). ⚠ Spelled out rather than imported: `TimelineErrorKind` is a
// VALUE in `@tenshu/sdk/timeline`, and a value import drags the Kotlin runtime into this bundle.
// It reads as "still loading / couldn't load", with a retry — the data is not gone, it is late.
export const SOURCE_SILENT = 'error_timeline_source_silent'

// Every `error_…` key the catalogue can name: the generic service keys, every key
// `TimelineErrorKind` mints, and the map's region reads (`MapRegionErrorKind` in
// `@tenshu/sdk/mapRegion`). Anything else falls back to `error_generic`, never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  'error_timeline_invalid_zone',
  'error_timeline_place_not_found',
  'error_timeline_blank_name',
  'error_timeline_invalid_role',
  'error_timeline_invalid_merge',
  'error_timeline_not_merged',
  'error_timeline_suggestion_gone',
  'error_timeline_corrupt_route',
  'error_timeline_already_merged',
  SOURCE_SILENT,
  'error_map_transport_unconfigured',
  'error_map_transport_failed',
  'error_map_catalog_unverified',
  'error_map_unknown_region',
  'error_map_bad_request',
  'error_map_unavailable',
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
