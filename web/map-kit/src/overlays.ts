// What the three consumers draw over the basemap: Maps' place pins, Timeline's trips and Fit's routes.
// Pure data → MapLibre sources + layers, so the mapping is unit-testable without WebGL.
import type { GeoJSONSourceSpecification, LayerSpecification } from 'maplibre-gl'
import type { MapTheme } from './style/basemapStyle'

export interface LatLng {
  readonly lat: number
  readonly lng: number
}

export interface MapPin {
  readonly id: string
  readonly position: LatLng
  readonly label?: string
}

/** One coloured run of a polyline — a trip's walk-then-train, a workout's heart-rate zones. */
export interface PolylineSegment {
  readonly points: readonly LatLng[]
  /** `#RRGGBB`. */
  readonly color: string
}

export interface MapPolyline {
  readonly id: string
  readonly segments: readonly PolylineSegment[]
}

export type MapCamera =
  | { readonly kind: 'center'; readonly center: LatLng; readonly zoom: number }
  | {
      readonly kind: 'bounds'
      readonly points: readonly LatLng[]
      readonly padding?: number
      /**
       * The point that picks the region, when the box's middle is the wrong one (one region per map,
       * §Web scope W.5): Berlin + New York's middle is the Atlantic. With an anchor the camera fits
       * only the points inside the anchor's region ([fitTarget]); the rest still get their pins.
       * Absent, the box's middle picks the region and every point is fitted — as before.
       */
      readonly anchor?: LatLng
    }

/**
 * `[west, south, east, north]` in degrees. ⚠ `east` may exceed 180: a box across the antimeridian
 * (Fiji, ±179°) is `[179, …, 181, …]`, never the whole world `[-179, …, 179, …]`.
 */
export type Box = readonly [number, number, number, number]

export const PINS_SOURCE = 'tenshu-pins'
export const POLYLINES_SOURCE = 'tenshu-polylines'

const COLOR = /^#[0-9A-Fa-f]{6}$/

function lngLat(p: LatLng): [number, number] {
  return [p.lng, p.lat]
}

export function pinsSource(pins: readonly MapPin[]): GeoJSONSourceSpecification {
  return {
    type: 'geojson',
    data: {
      type: 'FeatureCollection',
      features: pins.map((pin) => ({
        type: 'Feature',
        id: pin.id,
        properties: { id: pin.id, label: pin.label ?? '' },
        geometry: { type: 'Point', coordinates: lngLat(pin.position) },
      })),
    },
  }
}

/** One LineString feature per segment, carrying its colour. A segment needs two points to be a line. */
export function polylinesSource(polylines: readonly MapPolyline[]): GeoJSONSourceSpecification {
  return {
    type: 'geojson',
    data: {
      type: 'FeatureCollection',
      features: polylines.flatMap((line) =>
        line.segments
          .filter((segment) => segment.points.length >= 2)
          .map((segment, index) => {
            if (!COLOR.test(segment.color)) throw new Error(`polyline ${line.id}: colour must be #RRGGBB, got ${segment.color}`)
            return {
              type: 'Feature' as const,
              properties: { polyline: line.id, segment: index, color: segment.color },
              geometry: { type: 'LineString' as const, coordinates: segment.points.map(lngLat) },
            }
          }),
      ),
    },
  }
}

const PIN = { light: { fill: '#C2410C', stroke: '#FFFFFF', label: '#2A2822', halo: '#F7F4EE' }, dark: { fill: '#FB923C', stroke: '#1A1920', label: '#E8E4EC', halo: '#1A1920' } }

/** Above every basemap layer: lines, then pins, then pin labels. */
export function overlayLayers(theme: MapTheme): LayerSpecification[] {
  const pin = PIN[theme]
  return [
    {
      id: 'tenshu-polylines',
      type: 'line',
      source: POLYLINES_SOURCE,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ['get', 'color'], 'line-width': 4 },
    },
    {
      id: 'tenshu-pins',
      type: 'circle',
      source: PINS_SOURCE,
      paint: { 'circle-radius': 7, 'circle-color': pin.fill, 'circle-stroke-color': pin.stroke, 'circle-stroke-width': 2 },
    },
    {
      id: 'tenshu-pin-labels',
      type: 'symbol',
      source: PINS_SOURCE,
      filter: ['!=', ['get', 'label'], ''],
      layout: {
        'text-field': ['get', 'label'],
        // A fontstack the shipped glyphs carry (`:basemap-core` FONT_MEDIUM).
        'text-font': ['Noto Sans Medium'],
        'text-size': 12,
        'text-offset': [0, 1.2],
        'text-anchor': 'top',
      },
      paint: { 'text-color': pin.label, 'text-halo-color': pin.halo, 'text-halo-width': 1.2 },
    },
  ]
}

/** Longitude into [-180, 180). */
export function normalLng(lng: number): number {
  // In range already: returned exactly (the arithmetic below is not exact in floating point).
  if (lng >= -180 && lng < 180) return lng
  return ((((lng + 180) % 360) + 360) % 360) - 180
}

/**
 * The smallest box around [points]. Longitudes wrap: the box skips the widest empty gap between
 * them, so ±179° is a 2° box across the antimeridian rather than a 358° one across the world.
 */
export function pointsBox(points: readonly LatLng[]): Box | null {
  if (points.length === 0) return null
  let south = Infinity
  let north = -Infinity
  for (const p of points) {
    if (p.lat < south) south = p.lat
    if (p.lat > north) north = p.lat
  }
  const lngs = points.map((p) => normalLng(p.lng)).sort((a, b) => a - b)
  const first = lngs[0]!
  const last = lngs[lngs.length - 1]!
  // The gap that wraps (last → first, through ±180) wins a tie, so an ordinary box stays ordinary.
  let gap = first + 360 - last
  let west = first
  let east = last
  for (let i = 0; i + 1 < lngs.length; i++) {
    const g = lngs[i + 1]! - lngs[i]!
    if (g > gap) {
      gap = g
      west = lngs[i + 1]!
      east = lngs[i]! + 360
    }
  }
  return [west, south, east, north]
}

/** A catalog row's `[minLng, minLat, maxLng, maxLat]` as a [Box] (a row across ±180 has min > max). */
export function regionBox(bounds: readonly [number, number, number, number] | null | undefined): Box | null {
  if (!bounds) return null
  const [w, s, e, n] = bounds
  return [w, s, e < w ? e + 360 : e, n]
}

export function inBox(p: LatLng, box: Box): boolean {
  const [w, s, e, n] = box
  if (p.lat < s || p.lat > n) return false
  let lng = normalLng(p.lng)
  if (lng < w) lng += 360
  return lng <= e
}

/** The point the camera looks at — what picks the region (one region per map, §Web scope W.5). */
export function cameraCentre(camera: MapCamera): LatLng | null {
  if (camera.kind === 'center') return camera.center
  if (camera.anchor) return camera.anchor
  const box = pointsBox(camera.points)
  if (!box) return null
  return { lat: (box[1] + box[3]) / 2, lng: normalLng((box[0] + box[2]) / 2) }
}

/** An anchored camera with no region box to fit to fits the points this close to its anchor. */
export const ANCHOR_RADIUS_KM = 150
/** An anchored camera whose fit is the anchor alone opens at a city, not a doorstep. */
export const ANCHOR_ZOOM = 12

function km(a: LatLng, b: LatLng): number {
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 12_742 * Math.asin(Math.min(1, Math.sqrt(h)))
}

export type FitTarget =
  | { readonly kind: 'center'; readonly center: LatLng; readonly zoom: number }
  | { readonly kind: 'box'; readonly box: Box; readonly padding: number }

/**
 * Where [camera] puts the view. A `center` camera is itself; an un-anchored `bounds` camera fits
 * every point; an anchored one fits the points inside [region] (the anchor's region box, when the
 * catalog row has one) or else within [ANCHOR_RADIUS_KM] of the anchor — and the anchor alone opens
 * at [ANCHOR_ZOOM]. Null: nothing to look at yet (MapLibre's default view, never ±Infinity bounds).
 */
export function fitTarget(camera: MapCamera, region?: Box | null): FitTarget | null {
  if (camera.kind === 'center') return { kind: 'center', center: camera.center, zoom: camera.zoom }
  const padding = camera.padding ?? 32
  const anchor = camera.anchor
  if (!anchor) {
    const box = pointsBox(camera.points)
    return box ? { kind: 'box', box, padding } : null
  }
  const near = camera.points.filter((p) => (region ? inBox(p, region) : km(anchor, p) <= ANCHOR_RADIUS_KM))
  const box = pointsBox([anchor, ...near])!
  if (box[0] === box[2] && box[1] === box[3]) return { kind: 'center', center: anchor, zoom: ANCHOR_ZOOM }
  return { kind: 'box', box, padding }
}
