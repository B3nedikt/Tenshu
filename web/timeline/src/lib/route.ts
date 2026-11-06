// What the trip and moment maps draw, as pure data: route points in, `<TenshuMap>` overlays out
// (web-app-suite.md Wave 3, Timeline routes). No React and no bridge here, so the mapping is tested
// without a map.
//
// ⚠ The route is read in ONE call for every leg (`timeline.movementRoutes`, boundaries kept), never
// one call per leg. A collapsed ride (several traces behind one row) is still one leg: it has one mode.
// ⚠ A leg's MODE is never cached with its points: the cache is keyed by trace ids, and the colour
// comes from the leg as it is now (`routeOverlay`'s `legs` × `points`).
// ⚠ Longitudes are UNWRAPPED across the ±180° line (`unwrapLongitudes`), so a transpacific flight
// draws the short way and the camera fits it, not the whole world.
// ⚠ Native draws a trip as ONE line in the theme's primary colour with no pins (`TripRouteMap`).
// The web colours each leg by its mode and pins the two ends; an unknown mode keeps native's colour.
import type { TimelineDayMoment, TimelineRoutePoint } from '@tenshu/sdk/timeline'
import type { LatLng, MapCamera, MapPin, MapPolyline, PolylineSegment } from '@tenshu/map-kit'

// `#RRGGBB` per `TimelineDayMoment.movementMode`. Mid-tone hues, so a line reads on both the light
// and the dark basemap.
export const MODE_COLORS: Readonly<Record<string, string>> = {
  walk: '#2E7D32',
  run: '#C2185B',
  cycle: '#00838F',
  drive: '#E65100',
  transit: '#1565C0',
  flight: '#6A1B9A',
}

// The native route colour (`--primary`, light), for `unknown` and a newer peer's mode.
export const DEFAULT_ROUTE_COLOR = '#5A4A96'

export function modeColor(mode: string | null): string {
  return (mode !== null && MODE_COLORS[mode]) || DEFAULT_ROUTE_COLOR
}

// One movement of a trip or a moment, as the route read needs it.
export interface RouteLeg {
  readonly momentId: string
  readonly timestampMs: number
  readonly traceIds: readonly string[]
  readonly mode: string | null
}

// Native's place hero zoom (`BasemapCoord.zoom`).
export const PLACE_ZOOM = 14.5

// The movement rows among `moments` that have a trace, oldest first — the order the route is drawn
// in, whatever order the screen lists them in. `traceIds` before `traceId`: a collapsed ride's
// hero must draw every leg, or it contradicts the row's combined distance (native `loadRouteHero`).
export function routeLegs(moments: readonly TimelineDayMoment[]): RouteLeg[] {
  return moments
    .filter((m) => m.kind === 'Movement')
    .map((m) => ({
      momentId: m.momentId,
      timestampMs: m.timestampMs,
      traceIds: m.traceIds.length > 0 ? m.traceIds : m.traceId ? [m.traceId] : [],
      mode: m.movementMode,
    }))
    .filter((leg) => leg.traceIds.length > 0)
    .sort((a, b) => a.timestampMs - b.timestampMs)
}

export interface RouteOverlay {
  readonly polylines: readonly MapPolyline[]
  readonly pins: readonly MapPin[]
  readonly camera: MapCamera
}

function isCoordinate(p: TimelineRoutePoint): boolean {
  return (
    Number.isFinite(p.latitude) &&
    Number.isFinite(p.longitude) &&
    Math.abs(p.latitude) <= 90 &&
    Math.abs(p.longitude) <= 180
  )
}

/**
 * [points] with each longitude moved by a multiple of 360° so no step between consecutive points
 * exceeds 180°: Tokyo (139.7) → San Francisco (-122.4) becomes 139.7 → 237.6, the short way over the
 * Pacific. MapLibre draws and fits coordinates beyond ±180 for exactly this continuity. ⚠ Applied to
 * the WHOLE route, across leg boundaries, so a trip's legs stay joined and its bounds stay tight.
 */
export function unwrapLongitudes(points: readonly LatLng[]): LatLng[] {
  const out: LatLng[] = []
  for (const p of points) {
    const prev = out.at(-1)
    if (!prev) {
      out.push(p)
      continue
    }
    const lng = p.lng + 360 * Math.round((prev.lng - p.lng) / 360)
    out.push(lng === p.lng ? p : { lat: p.lat, lng })
  }
  return out
}

// A longitude in [-180, 180], untouched when it already is (the modulo would add float noise). A route that crosses the ±180° line is drawn with UNWRAPPED longitudes
// (`unwrapLongitudes`), so the camera's centre — and a pan's — can lie past 180; the region lookup (`AppMap`)
// takes WGS84 and refuses anything else as a bad request.
export function wrapLongitude(lng: number): number {
  if (lng >= -180 && lng <= 180) return lng
  return ((((lng + 180) % 360) + 360) % 360) - 180
}

/**
 * The polyline (one segment per leg, coloured by its CURRENT mode), the origin and destination pins,
 * and a camera fitted to every point. [points] is per leg, aligned with [legs] (`points[i]` belongs
 * to `legs[i]`). ⚠ `null` when no leg has a line (two points or more): a trip without route points
 * shows NO map, never an empty one.
 */
export function routeOverlay(
  legs: readonly RouteLeg[],
  points: readonly (readonly TimelineRoutePoint[])[],
  labels: { readonly origin: string; readonly destination: string },
): RouteOverlay | null {
  const drawn = legs
    .map((leg, i) => ({
      points: (points[i] ?? []).filter(isCoordinate).map((p): LatLng => ({ lat: p.latitude, lng: p.longitude })),
      color: modeColor(leg.mode),
    }))
    .filter((segment) => segment.points.length >= 2)
  if (drawn.length === 0) return null
  // Unwrapped as ONE path, then cut back into the legs it came from.
  const unwrapped = unwrapLongitudes(drawn.flatMap((s) => s.points))
  let at = 0
  const segments: PolylineSegment[] = drawn.map((s) => {
    const segment = { points: unwrapped.slice(at, at + s.points.length), color: s.color }
    at += s.points.length
    return segment
  })
  const all = unwrapped
  return {
    polylines: [{ id: 'route', segments }],
    pins: [
      { id: 'origin', position: all[0]!, label: labels.origin },
      { id: 'destination', position: all.at(-1)!, label: labels.destination },
    ],
    camera: { kind: 'bounds', points: all },
  }
}

// A place's single pin, or `null` for a place with no coordinate (imported without one) — native
// shows its flat hero there, the web shows no map.
export function placeOverlay(place: { readonly placeId: string; readonly latE7: number; readonly lngE7: number }): {
  readonly pins: readonly MapPin[]
  readonly camera: MapCamera
} | null {
  if (place.latE7 === 0 && place.lngE7 === 0) return null
  const position = { lat: place.latE7 / 1e7, lng: place.lngE7 / 1e7 }
  return { pins: [{ id: place.placeId, position }], camera: { kind: 'center', center: position, zoom: PLACE_ZOOM } }
}
