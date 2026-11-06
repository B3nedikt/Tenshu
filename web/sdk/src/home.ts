// Home entry point (web-app-suite Wave 4, Home run A): bridged devices with their state, the hub
// storage tile, the automations list and the activity feed — what the iOS route-1 Home reads
// (`home-and-mia-apps.md` §8.1). Backed by `Home` on the Kotlin side (`:tenshu-sdk-js`), a second
// reader of the same synced relations native Home reads through `:home-shared-logic` (`HomeBook.kt`).
//
// ⚠⚠ READ-ONLY, decided (web-app-suite.md §8 items 2 and 9): no device control, no editor, no pairing,
// no automation toggle / run-now, no routine evaluation. There is no write method here, and the
// runtime maps no Home operation, so none can be added by a call alone. ⚠ Nothing here starts work.
// ⚠ Every call is `{ ok: true, … }` or a `HomeFailure`; a failed read is a failure, never `[]`.

import * as bundle from './_bundle'

// A typed failure: branch on `kind`, show or log `message`. The promise reads carry it as `error`; an
// `observe…` `onError` receives it bare.
export interface HomeError {
  readonly kind: string
  readonly message?: string
}

export interface HomeFailure {
  readonly ok: false
  readonly error: HomeError
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from the
// service's typed error. Branch on `kind`, never on `message`.
export const HomeErrorKind = {
  badCursor: 'error_home_bad_cursor',
  badPageSize: 'error_home_bad_page_size',
  // The web's "not connected": a Home service this runtime cannot reach. Never an empty list.
  serviceUnavailable: 'error_home_service_unavailable',
  // An `observe…` stream that completed instead of failing. Still terminal: resubscribe to go on.
  streamEnded: 'error_home_stream_ended',
} as const

// Activity rows per page at most: a page is one postMessage, never the history. ⚠ A copy of the Kotlin
// constant `Home.activityPage` enforces (`HomeBook.kt`); apex's `homeSdkConstants.test.ts` pins them equal.
export const MAX_ACTIVITY_PAGE_SIZE = 200

// The `HomeDevice.KIND_*` tokens this build knows. ⚠ `kind` is the stored token: a newer hub's kind
// passes through, so render an unknown one as a generic reading (as native does), never drop it.
export const HOME_DEVICE_KINDS = ['light', 'plug', 'sensor', 'thermostat', 'speaker', 'camera', 'bridge', 'other'] as const
export type HomeDeviceKind = (typeof HOME_DEVICE_KINDS)[number]

export interface HomeDeviceState {
  readonly online: boolean
  // null = the device has no power dimension (never "off").
  readonly power: 'on' | 'off' | null
  // Each null means "this device does not report that".
  readonly levelPercent: number | null
  // Tenths of a degree Celsius.
  readonly temperatureDeciC: number | null
  readonly humidityPercent: number | null
  readonly batteryPercent: number | null
  readonly colorTempMireds: number | null
  // Tenths of a watt.
  readonly powerDrawDeciW: number | null
  // The last command the hub consumed for this device ('' = none) and its outcome.
  readonly lastCommandId: string
  readonly lastCommandOutcome: string
  // Hub clock, epoch ms.
  readonly freshAsOfMs: number
}

export interface HomeDevice {
  readonly deviceId: string
  readonly bridgeId: string
  readonly kind: HomeDeviceKind | (string & {})
  readonly manufacturer: string
  readonly model: string
  // `HomeDevice.CAP_*` ids: 'power', 'level', 'temperature', 'humidity', 'battery', 'colorTemp', 'permitJoin'.
  readonly capabilities: string[]
  readonly name: string
  // '' = unassigned (a room is a string on the device, not an entity).
  readonly room: string
  readonly joinedAtMs: number
  // null until the device's state row has synced — never plausible defaults.
  readonly state: HomeDeviceState | null
}

export interface HomeHubStorage {
  readonly deviceId: string
  readonly name: string
  readonly version: string
  readonly diskFreeBytes: number
  readonly diskTotalBytes: number
  readonly usedBytes: number
  readonly replicaFileCount: number
  readonly replicaByteCount: number
  // null = the hub has never completed a backup pass.
  readonly lastBackupPassAtMs: number | null
  // Hub clock, epoch ms.
  readonly freshAsOfMs: number
  // Judged at read time. ⚠ Nothing re-derives it while the rows stand still: re-check
  // `now - freshAsOfMs > staleAfterMs` on your own render clock.
  readonly stale: boolean
  readonly staleAfterMs: number
}

export interface HomeStateMatch {
  readonly field: string
  readonly op: 'eq' | 'neq' | 'lt' | 'gt'
  readonly value: string
}

// The engine's own program (`RoutineProgram`), one `type` tag per variant — the wire's own names.
export type HomeTrigger =
  // `days`: ISO 1 (Mon) .. 7 (Sun), ascending; [] = every day.
  | { readonly type: 'timeOfDay'; readonly minuteOfDay: number; readonly days: number[] }
  | { readonly type: 'sun'; readonly event: 'sunrise' | 'sunset'; readonly offsetMinutes: number }
  | { readonly type: 'deviceEvent'; readonly deviceId: string; readonly match: HomeStateMatch }
  | { readonly type: 'geofence'; readonly placeId: string; readonly direction: 'arrive' | 'leave' }
  | { readonly type: 'voicePhrase'; readonly phrase: string }

export type HomeCondition =
  // May wrap midnight (from > to).
  | { readonly type: 'timeWindow'; readonly fromMinuteOfDay: number; readonly toMinuteOfDay: number }
  | { readonly type: 'deviceStateIs'; readonly deviceId: string; readonly match: HomeStateMatch }
  | { readonly type: 'presence'; readonly expect: 'nobodyHome' | 'somebodyHome' }

export type HomeAction =
  | { readonly type: 'setPower'; readonly deviceId: string; readonly on: boolean }
  | { readonly type: 'setLevel'; readonly deviceId: string; readonly percent: number }
  | { readonly type: 'notify'; readonly title: string; readonly body: string }
  | { readonly type: 'runRoutine'; readonly routineId: string }

export interface HomeProgram {
  readonly trigger: HomeTrigger
  readonly conditions: HomeCondition[]
  readonly actions: HomeAction[]
}

export interface HomeAutomation {
  readonly routineId: string
  readonly name: string
  readonly enabled: boolean
  // 'active' | 'suspended' (the engine stood it down: `suspendedReason` says why).
  readonly state: string
  readonly suspendedReason: string
  readonly timeZoneId: string
  readonly lastRunAtMs: number | null
  // A routine this build cannot describe is listed with `program: null`, never dropped.
  readonly programStatus: 'parsed' | 'unsupportedVersion' | 'malformed'
  readonly program: HomeProgram | null
}

export interface HomeActivity {
  readonly firingId: string
  // Only automation runs are stored anywhere; device-event and voice history do not exist yet.
  readonly kind: 'automation'
  readonly routineId: string
  // '' = the routine has since been deleted: the firing stays, under no name.
  readonly routineName: string
  // Only runs with an outcome are rows (an in-flight firing is not).
  readonly state: 'ran' | 'failed' | 'skipped' | 'missed' | 'throttled'
  readonly claimedAtMs: number
  readonly finishedAtMs: number | null
  // Firings a `throttled` row absorbed.
  readonly coalescedCount: number
}

// An opaque keyset cursor: pass back exactly what `nextCursor` returned. `null` is the head.
export type ActivityCursor = string

export type HomeDevicesResult =
  | { readonly ok: true; readonly devices: HomeDevice[]; readonly rooms: string[] }
  | HomeFailure
// ⚠ `hub: null` = no hub reports disk figures (no tile — an answer); `ok: false` = the read failed.
export type HomeHubStorageResult = { readonly ok: true; readonly hub: HomeHubStorage | null } | HomeFailure
export type HomeAutomationsResult = { readonly ok: true; readonly automations: HomeAutomation[] } | HomeFailure
export type HomeActivityPageResult =
  | { readonly ok: true; readonly events: HomeActivity[]; readonly nextCursor: ActivityCursor | null }
  | HomeFailure

// `observe…` push on change and return the unsubscribe. Their `onError` is TERMINAL and receives the
// same typed `HomeError` the promise reads carry: `serviceUnavailable` for a stream that failed,
// `streamEnded` for one that completed. A failed READ inside a live stream is not terminal — it arrives
// through `callback` as `{ ok: false, … }`, like the promise form.
export interface HomeApi {
  // Every bridged device, relation order; `rooms` = the distinct non-blank rooms, in device order.
  devices(): Promise<HomeDevicesResult>
  observeDevices(callback: (result: HomeDevicesResult) => void, onError: (error: HomeError) => void): () => void
  hubStorage(): Promise<HomeHubStorageResult>
  observeHubStorage(callback: (result: HomeHubStorageResult) => void, onError: (error: HomeError) => void): () => void
  automations(): Promise<HomeAutomationsResult>
  // Re-emits on a routine change or a change among the newest runs (a new run, so a `lastRunAtMs`).
  observeAutomations(callback: (result: HomeAutomationsResult) => void, onError: (error: HomeError) => void): () => void
  // Keyset over (claimedAt desc, firingId asc): a row served is never served again, a row after the
  // cursor never skipped. A run arriving before the cursor is only in a refetched head.
  // ⚠ `nextCursor: null` is the end. A last page that is exactly `pageSize` long still carries a
  // cursor, and the page after it is `events: []` + `nextCursor: null`: that is the NORMAL end of the
  // feed, an answer, not an error — stop paging, show nothing new.
  activityPage(cursor: ActivityCursor | null, pageSize: number): Promise<HomeActivityPageResult>
  // Fires on subscribe and whenever a routine or the newest runs change (a new run, or a firing's
  // outcome landing): the signal to refetch the head. ⚠ A run arriving by sync that sorts below the
  // newest runs does not fire it on its own; the next change among them does.
  observeActivityChanges(callback: (version: number) => void, onError: (error: HomeError) => void): () => void
}

// ⚠ Typed as `HomeApi`, so the native declaration must stay assignable to the public one.
export const Home: HomeApi = bundle.Home.getInstance()
