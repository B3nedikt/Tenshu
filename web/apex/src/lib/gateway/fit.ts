// `fit.*`: the Fit web surface (web-app-suite Wave 3, Fit run A). Thin, validated pass-throughs to
// `Fit` (`@tenshu/sdk/fit`). The rules (the shared projection, keyset paging, not-found shapes, the
// import's typed refusals) live in the Kotlin SDK and are pinned by `FitWebContractTest`.
//
// ⚠ Not here (fit-app.md §Web scope): recording, Takeout, binary `.FIT` (refused by the SDK), the
// auto-import switch, edit, onboarding. ⚠ No app's allowlist names a `fit.*` method yet, so these are
// unreachable until run B adds `web/fit` and its registry entry (F5).
import { Fit, MAX_ACTIVITY_FILE_BYTES, MAX_WORKOUT_PAGE_SIZE } from '@tenshu/sdk/fit'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const fitGateway: GatewayModule = {
  handlers: {
    'fit.dailyAggregates': async (params) => {
      const w = readWindow(params, 'fit.dailyAggregates')
      return Fit.dailyAggregates(w.from, w.to, w.zone)
    },
    // History: a keyset page in, a page and the next cursor out. Never the whole history.
    'fit.workoutsPage': async (params) => {
      const { cursor, pageSize } = readPage(params)
      return Fit.workoutsPage(cursor, pageSize, readZone(params, 'fit.workoutsPage'))
    },
    // One workout with its streams. `workout: null` = no such workout; `ok: false` = the read failed.
    'fit.workout': async (params) =>
      Fit.workout(readId(params, 'workoutId', 'fit.workout'), readZone(params, 'fit.workout')),

    'fit.goals': async () => Fit.goals(),
    'fit.setGoals': async (params) =>
      Fit.setGoals(readInteger(params, 'activeMinutes', 'fit.setGoals'), readInteger(params, 'steps', 'fit.setGoals')),

    'fit.profile': async () => Fit.profile(),
    'fit.setUnits': async (params) => Fit.setUnits(readId(params, 'units', 'fit.setUnits')),
    // '' is a value here (it clears), not an omission.
    'fit.setName': async (params) => Fit.setName(readString(params, 'name', 'fit.setName')),
    'fit.setBirthDate': async (params) => Fit.setBirthDate(readString(params, 'date', 'fit.setBirthDate')),
    'fit.setSex': async (params) => Fit.setSex(readString(params, 'sex', 'fit.setSex')),
    'fit.recordHeight': async (params) =>
      Fit.recordHeight(readInteger(params, 'centimetres', 'fit.recordHeight')),
    'fit.recordWeight': async (params) => Fit.recordWeight(readNumber(params, 'kilograms', 'fit.recordWeight')),

    // Read-only (§Web scope 5): no switch, no "import now".
    'fit.dataSources': async () => Fit.dataSources(),

    // ⚠⚠ Deletes on every device (a tombstone): the calling UI confirms first.
    'fit.deleteWorkout': async (params) => Fit.deleteWorkout(readId(params, 'workoutId', 'fit.deleteWorkout')),
    // GPX / TCX bytes the app read from a file input. The SDK refuses everything else, typed.
    'fit.importActivityFile': async (params) => {
      const { name, bytes } = readFile(params)
      return Fit.importActivityFile(name, bytes, readZone(params, 'fit.importActivityFile'))
    },
  },
  subscriptions: {
    // Genuine push over the health / preference relations' Flows. ⚠ `fail` is wired through: a
    // collector that dies ends the subscriber's stream with a terminal error instead of freezing
    // its screen. History has no push form; `fit.workouts.changes` says when to refetch its head.
    'fit.dailyAggregates.observe': (params, emit, fail) => {
      const w = readWindow(params, 'fit.dailyAggregates.observe')
      return Fit.observeDailyAggregates(w.from, w.to, w.zone, (result) => emit(result), fail)
    },
    'fit.workouts.changes': (_params, emit, fail) => Fit.observeWorkoutChanges((version) => emit(version), fail),
    'fit.goals.observe': (_params, emit, fail) => Fit.observeGoals((result) => emit(result), fail),
    'fit.profile.observe': (_params, emit, fail) => Fit.observeProfile((result) => emit(result), fail),
    'fit.dataSources.observe': (_params, emit, fail) => Fit.observeDataSources((result) => emit(result), fail),
  },
}

// `zone` is required: '' means the browser's own zone, and a missing key must not quietly mean it.
function readZone(params: unknown, op: string): string {
  return readString(params, 'zone', op)
}

function readWindow(params: unknown, op: string): { from: string; to: string; zone: string } {
  return { from: readId(params, 'from', op), to: readId(params, 'to', op), zone: readZone(params, op) }
}

// ⚠⚠ int32, because it lands in a Kotlin `Int`: `Number.isInteger(1e15)` is true, and K/JS does not
// truncate a number crossing the boundary, so the value outran every `Int` the runtime does arithmetic
// on and reached synced rows native reads as 32-bit. The domain bounds (goal, height) are the SDK's.
const INT32_MIN = -2_147_483_648
const INT32_MAX = 2_147_483_647

function readInteger(params: unknown, key: string, op: string): number {
  const v = asRecord(params)[key]
  if (typeof v !== 'number' || !Number.isInteger(v) || v < INT32_MIN || v > INT32_MAX) {
    throw new Error(`${op} requires { ${key}: 32-bit integer }`)
  }
  return v
}

function readNumber(params: unknown, key: string, op: string): number {
  const v = asRecord(params)[key]
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error(`${op} requires { ${key}: number }`)
  return v
}

// `cursor` is optional (absent or null = the head) and opaque. `pageSize` is a required integer in
// 1..MAX_WORKOUT_PAGE_SIZE: a missing one must not quietly become "the whole history".
function readPage(params: unknown): { cursor: string | null; pageSize: number } {
  const r = asRecord(params)
  const cursor = r.cursor ?? null
  if (cursor !== null && typeof cursor !== 'string') throw new Error('fit.workoutsPage requires { cursor: string | null }')
  const pageSize = r.pageSize
  if (
    typeof pageSize !== 'number' ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > MAX_WORKOUT_PAGE_SIZE
  ) {
    throw new Error(`fit.workoutsPage requires { pageSize: integer 1..${MAX_WORKOUT_PAGE_SIZE} }`)
  }
  return { cursor, pageSize }
}

// The structured-cloned Int8Array the app sent (as `fs.uploadFile`). The size cap is checked here
// too, so an oversized file is refused before it crosses into the runtime.
function readFile(params: unknown): { name: string; bytes: Int8Array } {
  const r = asRecord(params)
  if (typeof r.name !== 'string' || r.name.length === 0 || !(r.bytes instanceof Int8Array)) {
    throw new Error('fit.importActivityFile requires { name, bytes: Int8Array, zone }')
  }
  if (r.bytes.byteLength > MAX_ACTIVITY_FILE_BYTES) {
    throw new Error(`fit.importActivityFile: file over ${MAX_ACTIVITY_FILE_BYTES} bytes`)
  }
  return { name: r.name, bytes: r.bytes }
}
