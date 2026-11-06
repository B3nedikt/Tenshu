// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view: a failed READ is a failure on the web (web-app-suite.md
// §4.1), and a refused question is an answer that says why, never "nothing found"
// (mia-query-layer.md §14.1).
import type { TFunction } from 'i18next'
import type { MiaFailure } from '@tenshu/sdk/mia'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = MiaFailure['error']

// Every key `MiaErrorKind` (`@tenshu/sdk/mia`) mints. ⚠ Spelled out rather than imported:
// `MiaErrorKind` is a VALUE there, and a value import drags the Kotlin runtime into this bundle.
// `sdkMirror.test.ts` pins this list equal to the SDK's, and each has copy in en/de/fr.
export const MIA_ERROR_KINDS = [
  'error_mia_empty_utterance',
  'error_mia_utterance_too_long',
  'error_mia_invalid_zone',
  'error_mia_bad_cursor',
  'error_mia_bad_page_size',
  'error_mia_conversation_not_found',
  'error_mia_session_cleared',
  'error_mia_unexpected_action',
  'error_mia_unknown_source',
  'error_mia_no_write_grant',
] as const

// Every `error_…` key the catalogue can name: the generic service keys (a failed grant write
// carries the preference service's derived key) and Mia's own. Anything else falls back to
// `error_generic`, never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  ...MIA_ERROR_KINDS,
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
