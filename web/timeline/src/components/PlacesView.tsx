// The places list (`timeline.places.observe`): every place, named or not. ⚠ An unnamed place shows
// "Unnamed place" with its coordinate as the secondary line — the web has no geocoder (§Web scope
// 2), so the user's own rename is how it gets a name.
import { useTranslation } from 'react-i18next'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { usePlaces } from '../lib/live'
import { dateTimeText, placeLabel, roleName } from '../lib/format'
import type { Open } from '../lib/nav'
import { Loading, ReadFailure } from './ui'

export function PlacesView({ onOpen }: { onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const live = usePlaces()
  const notice = <StreamNotice live={live} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />

  if (live.isPending) return <Loading />
  const result = live.data
  if (!result) return <ReadFailure error={live.error} onRetry={live.retry} />
  if (!result.ok) {
    return (
      <>
        {notice}
        <ReadFailure failure={result.error} onRetry={live.retry} />
      </>
    )
  }

  return (
    <div className="space-y-4">
      {notice}
      {result.places.length === 0 ? (
        <p className="text-sm text-text-2">{t('places.empty')}</p>
      ) : (
        <ul className="divide-y divide-line">
          {result.places.map((place) => {
            const label = placeLabel(t, place)
            const role = place.roleIsSuggestion ? null : roleName(t, place.role)
            return (
              <li key={place.placeId}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind: 'place', id: place.placeId })}
                  className="flex w-full items-start justify-between gap-3 px-2 py-2.5 text-left hover:bg-surface-2"
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-text">{label.title}</span>
                    {/* A confirmed role took the headline, so the place's own name moves here. */}
                    {role && place.name.trim() && <span className="block truncate text-xs text-text-2">{place.name.trim()}</span>}
                    {label.secondary && <span className="block truncate text-xs text-text-2">{label.secondary}</span>}
                  </span>
                  <span className="shrink-0 text-right text-xs text-text-2">
                    <span className="block">{t('places.visits', { count: place.visitCount })}</span>
                    {place.lastVisitMs !== null && (
                      <span className="block">{dateTimeText(i18n.language, place.lastVisitMs)}</span>
                    )}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
