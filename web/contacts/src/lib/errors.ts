// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty list: run A made a failed READ a failure precisely because the
// web has no reachability banner to explain a blank book (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { ContactsFailure } from '@tenshu/sdk/contact'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = ContactsFailure['error']

// Every `error_…` key the catalogue below can name. Anything else falls back to `error_generic`
// (same as the native catalogue's fallback), never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  'error_contacts_none_in_file',
  'error_contacts_nothing_to_export',
  'error_contacts_photo_unavailable',
  'error_contacts_invalid_details',
  'error_contacts_unknown_key',
  'error_invite_response_changed',
  'error_invite_code_not_shown',
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
