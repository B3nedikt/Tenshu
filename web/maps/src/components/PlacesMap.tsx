// The map: `<TenshuMap>` (W-MAP) with a pin per place. ⚠⚠ Every way of not drawing one — no WebGL,
// a tile transport the deployment did not configure or that fails, no region for the area — is
// map-kit's explicit status box beside the list, never a broken page, and the list never depends
// on the map.
// ⚠ Never imports `maplibre-gl`: the map-kit loads it with the first rendered map (`sdkBoundary.test.ts`).
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { TenshuMap, type MapCamera, type MapPin, type MapTheme, type TenshuMapLabels } from '@tenshu/map-kit'
import type { MapsPlace } from '@tenshu/sdk/maps'
import { regions } from '../lib/proxy'
import { placeLabel } from '../lib/format'

// The zoom a single place opens at — a street, not a city.
export const PLACE_ZOOM = 16

// The app's explicit Light/Dark choice when it has one (`data-theme`, @tenshu/web-tokens/theme);
// undefined lets the map-kit follow the system, as the page's own colours do.
function pageTheme(): MapTheme | undefined {
  const attr = typeof document === 'undefined' ? null : document.documentElement.getAttribute('data-theme')
  return attr === 'light' || attr === 'dark' ? attr : undefined
}

export function PlacesMap({
  places,
  camera,
  onPinClick,
}: {
  places: readonly MapsPlace[]
  camera: MapCamera
  onPinClick?: (placeId: string) => void
}) {
  const { t, i18n } = useTranslation()
  // Memoised: thousands of pins, and `<TenshuMap>` re-sends them to MapLibre when the array changes.
  const pins: MapPin[] = useMemo(
    () =>
      places.map((p) => ({
        id: p.placeId,
        position: { lat: p.latitude, lng: p.longitude },
        label: placeLabel(t, i18n.language, p).title,
      })),
    [places, t, i18n.language],
  )
  // By kind: the "not configured" reason is the copy for `error_map_transport_unconfigured`, and
  // every other way a region fails reads as the transport's failure (map-kit's own split).
  const labels: TenshuMapLabels = {
    map: t('map.label'),
    loading: t('map.loading'),
    unavailable: t('map.unavailable'),
    noWebGL: t('map.noWebGL'),
    notConfigured: t('errors.error_map_transport_unconfigured'),
    tilesFailed: t('errors.error_map_transport_failed'),
    noRegion: t('map.noRegion'),
  }
  return (
    <TenshuMap
      regions={regions}
      camera={camera}
      pins={pins}
      theme={pageTheme()}
      language={i18n.language}
      onPinClick={onPinClick}
      labels={labels}
      className="map-frame h-64 w-full overflow-hidden rounded-lg border border-line bg-surface-2 text-sm text-text-2 sm:h-80"
    />
  )
}
