// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view: a failed READ is a failure on the web, where no
// reachability banner explains a blank calendar (web-app-suite.md §4.1).
import type { TFunction } from 'i18next'
import type { CalendarFailure } from '@tenshu/sdk/calendar'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = CalendarFailure['error']

// Every `error_…` key the catalogue can name: run A's minted keys (`CalendarErrorKind`, spelled out
// because that table is a value export) plus the service's generic ones. Anything else falls back
// to `error_generic`, never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  'error_calendar_invalid_argument',
  'error_calendar_invalid_rule',
  'error_calendar_unknown_zone',
  'error_calendar_not_found',
  'error_calendar_none_in_file',
  'error_calendar_subscription_url',
  'error_calendar_no_device',
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}
