import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import type { Photo } from '@tenshu/sdk/photos'
import { albumsFor, photoDetails } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { formatCoordinate, formatDuration } from '../lib/media'
import { ErrorText } from './ui'

// The stored details of one photograph (capture time, size, camera, place, albums) — one list for
// the viewer's dark side panel and the app's Info pane, so the two cannot disagree. [tone] is the
// surface it sits on: the viewer is always dark; the pane follows the theme.
export function PhotoDetails({
  photo,
  tone,
  showName = true,
}: {
  photo: Photo
  tone: 'dark' | 'surface'
  // The pane names the photograph in its own heading.
  showName?: boolean
}) {
  const { t, i18n } = useTranslation()
  const details = useQuery({ queryKey: queryKeys.details(photo.path), queryFn: () => photoDetails(photo.path) })
  const albums = useQuery({
    queryKey: queryKeys.albumsFor(photo.contentId),
    queryFn: () => albumsFor(photo.contentId),
    enabled: photo.contentId !== '',
  })
  const muted = tone === 'dark' ? 'text-white/60' : 'text-text-2'
  const label = tone === 'dark' ? 'text-white/50' : 'text-text-3'
  const data = details.data
  const rows: [string, string][] = []
  if (data?.ok && data.details) {
    const d = data.details
    if (d.capturedAtMs !== null)
      rows.push([t('details.taken'), new Intl.DateTimeFormat(i18n.language, { dateStyle: 'full', timeStyle: 'short' }).format(d.capturedAtMs)])
    if (d.pixelWidth !== null && d.pixelHeight !== null) rows.push([t('details.dimensions'), `${d.pixelWidth} × ${d.pixelHeight}`])
    if (d.sizeBytes !== null) rows.push([t('details.size'), formatBytes(d.sizeBytes, i18n.language)])
    const camera = [d.cameraMake, d.cameraModel].filter(Boolean).join(' ')
    if (camera) rows.push([t('details.camera'), camera])
    if (d.durationMs !== null) rows.push([t('details.duration'), formatDuration(d.durationMs)])
    if (d.latE7 !== null && d.lngE7 !== null) rows.push([t('details.location'), formatCoordinate(d.latE7, d.lngE7)])
  }
  return (
    <>
      {details.isPending && <p className={muted}>{t('common.loading')}</p>}
      {details.error && <ErrorText error={details.error} />}
      {data && !data.ok && <ErrorText failure={data.error} />}
      {data?.ok && !data.details && <p className={muted}>{t('details.gone')}</p>}
      <dl className="space-y-2">
        {showName && (
          <div>
            <dt className={label}>{t('details.name')}</dt>
            <dd className="break-all">{photo.name}</dd>
          </div>
        )}
        {rows.map(([name, value]) => (
          <div key={name}>
            <dt className={label}>{name}</dt>
            <dd>{value}</dd>
          </div>
        ))}
        {albums.data?.ok && albums.data.albums.length > 0 && (
          <div>
            <dt className={label}>{t('details.albums')}</dt>
            <dd>{albums.data.albums.map((a) => a.name).join(', ')}</dd>
          </div>
        )}
      </dl>
      {albums.error && <ErrorText error={albums.error} />}
      {albums.data && !albums.data.ok && <ErrorText failure={albums.data.error} />}
    </>
  )
}

function formatBytes(bytes: number, locale: string): string {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'] as const
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit += 1
  }
  return new Intl.NumberFormat(locale, { style: 'unit', unit: units[unit], maximumFractionDigits: 1 }).format(value)
}
