// `Fit` — the Fit web surface (web-app-suite Wave 3, Fit run A).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).
// Rows mirror the `@JsExport` classes in `FitBook.kt`; calls mirror `Fit.kt`.
//
// ⚠ Every fold behind these rows is native's `:fit-projection` (`FitProjection`,
// `DataSourceProjection`). ⚠ Not here (fit-app.md §Web scope): recording, Takeout, binary `.FIT`
// (a typed refusal), the auto-import switch, edit, onboarding.

interface NativeFitDay {
  // `YYYY-MM-DD` in the reader zone.
  readonly date: string
  readonly steps: number
  readonly distanceM: number
  readonly activeEnergyKj: number
  readonly activeMinutes: number
}

interface NativeFitWorkout {
  readonly workoutId: string
  // 'Running' | 'Walking' | 'Cycling' | 'Strength' | 'Other'
  readonly activityType: string
  // 'Recorded' | 'HealthConnect' | 'AppleHealth' | 'File' | 'Manual' | 'Detected'
  readonly source: string
  readonly title: string
  readonly startMs: number
  readonly endMs: number
  // ISO local date-time in the reader zone, e.g. '2026-07-06T07:14'.
  readonly startLocal: string
  readonly durationSec: number
  // ⚠ null = unknown (the store's 0 sentinel), never zero.
  readonly distanceM: number | null
  readonly activeEnergyKj: number | null
  readonly avgHr: number | null
  // From the HR stream: always null on a History row.
  readonly maxHr: number | null
  readonly steps: number | null
  readonly paceSecPerKm: number | null
}

interface NativeFitHrPoint {
  readonly tSec: number
  readonly bpm: number
}

// WGS84 degrees — the data W-MAP draws.
interface NativeFitRoutePoint {
  readonly tSec: number
  readonly lat: number
  readonly lng: number
  readonly altM: number | null
}

interface NativeFitWorkoutDetail {
  readonly workout: NativeFitWorkout
  readonly heartRate: NativeFitHrPoint[]
  readonly route: NativeFitRoutePoint[]
}

interface NativeFitGoals {
  readonly activeMinutes: number
  readonly steps: number
}

// ⚠ Every field but `units` is null when unset — never a placeholder.
interface NativeFitProfile {
  readonly name: string | null
  readonly heightCm: number | null
  readonly weightKg: number | null
  readonly birthDate: string | null
  // 'female' | 'male' | 'other'
  readonly sex: string | null
  // 'metric' | 'imperial'
  readonly units: string
}

interface NativeFitDataSource {
  // 'TenshuHealth' | 'HealthConnect' | 'AppleHealth'
  readonly kind: string
  readonly deviceId: string | null
  readonly deviceLabel: string | null
  readonly isThisDevice: boolean
  // One of `FIT_DATA_SOURCE_STATES` (`fit.ts`), the Kotlin `FitDataSourceStateName` set.
  readonly state: string
  readonly atMs: number | null
  readonly itemCount: number | null
  readonly autoImport: boolean
  readonly needsAttention: boolean
}

interface NativeFitBatchImport {
  readonly lastRunAtMs: number | null
  // 'Succeeded' | 'NoData' | 'PermissionsMissing' | 'Failed'
  readonly lastStatus: string | null
  readonly runCount: number
}

type NativeFitOk<T> = ({ readonly ok: true } & T) | NativeFailure

interface FitInstance {
  // `[from, to]` inclusive `YYYY-MM-DD`, at most 400 days, oldest first. `zone` '' = the browser's.
  dailyAggregates(from: string, to: string, zone: string): Promise<NativeFitOk<{ readonly days: NativeFitDay[] }>>
  // ⚠ `onError` is TERMINAL: it fires once when the stream can no longer deliver.
  observeDailyAggregates(
    from: string,
    to: string,
    zone: string,
    callback: (result: NativeFitOk<{ readonly days: NativeFitDay[] }>) => void,
    onError: (message: string) => void,
  ): () => void

  // Keyset page, newest first. `cursor` null/'' = head; pass back `nextCursor` (opaque);
  // `nextCursor: null` after the last. `pageSize` 1..200, else `error_fit_bad_page_size`.
  workoutsPage(
    cursor: string | null,
    pageSize: number,
    zone: string,
  ): Promise<NativeFitOk<{ readonly workouts: NativeFitWorkout[]; readonly nextCursor: string | null }>>
  // A version that moves on every workout save or delete: the signal to refetch the head.
  observeWorkoutChanges(callback: (version: number) => void, onError: (message: string) => void): () => void
  // ⚠ `workout: null` = no such workout (an answer); `ok: false` = the read failed.
  workout(workoutId: string, zone: string): Promise<NativeFitOk<{ readonly workout: NativeFitWorkoutDetail | null }>>

  goals(): Promise<NativeFitOk<{ readonly goals: NativeFitGoals }>>
  observeGoals(
    callback: (result: NativeFitOk<{ readonly goals: NativeFitGoals }>) => void,
    onError: (message: string) => void,
  ): () => void
  setGoals(activeMinutes: number, steps: number): Promise<NativeFitOk<object>>

  profile(): Promise<NativeFitOk<{ readonly profile: NativeFitProfile }>>
  observeProfile(
    callback: (result: NativeFitOk<{ readonly profile: NativeFitProfile }>) => void,
    onError: (message: string) => void,
  ): () => void
  setUnits(units: string): Promise<NativeFitOk<object>>
  setName(name: string): Promise<NativeFitOk<object>>
  // '' clears.
  setBirthDate(date: string): Promise<NativeFitOk<object>>
  // '' clears.
  setSex(sex: string): Promise<NativeFitOk<object>>
  recordHeight(centimetres: number): Promise<NativeFitOk<object>>
  recordWeight(kilograms: number): Promise<NativeFitOk<object>>

  dataSources(): Promise<
    NativeFitOk<{ readonly sources: NativeFitDataSource[]; readonly batchImport: NativeFitBatchImport | null }>
  >
  observeDataSources(
    callback: (
      result: NativeFitOk<{ readonly sources: NativeFitDataSource[]; readonly batchImport: NativeFitBatchImport | null }>,
    ) => void,
    onError: (message: string) => void,
  ): () => void

  // Missing id = `error_fit_workout_not_found`.
  deleteWorkout(workoutId: string): Promise<NativeFitOk<object>>
  // GPX / TCX bytes from a file input. Typed refusals: unsupported format, binary FIT, malformed,
  // no workouts, too large. Never an empty success. `streamsPending`: saved, upload still queued.
  importActivityFile(
    name: string,
    bytes: Int8Array,
    zone: string,
  ): Promise<NativeFitOk<{ readonly workouts: NativeFitWorkout[]; readonly streamsPending: string[] }>>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Fit: { getInstance(): FitInstance }
}
