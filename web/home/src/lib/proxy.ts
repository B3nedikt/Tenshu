// Home's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read goes through the apex, which holds the keys and runs the
// runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire names and the
// typed calls.
//
// ⚠⚠ READ-ONLY, decided (web-app-suite.md §8 items 2 and 9; the spec is the iOS route-1 Home,
// home-and-mia-apps.md §8.1). Every method below is a read or the push form of one. There is no
// device command, pairing, automation toggle / edit / run-now or hub command here, and the runtime
// maps none (`HomePlugin.kt`), so none can be added by a call alone.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/home.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type {
  ActivityCursor,
  HomeActivityPageResult,
  HomeAutomationsResult,
  HomeDevicesResult,
  HomeHubStorageResult,
} from '@tenshu/sdk/home'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/home.ts`, `HOME_METHODS`).
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  Devices: 'home.devices',
  DevicesObserve: 'home.devices.observe',
  HubStorage: 'home.hubStorage',
  HubStorageObserve: 'home.hubStorage.observe',
  Automations: 'home.automations',
  AutomationsObserve: 'home.automations.observe',
  ActivityPage: 'home.activityPage',
  ActivityChanges: 'home.activity.changes',
} as const

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return bridge.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await bridge.call<void>(ProxyMethod.RuntimeUseHere)
}

// ── reads ───────────────────────────────────────────────────────────────────
// ⚠ Every read resolves `{ ok: true, … }` or a `HomeFailure` — a failure is never an empty list. A
// rejected promise is the bridge itself failing, not the service. A stream's `onError` is terminal and
// carries the SDK's typed error flattened to `"kind: message"` (`errors.ts` reads the kind back).

export function devices(): Promise<HomeDevicesResult> {
  return bridge.call<HomeDevicesResult>(ProxyMethod.Devices)
}

export function subscribeDevices(onValue: (v: HomeDevicesResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<HomeDevicesResult>(ProxyMethod.DevicesObserve, {}, onValue, onError)
}

// `hub: null` = no hub reports disk figures (no tile — an answer); `ok: false` = the read failed.
export function hubStorage(): Promise<HomeHubStorageResult> {
  return bridge.call<HomeHubStorageResult>(ProxyMethod.HubStorage)
}

export function subscribeHubStorage(onValue: (v: HomeHubStorageResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<HomeHubStorageResult>(ProxyMethod.HubStorageObserve, {}, onValue, onError)
}

export function automations(): Promise<HomeAutomationsResult> {
  return bridge.call<HomeAutomationsResult>(ProxyMethod.Automations)
}

export function subscribeAutomations(onValue: (v: HomeAutomationsResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<HomeAutomationsResult>(ProxyMethod.AutomationsObserve, {}, onValue, onError)
}

// One keyset page of the activity feed, newest first. `cursor` is the previous page's `nextCursor`
// (opaque), `null` for the head.
export function activityPage(cursor: ActivityCursor | null, pageSize: number): Promise<HomeActivityPageResult> {
  return bridge.call<HomeActivityPageResult>(ProxyMethod.ActivityPage, { cursor, pageSize })
}

// A version on subscribe and on every routine / newest-run change: "refetch the head".
export function subscribeActivityChanges(onValue: (version: number) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<number>(ProxyMethod.ActivityChanges, {}, onValue, onError)
}

export const APEX = bridge.apexOrigin
