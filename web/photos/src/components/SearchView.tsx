import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { useFeed } from '../lib/feed'
import { searchPhotos } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { parseSearch, type SearchQuery } from '../lib/searchQuery'
import { useCloseViewerWhenEmpty, type ViewerRoute } from '../lib/viewerRoute'
import { PhotoListView } from './PhotoListView'
import { ErrorPanel, Loading } from './ui'

// Photos search (web-apps-layout.md L7): by name, or by a date the box understood ("May 2025"),
// newest first, at most 500. The results are a grid like any other — select, menu, viewer, Info pane.
// ⚠ Content search (what is IN a photo) is not offered: photos-app.md §42.1 keeps it off the web.
export function SearchView({ query, viewer }: { query: string; viewer?: ViewerRoute }) {
  const { t, i18n } = useTranslation()
  const feed = useFeed()
  const parsed = useMemo(() => parseSearch(query, i18n.language), [query, i18n.language])
  const result = useQuery({
    queryKey: queryKeys.photoSearch(parsed?.name ?? '', parsed?.fromMs ?? null, parsed?.toMs ?? null),
    queryFn: () => searchPhotos(parsed!.name, parsed!.fromMs, parsed!.toMs),
    enabled: parsed !== null,
  })
  const data = result.data
  useCloseViewerWhenEmpty(viewer, !!data?.ok && data.photos.length === 0)

  if (!parsed) return <p className="text-sm text-text-2">{t('search.prompt')}</p>
  if (result.isPending) return <Loading />
  if (result.error) return <ErrorPanel error={result.error} onRetry={() => void result.refetch()} />
  if (data && !data.ok) return <ErrorPanel failure={data.error} onRetry={() => void result.refetch()} />
  if (!data?.ok) return null

  const count = data.photos.length
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <p className="text-sm text-text-2" role="status">
        {parsed.isDate
          ? t('search.fromDate', { count, when: describeRange(parsed, i18n.language) })
          : t('search.matching', { count, query: parsed.name })}
        {data.truncated && <span className="ml-2 text-text-3">{t('search.truncated')}</span>}
      </p>
      {count === 0 ? (
        <p className="text-sm text-text-2">{t('search.none')}</p>
      ) : (
        <PhotoListView
          photos={data.photos}
          viewer={viewer}
          onTrashed={() => {
            feed.refresh()
            void result.refetch()
          }}
        />
      )}
    </div>
  )
}

/** "2025", "May 2025" or "3 May 2025", from the range the box understood. */
function describeRange(q: SearchQuery, locale: string): string {
  const from = new Date(q.fromMs ?? 0)
  const to = new Date(q.toMs ?? 0)
  const sameMonth = from.getFullYear() === to.getFullYear() && from.getMonth() === to.getMonth()
  const sameDay = sameMonth && from.getDate() === to.getDate()
  const options: Intl.DateTimeFormatOptions = sameDay
    ? { day: 'numeric', month: 'long', year: 'numeric' }
    : sameMonth
      ? { month: 'long', year: 'numeric' }
      : { year: 'numeric' }
  return new Intl.DateTimeFormat(locale, options).format(from)
}
