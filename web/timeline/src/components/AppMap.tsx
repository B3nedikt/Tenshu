// The app's one map: `@tenshu/map-kit`'s `<TenshuMap>` over this app's bridge, with its copy from
// i18n (how-to new-web-app.md §2b).
//
// ⚠⚠ MapLibre stays LAZY: `@tenshu/map-kit`'s entry reaches neither `maplibre-gl` nor `pmtiles`;
// they load with the first map drawn (`mapSplit.test.ts`). Never import `maplibre-gl` here.
// ⚠ The FIRST region lookup (the one for the `camera` prop) failing renders by `kind` (`error_map_*`,
// or the bridge's own sentence) in the map's place: the wrapper below sees the answer before the map
// does. ⚠⚠ A lookup after a PAN never does: it stays with map-kit's own over-the-map alert, so one
// transient `error_map_transport_failed` cannot unmount a map the user is already looking at.
// ⚠ A runtime without a tile transport answers `error_map_transport_unconfigured`, which offers no
// retry (a retry cannot change it); the screen around the map stays fully usable either way.
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TenshuMap, type MapCamera, type MapPin, type MapPolyline, type RegionAccess } from '@tenshu/map-kit'
import { mapRegionAt, mapRegionRange } from '../lib/proxy'
import { wrapLongitude } from '../lib/route'
import { bridgeErrorMessage, failureMessage, type Failure } from '../lib/errors'
import { Button } from './ui'

// `error_map_transport_unconfigured` — spelled out: `RegionErrorKind` is a map-kit value, but this
// file only needs the one string, and a retry cannot change that answer.
const TRANSPORT_UNCONFIGURED = 'error_map_transport_unconfigured'

type MapFailure = { readonly failure: Failure } | { readonly error: unknown }

const FRAME = 'h-[200px] w-full overflow-hidden rounded-lg border border-line bg-surface-2'

export function AppMap({
  camera,
  pins,
  polylines,
  label,
  className = FRAME,
}: {
  camera: MapCamera
  pins?: readonly MapPin[]
  polylines?: readonly MapPolyline[]
  // The map's accessible name ("Route", "Map of Alfama").
  label: string
  className?: string
}) {
  const { t } = useTranslation()
  const [failed, setFailed] = useState<MapFailure | null>(null)
  // Whether this map has made its first lookup — the one that may replace it with the failure panel.
  // Reset by Retry, which mounts the map afresh.
  const looked = useRef(false)
  // Stable: map-kit re-resolves the region when `regions` changes.
  const regions = useMemo<RegionAccess>(
    () => ({
      async regionAt(latitude, longitude) {
        const first = !looked.current
        looked.current = true
        try {
          const result = await mapRegionAt(latitude, wrapLongitude(longitude))
          if (!result.ok && first) setFailed({ failure: result.error })
          return result
        } catch (error) {
          if (first) setFailed({ error })
          throw error
        }
      },
      readRange: mapRegionRange,
    }),
    [],
  )

  if (failed) {
    const failure = 'failure' in failed ? failed.failure : null
    return (
      <div role="status" data-testid="map-failure" data-kind={failure?.kind} className="rounded-lg border border-line bg-surface-2 px-4 py-3">
        <p className="text-sm font-medium text-text">{t('map.unavailable')}</p>
        <p className="mt-1 text-sm text-text-2">
          {failure ? failureMessage(t, failure) : bridgeErrorMessage(t, 'error' in failed ? failed.error : null)}
        </p>
        {failure?.kind !== TRANSPORT_UNCONFIGURED && (
          <Button
            className="mt-3"
            size="sm"
            variant="outline"
            onClick={() => {
              looked.current = false
              setFailed(null)
            }}
          >
            {t('common.retry')}
          </Button>
        )}
      </div>
    )
  }

  return (
    <TenshuMap
      regions={regions}
      camera={camera}
      pins={pins}
      polylines={polylines}
      className={className}
      labels={{
        map: label,
        loading: t('map.loading'),
        unavailable: t('map.unavailable'),
        noWebGL: t('map.noWebGL'),
        notConfigured: t('errors.error_map_transport_unconfigured'),
        tilesFailed: t('errors.error_map_transport_failed'),
        noRegion: t('map.noRegion'),
      }}
    />
  )
}
