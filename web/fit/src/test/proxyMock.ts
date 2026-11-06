// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams`.
import { vi } from 'vitest'
import type {
  DailyAggregatesResult,
  DataSourcesResult,
  FitDataSource,
  FitDay,
  FitFailure,
  FitProfile,
  FitWorkout,
  FitWorkoutDetail,
  FitWriteResult,
  GoalsResult,
  ImportResult,
  ProfileResult,
  WorkoutDetailResult,
  WorkoutsPageResult,
} from '@tenshu/sdk/fit'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean; params?: unknown }

function stream<T>() {
  const subs: Sub<T>[] = []
  return {
    subs,
    subscribe: (onValue: (v: T) => void, onError: (e: Error) => void) => {
      const sub: Sub<T> = { onValue, onError, closed: false }
      subs.push(sub)
      return () => {
        sub.closed = true
      }
    },
    push(value: T) {
      for (const s of subs) if (!s.closed) s.onValue(value)
    },
    fail(error: Error) {
      for (const s of subs) if (!s.closed) s.onError(error)
    },
    open: () => subs.filter((s) => !s.closed).length,
    reset: () => subs.splice(0),
  }
}

export const streams = {
  days: stream<DailyAggregatesResult>(),
  changes: stream<number>(),
  goals: stream<GoalsResult>(),
  profile: stream<ProfileResult>(),
  dataSources: stream<DataSourcesResult>(),
}

const written: FitWriteResult = { ok: true }

export const GOALS = { activeMinutes: 30, steps: 8_000 }

export const PROFILE: FitProfile = { name: null, heightCm: null, weightKg: null, birthDate: null, sex: null, units: 'metric' }

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  dailyAggregates: vi.fn(async (_from: string, _to: string): Promise<DailyAggregatesResult> => ({ ok: true, days: [] })),
  subscribeDailyAggregates: vi.fn((_from: string, _to: string, onValue: (v: DailyAggregatesResult) => void, onError: (e: Error) => void) =>
    streams.days.subscribe(onValue, onError),
  ),
  workoutsPage: vi.fn(async (_cursor: string | null, _size: number): Promise<WorkoutsPageResult> => ({ ok: true, workouts: [], nextCursor: null })),
  subscribeWorkoutChanges: vi.fn(streams.changes.subscribe),
  workout: vi.fn(async (_id: string): Promise<WorkoutDetailResult> => ({ ok: true, workout: null })),
  goals: vi.fn(async (): Promise<GoalsResult> => ({ ok: true, goals: GOALS })),
  subscribeGoals: vi.fn(streams.goals.subscribe),
  profile: vi.fn(async (): Promise<ProfileResult> => ({ ok: true, profile: PROFILE })),
  subscribeProfile: vi.fn(streams.profile.subscribe),
  dataSources: vi.fn(async (): Promise<DataSourcesResult> => ({ ok: true, sources: [], batchImport: null })),
  subscribeDataSources: vi.fn(streams.dataSources.subscribe),
  deleteWorkout: vi.fn(async (_id: string): Promise<FitWriteResult> => written),
  importActivityFile: vi.fn(async (_name: string, _bytes: Int8Array): Promise<ImportResult> => ({ ok: true, workouts: [], streamsPending: [] })),
  setGoals: vi.fn(async (_m: number, _s: number): Promise<FitWriteResult> => written),
  setUnits: vi.fn(async (_u: string): Promise<FitWriteResult> => written),
  setName: vi.fn(async (_n: string): Promise<FitWriteResult> => written),
  setBirthDate: vi.fn(async (_d: string): Promise<FitWriteResult> => written),
  setSex: vi.fn(async (_s: string): Promise<FitWriteResult> => written),
  recordHeight: vi.fn(async (_cm: number): Promise<FitWriteResult> => written),
  recordWeight: vi.fn(async (_kg: number): Promise<FitWriteResult> => written),
  mapRegionCall: vi.fn(async () => ({ ok: false, error: { kind: 'error_map_transport_unconfigured' } }) as never),
}

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: since Vitest 3 `restoreMocks` leaves `vi.fn(impl)` alone, so
  // a `mockResolvedValue` would leak into the next test. `mockReset` puts each back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  for (const s of Object.values(streams)) s.reset()
}

export function failure(kind: string): FitFailure {
  return { ok: false, error: { kind, message: `diagnostic for ${kind}` } }
}

// ── fixtures ─────────────────────────────────────────────────────────────────

export function dayRow(date: string, overrides: Partial<FitDay> = {}): FitDay {
  return { date, steps: 0, distanceM: 0, activeEnergyKj: 0, activeMinutes: 0, ...overrides }
}

export function workoutRow(workoutId: string, startMs: number, overrides: Partial<FitWorkout> = {}): FitWorkout {
  return {
    workoutId,
    activityType: 'Running',
    source: 'File',
    title: '',
    startMs,
    endMs: startMs + 1_800_000,
    startLocal: new Date(startMs).toISOString().slice(0, 19),
    durationSec: 1_800,
    distanceM: 5_000,
    activeEnergyKj: 1_200,
    avgHr: 140,
    maxHr: null,
    steps: null,
    paceSecPerKm: 360,
    ...overrides,
  }
}

export function detailOf(workout: FitWorkout, overrides: Partial<FitWorkoutDetail> = {}): FitWorkoutDetail {
  return { workout, heartRate: [], route: [], ...overrides }
}

export function source(overrides: Partial<FitDataSource> = {}): FitDataSource {
  return {
    kind: 'HealthConnect',
    deviceId: 'dev-1',
    deviceLabel: 'Pixel 8',
    isThisDevice: false,
    state: 'Imported',
    atMs: Date.UTC(2026, 9, 27, 8, 0),
    itemCount: 1_234,
    autoImport: true,
    needsAttention: false,
    ...overrides,
  }
}
