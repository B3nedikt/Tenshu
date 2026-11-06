// Gateway coverage for the projected Timeline bridge (web-app-suite.md Wave 2, Timeline run A).
//
// Same harness as `proxyGateway.contacts.test.ts`: every `@tenshu/sdk/*` module is mocked (the real
// ones value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring and its param
// validation — the projection and the writes themselves are pinned by `TimelineWebBridgeTest` and
// `TimelineBookMappingTest` in jsTest.
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

const { ensureRuntime, timeline } = vi.hoisted(() => {
  const ok = { ok: true }
  return {
    ensureRuntime: vi.fn(),
    timeline: {
      moments: vi.fn(async () => []),
      moment: vi.fn(async (_id: string) => ({ ok: true, moment: null })),
      trips: vi.fn(async () => []),
      traces: vi.fn(async () => []),
      feed: vi.fn(async (_zone: string) => ({ ok: true, days: [], trips: [], suggestion: null })),
      observeFeed: vi.fn(
        (_zone: string, _cb: (v: unknown) => void, _onError: (m: string) => void) => () => {},
      ),
      momentDetail: vi.fn(async (_id: string) => ({ ok: true, moment: null })),
      trip: vi.fn(async (_id: string, _zone: string) => ({ ok: true, trip: null })),
      places: vi.fn(async () => ({ ok: true, places: [] })),
      observePlaces: vi.fn((_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}),
      place: vi.fn(async (_id: string) => ({ ok: true, place: null })),
      placeVisitSummary: vi.fn(async (_id: string) => ({ ok: true, summary: null })),
      movementRoute: vi.fn(async (_ids: string[]) => ({ ok: true, points: [] })),
      movementRoutes: vi.fn(async (_legs: string[][]) => ({ ok: true, legs: [] })),
      renamePlace: vi.fn(async (_id: string, _name: string) => ok),
      setPlaceRole: vi.fn(async (_id: string, _role: string) => ok),
      mergePlaces: vi.fn(async (_a: string, _b: string) => ({ ok: true, survivorId: 'a' })),
      unmergePlace: vi.fn(async (_id: string) => ok),
      acceptTripSuggestion: vi.fn(async (_id: string, _zone: string) => ({ ok: true, tripId: 't' })),
      dismissTripSuggestion: vi.fn(async (_id: string) => ok),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: timeline }))

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

describe('proxyGateway — timeline projected bridge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the raw reads, the projected reads and the pure-data writes', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('timeline.')).sort()).toEqual([
      'timeline.acceptTripSuggestion',
      'timeline.dismissTripSuggestion',
      'timeline.feed',
      'timeline.mergePlaces',
      'timeline.moment',
      'timeline.momentDetail',
      'timeline.moments',
      'timeline.movementRoute',
      'timeline.movementRoutes',
      'timeline.place',
      'timeline.placeVisitSummary',
      'timeline.places',
      'timeline.renamePlace',
      'timeline.setPlaceRole',
      'timeline.traces',
      'timeline.trip',
      'timeline.trips',
      'timeline.unmergePlace',
    ])
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('timeline.')).sort()).toEqual([
      'timeline.feed.observe',
      'timeline.places.observe',
    ])
  })

  it('passes the zone through, and an empty zone means the browser’s own', async () => {
    await handler('timeline.feed')({ zone: 'Europe/Berlin' })
    expect(timeline.feed).toHaveBeenCalledWith('Europe/Berlin')
    await handler('timeline.feed')({ zone: '' })
    expect(timeline.feed).toHaveBeenLastCalledWith('')
  })

  it.each([
    ['timeline.feed', {}],
    ['timeline.feed', { zone: 7 }],
    ['timeline.trip', { tripId: 't' }],
    ['timeline.acceptTripSuggestion', { suggestionId: 's' }],
  ])('%s without a string zone is refused before dispatch', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires \{ zone: string \}/)
    expect(timeline.feed).not.toHaveBeenCalled()
    expect(timeline.trip).not.toHaveBeenCalled()
    expect(timeline.acceptTripSuggestion).not.toHaveBeenCalled()
  })

  it.each([
    ['timeline.momentDetail', 'momentId'],
    ['timeline.place', 'placeId'],
    ['timeline.placeVisitSummary', 'placeId'],
    ['timeline.unmergePlace', 'placeId'],
    ['timeline.dismissTripSuggestion', 'suggestionId'],
  ])('%s refuses a blank %s', async (method, key) => {
    await expect(handler(method)({ [key]: '' })).rejects.toThrow(new RegExp(`requires \\{ ${key} \\}`))
  })

  it('placeVisitSummary forwards the place id, and never the full place read', async () => {
    await handler('timeline.placeVisitSummary')({ placeId: 'p' })
    expect(timeline.placeVisitSummary).toHaveBeenCalledWith('p')
    expect(timeline.place).not.toHaveBeenCalled()
  })

  it('forwards each write with its arguments', async () => {
    await handler('timeline.renamePlace')({ placeId: 'p', name: '' })
    expect(timeline.renamePlace).toHaveBeenCalledWith('p', '') // a blank name is the SDK's typed failure
    await handler('timeline.mergePlaces')({ a: 'x', b: 'y' })
    expect(timeline.mergePlaces).toHaveBeenCalledWith('x', 'y')
    await handler('timeline.acceptTripSuggestion')({ suggestionId: 's', zone: 'UTC' })
    expect(timeline.acceptTripSuggestion).toHaveBeenCalledWith('s', 'UTC')
    await handler('timeline.trip')({ tripId: 't', zone: '' })
    expect(timeline.trip).toHaveBeenCalledWith('t', '')
  })

  it('setPlaceRole accepts only home / work / none — never an inferred-looking value', async () => {
    for (const role of ['home', 'work', 'none']) {
      await handler('timeline.setPlaceRole')({ placeId: 'p', role })
    }
    expect(timeline.setPlaceRole).toHaveBeenCalledTimes(3)
    await expect(handler('timeline.setPlaceRole')({ placeId: 'p', role: 'gym' })).rejects.toThrow(
      /requires \{ role: 'home' \| 'work' \| 'none' \}/,
    )
    await expect(handler('timeline.setPlaceRole')({ placeId: 'p' })).rejects.toThrow(/role/)
    expect(timeline.setPlaceRole).toHaveBeenCalledTimes(3)
  })

  it.each([
    ['missing', {}],
    ['empty', { traceIds: [] }],
    ['a blank id', { traceIds: ['a', ''] }],
    ['not strings', { traceIds: [1] }],
  ])('movementRoute refuses %s traceIds', async (_label, params) => {
    await expect(handler('timeline.movementRoute')(params)).rejects.toThrow(/requires \{ traceIds: string\[\] \}/)
    expect(timeline.movementRoute).not.toHaveBeenCalled()
  })

  it('movementRoute forwards the ids in order', async () => {
    await handler('timeline.movementRoute')({ traceIds: ['b', 'a'] })
    expect(timeline.movementRoute).toHaveBeenCalledWith(['b', 'a'])
  })

  it.each([
    ['missing', {}],
    ['empty', { legs: [] }],
    ['an empty leg', { legs: [['a'], []] }],
    ['a blank id', { legs: [['a', '']] }],
    ['a flat list', { legs: ['a'] }],
  ])('movementRoutes refuses %s legs', async (_label, params) => {
    await expect(handler('timeline.movementRoutes')(params)).rejects.toThrow(/requires \{ legs: string\[\]\[\] \}/)
    expect(timeline.movementRoutes).not.toHaveBeenCalled()
  })

  it('movementRoutes forwards the legs in order, in one call', async () => {
    await handler('timeline.movementRoutes')({ legs: [['b'], ['a', 'c']] })
    expect(timeline.movementRoutes.mock.calls).toEqual([[[['b'], ['a', 'c']]]])
  })

  it('the observe subscriptions wire emit and the terminal fail through', () => {
    const emit = vi.fn()
    const fail = vi.fn()
    subscription('timeline.feed.observe')({ zone: 'UTC' }, emit, fail)
    const [zone, cb, onError] = timeline.observeFeed.mock.calls[0]!
    expect(zone).toBe('UTC')
    cb({ ok: true, days: [] })
    expect(emit).toHaveBeenCalledWith({ ok: true, days: [] })
    onError('stream ended')
    expect(fail).toHaveBeenCalledWith('stream ended')

    subscription('timeline.places.observe')(undefined, emit, fail)
    const [placesCb, placesError] = timeline.observePlaces.mock.calls[0]!
    placesCb({ ok: true, places: [] })
    expect(emit).toHaveBeenLastCalledWith({ ok: true, places: [] })
    placesError('x')
    expect(fail).toHaveBeenLastCalledWith('x')
  })

  it('the feed subscription refuses a missing zone instead of grouping in some other zone', () => {
    expect(() => subscription('timeline.feed.observe')({}, vi.fn(), vi.fn())).toThrow(/requires \{ zone: string \}/)
    expect(timeline.observeFeed).not.toHaveBeenCalled()
  })
})
