import { useEffect, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useFeed } from '../lib/feed'
import { yearMarks } from '../lib/layout'
import { FEED_PAGE_SIZE } from '../lib/feedPager'
import { previewPathOf } from '../lib/media'
import { useOnScreen, useThumbnail } from '../lib/thumbnails'
import { ErrorPanel, Loading } from './ui'

// The Years collection (web-apps-layout.md §3.5): one card per year of the library, newest first,
// with its count and the year's newest photograph as the cover. A card opens the Library at that
// year. Built from the same capture-time index as the grid, so it costs one read, not a scan.
export function YearsView({ onOpenYear }: { onOpenYear: (startIndex: number) => void }) {
  const { t } = useTranslation()
  const feed = useFeed()
  const times = feed.times.data?.ok ? feed.times.data.times : null
  const marks = useMemo(() => (times ? yearMarks(times) : []), [times])

  if (feed.times.isPending) return <Loading />
  if (feed.times.error) return <ErrorPanel error={feed.times.error} onRetry={feed.refresh} />
  if (feed.times.data && !feed.times.data.ok) return <ErrorPanel failure={feed.times.data.error} onRetry={feed.refresh} />
  if (marks.length === 0) return <p className="text-sm text-text-2">{t('feed.empty')}</p>

  return (
    <ul className="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] gap-4 overflow-y-auto">
      {marks.map((mark) => (
        <li key={mark.year}>
          <YearCard
            year={mark.year}
            count={mark.count}
            coverIndex={mark.startIndex}
            onOpen={() => onOpenYear(mark.startIndex)}
          />
        </li>
      ))}
    </ul>
  )
}

function YearCard({ year, count, coverIndex, onOpen }: { year: number; count: number; coverIndex: number; onOpen: () => void }) {
  const { t } = useTranslation()
  const { pager, page } = useFeed()
  const ref = useRef<HTMLButtonElement>(null)
  const onScreen = useOnScreen(ref)
  // The cover is the year's first (newest) row of the feed. ⚠⚠ The keyset pager only walks forward,
  // so asking for an old year's cover would page in every row before it — the whole library, for
  // the oldest. A cover is paged in only when it is within one page of what is held; the rest show
  // their plain card until scrolling the Library has brought them in.
  const reachable = coverIndex < page.rows.length + FEED_PAGE_SIZE
  useEffect(() => {
    if (onScreen && reachable) void pager.ensure(coverIndex)
  }, [onScreen, reachable, pager, coverIndex])
  const cover = page.rows[coverIndex]
  const url = useThumbnail(cover ? previewPathOf(cover) : null, onScreen)

  return (
    <button
      ref={ref}
      type="button"
      onClick={onOpen}
      aria-label={t('years.open', { year })}
      className="relative block h-52 w-full overflow-hidden rounded-2xl bg-surface-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      {url && <img src={url} alt="" draggable={false} className="absolute inset-0 size-full object-cover" />}
      <span className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent px-4 pt-8 pb-3">
        <span className="block text-2xl font-bold text-white">{year}</span>
        <span className="block text-sm text-white/85">{t('years.count', { count })}</span>
      </span>
    </button>
  )
}
