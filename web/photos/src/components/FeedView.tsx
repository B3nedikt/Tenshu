import { useCallback, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useFeed } from '../lib/feed'
import { DENSITIES, HEADER_FORMAT, TILE_TARGET, sectionsFor, type Density } from '../lib/layout'
import { useCloseViewerWhenEmpty, type ViewerRoute } from '../lib/viewerRoute'
import { PhotoBrowser } from './PhotoBrowser'
import { Button, ErrorPanel, Loading } from './ui'

// The library: the capture-date grid over the keyset pager, month headers and the scrubber from
// the capture-time index, the selection and item menu, and the viewer over the same rows.
export function FeedView({
  density = 'months',
  onDensity,
  startAt = null,
  viewer,
  emptyText,
}: {
  // Days / Months / Years (web-apps-layout.md §3.5) — the same feed grouped and sized three ways.
  density?: Density
  // With the feed index at the top, so the new density opens at the same date.
  onDensity?: (density: Density, topIndex: number) => void
  // Where to open (a Years card's first photograph).
  startAt?: number | null
  // The viewer's place in the URL (`open=<feed index>`); omitted = local state.
  viewer?: ViewerRoute
  // What an empty feed says (the Videos collection has its own line).
  emptyText?: string
} = {}) {
  const { t } = useTranslation()
  const feed = useFeed()
  const { pager, page } = feed

  const times = feed.times.data?.ok ? feed.times.data.times : null
  useCloseViewerWhenEmpty(viewer, !!times && times.length === 0)
  const sections = useMemo(() => (times ? sectionsFor(times, density) : []), [times, density])
  const need = useCallback((index: number) => void pager.ensure(index), [pager])
  const top = useRef(0)
  const onTopIndex = useCallback((index: number) => {
    top.current = index
  }, [])
  const photoAt = useCallback((index: number) => page.rows[index], [page.rows])

  if (feed.times.isPending) return <Loading />
  if (feed.times.error) return <ErrorPanel error={feed.times.error} onRetry={feed.refresh} />
  if (feed.times.data && !feed.times.data.ok) return <ErrorPanel failure={feed.times.data.error} onRetry={feed.refresh} />
  if (!times) return null

  // ⚠ The skeleton is the index; a row synced in after it was read is past its end until the user
  // refreshes (the feed has no push form). The pager never repeats a row either way.
  const total = times.length

  const densities = onDensity && (
    <div role="radiogroup" aria-label={t('density.label')} className="flex rounded-lg border border-line bg-surface-2 p-0.5 text-sm">
      {DENSITIES.map((d) => (
        <button
          key={d}
          type="button"
          role="radio"
          aria-checked={density === d}
          onClick={() => onDensity(d, top.current)}
          className={`rounded-md px-3 py-1 ${density === d ? 'bg-primary-soft font-medium text-text' : 'text-text-2 hover:text-text'}`}
        >
          {t(`density.${d}`)}
        </button>
      ))}
    </div>
  )
  const toolbar = (
    <>
      {densities}
      <Button size="sm" variant="ghost" onClick={feed.refresh}>
        {t('feed.refresh')}
      </Button>
    </>
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      {page.failure && (
        <ErrorPanel
          failure={'failure' in page.failure ? page.failure.failure : null}
          error={'error' in page.failure ? page.failure.error : undefined}
          onRetry={() => void pager.retry()}
        />
      )}
      {total === 0 ? (
        <>
          <div className="flex items-center gap-2">
            <span className="flex-1" />
            {toolbar}
          </div>
          <p className="text-sm text-text-2">{emptyText ?? t('feed.empty')}</p>
        </>
      ) : (
        <PhotoBrowser
          total={total}
          sections={sections}
          photoAt={photoAt}
          loaded={page.rows}
          need={need}
          times={times}
          scrollToIndex={startAt}
          onTopIndex={onTopIndex}
          tileTarget={TILE_TARGET[density]}
          headerFormat={HEADER_FORMAT[density]}
          viewer={viewer}
          onTrashed={feed.refresh}
          toolbar={toolbar}
        />
      )}
    </div>
  )
}
