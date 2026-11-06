// Fit's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/fit.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type {
  DailyAggregatesResult,
  DataSourcesResult,
  FitSex,
  FitUnits,
  FitWriteResult,
  GoalsResult,
  ImportResult,
  ProfileResult,
  WorkoutCursor,
  WorkoutDetailResult,
  WorkoutsPageResult,
} from '@tenshu/sdk/fit'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/fit.ts` and `mapRegion.ts`).
// ⚠ Not here, on purpose: `mapRegion.regionsIn` / `mapRegion.catalog` (a route map asks only "which
// region covers this point" and reads its bytes). Add them only with a screen that calls them.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  DailyAggregates: 'fit.dailyAggregates',
  DailyAggregatesObserve: 'fit.dailyAggregates.observe',
  WorkoutsPage: 'fit.workoutsPage',
  WorkoutsChanges: 'fit.workouts.changes',
  Workout: 'fit.workout',
  DeleteWorkout: 'fit.deleteWorkout',
  ImportActivityFile: 'fit.importActivityFile',
  Goals: 'fit.goals',
  GoalsObserve: 'fit.goals.observe',
  SetGoals: 'fit.setGoals',
  Profile: 'fit.profile',
  ProfileObserve: 'fit.profile.observe',
  SetUnits: 'fit.setUnits',
  SetName: 'fit.setName',
  SetBirthDate: 'fit.setBirthDate',
  SetSex: 'fit.setSex',
  RecordHeight: 'fit.recordHeight',
  RecordWeight: 'fit.recordWeight',
  DataSources: 'fit.dataSources',
  DataSourcesObserve: 'fit.dataSources.observe',
  // The route map (`@tenshu/map-kit`'s `MapRegionMethod`, spelled out so this table is the whole list).
  MapRegionAt: 'mapRegion.regionAt',
  MapRegionStreamRange: 'mapRegion.streamRange',
} as const

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)

// Days group in this zone. '' = the browser's own (the SDK resolves it with `Intl`), the zone the
// page renders its clocks in, so a row's day and its time always agree (fit-app.md §Web scope 6f).
const ZONE = ''

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return bridge.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await bridge.call<void>(ProxyMethod.RuntimeUseHere)
}

// ── reads ───────────────────────────────────────────────────────────────────
// ⚠ Every read resolves `{ ok: true, … }` or a `FitFailure` — a failure is never an empty list or
// a default number. A rejected promise is the bridge itself failing, not the service.

// The dashboard window, `from`..`to` inclusive ('YYYY-MM-DD').
export function dailyAggregates(from: string, to: string): Promise<DailyAggregatesResult> {
  return bridge.call<DailyAggregatesResult>(ProxyMethod.DailyAggregates, { from, to, zone: ZONE })
}

// The same window, pushed on every change; `onError` is terminal.
export function subscribeDailyAggregates(
  from: string,
  to: string,
  onValue: (v: DailyAggregatesResult) => void,
  onError: (e: Error) => void,
): () => void {
  return bridge.subscribe<DailyAggregatesResult>(ProxyMethod.DailyAggregatesObserve, { from, to, zone: ZONE }, onValue, onError)
}

// One keyset page of History, newest first. `cursor` is the previous page's `nextCursor` (opaque),
// `null` for the head.
export function workoutsPage(cursor: WorkoutCursor | null, pageSize: number): Promise<WorkoutsPageResult> {
  return bridge.call<WorkoutsPageResult>(ProxyMethod.WorkoutsPage, { cursor, pageSize, zone: ZONE })
}

// A version that moves on every workout save or delete: "refetch the head".
export function subscribeWorkoutChanges(onValue: (version: number) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<number>(ProxyMethod.WorkoutsChanges, {}, onValue, onError)
}

export function workout(workoutId: string): Promise<WorkoutDetailResult> {
  return bridge.call<WorkoutDetailResult>(ProxyMethod.Workout, { workoutId, zone: ZONE })
}

export function goals(): Promise<GoalsResult> {
  return bridge.call<GoalsResult>(ProxyMethod.Goals)
}

export function subscribeGoals(onValue: (v: GoalsResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<GoalsResult>(ProxyMethod.GoalsObserve, {}, onValue, onError)
}

export function profile(): Promise<ProfileResult> {
  return bridge.call<ProfileResult>(ProxyMethod.Profile)
}

export function subscribeProfile(onValue: (v: ProfileResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<ProfileResult>(ProxyMethod.ProfileObserve, {}, onValue, onError)
}

export function dataSources(): Promise<DataSourcesResult> {
  return bridge.call<DataSourcesResult>(ProxyMethod.DataSources)
}

export function subscribeDataSources(onValue: (v: DataSourcesResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<DataSourcesResult>(ProxyMethod.DataSourcesObserve, {}, onValue, onError)
}

// ── writes (pure data — fit-app.md §Web scope) ───────────────────────────────

export function deleteWorkout(workoutId: string): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.DeleteWorkout, { workoutId })
}

// GPX / TCX bytes from a file input. ⚠ An `Int8Array`: the gateway refuses anything else, untyped.
export function importActivityFile(name: string, bytes: Int8Array): Promise<ImportResult> {
  return bridge.call<ImportResult>(ProxyMethod.ImportActivityFile, { name, bytes, zone: ZONE })
}

export function setGoals(activeMinutes: number, steps: number): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.SetGoals, { activeMinutes, steps })
}

export function setUnits(units: FitUnits): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.SetUnits, { units })
}

export function setName(name: string): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.SetName, { name })
}

export function setBirthDate(date: string): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.SetBirthDate, { date })
}

export function setSex(sex: FitSex): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.SetSex, { sex })
}

// ⚠ Always metric on the wire: the profile's units are presentation only.
export function recordHeight(centimetres: number): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.RecordHeight, { centimetres })
}

export function recordWeight(kilograms: number): Promise<FitWriteResult> {
  return bridge.call<FitWriteResult>(ProxyMethod.RecordWeight, { kilograms })
}

// ── the route map ────────────────────────────────────────────────────────────

// The two calls `<TenshuMap>` makes (`bridgeRegionAccess` over this, in `RouteMap.tsx`).
// ⚠ Not `bridgeRegionAccess` here: this module is in every chunk, and `@tenshu/map-kit` must be
// reached only by the lazily loaded route map (`bundleSplit.test.ts`).
export function mapRegionCall<T>(method: string, params?: unknown): Promise<T> {
  if (method !== ProxyMethod.MapRegionAt && method !== ProxyMethod.MapRegionStreamRange) {
    return Promise.reject(new Error(`not permitted: ${method} is not in Fit's map calls`))
  }
  return bridge.call<T>(method, params)
}

export const APEX = bridge.apexOrigin
