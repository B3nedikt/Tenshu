// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty view: a failed READ is a failure on the web, because there is no
// reachability banner to explain a blank list (web-app-suite.md §4.1, §4.H).
import type { TFunction } from 'i18next'
import type { HomeError } from '@tenshu/sdk/home'
import { bridgeErrorKind, knownErrorKind } from '@tenshu/app-kit/errors'

export type Failure = HomeError

// Every key `HomeErrorKind` mints. ⚠ Spelled out rather than imported: `HomeErrorKind` is a VALUE in
// `@tenshu/sdk/home`, and a value import drags the Kotlin runtime into this bundle. `errors.test.ts`
// pins this list against the SDK's source.
export const HOME_ERROR_KINDS = [
  'error_home_bad_cursor',
  'error_home_bad_page_size',
  'error_home_service_unavailable',
  'error_home_stream_ended',
] as const

// Anything else falls back to `error_generic`, never to the raw key.
export const KNOWN_ERROR_KINDS = [
  'error_generic',
  'error_unexpected',
  'error_internal',
  'error_not_found',
  'error_invalid_caller',
  'error_unannounced_session',
  'error_unsupported_operation',
  ...HOME_ERROR_KINDS,
] as const

export function failureMessage(t: TFunction, error: Failure): string {
  return t(`errors.${knownErrorKind(error.kind, KNOWN_ERROR_KINDS)}`)
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) — not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export function bridgeErrorMessage(t: TFunction, error: unknown): string {
  return t(`errors.${bridgeErrorKind(error)}`)
}

// A terminal stream error crosses the bridge as a message string: the apex gateway flattens the SDK's
// typed `{ kind, message }` to `"kind: message"` (or the bare kind), `gateway/home.ts`. Read the kind
// back so the notice can say WHICH failure it was; a message with no `error_…` lead is the bridge
// itself (a refusal, the frame going away) and is told apart by `bridgeErrorKind`.
export function streamErrorKind(error: Error): string | null {
  const match = /^(error_[a-z0-9_]+)(?::|$)/.exec(error.message)
  return match ? match[1]! : null
}

export function streamErrorMessage(t: TFunction, error: Error): string {
  const kind = streamErrorKind(error)
  return kind ? failureMessage(t, { kind }) : bridgeErrorMessage(t, error)
}
