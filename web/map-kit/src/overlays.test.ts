import { describe, expect, it } from 'vitest'
import {
  ANCHOR_ZOOM,
  cameraCentre,
  fitTarget,
  inBox,
  overlayLayers,
  pinsSource,
  PINS_SOURCE,
  pointsBox,
  polylinesSource,
  POLYLINES_SOURCE,
  regionBox,
} from './overlays'

const A = { lat: 43.7731, lng: 11.256 }
const B = { lat: 43.768, lng: 11.2531 }
const C = { lat: 43.7652, lng: 11.25 }

type FC = { features: Array<{ properties: Record<string, unknown>; geometry: { type: string; coordinates: unknown } }> }

describe('overlays', () => {
  it('a pin is a Point feature at [lng, lat] carrying its id and label', () => {
    const data = pinsSource([{ id: 'duomo', position: A, label: 'Duomo' }, { id: 'x', position: B }]).data as FC
    expect(data.features.map((f) => f.properties)).toEqual([{ id: 'duomo', label: 'Duomo' }, { id: 'x', label: '' }])
    expect(data.features[0]!.geometry).toEqual({ type: 'Point', coordinates: [11.256, 43.7731] })
  })

  it('each polyline segment is its own LineString with its own colour', () => {
    const data = polylinesSource([
      { id: 'trip', segments: [{ points: [A, B], color: '#2563EB' }, { points: [B, C], color: '#DC2626' }, { points: [C], color: '#000000' }] },
    ]).data as FC
    expect(data.features.map((f) => f.properties)).toEqual([
      { polyline: 'trip', segment: 0, color: '#2563EB' },
      { polyline: 'trip', segment: 1, color: '#DC2626' },
    ])
    expect(data.features[1]!.geometry.coordinates).toEqual([[11.2531, 43.768], [11.25, 43.7652]])
  })

  it('refuses a colour that is not #RRGGBB', () => {
    expect(() => polylinesSource([{ id: 't', segments: [{ points: [A, B], color: 'red' }] }])).toThrow(/#RRGGBB/)
  })

  it.each(['light', 'dark'] as const)('%s layers: lines paint their feature colour, pins and labels sit on the pin source', (theme) => {
    const layers = overlayLayers(theme)
    expect(layers.map((l) => [l.id, 'source' in l ? l.source : null])).toEqual([
      ['tenshu-polylines', POLYLINES_SOURCE],
      ['tenshu-pins', PINS_SOURCE],
      ['tenshu-pin-labels', PINS_SOURCE],
    ])
    expect((layers[0] as { paint: Record<string, unknown> }).paint['line-color']).toEqual(['get', 'color'])
  })

  it('the camera centre picks the region: the centre itself, or the middle of the bounds', () => {
    expect(cameraCentre({ kind: 'center', center: A, zoom: 14 })).toEqual(A)
    expect(cameraCentre({ kind: 'bounds', points: [A, C] })).toEqual({ lat: (A.lat + C.lat) / 2, lng: (A.lng + C.lng) / 2 })
    expect(cameraCentre({ kind: 'bounds', points: [] })).toBeNull()
  })

  it('the box skips the widest empty gap: ±179° is 2° across the antimeridian, not 358° across the world', () => {
    expect(pointsBox([{ lat: 1, lng: 179 }, { lat: 2, lng: -179 }])).toEqual([179, 1, 181, 2])
    expect(pointsBox([{ lat: 1, lng: -179 }, { lat: 2, lng: 179 }])).toEqual([179, 1, 181, 2])
    expect(cameraCentre({ kind: 'bounds', points: [{ lat: 0, lng: 179 }, { lat: 0, lng: -179 }] })!.lng).toBe(-180)
    // An ordinary box is unchanged.
    expect(pointsBox([A, C])).toEqual([C.lng, C.lat, A.lng, A.lat])
    expect(pointsBox([])).toBeNull()
    expect(inBox({ lat: 1.5, lng: -179.5 }, [179, 1, 181, 2])).toBe(true)
    expect(inBox({ lat: 1.5, lng: 0 }, [179, 1, 181, 2])).toBe(false)
    expect(regionBox([170, -20, -170, -10])).toEqual([170, -20, 190, -10])
  })

  it('an anchor picks the region and fits only what is inside it, else what is near it', () => {
    const berlin = { lat: 52.52, lng: 13.405 }
    const potsdam = { lat: 52.39, lng: 13.065 }
    const newYork = { lat: 40.713, lng: -74.006 }
    const camera = { kind: 'bounds' as const, points: [berlin, potsdam, newYork], anchor: berlin }
    expect(cameraCentre(camera)).toEqual(berlin)
    expect(fitTarget(camera, [11.2, 51.3, 14.8, 53.6])).toEqual({ kind: 'box', box: [13.065, 52.39, 13.405, 52.52], padding: 32 })
    // No region box (no region, or a row without one): the places near the anchor.
    expect(fitTarget(camera, null)).toEqual({ kind: 'box', box: [13.065, 52.39, 13.405, 52.52], padding: 32 })
    expect(fitTarget({ ...camera, points: [berlin, newYork] }, null)).toEqual({ kind: 'center', center: berlin, zoom: ANCHOR_ZOOM })
    // Un-anchored: every point, as before.
    expect(fitTarget({ kind: 'bounds', points: [berlin, newYork] })).toEqual({
      kind: 'box',
      box: [-74.006, 40.713, 13.405, 52.52],
      padding: 32,
    })
  })
})
