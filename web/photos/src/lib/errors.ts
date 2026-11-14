// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty grid: a failed READ is a failure on the web, because there is
// no reachability banner to explain a blank library (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { PhotosFailure } from '@tenshu/sdk/photos'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = PhotosFailure['error']

// Every `error_…` key the catalogue can name: the eight `PhotosErrorKind` keys run A mints (spelled
// out — `@tenshu/sdk/photos` is a value import this bundle may not make), plus the generic ones the
// service's typed errors derive. Anything else falls back to `error_generic`, never the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  'error_photos_bad_cursor',
  'error_photos_bad_page_size',
  'error_photos_bad_kind',
  'error_photos_unknown_album',
  'error_photos_not_found',
  'error_photos_no_content_id',
  'error_photos_not_in_trash',
  'error_photos_not_a_photo',
  'error_photos_file_op_failed',
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}

// What a write reports back to the control that ran it: `null` on success, else what to show.
export type Outcome = { failure: Failure } | { error: unknown } | null

export async function attempt(run: () => Promise<{ ok: true } | { ok: false; error: Failure }>): Promise<Outcome> {
  try {
    const result = await run()
    return result.ok ? null : { failure: result.error }
  } catch (error) {
    return { error }
  }
}

export function outcomeMessage(t: TFunction, outcome: NonNullable<Outcome>): string {
  return 'failure' in outcome ? failureMessage(t, outcome.failure) : bridgeErrorMessage(t, outcome.error)
}
