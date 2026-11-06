// The protocol adapter: MapLibre asks for a range → the bridge is asked for exactly that range; the
// bridge fails → MapLibre gets a REJECTED tile (an error event), never an empty tile. Exercised over
// the real Firenze demo extract with the real `pmtiles` reader.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearRegions, handleRegionRequest, RegionArchiveSource, registerRegion } from './protocol'
import { MAX_RANGE_BYTES, RegionTileError, type RegionAccess } from './regions'

const DEMO = resolve(dirname(fileURLToPath(import.meta.url)), '../../../ui-map/src/androidMain/assets/tenshu_basemap.pmtiles')
// A Uint8Array of its own: a Node Buffer's `.buffer` is a shared pool.
const archive = new Uint8Array(readFileSync(DEMO))

function access(bytes: Uint8Array = archive): RegionAccess & { readRange: ReturnType<typeof vi.fn> } {
  return {
    regionAt: vi.fn(),
    readRange: vi.fn(async (_id: string, offset: number, length: number) => ({
      ok: true as const,
      bytes: bytes.slice(offset, offset + length).buffer,
    })),
  }
}

// z14 tile over central Florence (11.255 E, 43.770 N).
function florenceTile(z = 14) {
  const n = 2 ** z
  const x = Math.floor(((11.255 + 180) / 360) * n)
  const lat = (43.77 * Math.PI) / 180
  const y = Math.floor(((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2) * n)
  return { z, x, y }
}

afterEach(() => clearRegions())

describe('RegionArchiveSource', () => {
  it('asks the bridge for exactly the requested range', async () => {
    const a = access()
    const got = await new RegionArchiveSource('it-toscana', a).getBytes(1000, 500)
    expect(a.readRange).toHaveBeenCalledExactlyOnceWith('it-toscana', 1000, 500)
    expect(new Uint8Array(got.data)).toEqual(new Uint8Array(archive.subarray(1000, 1500)))
  })

  it('splits a read longer than one bridge window, and stops at EOF', async () => {
    const big = new Uint8Array(MAX_RANGE_BYTES * 2 + 10).map((_, i) => i % 251)
    const a = access(big)
    const got = await new RegionArchiveSource('r', a).getBytes(5, MAX_RANGE_BYTES * 3)
    expect(a.readRange.mock.calls).toEqual([
      ['r', 5, MAX_RANGE_BYTES],
      ['r', 5 + MAX_RANGE_BYTES, MAX_RANGE_BYTES],
      ['r', 5 + 2 * MAX_RANGE_BYTES, MAX_RANGE_BYTES],
    ])
    expect(got.data.byteLength).toBe(big.length - 5)
  })

  it('rejects with the bridge kind — never resolves empty bytes', async () => {
    const a = access()
    a.readRange.mockResolvedValueOnce({ ok: false, error: { kind: 'error_map_transport_failed', message: 'HTTP 503' } })
    await expect(new RegionArchiveSource('r', a).getBytes(0, 16)).rejects.toMatchObject({
      name: 'RegionTileError',
      kind: 'error_map_transport_failed',
    })
  })
})

describe('the tenshu-pmtiles protocol', () => {
  it('serves TileJSON from the archive header', async () => {
    const url = registerRegion('demo-firenze', access())
    expect(url).toBe('tenshu-pmtiles://demo-firenze')
    const { data } = (await handleRegionRequest({ url, type: 'json' }, new AbortController())) as {
      data: { tiles: string[]; minzoom: number; maxzoom: number; bounds: number[] }
    }
    expect(data.tiles).toEqual(['tenshu-pmtiles://demo-firenze/{z}/{x}/{y}'])
    expect(data.maxzoom).toBeGreaterThanOrEqual(14)
    expect(data.bounds[0]).toBeGreaterThan(11)
    expect(data.bounds[1]).toBeGreaterThan(43)
  })

  it('serves a real Florence tile through the bridge reader', async () => {
    const a = access()
    const url = registerRegion('demo-firenze', a)
    const { z, x, y } = florenceTile()
    const { data } = await handleRegionRequest({ url: `${url}/${z}/${x}/${y}` }, new AbortController())
    expect((data as Uint8Array).byteLength).toBeGreaterThan(100)
    // Every byte came through ranged reads of the archive — and none past it.
    for (const [, offset, length] of a.readRange.mock.calls as Array<[string, number, number]>) {
      expect(offset + length).toBeLessThanOrEqual(Math.max(archive.length, 16384))
    }
  })

  it('a tile the archive does not have is an empty tile (open sea), not an error', async () => {
    const url = registerRegion('demo-firenze', access())
    const { data } = await handleRegionRequest({ url: `${url}/14/0/0` }, new AbortController())
    expect((data as Uint8Array).byteLength).toBe(0)
  })

  it('a bridge failure becomes a rejected tile — MapLibre reports it, nothing renders blank silently', async () => {
    const a = access()
    const url = registerRegion('demo-firenze', a)
    a.readRange.mockResolvedValue({ ok: false, error: { kind: 'error_map_transport_unconfigured' } })
    const { z, x, y } = florenceTile()
    await expect(handleRegionRequest({ url: `${url}/${z}/${x}/${y}` }, new AbortController())).rejects.toBeInstanceOf(
      RegionTileError,
    )
  })

  it('an unregistered archive is an error, never a fetch of the URL', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    await expect(handleRegionRequest({ url: 'tenshu-pmtiles://nowhere/1/0/0' }, new AbortController())).rejects.toMatchObject({
      kind: 'error_map_unknown_region',
    })
    await expect(handleRegionRequest({ url: 'tenshu-pmtiles://nowhere', type: 'json' }, new AbortController())).rejects.toBeInstanceOf(
      RegionTileError,
    )
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
