// `Mia` — the Mia web surface (web-app-suite Wave 4, Mia run A).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).
// Rows mirror the `@JsExport` classes in `MiaBook.kt`; calls mirror `Mia.kt`.
//
// ⚠ The planner is native's `AskMiaImplementation`, run in the tab, and it is the deterministic
// grammar only: no model. ⚠ Every action is withheld. The grant switches are native Settings' writes
// (run B, web-app-suite §8 2026-10-28) and the only realm write here.
// ⚠ Not here (web-app-suite §2): voice, widget, assistant role, generative layer.

interface NativeMiaTarget {
  // The owning schema's type id, e.g. 'tenshu/place/place', 'tenshu/calendar/eventOccurrence'.
  readonly type: string
  // Its key parts: one for most rows, `[eventId, recurrenceId]` for a calendar occurrence.
  readonly keys: string[]
}

interface NativeMiaHit {
  readonly target: NativeMiaTarget
  readonly title: string
  // May be ''.
  readonly subtitle: string
  // ⚠ null = the row carries no time (unknown), never 1970.
  readonly atMs: number | null
}

interface NativeMiaAnswer {
  // 'answered' | 'noMatch' | 'unavailable' — native's verdicts. ⚠⚠ A refusal is 'noMatch', never an
  // 'answered' with no hits.
  readonly verdict: string
  readonly explanation: string
  readonly hits: NativeMiaHit[]
}

interface NativeMiaTurn {
  readonly turnId: string
  readonly askedAtMs: number
  readonly utterance: string
  readonly answer: NativeMiaAnswer
}

interface NativeMiaConversation {
  readonly conversationId: string
  readonly startedAtMs: number
  readonly title: string
  readonly turnCount: number
  readonly updatedAtMs: number
}

interface NativeMiaGrant {
  // A `MiaSource` name: 'FILES' | 'TIMELINE' | 'FITNESS' | 'PLACES' | 'DEVICES' | 'ROUTINES' | …
  readonly source: string
  readonly read: boolean
  // null where nothing can write the source.
  readonly write: boolean | null
}

type NativeMiaOk<T> = ({ readonly ok: true } & T) | NativeFailure

interface NativeMiaHistory {
  readonly conversations: NativeMiaConversation[]
  readonly replying: boolean
}

interface MiaInstance {
  // `zone` '' = the browser's. Refused (nothing recorded): blank, over 500 characters, unknown zone.
  // A turn that could not be asked is recorded with verdict 'unavailable' and answers ok.
  // A sign-out / lock after the call voids it: `error_mia_session_cleared`, nothing recorded.
  ask(utterance: string, zone: string): Promise<NativeMiaOk<{ readonly conversationId: string; readonly turn: NativeMiaTurn }>>

  history(): Promise<NativeMiaOk<NativeMiaHistory>>
  // ⚠ `onError` is TERMINAL: it fires once when the stream can no longer deliver.
  observeHistory(callback: (result: NativeMiaOk<NativeMiaHistory>) => void, onError: (message: string) => void): () => void
  // Newest first. `cursor` null/'' = head; pass back `nextCursor` (opaque); null after the oldest.
  // `pageSize` 1..100.
  turnsPage(
    conversationId: string,
    cursor: string | null,
    pageSize: number,
  ): Promise<NativeMiaOk<{ readonly turns: NativeMiaTurn[]; readonly nextCursor: string | null }>>

  // What the planner may read, as the proxy reads it per question.
  grants(): Promise<NativeMiaOk<{ readonly grants: NativeMiaGrant[] }>>
  // Native Settings' switches (`MiaGrantsRepository.setGranted` / `setWritable`): the realm row,
  // then the whole set read back. Refused, nothing written: an unknown source
  // (`error_mia_unknown_source`), a write switch on a source with no write tier
  // (`error_mia_no_write_grant`).
  // `grants: null` = written, but the read-back failed: re-read (the push follows). `ok: false` = nothing written.
  setGranted(source: string, granted: boolean): Promise<NativeMiaOk<{ readonly grants: NativeMiaGrant[] | null }>>
  setWritable(source: string, granted: boolean): Promise<NativeMiaOk<{ readonly grants: NativeMiaGrant[] | null }>>
  observeGrants(
    callback: (result: NativeMiaOk<{ readonly grants: NativeMiaGrant[] }>) => void,
    onError: (message: string) => void,
  ): () => void
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Mia: { getInstance(): MiaInstance }
}
