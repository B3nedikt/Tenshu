// A workout's route on the shared map (`@tenshu/map-kit`): one polyline, the camera fitted to
// it, a start and an end pin. ⚠ MapLibre loads with this component's first render, never with the
// dashboard or History (`<TenshuMap>` is lazy by construction; `bundleSplit.test.ts` pins it).
//
// Tiles are live (`maps-region-delivery.md` §Web scope W.1): the apex runtime fetches the tile
// host, and the bytes reach this frame over the bridge. When they cannot (offline, a refused fetch, no
// region for the area, no WebGL), map-kit renders an explicit reason here; the rest of the detail is
// unaffected. `map.notConfigured` is only for a runtime built without a tile transport.
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { bridgeRegionAccess, TenshuMap, type LatLng, type MapTheme } from '@tenshu/map-kit'
import type { FitRoutePoint } from '@tenshu/sdk/fit'
import { clockText } from '../lib/format'
import { nearestIndex } from '../lib/trend'
import { mapRegionCall } from '../lib/proxy'

const regionAccess = bridgeRegionAccess(mapRegionCall)

// The route colour: `#RRGGBB`, as map-kit requires.
export const ROUTE_COLOR = '#7C3AED'

function theme(): MapTheme {
  const explicit = typeof document === 'undefined' ? undefined : document.documentElement.dataset.theme
  if (explicit === 'dark' || explicit === 'light') return explicit
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

// [cursor]: the charts' shared pointer (seconds from the start) — drawn as a pin where the athlete
// was at that moment (fit-web-parity.md §2.2). ⚠ The camera and the line are memoised on the route:
// only the pins change as the pointer moves, so the map never re-fits under it.
export function RouteMap({ route, cursor = null }: { route: readonly FitRoutePoint[]; cursor?: number | null }) {
  const { t } = useTranslation()
  const points: LatLng[] = useMemo(() => route.map((p) => ({ lat: p.lat, lng: p.lng })), [route])
  const camera = useMemo(() => ({ kind: 'bounds' as const, points, padding: 32 }), [points])
  const polylines = useMemo(() => [{ id: 'route', segments: [{ points, color: ROUTE_COLOR }] }], [points])
  const times = useMemo(() => route.map((p) => p.tSec), [route])
  // The pins change only when the cursor reaches ANOTHER route point (a binary search, not a scan).
  const atIndex = cursor !== null ? nearestIndex(times, cursor) : null
  const startLabel = t('detail.start')
  const endLabel = t('detail.end')
  const pins = useMemo(() => {
    const at = atIndex !== null ? route[atIndex]! : null
    return [
      { id: 'start', position: points[0]!, label: startLabel },
      { id: 'end', position: points[points.length - 1]!, label: endLabel },
      ...(at ? [{ id: 'cursor', position: { lat: at.lat, lng: at.lng }, label: clockText(at.tSec) }] : []),
    ]
  }, [atIndex, route, points, startLabel, endLabel])
  return (
    <TenshuMap
      regions={regionAccess}
      camera={camera}
      polylines={polylines}
      pins={pins}
      theme={theme()}
      labels={{
        map: t('map.map'),
        loading: t('map.loading'),
        unavailable: t('map.unavailable'),
        noWebGL: t('map.noWebGL'),
        notConfigured: t('map.notConfigured'),
        tilesFailed: t('map.tilesFailed'),
        noRegion: t('map.noRegion'),
      }}
      className="h-72 w-full overflow-hidden rounded-lg border border-line"
    />
  )
}
