// Fit entry point (web-app-suite Wave 3, Fit run A): the dashboard's daily aggregates, History with
// keyset paging, workout detail with its HR and route streams, goals, profile, the data-source rows
// (read-only), and the pure-data writes: goals, profile, delete, and a GPX / TCX import. Backed by
// `Fit` on the Kotlin side (`:tenshu-sdk-js`): the same `:health-service` ops native Fit runs, folded
// by the same `:fit-projection` module, dispatched through the apex in-process runtime.
//
// ⚠ Not here (fit-app.md §Web scope): recording, Takeout, binary `.FIT`, the auto-import switch,
// workout edit (native has none), onboarding. ⚠ Nothing here starts background work.

import * as bundle from './_bundle'

export interface FitFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from the
// service's typed error. Branch on `kind`, never on `message`.
export const FitErrorKind = {
  badCursor: 'error_fit_bad_cursor',
  badPageSize: 'error_fit_bad_page_size',
  badWindow: 'error_fit_bad_window',
  invalidZone: 'error_fit_invalid_zone',
  workoutNotFound: 'error_fit_workout_not_found',
  invalidGoal: 'error_fit_invalid_goal',
  invalidProfileValue: 'error_fit_invalid_profile_value',
  unsupportedFormat: 'error_fit_unsupported_format',
  binaryUnsupported: 'error_fit_binary_unsupported',
  malformedFile: 'error_fit_malformed_file',
  noWorkouts: 'error_fit_no_workouts',
  streamsUnreadable: 'error_fit_streams_unreadable',
  fileTooLarge: 'error_fit_file_too_large',
} as const

// Workouts per History page at most: a page is one postMessage, never the history.
export const MAX_WORKOUT_PAGE_SIZE = 200
// The widest `dailyAggregates` window, in days.
export const MAX_AGGREGATE_WINDOW_DAYS = 400
// The biggest activity file `importActivityFile` accepts.
export const MAX_ACTIVITY_FILE_BYTES = 32 * 1024 * 1024
// The write domains the SDK enforces (typed `invalidGoal` / `invalidProfileValue` outside them).
// Goals are native's own stepper bounds; the body caps are sanity bounds native does not have.
export const GOAL_ACTIVE_MINUTES = { min: 5, max: 240 } as const
export const GOAL_STEPS = { min: 1_000, max: 30_000 } as const
export const MAX_HEIGHT_CM = 300
export const MAX_WEIGHT_KG = 1_000
// Birth dates run from here to today.
export const EARLIEST_BIRTH_DATE = '1900-01-01'

export type FitActivityType = 'Running' | 'Walking' | 'Cycling' | 'Strength' | 'Other'
export type FitSource = 'Recorded' | 'HealthConnect' | 'AppleHealth' | 'File' | 'Manual' | 'Detected'

export interface FitDay {
  readonly date: string
  readonly steps: number
  readonly distanceM: number
  readonly activeEnergyKj: number
  readonly activeMinutes: number
}

export interface FitWorkout {
  readonly workoutId: string
  readonly activityType: FitActivityType
  readonly source: FitSource
  readonly title: string
  readonly startMs: number
  readonly endMs: number
  // ISO local date-time in the reader zone: the day the row groups under.
  readonly startLocal: string
  readonly durationSec: number
  // ⚠ null = unknown, never zero.
  readonly distanceM: number | null
  readonly activeEnergyKj: number | null
  readonly avgHr: number | null
  // Only on the detail read (it comes from the HR stream).
  readonly maxHr: number | null
  readonly steps: number | null
  readonly paceSecPerKm: number | null
}

export interface FitHrPoint {
  readonly tSec: number
  readonly bpm: number
}

// WGS84 degrees: what W-MAP draws.
export interface FitRoutePoint {
  readonly tSec: number
  readonly lat: number
  readonly lng: number
  readonly altM: number | null
}

export interface FitWorkoutDetail {
  readonly workout: FitWorkout
  readonly heartRate: FitHrPoint[]
  readonly route: FitRoutePoint[]
}

export interface FitGoals {
  readonly activeMinutes: number
  readonly steps: number
}

export type FitUnits = 'metric' | 'imperial'
export type FitSex = 'female' | 'male' | 'other'

// ⚠ Every field but `units` is null when unset: render "Not set", never a placeholder.
export interface FitProfile {
  readonly name: string | null
  readonly heightCm: number | null
  readonly weightKg: number | null
  readonly birthDate: string | null
  readonly sex: FitSex | null
  readonly units: FitUnits
}

// Every state a data-source row can carry: exactly Kotlin's `FitDataSourceStateName`, in its order
// (pinned both ways: `FitDataSourceStateTest` in jsTest, `fitDataSourceStates.test.ts` in apex).
// ⚠ Several never occur on web today (`Running` needs a live import, `PermissionNeeded` and
// `Unavailable` a platform store), but the shared projection can emit them: handle every one.
export const FIT_DATA_SOURCE_STATES = [
  'NeverRun',
  'Imported',
  'NoData',
  'Failed',
  'Running',
  'Recording',
  'PermissionNeeded',
  'Unavailable',
  'Sample',
] as const
export type FitDataSourceState = (typeof FIT_DATA_SOURCE_STATES)[number]

export interface FitDataSource {
  readonly kind: 'TenshuHealth' | 'HealthConnect' | 'AppleHealth'
  readonly deviceId: string | null
  readonly deviceLabel: string | null
  readonly isThisDevice: boolean
  readonly state: FitDataSourceState
  readonly atMs: number | null
  readonly itemCount: number | null
  readonly autoImport: boolean
  readonly needsAttention: boolean
}

export interface FitBatchImport {
  readonly lastRunAtMs: number | null
  readonly lastStatus: 'Succeeded' | 'NoData' | 'PermissionsMissing' | 'Failed' | null
  readonly runCount: number
}

// An opaque keyset cursor: pass back exactly what `nextCursor` returned. `null` is the head.
export type WorkoutCursor = string

export type DailyAggregatesResult = { readonly ok: true; readonly days: FitDay[] } | FitFailure
export type WorkoutsPageResult =
  | { readonly ok: true; readonly workouts: FitWorkout[]; readonly nextCursor: WorkoutCursor | null }
  | FitFailure
// ⚠ `workout: null` = no such workout (an answer); `ok: false` = the read failed.
export type WorkoutDetailResult = { readonly ok: true; readonly workout: FitWorkoutDetail | null } | FitFailure
export type GoalsResult = { readonly ok: true; readonly goals: FitGoals } | FitFailure
export type ProfileResult = { readonly ok: true; readonly profile: FitProfile } | FitFailure
export type DataSourcesResult =
  | { readonly ok: true; readonly sources: FitDataSource[]; readonly batchImport: FitBatchImport | null }
  | FitFailure
// `workouts` lists each STORED id once (two tracks with the same first fix are one row, the later
// write's). ⚠ `streamsPending`: ids among them that are saved, with their route / HR readable here,
// whose upload has not reached the relay yet; the next sync tick sends it. Saved, not failed: a retry
// is safe but not needed. A workout that did not save is `ok: false`, naming any already saved.
export type ImportResult =
  | { readonly ok: true; readonly workouts: FitWorkout[]; readonly streamsPending: string[] }
  | FitFailure
export type FitWriteResult = { readonly ok: true } | FitFailure

// Operation surface: every method is Promise-returning and safe as a TanStack Query `queryFn`.
// `observe*` push on change and return the unsubscribe; their `onError` is terminal.
export const Fit = bundle.Fit.getInstance()
