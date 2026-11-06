// Gateway coverage for the Maps bridge, `place.*` (web-app-suite.md Wave 3, Maps run A).
//
// Same harness as `proxyGateway.contacts.test.ts`: every `@tenshu/sdk/*` module is mocked (the real
// ones value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring and its param
// validation — the projection and the writes themselves are pinned by `MapsWebBridgeTest` in jsTest.
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

const { ensureRuntime, maps } = vi.hoisted(() => ({
  ensureRuntime: vi.fn(),
  maps: {
    places: vi.fn(async () => ({ ok: true, places: [] })),
    observePlaces: vi.fn((_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}),
    place: vi.fn(async (_id: string) => ({ ok: true, place: null })),
    renamePlace: vi.fn(async (_id: string, _name: string) => ({ ok: true })),
  },
}))

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/maps', () => ({ Maps: maps }))

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
vi.mock('@tenshu/sdk/photos', () => ({ Photos: {}, MAX_FEED_PAGE_SIZE: 500 }))

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

describe('proxyGateway — place (Maps) bridge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the list, the detail and rename — no saved / note / list method', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('place.')).sort()).toEqual([
      'place.get',
      'place.list',
      'place.rename',
    ])
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('place.')).sort()).toEqual([
      'place.list.observe',
    ])
  })

  it('only the Maps app may call place.* — exactly the four methods (Maps run B)', () => {
    for (const app of HOSTED_APPS) {
      const place = app.methods.filter((m) => m.startsWith('place.'))
      expect(place, app.id).toEqual(app.id === 'maps' ? ['place.list', 'place.list.observe', 'place.get', 'place.rename'] : [])
    }
  })

  it('the list and the detail dispatch to Maps, the detail with its id', async () => {
    await expect(handler('place.list')(undefined)).resolves.toEqual({ ok: true, places: [] })
    expect(maps.places).toHaveBeenCalledTimes(1)
    await expect(handler('place.get')({ placeId: 'p' })).resolves.toEqual({ ok: true, place: null })
    expect(maps.place).toHaveBeenCalledWith('p')
  })

  it.each([
    ['place.get', {}],
    ['place.get', { placeId: '' }],
    ['place.rename', { name: 'x' }],
    ['place.rename', { placeId: '', name: 'x' }],
  ])('%s refuses a missing or blank placeId before dispatch', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires \{ placeId \}/)
    expect(maps.place).not.toHaveBeenCalled()
    expect(maps.renamePlace).not.toHaveBeenCalled()
  })

  it('rename forwards a blank name (the SDK’s typed failure) but refuses a missing one', async () => {
    await handler('place.rename')({ placeId: 'p', name: '' })
    expect(maps.renamePlace).toHaveBeenCalledWith('p', '')
    await expect(handler('place.rename')({ placeId: 'p' })).rejects.toThrow(/requires \{ name: string \}/)
    await expect(handler('place.rename')({ placeId: 'p', name: 3 })).rejects.toThrow(/name: string/)
    expect(maps.renamePlace).toHaveBeenCalledTimes(1)
  })

  it('the list subscription wires emit and the terminal fail through, and returns the unsubscribe', () => {
    const emit = vi.fn()
    const fail = vi.fn()
    const stop = vi.fn()
    maps.observePlaces.mockReturnValueOnce(stop)
    const unsubscribe = subscription('place.list.observe')(undefined, emit, fail)
    const [cb, onError] = maps.observePlaces.mock.calls[0]!
    cb({ ok: true, places: [] })
    expect(emit).toHaveBeenCalledWith({ ok: true, places: [] })
    onError('stream ended')
    expect(fail).toHaveBeenCalledWith('stream ended')
    unsubscribe()
    expect(stop).toHaveBeenCalledTimes(1)
  })
})
