// Gateway coverage for the Home surface (web-app-suite Wave 4, Home run A).
//
// Same harness as `proxyGateway.fit.test.ts`: every `@tenshu/sdk/*` module is mocked (the real ones
// value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring, its param
// validation and that it stays READ-ONLY. The reads themselves are pinned by `HomeWebContractTest`.
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

const { ensureRuntime, home } = vi.hoisted(() => {
  const observer = (_cb: (v: unknown) => void, _onError: (e: { kind: string; message?: string }) => void) => () => {}
  return {
    ensureRuntime: vi.fn(),
    home: {
      devices: vi.fn(async () => ({ ok: true, devices: [], rooms: [] })),
      observeDevices: vi.fn(observer),
      hubStorage: vi.fn(async () => ({ ok: true, hub: null })),
      observeHubStorage: vi.fn(observer),
      automations: vi.fn(async () => ({ ok: true, automations: [] })),
      observeAutomations: vi.fn(observer),
      activityPage: vi.fn(async (_c: string | null, _n: number) => ({ ok: true, events: [], nextCursor: null })),
      observeActivityChanges: vi.fn((_cb: (v: number) => void, _onError: (e: { kind: string; message?: string }) => void) => () => {}),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/home', () => ({ Home: home, MAX_ACTIVITY_PAGE_SIZE: 200 }))
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
vi.mock('@tenshu/sdk/fit', () => ({ Fit: {}, MAX_WORKOUT_PAGE_SIZE: 200, MAX_ACTIVITY_FILE_BYTES: 33554432 }))
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
import { HOME_METHODS } from './gateway/home'

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

describe('proxyGateway — home', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the read surface — HOME_METHODS, nothing more', () => {
    const served = [...Object.keys(captured), ...Object.keys(subscriptions)].filter((m) => m.startsWith('home.'))
    expect(served.sort()).toEqual([...HOME_METHODS].sort())
    expect(Object.keys(captured).filter((m) => m.startsWith('home.')).sort()).toEqual([
      'home.activityPage',
      'home.automations',
      'home.devices',
      'home.hubStorage',
    ])
  })

  // ⚠⚠ web-app-suite.md §8 items 2 and 9: Home on web is READ-ONLY. A write-shaped name here means
  // the decision was reversed in code; reverse it in the doc first.
  it('exposes no write: no command, rename, pairing, toggle, save, delete, run-now or hub command', () => {
    const writeShaped = /set|save|update|delete|remove|rename|toggle|enable|disable|run|command|pair|permit|join|issue|restart|backup|evaluate|add|create|edit/i
    for (const method of HOME_METHODS) {
      // The verb is everything after the namespace, minus the push suffixes.
      const verb = method.replace(/^home\./, '').replace(/\.(observe|changes)$/, '')
      expect(verb, method).not.toMatch(writeShaped)
    }
    // And the SDK object the gateway forwards to has nothing else to forward to.
    expect(Object.keys(home).sort()).toEqual([
      'activityPage',
      'automations',
      'devices',
      'hubStorage',
      'observeActivityChanges',
      'observeAutomations',
      'observeDevices',
      'observeHubStorage',
    ])
  })

  it.each<[string, unknown, keyof typeof home, unknown[]]>([
    ['home.devices', undefined, 'devices', []],
    ['home.hubStorage', undefined, 'hubStorage', []],
    ['home.automations', undefined, 'automations', []],
    ['home.activityPage', { pageSize: 20 }, 'activityPage', [null, 20]],
    ['home.activityPage', { cursor: null, pageSize: 1 }, 'activityPage', [null, 1]],
    ['home.activityPage', { cursor: 'a1.5.f', pageSize: 200 }, 'activityPage', ['a1.5.f', 200]],
  ])('%s forwards %j and passes the result through', async (method, params, op, args) => {
    const result = await handler(method)(params)
    expect(home[op]).toHaveBeenCalledWith(...args)
    expect(result).toEqual(await (home[op] as unknown as (...a: unknown[]) => unknown)(...args))
    expect(ensureRuntime).toHaveBeenCalled()
  })

  it('passes a failure envelope through untouched — never as an empty success', async () => {
    const failure = { ok: false, error: { kind: 'error_home_service_unavailable', message: 'routine' } }
    home.automations.mockResolvedValueOnce(failure as never)
    await expect(handler('home.automations')(undefined)).resolves.toEqual(failure)
    home.devices.mockResolvedValueOnce(failure as never)
    await expect(handler('home.devices')(undefined)).resolves.toEqual(failure)
  })

  it('passes "no hub" through as hub: null, an answer', async () => {
    await expect(handler('home.hubStorage')(undefined)).resolves.toEqual({ ok: true, hub: null })
  })

  it.each<[unknown]>([
    // A missing pageSize must not become "the whole feed".
    [undefined],
    [{}],
    [{ pageSize: 0 }],
    [{ pageSize: 201 }],
    [{ pageSize: 2.5 }],
    [{ pageSize: '20' }],
    [{ pageSize: Number.NaN }],
    [{ cursor: 7, pageSize: 20 }],
    [{ cursor: {}, pageSize: 20 }],
  ])('home.activityPage rejects malformed params %j without dispatching', async (params) => {
    await expect(handler('home.activityPage')(params)).rejects.toThrow(/requires/)
    for (const op of Object.values(home)) expect(op).not.toHaveBeenCalled()
  })

  it('the subscriptions emit what the SDK pushes, wire `fail` through and return its unsubscribe', () => {
    const stop = vi.fn()
    home.observeDevices.mockImplementationOnce((cb) => {
      cb({ ok: true, devices: [], rooms: [] })
      return stop
    })
    const emit = vi.fn()
    const fail = vi.fn()
    const unsubscribe = subscription('home.devices.observe')(undefined, emit, fail)
    expect(emit).toHaveBeenCalledWith({ ok: true, devices: [], rooms: [] })
    unsubscribe()
    expect(stop).toHaveBeenCalledTimes(1)

    // The SDK's terminal error is typed; `fail` gets it as one string with the catalogue key first.
    home.observeHubStorage.mockImplementationOnce((_cb, onError) => {
      onError({ kind: 'error_home_stream_ended', message: 'home stream ended' })
      return () => {}
    })
    subscription('home.hubStorage.observe')(undefined, emit, fail)
    expect(fail).toHaveBeenCalledWith('error_home_stream_ended: home stream ended')

    home.observeAutomations.mockImplementationOnce((_cb, onError) => {
      onError({ kind: 'error_home_service_unavailable' })
      return () => {}
    })
    subscription('home.automations.observe')(undefined, emit, fail)
    expect(fail).toHaveBeenLastCalledWith('error_home_service_unavailable')

    home.observeActivityChanges.mockImplementationOnce((cb) => {
      cb(3)
      return () => {}
    })
    subscription('home.activity.changes')(undefined, emit, fail)
    expect(emit).toHaveBeenCalledWith(3)
  })
})
