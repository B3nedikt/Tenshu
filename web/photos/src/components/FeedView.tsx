import { useCallback, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useFeed } from '../lib/feed'
import { DENSITIES, HEADER_FORMAT, MAX_COLUMNS, MIN_COLUMNS, TILE_TARGET, sectionsFor, type Density } from '../lib/layout'
import { useCloseViewerWhenEmpty, type ViewerRoute } from '../lib/viewerRoute'
import { PhotoBrowser } from './PhotoBrowser'
import { AspectRatioIcon, CropSquareIcon, GridViewIcon, MosaicIcon } from './icons'
import { Button, ErrorPanel, Loading } from './ui'

// The library: the capture-date grid over the keyset pager, section headers and the date bubble from
// the capture-time index, the selection and item menu, and the viewer over the same rows.
export function FeedView({
  density = 'months',
  onDensity,
  mosaic = false,
  fit = false,
  onLayout,
  columns,
  onColumns,
  startAt = null,
  viewer,
  emptyText,
}: {
  // Days / Months / Years (web-apps-layout.md §3.5) — the same feed grouped and sized three ways.
  density?: Density
  // With the feed index at the top, so the new density opens at the same date.
  onDensity?: (density: Density, topIndex: number) => void
  // The layout switches (web-apps-layout.md §4.9): native's Mosaic, and whole photographs rather
  // than square crops. ⚠ Mosaic does not apply to Years: a contact sheet's tiles are too small.
  mosaic?: boolean
  fit?: boolean
  onLayout?: (mosaic: boolean, fit: boolean) => void
  // The size slider (§4.10): photos per row, 8 … 3. ⚠ Not for Years either — its contact sheet is
  // the point of it. Omitted = sized by the density.
  columns?: number
  onColumns?: (columns: number) => void
  // Where to open (a Years card's first photograph).
  startAt?: number | null
  // The viewer's place in the URL (`open=<feed index>`); omitted = local state.
  viewer?: ViewerRoute
  // What an empty feed says (the Videos collection has its own line).
  emptyText?: string
} = {}) {
  const { t } = useTranslation()
  const feed = useFeed()
  const [drawn, setDrawn] = useState<number | null>(null)
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
  const mosaicApplies = density !== 'years'
  const switches = onLayout && (
    <div className="flex items-center gap-0.5">
      <button
        type="button"
        aria-pressed={mosaic && mosaicApplies}
        aria-label={t('layout.mosaic')}
        title={mosaicApplies ? t('layout.mosaic') : t('layout.mosaicYears')}
        disabled={!mosaicApplies}
        onClick={() => onLayout(!mosaic, fit)}
        className="rounded-md p-1.5 text-text-2 hover:bg-surface-2 hover:text-text disabled:opacity-40 disabled:hover:bg-transparent aria-pressed:text-primary"
      >
        {/* Like native's button, the icon is the CURRENT arrangement. */}
        {mosaic && mosaicApplies ? <MosaicIcon /> : <GridViewIcon />}
      </button>
      <button
        type="button"
        aria-pressed={fit}
        aria-label={t('layout.fit')}
        title={t('layout.fit')}
        onClick={() => onLayout(mosaic, !fit)}
        className="rounded-md p-1.5 text-text-2 hover:bg-surface-2 hover:text-text aria-pressed:text-primary"
      >
        {fit ? <AspectRatioIcon /> : <CropSquareIcon />}
      </button>
    </div>
  )
  // iCloud's slider: − makes photos smaller (more per row), + larger. The range runs left = small.
  // ⚠ Its label names the count DRAWN (`drawn`), which a narrow window or Mosaic's whole blocks can
  // make differ from the step — a label that says 8 over nine columns is a false statement.
  const sized = columns !== undefined && density !== 'years'
  const shown = drawn ?? columns
  const resize = (next: number) => {
    const clamped = Math.min(MAX_COLUMNS, Math.max(MIN_COLUMNS, next))
    if (onColumns && columns !== undefined && clamped !== columns) onColumns(clamped)
  }
  const slider = onColumns && columns !== undefined && (
    <div className="flex items-center gap-1 text-text-2" title={sized ? t('size.perRow', { count: shown }) : t('size.years')}>
      <button
        type="button"
        aria-label={t('size.smaller')}
        disabled={!sized || columns >= MAX_COLUMNS}
        onClick={() => resize(columns + 1)}
        className="rounded-md px-1.5 text-lg leading-6 hover:bg-surface-2 hover:text-text disabled:opacity-40 disabled:hover:bg-transparent"
      >
        −
      </button>
      <input
        type="range"
        aria-label={t('size.label')}
        aria-valuetext={t('size.perRow', { count: shown })}
        min={0}
        max={MAX_COLUMNS - MIN_COLUMNS}
        step={1}
        value={MAX_COLUMNS - columns}
        disabled={!sized}
        onChange={(e) => resize(MAX_COLUMNS - Number(e.currentTarget.value))}
        className="w-24 accent-primary disabled:opacity-40"
      />
      <button
        type="button"
        aria-label={t('size.larger')}
        disabled={!sized || columns <= MIN_COLUMNS}
        onClick={() => resize(columns - 1)}
        className="rounded-md px-1.5 text-lg leading-6 hover:bg-surface-2 hover:text-text disabled:opacity-40 disabled:hover:bg-transparent"
      >
        +
      </button>
    </div>
  )
  const toolbar = (
    <>
      {densities}
      {slider}
      {switches}
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
          mosaic={mosaic && mosaicApplies}
          columns={density === 'years' ? undefined : columns}
          onColumnsDrawn={setDrawn}
          fit={fit}
          headerFormat={HEADER_FORMAT[density]}
          viewer={viewer}
          onTrashed={feed.refresh}
          toolbar={toolbar}
        />
      )}
    </div>
  )
}
