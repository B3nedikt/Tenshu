// A movement route over the basemap: the trip detail's hero (every leg, coloured by mode) and a
// movement moment's (native `TripRouteMap`, M5). Every leg is read in ONE call
// (`timeline.movementRoutes`), which keeps the leg boundaries.
//
// ⚠ No route points → no map at all, never an empty one. ⚠ A failed read renders by `kind`, in the
// map's place, and leaves the rest of the screen (the legs list) as it was.
// ⚠⚠ The cache holds POINTS per leg, keyed by the trace ids; the colour comes from `legs` as they are
// NOW. Caching the leg (its mode) under a key that does not name the mode painted a reclassified
// leg in the colour of whichever render read it first.
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { movementRoutes } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { routeOverlay, type RouteLeg } from '../lib/route'
import { AppMap } from './AppMap'
import { ReadFailure } from './ui'

export function RouteMap({ legs }: { legs: readonly RouteLeg[] }) {
  const { t } = useTranslation()
  const traceIds = legs.map((leg) => leg.traceIds)
  const query = useQuery({
    queryKey: queryKeys.route(traceIds.map((ids) => ids.join('+'))),
    queryFn: () => movementRoutes(traceIds),
    enabled: legs.length > 0,
  })
  // Memoised on the answer and the legs: the map pushes new overlays into MapLibre whenever their
  // identity changes.
  const points = query.data?.ok ? query.data.legs : null
  const overlay = useMemo(
    () => (points ? routeOverlay(legs, points, { origin: t('map.origin'), destination: t('map.destination') }) : null),
    [legs, points, t],
  )
  if (legs.length === 0) return null
  if (query.isPending) {
    return (
      <p role="status" className="text-sm text-text-2">
        {t('map.routeLoading')}
      </p>
    )
  }
  if (query.isError) return <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
  if (!query.data.ok) return <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
  if (!overlay) return null
  return <AppMap label={t('map.route')} camera={overlay.camera} pins={overlay.pins} polylines={overlay.polylines} />
}
