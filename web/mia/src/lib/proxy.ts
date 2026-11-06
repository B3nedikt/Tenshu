// Mia's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/mia.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
// ⚠⚠ Threat model A39: through `mia.ask` this origin reads every source the grants allow. Nothing
// else from another app's namespace belongs here.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type {
  AskResult,
  GrantsResult,
  HistoryResult,
  MiaSource,
  MiaTurnCursor,
  SetGrantResult,
  TurnsPageResult,
} from '@tenshu/sdk/mia'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/mia.ts`). ⚠ Spelled out
// rather than imported: `MiaMethod` is a VALUE in `@tenshu/sdk/mia`. `lib/sdkMirror.test.ts` pins them.
// ⚠ No confirm, no delete, no voice (web-app-suite.md §2): the gateway has none to call.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  Ask: 'mia.ask',
  History: 'mia.history',
  HistoryObserve: 'mia.history.observe',
  TurnsPage: 'mia.turnsPage',
  Grants: 'mia.grants',
  GrantsObserve: 'mia.grants.observe',
  SetGranted: 'mia.grants.setGranted',
  SetWritable: 'mia.grants.setWritable',
} as const

// The gateway's bound on an utterance (`MAX_UTTERANCE_CHARS` in `@tenshu/sdk/mia`, measured on the
// trimmed text). ⚠ A copy, for the same reason as the table above; `sdkMirror.test.ts` pins it.
export const MAX_UTTERANCE_CHARS = 500

// The gateway's ceiling on a turns page is 100; a screenful is plenty.
export const TURN_PAGE_SIZE = 20

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return bridge.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await bridge.call<void>(ProxyMethod.RuntimeUseHere)
}

// ── chat ────────────────────────────────────────────────────────────────────
// ⚠ Every call resolves `{ ok: true, … }` or a `MiaFailure`. A rejected promise is the bridge itself
// failing (no answer, a refusal), not the service.

// `zone` '' = this browser's own: "tomorrow" is the reader's tomorrow (mia-query-layer.md §7.4).
export function ask(utterance: string): Promise<AskResult> {
  return bridge.call<AskResult>(ProxyMethod.Ask, { utterance, zone: '' })
}

// ── history ─────────────────────────────────────────────────────────────────

export function history(): Promise<HistoryResult> {
  return bridge.call<HistoryResult>(ProxyMethod.History)
}

// A stable module-level function: `useLive` resubscribes when its `subscribe` changes.
export function subscribeHistory(onValue: (v: HistoryResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<HistoryResult>(ProxyMethod.HistoryObserve, {}, onValue, onError)
}

// Newest first. `cursor` null = the head; pass back `nextCursor` exactly.
export function turnsPage(conversationId: string, cursor: MiaTurnCursor | null): Promise<TurnsPageResult> {
  return bridge.call<TurnsPageResult>(ProxyMethod.TurnsPage, { conversationId, cursor, pageSize: TURN_PAGE_SIZE })
}

// ── grants ──────────────────────────────────────────────────────────────────

export function grants(): Promise<GrantsResult> {
  return bridge.call<GrantsResult>(ProxyMethod.Grants)
}

export function subscribeGrants(onValue: (v: GrantsResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<GrantsResult>(ProxyMethod.GrantsObserve, {}, onValue, onError)
}

// Native Settings' switches. On success the WHOLE set, read back after the write — draw that.
export function setGranted(source: MiaSource, granted: boolean): Promise<SetGrantResult> {
  return bridge.call<SetGrantResult>(ProxyMethod.SetGranted, { source, granted })
}

export function setWritable(source: MiaSource, granted: boolean): Promise<SetGrantResult> {
  return bridge.call<SetGrantResult>(ProxyMethod.SetWritable, { source, granted })
}

export const APEX = bridge.apexOrigin
