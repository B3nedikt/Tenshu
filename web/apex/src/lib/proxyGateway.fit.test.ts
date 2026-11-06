// Gateway coverage for the Fit surface (web-app-suite Wave 3, Fit run A).
//
// Same harness as `proxyGateway.photos.test.ts`: every `@tenshu/sdk/*` module is mocked (the real
// ones value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring and its param
// validation. The ops themselves are pinned by `FitWebContractTest` in jsTest.
import { describe, expect, it, vi, beforeEach } from 'vitest'

type Handler = (params: unknown) => unknown
type Subscription = (
  params: unknown,
  emit: (value: unknown) => void,
  fail: (message: string) => void,
) => () => void
let captured: Record<string, Handler> = {}
let subscriptions: Record<string, Subscription> = {}

vi.mock('@tenshu/sdk/bridge', () => ({
  serveProxyGateway: (options: {
    handlers: Record<string, Handler>
    subscriptions?: Record<string, Subscription>
  }) => {
    captured = options.handlers
    subscriptions = options.subscriptions ?? {}
    return { dispose: () => {} }
  },
}))

const { ensureRuntime, fit } = vi.hoisted(() => {
  const ok = { ok: true }
  const observer = (_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}
  return {
    ensureRuntime: vi.fn(),
    fit: {
      dailyAggregates: vi.fn(async (_f: string, _t: string, _z: string) => ({ ok: true, days: [] })),
      observeDailyAggregates: vi.fn(
        (_f: string, _t: string, _z: string, _cb: (v: unknown) => void, _onError: (m: string) => void) => () => {},
      ),
      workoutsPage: vi.fn(async (_c: string | null, _n: number, _z: string) => ({
        ok: true,
        workouts: [],
        nextCursor: null,
      })),
      observeWorkoutChanges: vi.fn((_cb: (v: number) => void, _onError: (m: string) => void) => () => {}),
      workout: vi.fn(async (_id: string, _z: string) => ({ ok: true, workout: null })),
      goals: vi.fn(async () => ({ ok: true, goals: { activeMinutes: 30, steps: 8000 } })),
      observeGoals: vi.fn(observer),
      setGoals: vi.fn(async (_a: number, _s: number) => ok),
      profile: vi.fn(async () => ({ ok: true, profile: {} })),
      observeProfile: vi.fn(observer),
      setUnits: vi.fn(async (_u: string) => ok),
      setName: vi.fn(async (_n: string) => ok),
      setBirthDate: vi.fn(async (_d: string) => ok),
      setSex: vi.fn(async (_s: string) => ok),
      recordHeight: vi.fn(async (_cm: number) => ok),
      recordWeight: vi.fn(async (_kg: number) => ok),
      dataSources: vi.fn(async () => ({ ok: true, sources: [], batchImport: null })),
      observeDataSources: vi.fn(observer),
      deleteWorkout: vi.fn(async (_id: string) => ok),
      importActivityFile: vi.fn(async (_n: string, _b: Int8Array, _z: string) => ({ ok: true, workouts: [] })),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/fit', () => ({ Fit: fit, MAX_WORKOUT_PAGE_SIZE: 200, MAX_ACTIVITY_FILE_BYTES: 64 }))
vi.mock('@tenshu/sdk/home', () => ({ Home: {}, MAX_ACTIVITY_PAGE_SIZE: 200 }))
vi.mock('@tenshu/sdk/mia', () => ({
  Mia: {},
  MAX_UTTERANCE_CHARS: 500,
  MAX_TURN_PAGE_SIZE: 100,
  MiaMethod: {
    Ask: 'mia.ask',
    History: 'mia.history',
    HistoryObserve: 'mia.history.observe',
    TurnsPage: 'mia.turnsPage',
    Grants: 'mia.grants',
    GrantsObserve: 'mia.grants.observe',
    SetGranted: 'mia.grants.setGranted',
    SetWritable: 'mia.grants.setWritable',
  },
}))
vi.mock('@tenshu/sdk/photos', () => ({ Photos: {}, MAX_FEED_PAGE_SIZE: 500 }))

vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: {} }))
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/syncHealth', () => ({ SyncHealth: {} }))
vi.mock('@tenshu/sdk/syncStatus', () => ({ SyncStatus: {} }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: {} }))
vi.mock('@tenshu/sdk/calendar', () => ({ Calendar: {} }))

import { mountProxyGateway } from './proxyGateway'

function handler(method: string): Handler {
  const h = captured[method]
  if (!h) throw new Error(`gateway registered no handler for '${method}'`)
  return h
}

function subscription(method: string): Subscription {
  const s = subscriptions[method]
  if (!s) throw new Error(`gateway registered no subscription for '${method}'`)
  return s
}

const bytes = new Int8Array([60, 103, 112, 120])

describe('proxyGateway — fit', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the run-A surface: no recording, edit, Takeout or auto-import switch', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('fit.')).sort()).toEqual(
      [
        'fit.dailyAggregates',
        'fit.dataSources',
        'fit.deleteWorkout',
        'fit.goals',
        'fit.importActivityFile',
        'fit.profile',
        'fit.recordHeight',
        'fit.recordWeight',
        'fit.setBirthDate',
        'fit.setGoals',
        'fit.setName',
        'fit.setSex',
        'fit.setUnits',
        'fit.workout',
        'fit.workoutsPage',
      ].sort(),
    )
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('fit.')).sort()).toEqual([
      'fit.dailyAggregates.observe',
      'fit.dataSources.observe',
      'fit.goals.observe',
      'fit.profile.observe',
      'fit.workouts.changes',
    ])
  })

  it.each<[string, unknown, keyof typeof fit, unknown[]]>([
    ['fit.dailyAggregates', { from: '2026-01-01', to: '2026-01-31', zone: '' }, 'dailyAggregates', ['2026-01-01', '2026-01-31', '']],
    ['fit.workoutsPage', { pageSize: 20, zone: 'Asia/Tokyo' }, 'workoutsPage', [null, 20, 'Asia/Tokyo']],
    ['fit.workoutsPage', { cursor: 'w1.5.a', pageSize: 200, zone: '' }, 'workoutsPage', ['w1.5.a', 200, '']],
    ['fit.workout', { workoutId: 'gpx-1', zone: '' }, 'workout', ['gpx-1', '']],
    ['fit.goals', undefined, 'goals', []],
    ['fit.setGoals', { activeMinutes: 45, steps: 10000 }, 'setGoals', [45, 10000]],
    ['fit.profile', undefined, 'profile', []],
    ['fit.setUnits', { units: 'imperial' }, 'setUnits', ['imperial']],
    ['fit.setName', { name: '' }, 'setName', ['']],
    ['fit.setBirthDate', { date: '' }, 'setBirthDate', ['']],
    ['fit.setSex', { sex: 'female' }, 'setSex', ['female']],
    ['fit.recordHeight', { centimetres: 171 }, 'recordHeight', [171]],
    ['fit.recordWeight', { kilograms: 74.3 }, 'recordWeight', [74.3]],
    ['fit.dataSources', undefined, 'dataSources', []],
    ['fit.deleteWorkout', { workoutId: 'w' }, 'deleteWorkout', ['w']],
    ['fit.importActivityFile', { name: 'run.gpx', bytes, zone: '' }, 'importActivityFile', ['run.gpx', bytes, '']],
  ])('%s forwards %j and passes the result through', async (method, params, op, args) => {
    const result = await handler(method)(params)
    expect(fit[op]).toHaveBeenCalledWith(...args)
    expect(result).toEqual(await (fit[op] as unknown as (...a: unknown[]) => unknown)(...args))
    expect(ensureRuntime).toHaveBeenCalled()
  })

  it('passes a failure envelope through untouched — never as an empty success', async () => {
    const failure = { ok: false, error: { kind: 'error_fit_malformed_file', message: 'a.gpx' } }
    fit.importActivityFile.mockResolvedValueOnce(failure as never)
    await expect(handler('fit.importActivityFile')({ name: 'a.gpx', bytes, zone: '' })).resolves.toEqual(failure)
  })

  it('passes a not-found workout through as null', async () => {
    await expect(handler('fit.workout')({ workoutId: 'gone', zone: '' })).resolves.toEqual({ ok: true, workout: null })
  })

  it.each<[string, unknown]>([
    // A missing pageSize must not become "the whole history"; a missing zone must not become ''.
    ['fit.workoutsPage', { zone: '' }],
    ['fit.workoutsPage', { pageSize: 0, zone: '' }],
    ['fit.workoutsPage', { pageSize: 201, zone: '' }],
    ['fit.workoutsPage', { pageSize: 2.5, zone: '' }],
    ['fit.workoutsPage', { cursor: 7, pageSize: 20, zone: '' }],
    ['fit.workoutsPage', { pageSize: 20 }],
    ['fit.dailyAggregates', { from: '2026-01-01', to: '2026-01-02' }],
    ['fit.dailyAggregates', { from: '', to: '2026-01-02', zone: '' }],
    ['fit.workout', { workoutId: '', zone: '' }],
    ['fit.workout', { workoutId: 'w' }],
    ['fit.setGoals', { activeMinutes: 30 }],
    ['fit.setGoals', { activeMinutes: '30', steps: 8000 }],
    ['fit.setGoals', { activeMinutes: 30.5, steps: 8000 }],
    // ⚠ int32 at the gateway: `Number.isInteger(1e15)` is true, and it would reach a Kotlin Int.
    ['fit.setGoals', { activeMinutes: 30, steps: 1e15 }],
    ['fit.setGoals', { activeMinutes: 2_147_483_648, steps: 8000 }],
    ['fit.setGoals', { activeMinutes: 30, steps: -2_147_483_649 }],
    ['fit.recordHeight', { centimetres: 1e15 }],
    ['fit.setUnits', { units: '' }],
    ['fit.setName', {}],
    ['fit.setBirthDate', { date: null }],
    ['fit.setSex', undefined],
    ['fit.recordHeight', { centimetres: 171.5 }],
    ['fit.recordWeight', { kilograms: Number.NaN }],
    ['fit.recordWeight', { kilograms: '74' }],
    ['fit.deleteWorkout', { workoutId: '' }],
    ['fit.importActivityFile', { name: 'a.gpx', bytes: [1, 2], zone: '' }],
    ['fit.importActivityFile', { name: '', bytes, zone: '' }],
    ['fit.importActivityFile', { name: 'a.gpx', bytes }],
  ])('%s rejects malformed params %j without dispatching', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    for (const op of Object.values(fit)) expect(op).not.toHaveBeenCalled()
  })

  it('passes the int32 bounds themselves through to the SDK, which owns the domain', async () => {
    await handler('fit.setGoals')({ activeMinutes: 2_147_483_647, steps: -2_147_483_648 })
    expect(fit.setGoals).toHaveBeenCalledWith(2_147_483_647, -2_147_483_648)
  })

  it('refuses an oversized file before it reaches the runtime', async () => {
    await expect(
      handler('fit.importActivityFile')({ name: 'big.gpx', bytes: new Int8Array(65), zone: '' }),
    ).rejects.toThrow(/over 64 bytes/)
    expect(fit.importActivityFile).not.toHaveBeenCalled()
  })

  it('the observe subscriptions emit what the SDK pushes, wire `fail` through and return its unsubscribe', () => {
    const stop = vi.fn()
    fit.observeGoals.mockImplementationOnce((cb) => {
      cb({ ok: true, goals: { activeMinutes: 30, steps: 8000 } })
      return stop
    })
    const emit = vi.fn()
    const fail = vi.fn()
    const unsubscribe = subscription('fit.goals.observe')(undefined, emit, fail)
    expect(emit).toHaveBeenCalledWith({ ok: true, goals: { activeMinutes: 30, steps: 8000 } })
    unsubscribe()
    expect(stop).toHaveBeenCalledTimes(1)

    fit.observeProfile.mockImplementationOnce((_cb, onError) => {
      onError('fit stream ended')
      return () => {}
    })
    subscription('fit.profile.observe')(undefined, emit, fail)
    expect(fail).toHaveBeenCalledWith('fit stream ended')

    subscription('fit.dailyAggregates.observe')({ from: '2026-01-01', to: '2026-01-07', zone: '' }, emit, fail)
    expect(fit.observeDailyAggregates).toHaveBeenCalledWith('2026-01-01', '2026-01-07', '', expect.any(Function), fail)
    expect(() => subscription('fit.dailyAggregates.observe')({ from: '2026-01-01' }, emit, fail)).toThrow(/requires/)

    fit.observeWorkoutChanges.mockImplementationOnce((cb) => {
      cb(7)
      return () => {}
    })
    subscription('fit.workouts.changes')(undefined, emit, fail)
    expect(emit).toHaveBeenCalledWith(7)

    subscription('fit.dataSources.observe')(undefined, emit, fail)
    expect(fit.observeDataSources).toHaveBeenCalledWith(expect.any(Function), fail)
  })
})
