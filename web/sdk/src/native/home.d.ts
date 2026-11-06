// `Home` — the read-only Home surface (web-app-suite Wave 4, Home run A).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).
// Rows mirror the `@JsExport` classes in `HomeBook.kt`; calls mirror `Home.kt`.
//
// ⚠⚠ READ-ONLY (web-app-suite.md §8 items 2 and 9): no write is declared here because none exists —
// the runtime maps no Home operation at all (`HomePlugin.kt`).

interface NativeHomeDeviceState {
  readonly online: boolean
  // null = the device has no power dimension.
  readonly power: 'on' | 'off' | null
  readonly levelPercent: number | null
  // Tenths of a °C.
  readonly temperatureDeciC: number | null
  readonly humidityPercent: number | null
  readonly batteryPercent: number | null
  readonly colorTempMireds: number | null
  // Tenths of a watt.
  readonly powerDrawDeciW: number | null
  // '' = none.
  readonly lastCommandId: string
  // 'ok' | 'failed' | 'unsupported' | ''
  readonly lastCommandOutcome: string
  readonly freshAsOfMs: number
}

interface NativeHomeDevice {
  readonly deviceId: string
  readonly bridgeId: string
  // The stored `HomeDevice.KIND_*` token; an unknown one passes through.
  readonly kind: string
  readonly manufacturer: string
  readonly model: string
  readonly capabilities: string[]
  readonly name: string
  // '' = unassigned.
  readonly room: string
  readonly joinedAtMs: number
  // null until the device's state row has synced.
  readonly state: NativeHomeDeviceState | null
}

interface NativeHomeHubStorage {
  readonly deviceId: string
  readonly name: string
  readonly version: string
  readonly diskFreeBytes: number
  readonly diskTotalBytes: number
  readonly usedBytes: number
  readonly replicaFileCount: number
  readonly replicaByteCount: number
  // null = never backed up.
  readonly lastBackupPassAtMs: number | null
  readonly freshAsOfMs: number
  readonly stale: boolean
  readonly staleAfterMs: number
}

interface NativeHomeStateMatch {
  readonly field: string
  readonly op: 'eq' | 'neq' | 'lt' | 'gt'
  readonly value: string
}

type NativeHomeTrigger =
  | { readonly type: 'timeOfDay'; readonly minuteOfDay: number; readonly days: number[] }
  | { readonly type: 'sun'; readonly event: 'sunrise' | 'sunset'; readonly offsetMinutes: number }
  | { readonly type: 'deviceEvent'; readonly deviceId: string; readonly match: NativeHomeStateMatch }
  | { readonly type: 'geofence'; readonly placeId: string; readonly direction: 'arrive' | 'leave' }
  | { readonly type: 'voicePhrase'; readonly phrase: string }

type NativeHomeCondition =
  | { readonly type: 'timeWindow'; readonly fromMinuteOfDay: number; readonly toMinuteOfDay: number }
  | { readonly type: 'deviceStateIs'; readonly deviceId: string; readonly match: NativeHomeStateMatch }
  | { readonly type: 'presence'; readonly expect: 'nobodyHome' | 'somebodyHome' }

type NativeHomeAction =
  | { readonly type: 'setPower'; readonly deviceId: string; readonly on: boolean }
  | { readonly type: 'setLevel'; readonly deviceId: string; readonly percent: number }
  | { readonly type: 'notify'; readonly title: string; readonly body: string }
  | { readonly type: 'runRoutine'; readonly routineId: string }

interface NativeHomeProgram {
  readonly trigger: NativeHomeTrigger
  readonly conditions: NativeHomeCondition[]
  readonly actions: NativeHomeAction[]
}

interface NativeHomeAutomation {
  readonly routineId: string
  readonly name: string
  readonly enabled: boolean
  // 'active' | 'suspended'
  readonly state: string
  readonly suspendedReason: string
  readonly timeZoneId: string
  readonly lastRunAtMs: number | null
  readonly programStatus: 'parsed' | 'unsupportedVersion' | 'malformed'
  // null exactly when programStatus is not 'parsed'.
  readonly program: NativeHomeProgram | null
}

interface NativeHomeActivity {
  readonly firingId: string
  readonly kind: 'automation'
  readonly routineId: string
  // '' = the routine has since been deleted.
  readonly routineName: string
  readonly state: 'ran' | 'failed' | 'skipped' | 'missed' | 'throttled'
  readonly claimedAtMs: number
  readonly finishedAtMs: number | null
  readonly coalescedCount: number
}

type NativeHomeOk<T> = ({ readonly ok: true } & T) | NativeFailure

// What an `observe…` `onError` receives: the promise reads' `error`, bare (`Home.observeJob`).
interface NativeHomeError {
  readonly kind: string
  readonly message?: string
}

type NativeHomeDevices = NativeHomeOk<{ readonly devices: NativeHomeDevice[]; readonly rooms: string[] }>
type NativeHomeHub = NativeHomeOk<{ readonly hub: NativeHomeHubStorage | null }>
type NativeHomeAutomations = NativeHomeOk<{ readonly automations: NativeHomeAutomation[] }>
type NativeHomeActivityPage = NativeHomeOk<{
  readonly events: NativeHomeActivity[]
  readonly nextCursor: string | null
}>

interface HomeInstance {
  devices(): Promise<NativeHomeDevices>
  observeDevices(callback: (result: NativeHomeDevices) => void, onError: (error: NativeHomeError) => void): () => void
  hubStorage(): Promise<NativeHomeHub>
  observeHubStorage(callback: (result: NativeHomeHub) => void, onError: (error: NativeHomeError) => void): () => void
  automations(): Promise<NativeHomeAutomations>
  observeAutomations(callback: (result: NativeHomeAutomations) => void, onError: (error: NativeHomeError) => void): () => void
  // `cursor` null or '' = the head; `pageSize` 1..200. An exactly-full last page carries a cursor; the
  // page after it is `[]` + `null`, the normal end.
  activityPage(cursor: string | null, pageSize: number): Promise<NativeHomeActivityPage>
  // A version number, on subscribe and on every run / routine change: refetch the feed's head.
  observeActivityChanges(callback: (version: number) => void, onError: (error: NativeHomeError) => void): () => void
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Home: { getInstance(): HomeInstance }
}
