// `<TenshuMap>`'s states. MapLibre itself is mocked: jsdom has no WebGL, and what is under test is
// that every way of not drawing a map SAYS so.
import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RegionAccess } from './regions'

const { webgl, MapCtor, fits, maps } = vi.hoisted(() => ({
  webgl: { ok: true },
  MapCtor: vi.fn(),
  fits: vi.fn(),
  maps: [] as Array<{ handlers: Record<string, (e: unknown) => void>; center: { lat: number; lng: number }; zoom: number }>,
}))
vi.mock('./webgl', () => ({ supportsWebGL: () => webgl.ok }))
vi.mock('maplibre-gl', () => {
  class FakeMap {
    handlers: Record<string, (e: unknown) => void> = {}
    center = { lat: 0, lng: 0 }
    zoom = 0
    constructor(options: unknown) {
      MapCtor(options)
      maps.push(this)
    }
    on(event: string, a: unknown, b?: unknown) {
      this.handlers[event] = (typeof a === 'function' ? a : b) as (e: unknown) => void
    }
    getCenter() {
      return this.center
    }
    getZoom() {
      return this.zoom
    }
    fitBounds(bounds: unknown, options: unknown) {
      fits('box', bounds, options)
    }
    jumpTo(options: unknown) {
      fits('center', options)
    }
    remove() {}
    getSource() {
      return undefined
    }
  }
  return { default: { Map: FakeMap, addProtocol: vi.fn() } }
})
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}))

const { TenshuMap } = await import('./TenshuMap')

const camera = { kind: 'center' as const, center: { lat: 43.77, lng: 11.25 }, zoom: 14 }

function regions(regionAt: RegionAccess['regionAt']): RegionAccess {
  return { regionAt, readRange: vi.fn() }
}

describe('<TenshuMap>', () => {
  beforeEach(() => {
    webgl.ok = true
    MapCtor.mockClear()
    fits.mockClear()
    maps.length = 0
  })

  it('without WebGL: an explicit "Map unavailable", and MapLibre is never constructed', async () => {
    webgl.ok = false
    const regionAt = vi.fn()
    render(<TenshuMap regions={regions(regionAt)} camera={camera} />)
    expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Map unavailable')
    expect(screen.getByTestId('map-unavailable')).toHaveTextContent(/WebGL/)
    expect(regionAt).not.toHaveBeenCalled()
    expect(MapCtor).not.toHaveBeenCalled()
  })

  it('an unconfigured tile transport reads as "not set up", not as a blank map', async () => {
    const regionAt = vi.fn(async () => ({ ok: false as const, error: { kind: 'error_map_transport_unconfigured' } }))
    render(<TenshuMap regions={regions(regionAt)} camera={camera} />)
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Map tiles are not set up for this server.'))
    expect(regionAt).toHaveBeenCalledWith(43.77, 11.25)
    expect(MapCtor).not.toHaveBeenCalled()
  })

  it('a covering region builds the map over the region protocol, credited to OpenStreetMap', async () => {
    const regionAt = vi.fn(async () => ({
      ok: true as const,
      region: { regionId: 'it-toscana', name: 'Toscana', byteSize: 1, bounds: [9.6, 42.2, 12.4, 44.5] as const },
    }))
    render(<TenshuMap regions={regions(regionAt)} camera={{ ...camera, zoom: 19 }} theme="dark" />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    const options = MapCtor.mock.calls[0]![0] as {
      style: { sources: Record<string, { url: string }> }
      zoom: number
      attributionControl: { customAttribution: string }
    }
    expect(options.style.sources.protomaps!.url).toBe('tenshu-pmtiles://it-toscana')
    expect(options.zoom).toBe(15) // clamped to BASEMAP_MAX_ZOOM
    expect(options.attributionControl.customAttribution).toBe('© OpenStreetMap contributors')
  })

  it('no region under the camera: the background map, and a notice saying so', async () => {
    const regionAt = vi.fn(async () => ({ ok: true as const, region: null }))
    render(<TenshuMap regions={regions(regionAt)} camera={camera} />)
    await waitFor(() => expect(screen.getByText('No map data for this area.')).toBeInTheDocument())
    const options = MapCtor.mock.calls[0]![0] as { style: { sources: object } }
    expect(options.style.sources).toEqual({})
  })

  it('a bounds camera with no points yet: no ±Infinity bounds, and the no-region state', async () => {
    const regionAt = vi.fn()
    render(<TenshuMap regions={regions(regionAt)} camera={{ kind: 'bounds', points: [] }} />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    const options = MapCtor.mock.calls[0]![0] as { bounds?: unknown; center?: unknown; zoom?: unknown }
    expect(options.bounds).toBeUndefined()
    expect(options.center).toBeUndefined() // MapLibre's default view
    expect(options.zoom).toBeUndefined()
    expect(screen.getByText('No map data for this area.')).toBeInTheDocument()
    expect(regionAt).not.toHaveBeenCalled()
  })

  it('a user pan into another region resolves that region, and the rebuilt map keeps the view', async () => {
    const regionAt = vi.fn(async (lat: number) => ({
      ok: true as const,
      region:
        lat > 47
          ? { regionId: 'de-bayern', name: 'Bayern', byteSize: 1, bounds: [8.9, 47.2, 13.9, 50.6] as const }
          : { regionId: 'it-toscana', name: 'Toscana', byteSize: 1, bounds: [9.6, 42.2, 12.4, 44.5] as const },
    }))
    render(<TenshuMap regions={regions(regionAt)} camera={camera} />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())

    // A programmatic move (the prop camera) is not a pan.
    act(() => maps[0]!.handlers.moveend!({}))
    expect(regionAt).toHaveBeenCalledOnce()

    act(() => {
      maps[0]!.center = { lat: 48.137, lng: 11.575 }
      maps[0]!.zoom = 12
      maps[0]!.handlers.moveend!({ originalEvent: {} })
    })
    await waitFor(() => expect(MapCtor).toHaveBeenCalledTimes(2))
    expect(regionAt).toHaveBeenLastCalledWith(48.137, 11.575)
    const rebuilt = MapCtor.mock.calls[1]![0] as { style: { sources: Record<string, { url: string }> }; center: number[]; zoom: number }
    expect(rebuilt.style.sources.protomaps!.url).toBe('tenshu-pmtiles://de-bayern')
    expect(rebuilt.center).toEqual([11.575, 48.137])
    expect(rebuilt.zoom).toBe(12)
  })

  // ── an anchored bounds camera: the anchor picks the region, not the box's middle ──

  const BERLIN = { lat: 52.52, lng: 13.405 }
  const POTSDAM = { lat: 52.39, lng: 13.065 }
  const NEW_YORK = { lat: 40.713, lng: -74.006 }
  const MUNICH = { lat: 48.137, lng: 11.575 }
  const HAMBURG = { lat: 53.551, lng: 9.994 }
  const BERLIN_ROW = { regionId: 'de-berlin', name: 'Berlin', byteSize: 1, bounds: [11.2, 51.3, 14.8, 53.6] as const }
  const BAYERN_ROW = { regionId: 'de-bayern', name: 'Bayern', byteSize: 1, bounds: [8.9, 47.2, 13.9, 50.6] as const }

  // A catalog that, like the real one, covers land and nothing in the Atlantic.
  function catalog() {
    return vi.fn(async (lat: number, lng: number) => {
      const rows = [BERLIN_ROW, BAYERN_ROW]
      const row = rows.find((r) => lng >= r.bounds[0] && lng <= r.bounds[2] && lat >= r.bounds[1] && lat <= r.bounds[3])
      return { ok: true as const, region: row ?? null }
    })
  }

  it('without an anchor, Berlin + New York looks up the Atlantic midpoint — the bug the anchor fixes', async () => {
    const regionAt = catalog()
    render(<TenshuMap regions={regions(regionAt)} camera={{ kind: 'bounds', points: [BERLIN, NEW_YORK] }} />)
    await waitFor(() => expect(screen.getByText('No map data for this area.')).toBeInTheDocument())
    const [lat, lng] = regionAt.mock.calls[0]!
    expect(lat).toBeCloseTo((BERLIN.lat + NEW_YORK.lat) / 2)
    expect(lng).toBeCloseTo((BERLIN.lng + NEW_YORK.lng) / 2)
  })

  it("Berlin + New York anchored on Berlin: Berlin's region, fitted to the places inside it only", async () => {
    const regionAt = catalog()
    render(
      <TenshuMap
        regions={regions(regionAt)}
        camera={{ kind: 'bounds', points: [BERLIN, POTSDAM, NEW_YORK], anchor: BERLIN }}
        pins={[{ id: 'b', position: BERLIN }, { id: 'p', position: POTSDAM }, { id: 'ny', position: NEW_YORK }]}
      />,
    )
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    expect(regionAt).toHaveBeenCalledWith(BERLIN.lat, BERLIN.lng)
    const options = MapCtor.mock.calls[0]![0] as { style: { sources: Record<string, { url: string }> }; bounds: number[] }
    expect(options.style.sources.protomaps!.url).toBe('tenshu-pmtiles://de-berlin')
    // Berlin and Potsdam; New York is outside the region box, so it is not fitted (its pin stays).
    expect(options.bounds).toEqual([POTSDAM.lng, POTSDAM.lat, BERLIN.lng, BERLIN.lat])
    expect(screen.queryByText('No map data for this area.')).not.toBeInTheDocument()
  })

  it("Munich + Hamburg anchored on Munich: Bayern's tiles, and the anchor alone opens at a city zoom", async () => {
    const regionAt = catalog()
    render(<TenshuMap regions={regions(regionAt)} camera={{ kind: 'bounds', points: [MUNICH, HAMBURG], anchor: MUNICH }} />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    expect(regionAt).toHaveBeenCalledWith(MUNICH.lat, MUNICH.lng)
    const options = MapCtor.mock.calls[0]![0] as { style: { sources: Record<string, { url: string }> }; center: number[]; zoom: number; bounds?: unknown }
    expect(options.style.sources.protomaps!.url).toBe('tenshu-pmtiles://de-bayern')
    expect(options.bounds).toBeUndefined()
    expect(options.center).toEqual([MUNICH.lng, MUNICH.lat])
    expect(options.zoom).toBe(12)
  })

  it('±179°: the box crosses the antimeridian (2° wide), never the whole world', async () => {
    const regionAt = vi.fn(async () => ({ ok: true as const, region: null }))
    const east = { lat: -17.8, lng: 179 }
    const west = { lat: -16.5, lng: -179 }
    render(<TenshuMap regions={regions(regionAt)} camera={{ kind: 'bounds', points: [east, west] }} />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    const [, lng] = regionAt.mock.calls[0]! as unknown as [number, number]
    expect(Math.abs(lng)).toBeCloseTo(180) // the middle is at ±180, not at 0° (Greenwich)
    const options = MapCtor.mock.calls[0]![0] as { bounds: number[] }
    expect(options.bounds).toEqual([179, -17.8, 181, -16.5])
  })

  it("a pan is never undone by the anchored camera's region box arriving", async () => {
    const regionAt = catalog()
    render(<TenshuMap regions={regions(regionAt)} camera={{ kind: 'bounds', points: [BERLIN, POTSDAM], anchor: BERLIN }} />)
    await waitFor(() => expect(MapCtor).toHaveBeenCalledOnce())
    fits.mockClear()
    act(() => {
      maps[0]!.center = MUNICH
      maps[0]!.zoom = 9
      maps[0]!.handlers.moveend!({ originalEvent: {} })
    })
    await waitFor(() => expect(MapCtor).toHaveBeenCalledTimes(2))
    expect(fits).not.toHaveBeenCalled()
    const rebuilt = MapCtor.mock.calls[1]![0] as { center: number[]; zoom: number }
    expect(rebuilt.center).toEqual([MUNICH.lng, MUNICH.lat])
  })
})
