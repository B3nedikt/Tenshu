// Gateway coverage for the Mia surface, `mia.*` (web-app-suite Wave 4, Mia runs A and B).
//
// Same harness as `proxyGateway.fit.test.ts`: every `@tenshu/sdk/*` module is mocked (the real ones
// value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring, its param
// validation, and that only the Mia app may call it (threat model A39). The planner, verdicts and history are pinned by
// `MiaWebContractTest` in jsTest.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { isMethodPermitted } from '@tenshu/sdk/bridge'

type Handler = (params: unknown) => unknown
type Subscription = (
  params: unknown,
  emit: (value: unknown) => void,
  fail: (message: string) => void,
) => () => void
let captured: Record<string, Handler> = {}
let subscriptions: Record<string, Subscription> = {}

vi.mock('@tenshu/sdk/bridge', async (importOriginal) => ({
  // The real allowlist matcher: the "no app may call mia.*" pin must use the gateway's own rule.
  isMethodPermitted: (await importOriginal<typeof import('@tenshu/sdk/bridge')>()).isMethodPermitted,
  serveProxyGateway: (options: {
    handlers: Record<string, Handler>
    subscriptions?: Record<string, Subscription>
  }) => {
    captured = options.handlers
    subscriptions = options.subscriptions ?? {}
    return { dispose: () => {} }
  },
}))

const { ensureRuntime, mia, MiaMethod } = vi.hoisted(() => {
  const observer = (_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}
  return {
    ensureRuntime: vi.fn(),
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
    mia: {
      ask: vi.fn(async (_u: string, _z: string) => ({ ok: true, conversationId: 'c', turn: {} })),
      history: vi.fn(async () => ({ ok: true, conversations: [], replying: false })),
      observeHistory: vi.fn(observer),
      turnsPage: vi.fn(async (_id: string, _c: string | null, _n: number) => ({ ok: true, turns: [], nextCursor: null })),
      grants: vi.fn(async () => ({ ok: true, grants: [] })),
      observeGrants: vi.fn(observer),
      setGranted: vi.fn(async (_s: string, _g: boolean) => ({ ok: true, grants: [] })),
      setWritable: vi.fn(async (_s: string, _g: boolean) => ({ ok: true, grants: [] })),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/mia', () => ({
  Mia: mia,
  MiaMethod,
  MAX_UTTERANCE_CHARS: 500,
  MAX_TURN_PAGE_SIZE: 100,
  MIA_SOURCES: ['FILES', 'TIMELINE', 'FITNESS', 'PLACES', 'DEVICES', 'ROUTINES', 'LABELS', 'VISUAL_SEARCH', 'CALENDAR'],
}))
vi.mock('@tenshu/sdk/home', () => ({ Home: {}, MAX_ACTIVITY_PAGE_SIZE: 200 }))
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
import { HOSTED_APPS } from '../apps/registry'

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

const ALL_MIA = Object.values(MiaMethod)

describe('proxyGateway — mia', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the surface: the grant switches, but no confirm, no delete, no voice', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('mia.')).sort()).toEqual(
      ['mia.ask', 'mia.grants', 'mia.grants.setGranted', 'mia.grants.setWritable', 'mia.history', 'mia.turnsPage'].sort(),
    )
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('mia.')).sort()).toEqual([
      'mia.grants.observe',
      'mia.history.observe',
    ])
    expect([...Object.keys(captured), ...Object.keys(subscriptions)].filter((m) => m.startsWith('mia.')).sort()).toEqual(
      [...ALL_MIA].sort(),
    )
  })

  // ⚠⚠ Threat model A39: an origin allowed `mia.ask` reads, through questions, every source the
  // grants allow. So ONLY the Mia app may name `mia.*` — every other hosted app's allowlist is held to
  // none, by the gateway's own matcher (a namespace wildcard is caught as well as an exact name).
  it('only the Mia app may call mia.*', () => {
    expect(HOSTED_APPS.map((a) => a.id)).toContain('mia')
    for (const app of HOSTED_APPS.filter((a) => a.id !== 'mia')) {
      for (const method of ALL_MIA) {
        expect(isMethodPermitted(app.methods, method), `${app.id} may call ${method}`).toBe(false)
      }
      expect(app.methods.filter((m) => m.startsWith('mia.') || m === 'mia.*'), app.id).toEqual([])
    }
  })

  // And Mia names them by exact name — no wildcard, so a `mia.*` method added later (a confirm, a
  // delete) is not reachable by default. Today the app calls every one.
  it('the Mia app names exactly the methods it calls, never a wildcard', () => {
    const app = HOSTED_APPS.find((a) => a.id === 'mia')!
    expect(app.methods.filter((m) => m.startsWith('mia.')).sort()).toEqual([...ALL_MIA].sort())
    expect(app.methods).not.toContain('mia.*')
    expect(app.methods).not.toContain('*')
  })

  it.each<[string, unknown, keyof typeof mia, unknown[]]>([
    ['mia.ask', { utterance: 'what is on my calendar tomorrow', zone: '' }, 'ask', ['what is on my calendar tomorrow', '']],
    ['mia.ask', { utterance: 'workouts last week', zone: 'Asia/Tokyo' }, 'ask', ['workouts last week', 'Asia/Tokyo']],
    // A blank one passes through: it is the SDK's typed `error_mia_empty_utterance`, not a throw.
    ['mia.ask', { utterance: '   ', zone: '' }, 'ask', ['   ', '']],
    ['mia.history', undefined, 'history', []],
    ['mia.turnsPage', { conversationId: 'c1', pageSize: 20 }, 'turnsPage', ['c1', null, 20]],
    ['mia.turnsPage', { conversationId: 'c1', cursor: 'm1.5', pageSize: 100 }, 'turnsPage', ['c1', 'm1.5', 100]],
    ['mia.grants', undefined, 'grants', []],
    ['mia.grants.setGranted', { source: 'FITNESS', granted: false }, 'setGranted', ['FITNESS', false]],
    ['mia.grants.setGranted', { source: 'CALENDAR', granted: true }, 'setGranted', ['CALENDAR', true]],
    // A source with no write tier passes through: it is the SDK's typed `error_mia_no_write_grant`.
    ['mia.grants.setWritable', { source: 'FILES', granted: true }, 'setWritable', ['FILES', true]],
    ['mia.grants.setWritable', { source: 'CALENDAR', granted: false }, 'setWritable', ['CALENDAR', false]],
  ])('%s %j dispatches to Mia.%s', async (method, params, fn, args) => {
    await handler(method)(params)
    expect(mia[fn]).toHaveBeenCalledWith(...args)
  })

  it('bounds the utterance, inclusively, on its trimmed length', async () => {
    await handler('mia.ask')({ utterance: 'a'.repeat(500), zone: '' })
    await handler('mia.ask')({ utterance: `  ${'a'.repeat(500)}  `, zone: '' })
    expect(mia.ask).toHaveBeenCalledTimes(2)
    await expect(handler('mia.ask')({ utterance: 'a'.repeat(501), zone: '' })).rejects.toThrow(/at most 500/)
    expect(mia.ask).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['mia.ask', {}],
    ['mia.ask', { utterance: 7, zone: '' }],
    ['mia.ask', { utterance: 'hi' }],
    ['mia.ask', { utterance: 'hi', zone: null }],
    ['mia.turnsPage', { pageSize: 20 }],
    ['mia.turnsPage', { conversationId: '', pageSize: 20 }],
    ['mia.turnsPage', { conversationId: 'c1' }],
    ['mia.turnsPage', { conversationId: 'c1', pageSize: 0 }],
    ['mia.turnsPage', { conversationId: 'c1', pageSize: 101 }],
    ['mia.turnsPage', { conversationId: 'c1', pageSize: 2.5 }],
    ['mia.turnsPage', { conversationId: 'c1', cursor: 7, pageSize: 20 }],
  ])('%s refuses %j before the runtime', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    expect(mia.ask).not.toHaveBeenCalled()
    expect(mia.turnsPage).not.toHaveBeenCalled()
  })

  it.each([
    ['mia.grants.setGranted', {}],
    ['mia.grants.setGranted', { source: 'FITNESS' }],
    // ⚠ A string 'false' must never be read as a truthy switch.
    ['mia.grants.setGranted', { source: 'FITNESS', granted: 'false' }],
    ['mia.grants.setGranted', { source: 'FITNESS', granted: 0 }],
    ['mia.grants.setGranted', { source: 'fitness', granted: false }],
    ['mia.grants.setGranted', { source: 'WEATHER', granted: false }],
    ['mia.grants.setWritable', { source: 7, granted: true }],
    ['mia.grants.setWritable', { source: 'CALENDAR', granted: null }],
  ])('%s refuses %j before the runtime, writing nothing', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    expect(mia.setGranted).not.toHaveBeenCalled()
    expect(mia.setWritable).not.toHaveBeenCalled()
  })

  it('passes a refusal through as a value, untouched', async () => {
    const refused = { ok: false, error: { kind: 'error_mia_invalid_zone', message: 'Mars/Olympus' } }
    mia.ask.mockResolvedValueOnce(refused as never)
    expect(await handler('mia.ask')({ utterance: 'hi', zone: 'Mars/Olympus' })).toBe(refused)
  })

  it('wires both pushes with their terminal fail', () => {
    const emit = vi.fn()
    const fail = vi.fn()
    subscription('mia.history.observe')(undefined, emit, fail)
    subscription('mia.grants.observe')(undefined, emit, fail)
    expect(mia.observeHistory).toHaveBeenCalledWith(expect.any(Function), fail)
    expect(mia.observeGrants).toHaveBeenCalledWith(expect.any(Function), fail)
    mia.observeHistory.mock.calls[0]![0]({ ok: true })
    expect(emit).toHaveBeenCalledWith({ ok: true })
  })
})
