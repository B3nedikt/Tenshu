// Mia entry point (web-app-suite Wave 4, Mia run A): text chat over the deterministic planner, the
// conversation history, and the grants view. Backed by `Mia` on the Kotlin side (`:tenshu-sdk-js`):
// native's own `AskMiaImplementation` — grammar, lowering, the MQ-D10 validator and the reads — run
// in the apex's in-process runtime.
//
// ⚠⚠ No model (web-app-suite §8 item 8): the planner is the grammar, as on every native platform.
// ⚠⚠ No action, enforced: every action (device command, routine run, new event) is withheld and
// answers 'noMatch'. This surface writes the conversation, in memory for the runtime's life (signing
// out clears it), and the grant switches native Settings writes (run B, web-app-suite §8,
// 2026-10-28) — the same realm rows, so a switch here turns a source off on every device.
// ⚠ Not here (web-app-suite §2): voice, widget, assistant role, generative layer.

import * as bundle from './_bundle'

export interface MiaFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from the
// service's typed error. Branch on `kind`, never on `message`.
export const MiaErrorKind = {
  emptyUtterance: 'error_mia_empty_utterance',
  utteranceTooLong: 'error_mia_utterance_too_long',
  invalidZone: 'error_mia_invalid_zone',
  badCursor: 'error_mia_bad_cursor',
  badPageSize: 'error_mia_bad_page_size',
  conversationNotFound: 'error_mia_conversation_not_found',
  // A sign-out or lock cleared the session while this question was being asked; nothing was kept.
  sessionCleared: 'error_mia_session_cleared',
  unexpectedAction: 'error_mia_unexpected_action',
  // A grant switch named a source that does not exist. Nothing was written.
  unknownSource: 'error_mia_unknown_source',
  // A write switch on a source with no write tier (everything but CALENDAR). Nothing was written.
  noWriteGrant: 'error_mia_no_write_grant',
} as const

// The longest utterance `ask` accepts (after trimming); the gateway refuses longer before the runtime.
export const MAX_UTTERANCE_CHARS = 500
// Turns per `turnsPage` at most: a page is one postMessage, never the whole conversation.
export const MAX_TURN_PAGE_SIZE = 100

// The wire names, shared by the apex gateway (`web/apex/src/lib/gateway/mia.ts`) and the Mia app's
// `ProxyMethod` table. ⚠⚠ Only the Mia origin's allowlist may name one (F5; threat model A39).
export const MiaMethod = {
  Ask: 'mia.ask',
  History: 'mia.history',
  HistoryObserve: 'mia.history.observe',
  TurnsPage: 'mia.turnsPage',
  Grants: 'mia.grants',
  GrantsObserve: 'mia.grants.observe',
  SetGranted: 'mia.grants.setGranted',
  SetWritable: 'mia.grants.setWritable',
} as const

// Native's verdicts (`MiaAnswer`), exactly. ⚠⚠ 'noMatch' covers both "I did not understand" and "I
// understood, and will not" (a revoked grant, an action this device withholds); `explanation` says
// which. A refusal is never an 'answered' with no hits: that renders as "nothing matched" and
// asserts an absence nobody checked. 'unavailable' is "I could not look".
export type MiaVerdict = 'answered' | 'noMatch' | 'unavailable'

// `MiaSource`, in its order.
export const MIA_SOURCES = [
  'FILES',
  'TIMELINE',
  'FITNESS',
  'PLACES',
  'DEVICES',
  'ROUTINES',
  'LABELS',
  'VISUAL_SEARCH',
  'CALENDAR',
] as const
export type MiaSource = (typeof MIA_SOURCES)[number]

export interface MiaTarget {
  // The owning schema's type id, e.g. 'tenshu/place/place', 'tenshu/calendar/eventOccurrence'.
  readonly type: string
  // One key for most rows; `[eventId, recurrenceId]` for a calendar occurrence.
  readonly keys: string[]
}

export interface MiaHit {
  readonly target: MiaTarget
  readonly title: string
  readonly subtitle: string
  // ⚠ null = unknown, never 1970.
  readonly atMs: number | null
}

export interface MiaAnswer {
  readonly verdict: MiaVerdict
  // Always render it: what Mia searched, or why it did not.
  readonly explanation: string
  // Empty unless 'answered' — and an empty 'answered' means nothing matched.
  readonly hits: MiaHit[]
}

export interface MiaTurn {
  readonly turnId: string
  readonly askedAtMs: number
  readonly utterance: string
  readonly answer: MiaAnswer
}

export interface MiaConversation {
  readonly conversationId: string
  readonly startedAtMs: number
  // The first question, as asked.
  readonly title: string
  readonly turnCount: number
  readonly updatedAtMs: number
}

export interface MiaGrant {
  readonly source: MiaSource
  readonly read: boolean
  // null where nothing can write the source (everything but CALENDAR today).
  readonly write: boolean | null
}

// An opaque keyset cursor: pass back exactly what `nextCursor` returned. `null` is the head.
export type MiaTurnCursor = string

// A turn that could not be asked is still `ok: true`, with verdict 'unavailable'; `ok: false` means
// the call was refused before anything was asked, and nothing was recorded.
export type AskResult = { readonly ok: true; readonly conversationId: string; readonly turn: MiaTurn } | MiaFailure
// At most one conversation (one per session), newest first.
export type HistoryResult =
  | { readonly ok: true; readonly conversations: MiaConversation[]; readonly replying: boolean }
  | MiaFailure
export type TurnsPageResult =
  | { readonly ok: true; readonly turns: MiaTurn[]; readonly nextCursor: MiaTurnCursor | null }
  | MiaFailure
export type GrantsResult = { readonly ok: true; readonly grants: MiaGrant[] } | MiaFailure
// A grant switch: on success, the whole set read back AFTER the write — draw that, never the
// requested value. On failure nothing changed. ⚠ `grants: null` = the write landed but the read-back
// failed: re-read the grants (the observe push follows); never show it as a failure.
export type SetGrantResult = { readonly ok: true; readonly grants: MiaGrant[] | null } | MiaFailure

// Operation surface: every method is Promise-returning and safe as a TanStack Query `queryFn`.
// `observe*` push on change and return the unsubscribe; their `onError` is terminal.
export const Mia = bundle.Mia.getInstance()
