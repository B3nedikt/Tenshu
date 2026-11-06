// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams`.
import { vi } from 'vitest'
import type {
  HomeActivity,
  HomeActivityPageResult,
  HomeAutomation,
  HomeAutomationsResult,
  HomeDevice,
  HomeDeviceState,
  HomeDevicesResult,
  HomeFailure,
  HomeHubStorage,
  HomeHubStorageResult,
} from '@tenshu/sdk/home'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean }

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
  devices: stream<HomeDevicesResult>(),
  hub: stream<HomeHubStorageResult>(),
  automations: stream<HomeAutomationsResult>(),
  changes: stream<number>(),
}

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  devices: vi.fn(async (): Promise<HomeDevicesResult> => ({ ok: true, devices: [], rooms: [] })),
  subscribeDevices: vi.fn(streams.devices.subscribe),
  hubStorage: vi.fn(async (): Promise<HomeHubStorageResult> => ({ ok: true, hub: null })),
  subscribeHubStorage: vi.fn(streams.hub.subscribe),
  automations: vi.fn(async (): Promise<HomeAutomationsResult> => ({ ok: true, automations: [] })),
  subscribeAutomations: vi.fn(streams.automations.subscribe),
  activityPage: vi.fn(async (_cursor: string | null, _size: number): Promise<HomeActivityPageResult> => ({ ok: true, events: [], nextCursor: null })),
  subscribeActivityChanges: vi.fn(streams.changes.subscribe),
}

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: it puts each `vi.fn(impl)` back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  for (const s of Object.values(streams)) s.reset()
}

export function failure(kind: string): HomeFailure {
  return { ok: false, error: { kind, message: `diagnostic for ${kind}` } }
}

// ── fixtures ─────────────────────────────────────────────────────────────────

export function state(overrides: Partial<HomeDeviceState> = {}): HomeDeviceState {
  return {
    online: true,
    power: null,
    levelPercent: null,
    temperatureDeciC: null,
    humidityPercent: null,
    batteryPercent: null,
    colorTempMireds: null,
    powerDrawDeciW: null,
    lastCommandId: '',
    lastCommandOutcome: '',
    freshAsOfMs: Date.UTC(2026, 9, 28, 7, 0),
    ...overrides,
  }
}

export function device(deviceId: string, overrides: Partial<HomeDevice> = {}): HomeDevice {
  return {
    deviceId,
    bridgeId: 'bridge-1',
    kind: 'light',
    manufacturer: 'IKEA',
    model: 'TRADFRI',
    capabilities: ['power', 'level'],
    name: deviceId,
    room: '',
    joinedAtMs: 0,
    state: state(),
    ...overrides,
  }
}

export function hub(overrides: Partial<HomeHubStorage> = {}): HomeHubStorage {
  return {
    deviceId: 'hub-1',
    name: 'Tenshu Hub',
    version: '1.0',
    diskFreeBytes: 1_500_000_000_000,
    diskTotalBytes: 2_000_000_000_000,
    usedBytes: 500_000_000_000,
    replicaFileCount: 10,
    replicaByteCount: 1_000,
    lastBackupPassAtMs: null,
    freshAsOfMs: Date.UTC(2026, 9, 28, 7, 0),
    stale: false,
    staleAfterMs: 2 * 3_600_000,
    ...overrides,
  }
}

export function automation(routineId: string, overrides: Partial<HomeAutomation> = {}): HomeAutomation {
  return {
    routineId,
    name: routineId,
    enabled: true,
    state: 'active',
    suspendedReason: '',
    timeZoneId: 'Europe/Berlin',
    lastRunAtMs: null,
    programStatus: 'parsed',
    program: {
      trigger: { type: 'timeOfDay', minuteOfDay: 22 * 60 + 30, days: [] },
      conditions: [],
      actions: [{ type: 'setPower', deviceId: 'lamp', on: true }],
    },
    ...overrides,
  }
}

export function run(firingId: string, claimedAtMs: number, overrides: Partial<HomeActivity> = {}): HomeActivity {
  return {
    firingId,
    kind: 'automation',
    routineId: 'r1',
    routineName: 'Evening lights',
    state: 'ran',
    claimedAtMs,
    finishedAtMs: claimedAtMs + 1_000,
    coalescedCount: 0,
    ...overrides,
  }
}
