// The map's data, without a map: legs → coloured segments, the two end pins, the fitted camera.
import { describe, expect, it } from 'vitest'
import { movement, routePoints, visit } from '../test/proxyMock'
import { DEFAULT_ROUTE_COLOR, MODE_COLORS, PLACE_ZOOM, modeColor, placeOverlay, routeLegs, routeOverlay, unwrapLongitudes, type RouteLeg } from './route'

const LABELS = { origin: 'Start', destination: 'End' }
const T = Date.UTC(2026, 5, 3, 9)

function leg(momentId: string, mode: string | null, timestampMs = T): RouteLeg {
  return { momentId, timestampMs, traceIds: [`tr-${momentId}`], mode }
}

describe('routeLegs', () => {
  it('keeps the movements with a trace, oldest first, whatever order they are listed in', () => {
    const later = movement('b', T + 3_600_000, 'transit', 5_000)
    const earlier = movement('a', T, 'walk', 900)
    const stay = visit('v', T + 1_800_000, { id: 'p', name: 'Alfama' })
    expect(routeLegs([later, stay, earlier])).toEqual([
      { momentId: 'a', timestampMs: T, traceIds: ['tr-a'], mode: 'walk' },
      { momentId: 'b', timestampMs: T + 3_600_000, traceIds: ['tr-b'], mode: 'transit' },
    ])
  })

  it('a collapsed ride is ONE leg over all its traces; a movement without a trace is no leg', () => {
    const ride = { ...movement('r', T, 'transit', 12_000), traceIds: ['tr-1', 'tr-2'] }
    const traceless = { ...movement('x', T, 'walk', 100), traceId: null, traceIds: [] }
    expect(routeLegs([ride, traceless])).toEqual([{ momentId: 'r', timestampMs: T, traceIds: ['tr-1', 'tr-2'], mode: 'transit' }])
  })
})

describe('modeColor', () => {
  it('one #RRGGBB per mode, distinct; unknown and a newer peer’s mode keep the native route colour', () => {
    const colours = Object.values(MODE_COLORS)
    for (const c of colours) expect(c).toMatch(/^#[0-9A-F]{6}$/)
    expect(new Set(colours).size).toBe(colours.length)
    expect(modeColor('walk')).toBe(MODE_COLORS.walk)
    expect(modeColor('unknown')).toBe(DEFAULT_ROUTE_COLOR)
    expect(modeColor('hovercraft')).toBe(DEFAULT_ROUTE_COLOR)
    expect(modeColor(null)).toBe(DEFAULT_ROUTE_COLOR)
  })
})

describe('routeOverlay', () => {
  const walk = routePoints([38.711, -9.13], [38.712, -9.131], [38.713, -9.132])
  const train = routePoints([38.713, -9.132], [38.75, -9.2], [38.8, -9.38])

  it('one polyline, one segment per leg, coloured by its mode, in leg order', () => {
    const overlay = routeOverlay([leg('a', 'walk'), leg('b', 'transit')], [walk, train], LABELS)!
    expect(overlay.polylines).toHaveLength(1)
    expect(overlay.polylines[0]!.segments).toEqual([
      { color: MODE_COLORS.walk, points: walk.map((p) => ({ lat: p.latitude, lng: p.longitude })) },
      { color: MODE_COLORS.transit, points: train.map((p) => ({ lat: p.latitude, lng: p.longitude })) },
    ])
  })

  it('pins the first point of the first leg and the last point of the last', () => {
    const overlay = routeOverlay([leg('a', 'walk'), leg('b', 'transit')], [walk, train], LABELS)!
    expect(overlay.pins).toEqual([
      { id: 'origin', position: { lat: 38.711, lng: -9.13 }, label: 'Start' },
      { id: 'destination', position: { lat: 38.8, lng: -9.38 }, label: 'End' },
    ])
  })

  it('fits the camera to every point of the route', () => {
    const overlay = routeOverlay([leg('a', 'walk'), leg('b', 'transit')], [walk, train], LABELS)!
    expect(overlay.camera).toEqual({ kind: 'bounds', points: [...walk, ...train].map((p) => ({ lat: p.latitude, lng: p.longitude })) })
  })

  it('a leg with fewer than two usable points is skipped; the ends come from the legs that draw', () => {
    const lonely = routePoints([40, -8])
    const broken = [...routePoints([91, 0]), { latitude: Number.NaN, longitude: 1, timestampMs: 0 }]
    const overlay = routeOverlay(
      [leg('x', 'walk'), leg('a', 'cycle'), leg('y', 'drive')],
      [lonely, walk, broken],
      LABELS,
    )!
    expect(overlay.polylines[0]!.segments.map((s) => s.color)).toEqual([MODE_COLORS.cycle])
    expect(overlay.pins.map((p) => p.position)).toEqual([
      { lat: 38.711, lng: -9.13 },
      { lat: 38.713, lng: -9.132 },
    ])
  })

  it('no route points at all is NO map — null, never an empty overlay', () => {
    expect(routeOverlay([], [], LABELS)).toBeNull()
    expect(routeOverlay([leg('a', 'walk')], [[]], LABELS)).toBeNull()
    expect(routeOverlay([leg('a', 'walk')], [routePoints([1, 2])], LABELS)).toBeNull()
  })

  it('the colour is the leg’s mode as passed now, never one stored with the points', () => {
    const first = routeOverlay([leg('a', 'walk')], [walk], LABELS)!
    const reclassified = routeOverlay([leg('a', 'cycle')], [walk], LABELS)!
    expect(first.polylines[0]!.segments[0]!.color).toBe(MODE_COLORS.walk)
    expect(reclassified.polylines[0]!.segments[0]!.color).toBe(MODE_COLORS.cycle)
  })
})

describe('the ±180° line', () => {
  // Tokyo (Haneda) → San Francisco: the flight crosses the antimeridian over the Pacific.
  const flight = routePoints([35.55, 139.78], [42.0, 165.0], [45.0, 179.5], [45.5, -178.0], [40.0, -140.0], [37.62, -122.38])

  it('a Tokyo→San Francisco leg draws the short way: no step jumps more than 180°', () => {
    const overlay = routeOverlay([leg('f', 'flight')], [flight], LABELS)!
    const lngs = overlay.polylines[0]!.segments[0]!.points.map((p) => p.lng)
    expect(lngs).toEqual([139.78, 165.0, 179.5, 182.0, 220.0, 237.62])
    for (let i = 1; i < lngs.length; i++) expect(Math.abs(lngs[i]! - lngs[i - 1]!)).toBeLessThanOrEqual(180)
  })

  it('the camera fits the Pacific, not the whole world', () => {
    const overlay = routeOverlay([leg('f', 'flight')], [flight], LABELS)!
    expect(overlay.camera.kind).toBe('bounds')
    const lngs = overlay.camera.kind === 'bounds' ? overlay.camera.points.map((p) => p.lng) : []
    expect(Math.max(...lngs) - Math.min(...lngs)).toBeCloseTo(237.62 - 139.78, 6) // ~98°, not ~302°
    expect(overlay.pins.map((p) => p.position)).toEqual([
      { lat: 35.55, lng: 139.78 },
      { lat: 37.62, lng: 237.62 },
    ])
  })

  it('the unwrapping runs across leg boundaries, so a trip’s legs stay joined', () => {
    const toAirport = routePoints([35.6, 139.7], [35.55, 139.78])
    const overlay = routeOverlay([leg('w', 'walk'), leg('f', 'flight'), leg('d', 'drive')], [toAirport, flight, routePoints([37.62, -122.38], [37.77, -122.42])], LABELS)!
    const segments = overlay.polylines[0]!.segments
    expect(segments.map((s) => s.color)).toEqual([MODE_COLORS.walk, MODE_COLORS.flight, MODE_COLORS.drive])
    const drive = segments[2]!.points.map((p) => p.lng)
    expect(drive[0]).toBeCloseTo(237.62, 9) // continues from the flight's end, not from -122.38
    expect(drive[1]).toBeCloseTo(237.58, 9)
  })

  it('a route that never nears the line is untouched', () => {
    expect(unwrapLongitudes([{ lat: 0, lng: -170 }, { lat: 0, lng: 170 }])).toEqual([{ lat: 0, lng: -170 }, { lat: 0, lng: -190 }])
    const lisbon = [{ lat: 38.7, lng: -9.1 }, { lat: 38.8, lng: -9.4 }]
    expect(unwrapLongitudes(lisbon)).toEqual(lisbon)
  })
})

describe('placeOverlay', () => {
  it('one pin on the place, the camera centred on it at the native hero zoom', () => {
    expect(placeOverlay({ placeId: 'p1', latE7: 481_374_300, lngE7: 115_754_900 })).toEqual({
      pins: [{ id: 'p1', position: { lat: 48.13743, lng: 11.57549 } }],
      camera: { kind: 'center', center: { lat: 48.13743, lng: 11.57549 }, zoom: PLACE_ZOOM },
    })
  })

  it('a place without a coordinate has no map', () => {
    expect(placeOverlay({ placeId: 'p1', latE7: 0, lngE7: 0 })).toBeNull()
  })
})
