// A workout's route on the shared map (`@tenshu/map-kit`): one polyline, the camera fitted to
// it, a start and an end pin. ⚠ MapLibre loads with this component's first render, never with the
// dashboard or History (`<TenshuMap>` is lazy by construction; `bundleSplit.test.ts` pins it).
//
// Tiles are live (`maps-region-delivery.md` §Web scope W.1): the apex runtime fetches the tile
// host, and the bytes reach this frame over the bridge. When they cannot (offline, a refused fetch, no
// region for the area, no WebGL), map-kit renders an explicit reason here; the rest of the detail is
// unaffected. `map.notConfigured` is only for a runtime built without a tile transport.
import { useTranslation } from 'react-i18next'
import { bridgeRegionAccess, TenshuMap, type LatLng, type MapTheme } from '@tenshu/map-kit'
import type { FitRoutePoint } from '@tenshu/sdk/fit'
import { mapRegionCall } from '../lib/proxy'

const regionAccess = bridgeRegionAccess(mapRegionCall)

// The route colour: `#RRGGBB`, as map-kit requires.
export const ROUTE_COLOR = '#7C3AED'

function theme(): MapTheme {
  const explicit = typeof document === 'undefined' ? undefined : document.documentElement.dataset.theme
  if (explicit === 'dark' || explicit === 'light') return explicit
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function RouteMap({ route }: { route: readonly FitRoutePoint[] }) {
  const { t } = useTranslation()
  const points: LatLng[] = route.map((p) => ({ lat: p.lat, lng: p.lng }))
  return (
    <TenshuMap
      regions={regionAccess}
      camera={{ kind: 'bounds', points, padding: 32 }}
      polylines={[{ id: 'route', segments: [{ points, color: ROUTE_COLOR }] }]}
      pins={[
        { id: 'start', position: points[0]!, label: t('detail.start') },
        { id: 'end', position: points[points.length - 1]!, label: t('detail.end') },
      ]}
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
