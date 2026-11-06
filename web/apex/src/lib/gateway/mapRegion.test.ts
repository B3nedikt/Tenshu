// `mapRegion.*` (web-app-suite.md W-MAP): the gateway's param validation, its pass-through to
// `MapRegion`, and — through the REAL bridge — that a range's bytes are transferred, not cloned.
// The ops themselves are pinned by `MapRegionWebContractTest` in jsTest.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { serveProxyGateway, type ProxyGateway } from '@tenshu/sdk/bridge'

const { mapRegion } = vi.hoisted(() => ({
  mapRegion: {
    catalog: vi.fn(async () => ({ ok: true, buildDate: '2026-09', regions: [] })),
    regionAt: vi.fn(async (_lat: number, _lng: number) => ({ ok: true, region: null })),
    regionsIn: vi.fn(async (_a: number, _b: number, _c: number, _d: number) => ({ ok: true, regions: [] })),
    streamRange: vi.fn(async (_id: string, _offset: number, length: number) => ({
      ok: true,
      bytes: new Uint8Array(length).fill(7).buffer,
    })),
  },
}))
vi.mock('@tenshu/sdk/mapRegion', () => ({ MapRegion: mapRegion, MAX_REGION_RANGE_BYTES: 1 << 20 }))

const { mapRegionGateway } = await import('./mapRegion')

const handler = (method: string) => {
  const h = mapRegionGateway.handlers[method]
  if (!h) throw new Error(`no handler for ${method}`)
  return h
}

describe('mapRegion gateway', () => {
  beforeEach(() => vi.clearAllMocks())

  it('serves exactly the read surface: no download, delete, progress or prefetch', () => {
    expect(Object.keys(mapRegionGateway.handlers).sort()).toEqual([
      'mapRegion.catalog',
      'mapRegion.regionAt',
      'mapRegion.regionsIn',
      'mapRegion.streamRange',
    ])
    expect(mapRegionGateway.subscriptions).toBeUndefined()
  })

  it('passes coordinates through as numbers', async () => {
    await handler('mapRegion.regionAt')({ latitude: 43.77, longitude: 11.25 })
    expect(mapRegion.regionAt).toHaveBeenCalledWith(43.77, 11.25)
    await handler('mapRegion.regionsIn')({ minLng: 11.2, minLat: 43.7, maxLng: 11.3, maxLat: 43.8 })
    expect(mapRegion.regionsIn).toHaveBeenCalledWith(11.2, 43.7, 11.3, 43.8)
  })

  it.each([
    ['mapRegion.regionAt', { latitude: '43' , longitude: 11 }],
    ['mapRegion.regionAt', { latitude: Number.NaN, longitude: 11 }],
    ['mapRegion.regionsIn', { minLng: 1, minLat: 2, maxLng: 3 }],
    ['mapRegion.streamRange', { regionId: '', offset: 0, length: 16 }],
    ['mapRegion.streamRange', { regionId: 'it', offset: -1, length: 16 }],
    ['mapRegion.streamRange', { regionId: 'it', offset: 0.5, length: 16 }],
    ['mapRegion.streamRange', { regionId: 'it', offset: 0, length: 0 }],
    ['mapRegion.streamRange', { regionId: 'it', offset: 0, length: (1 << 20) + 1 }],
  ])('%s refuses %j before the runtime', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    expect(mapRegion.regionAt).not.toHaveBeenCalled()
    expect(mapRegion.regionsIn).not.toHaveBeenCalled()
    expect(mapRegion.streamRange).not.toHaveBeenCalled()
  })

  it('passes a failure through as a value, never as bytes', async () => {
    const failure = { ok: false, error: { kind: 'error_map_unknown_region', message: 'no' } }
    mapRegion.streamRange.mockResolvedValueOnce(failure as never)
    expect(await handler('mapRegion.streamRange')({ regionId: 'x', offset: 0, length: 16 })).toBe(failure)
  })
})

// The real bridge: what the app frame receives, and how.
const MAPS = 'https://maps.tenshu.app'
const REQUEST = 'tenshu/bridge/req'
const RESPONSE = 'tenshu/bridge/res'

function fire(data: unknown, origin: string, source: { postMessage: ReturnType<typeof vi.fn> }) {
  const ev = new MessageEvent('message', { data, origin })
  Object.defineProperty(ev, 'source', { value: source, configurable: true })
  window.dispatchEvent(ev)
}

describe('mapRegion.streamRange over the bridge', () => {
  let gateway: ProxyGateway | null = null
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => {
    gateway?.dispose()
    gateway = null
  })

  it('posts the range result with its ArrayBuffer in the transfer list', async () => {
    gateway = serveProxyGateway({
      permissions: { [MAPS]: ['mapRegion.*'] },
      handlers: { ...mapRegionGateway.handlers },
    })
    const source = { postMessage: vi.fn() }
    fire({ kind: REQUEST, id: 'r1', method: 'mapRegion.streamRange', params: { regionId: 'it', offset: 0, length: 32 } }, MAPS, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())

    const [envelope, options] = source.postMessage.mock.calls[0] as [
      { kind: string; ok: boolean; result: { ok: boolean; bytes: ArrayBuffer } },
      { targetOrigin: string; transfer: Transferable[] },
    ]
    expect(envelope.kind).toBe(RESPONSE)
    expect(envelope.ok).toBe(true)
    // The wrapper never crosses: the app gets the plain result.
    expect(envelope.result.ok).toBe(true)
    expect(envelope.result.bytes.byteLength).toBe(32)
    expect(options.targetOrigin).toBe(MAPS)
    expect(options.transfer).toEqual([envelope.result.bytes])
  })

  it('a refused origin gets no bytes and no transfer', async () => {
    gateway = serveProxyGateway({ permissions: { [MAPS]: ['contact.*'] }, handlers: { ...mapRegionGateway.handlers } })
    const source = { postMessage: vi.fn() }
    fire({ kind: REQUEST, id: 'r2', method: 'mapRegion.streamRange', params: { regionId: 'it', offset: 0, length: 32 } }, MAPS, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())
    expect(source.postMessage.mock.calls[0]?.[1]).toBe(MAPS)
    expect(mapRegion.streamRange).not.toHaveBeenCalled()
  })
})
