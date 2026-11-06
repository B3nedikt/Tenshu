// The MapLibre half of `<TenshuMap>`, loaded on first render only (`React.lazy` in `TenshuMap.tsx`).
// ⚠ The ONLY map-kit module that may import `maplibre-gl` or `./protocol` (which imports `pmtiles`):
// everything that reaches them statically ships them to every route of the consuming app.
import maplibregl, { type GeoJSONSource, type LngLatBoundsLike, type MapLayerMouseEvent } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  cameraCentre,
  fitTarget,
  regionBox,
  overlayLayers,
  pinsSource,
  PINS_SOURCE,
  polylinesSource,
  POLYLINES_SOURCE,
  type Box,
  type LatLng,
  type MapCamera,
} from './overlays'
import { handleRegionRequest, PROTOCOL, registerRegion } from './protocol'
import { RegionErrorKind, RegionTileError } from './regions'
import { BASEMAP_MAX_ZOOM, buildBasemapStyle } from './style/basemapStyle'
import { MapUnavailable } from './chrome'
import type { TenshuMapLabels, TenshuMapProps } from './props'

let protocolAdded = false
function ensureProtocol() {
  if (protocolAdded) return
  maplibregl.addProtocol(PROTOCOL, handleRegionRequest)
  protocolAdded = true
}

/** ODbL: every map drawing OpenStreetMap data carries the credit (`BasemapAttributionTest` on native). */
export const OSM_ATTRIBUTION = '© OpenStreetMap contributors'

// `centreKey` is the point the region was resolved for; its `box` the catalog row's bounds.
type Region =
  | { readonly kind: 'resolving' }
  | { readonly kind: 'ready'; readonly tileUrl: string | null; readonly box: Box | null; readonly centreKey: string }
  | { readonly kind: 'failed'; readonly reason: string }

function keyOf(centre: LatLng | null): string {
  return centre ? `${centre.lat.toFixed(3)},${centre.lng.toFixed(3)}` : ''
}

function cameraOptions(
  camera: MapCamera,
  region: Box | null | undefined,
): { center?: [number, number]; zoom?: number; bounds?: LngLatBoundsLike; fitBoundsOptions?: object } {
  const target = fitTarget(camera, region)
  // No points yet (a route still loading): MapLibre's default view.
  if (!target) return {}
  if (target.kind === 'center') {
    return { center: [target.center.lng, target.center.lat], zoom: Math.min(target.zoom, BASEMAP_MAX_ZOOM) }
  }
  // ⚠ `east` may be past 180 (a box across the antimeridian); MapLibre fits that box, not the world.
  return { bounds: [...target.box] as [number, number, number, number], fitBoundsOptions: { padding: target.padding, maxZoom: BASEMAP_MAX_ZOOM } }
}

export default function MapView(props: TenshuMapProps & { labels: TenshuMapLabels; theme: 'light' | 'dark'; language?: string }) {
  const { regions, camera, pins = [], polylines = [], theme, language, labels, onPinClick, className } = props
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<maplibregl.Map | null>(null)
  const [region, setRegion] = useState<Region>({ kind: 'resolving' })
  // The tile URL whose tiles failed; an alert shows while that region is the one on screen.
  const [failedTiles, setFailedTiles] = useState<string | null>(null)
  const latest = useRef({ pins, polylines, onPinClick })
  useLayoutEffect(() => {
    latest.current = { pins, polylines, onPinClick }
  })

  // ⚠ The region follows what the user LOOKS at, not just the prop: a pan into Bavaria must resolve
  // Bavaria. A pan is tagged with the camera it panned away from, so a new `camera` prop wins again.
  // Memoised: a list map's camera can carry thousands of points.
  const cameraKey = useMemo(() => JSON.stringify(camera), [camera])
  const [panned, setPanned] = useState<{ readonly cameraKey: string; readonly centre: LatLng } | null>(null)
  const isPanned = panned?.cameraKey === cameraKey
  const ownCentreKey = keyOf(cameraCentre(camera))
  const centre = isPanned ? panned.centre : cameraCentre(camera)
  const centreKey = keyOf(centre)
  // Where the user left the view, so a rebuild for a new region keeps it instead of jumping back.
  const view = useRef<{ readonly cameraKey: string; readonly center: [number, number]; readonly zoom: number } | null>(null)
  const cameraKeyRef = useRef(cameraKey)
  useLayoutEffect(() => {
    cameraKeyRef.current = cameraKey
  })

  // Which region the camera looks at. One region per map (maps-region-delivery.md §Web scope W.5).
  useEffect(() => {
    let live = true
    if (!centre) return // rendered as "no region" below, without a lookup
    regions.regionAt(centre.lat, centre.lng).then(
      (result) => {
        if (!live) return
        if (!result.ok) {
          const reason = result.error.kind === RegionErrorKind.transportUnconfigured ? labels.notConfigured : labels.tilesFailed
          setRegion({ kind: 'failed', reason })
        } else {
          setRegion({
            kind: 'ready',
            tileUrl: result.region ? registerRegion(result.region.regionId, regions) : null,
            box: regionBox(result.region?.bounds),
            centreKey,
          })
        }
      },
      () => live && setRegion({ kind: 'failed', reason: labels.tilesFailed }),
    )
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the rounded centre, not the object
  }, [centreKey, regions])

  const shown: Region = centre ? region : { kind: 'ready', tileUrl: null, box: null, centreKey: '' }
  const tileUrl = shown.kind === 'ready' ? shown.tileUrl : undefined
  const tilesFailed = tileUrl != null && failedTiles === tileUrl
  // The box an anchored camera fits within: its OWN region's, never one a pan resolved.
  const fitBox = shown.kind === 'ready' && shown.centreKey === ownCentreKey ? shown.box : undefined
  const fitKey = `${cameraKey}|${camera.kind === 'bounds' && camera.anchor ? JSON.stringify(fitBox ?? null) : ''}`
  const latestFit = useRef({ camera, fitBox, isPanned })
  useLayoutEffect(() => {
    latestFit.current = { camera, fitBox, isPanned }
  })

  // The map itself: rebuilt when the region, theme or language changes (each is a new style).
  useEffect(() => {
    if (tileUrl === undefined || !container.current) return
    ensureProtocol()
    const origin = window.location.origin
    const style = buildBasemapStyle({
      theme,
      language,
      tileUrl,
      glyphsUrl: `${props.glyphsBase ?? `${origin}/glyphs`}/{fontstack}/{range}.pbf`,
      ownOrigin: origin,
    })
    const kept = view.current?.cameraKey === cameraKeyRef.current ? view.current : null
    const m = new maplibregl.Map({
      container: container.current,
      style,
      ...(kept ? { center: kept.center, zoom: kept.zoom } : cameraOptions(camera, fitBox)),
      attributionControl: { compact: true, customAttribution: OSM_ATTRIBUTION },
    })
    map.current = m
    m.on('load', () => {
      m.addSource(POLYLINES_SOURCE, polylinesSource(latest.current.polylines))
      m.addSource(PINS_SOURCE, pinsSource(latest.current.pins))
      for (const layer of overlayLayers(theme)) m.addLayer(layer)
    })
    m.on('moveend', (e: { originalEvent?: unknown }) => {
      if (!e.originalEvent) return // the prop camera moved it; the prop already names the region
      const c = m.getCenter()
      const key = cameraKeyRef.current
      view.current = { cameraKey: key, center: [c.lng, c.lat], zoom: m.getZoom() }
      setPanned({ cameraKey: key, centre: { lat: c.lat, lng: c.lng } })
    })
    m.on('click', 'tenshu-pins', (e: MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id
      if (typeof id === 'string') latest.current.onPinClick?.(id)
    })
    // ⚠ A tile that failed is an error event, never a silent blank: say so over the map.
    m.on('error', (e: { error?: unknown; sourceId?: string }) => {
      if (e.error instanceof RegionTileError || e.sourceId === 'protomaps') setFailedTiles(tileUrl)
    })
    return () => {
      m.remove()
      if (map.current === m) map.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the camera and overlays update in place below
  }, [tileUrl, theme, language, props.glyphsBase])

  // Overlays update in place.
  useEffect(() => {
    const m = map.current
    if (!m) return
    ;(m.getSource(PINS_SOURCE) as GeoJSONSource | undefined)?.setData(pinsSource(pins).data)
    ;(m.getSource(POLYLINES_SOURCE) as GeoJSONSource | undefined)?.setData(polylinesSource(polylines).data)
  }, [pins, polylines])

  // The camera moves in place — on a new camera, and when an anchored camera's region box arrives.
  // ⚠ Never after a pan: the user's view wins until the next camera prop.
  useEffect(() => {
    const m = map.current
    const { camera: c, fitBox: box, isPanned: moved } = latestFit.current
    if (!m || moved) return
    const options = cameraOptions(c, box)
    if (options.bounds) m.fitBounds(options.bounds, options.fitBoundsOptions)
    else if (options.center) m.jumpTo({ center: options.center, zoom: options.zoom })
  }, [fitKey])

  if (shown.kind === 'failed') return <MapUnavailable title={labels.unavailable} reason={shown.reason} className={className} />
  return (
    <div className={className} style={{ position: 'relative' }}>
      <div ref={container} role="region" aria-label={labels.map} style={{ position: 'absolute', inset: 0 }} />
      {tilesFailed && <div role="alert" style={{ position: 'absolute', top: 8, left: 8, right: 8 }}>{labels.tilesFailed}</div>}
      {shown.kind === 'ready' && shown.tileUrl === null && <div role="status" style={{ position: 'absolute', top: 8, left: 8 }}>{labels.noRegion}</div>}
    </div>
  )
}
