// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view or a default number: a failed READ is a failure on the
// web, because there is no reachability banner to explain a blank dashboard (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { FitFailure } from '@tenshu/sdk/fit'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = FitFailure['error']

// Every `error_…` key the catalogue can name: the generic service keys plus every key
// `FitErrorKind` mints. ⚠ Spelled out rather than imported: `FitErrorKind` is a VALUE in
// `@tenshu/sdk/fit`, and a value import drags the Kotlin runtime into this bundle.
// `errors.test.ts` pins this list against the SDK's source. Anything else falls back to
// `error_generic`, never to the raw key.
export const FIT_ERROR_KINDS = [
  'error_fit_bad_cursor',
  'error_fit_bad_page_size',
  'error_fit_bad_window',
  'error_fit_invalid_zone',
  'error_fit_workout_not_found',
  'error_fit_invalid_goal',
  'error_fit_invalid_profile_value',
  'error_fit_unsupported_format',
  'error_fit_binary_unsupported',
  'error_fit_malformed_file',
  'error_fit_no_workouts',
  'error_fit_streams_unreadable',
  'error_fit_file_too_large',
] as const

export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  ...FIT_ERROR_KINDS,
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
