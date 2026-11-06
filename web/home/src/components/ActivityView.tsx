// The Activity tab (home-and-mia-apps.md §3, the iOS `ActivityView`): one chronological feed grouped by
// day (Today / Yesterday / the date), with native's filter — All, Devices, Automations, Voice.
//
// ⚠ Only automation runs are stored anywhere (device-event history and voice do not exist yet), so the
// Devices and Voice filters say what is missing rather than implying a quiet home — native's three
// sentences. ⚠⚠ Virtualised over keyset pages (`lib/activity.ts`): only the rows in (and just around)
// the viewport are in the DOM, at a fixed height, and the next page is asked for as the window nears the
// end of what is loaded. Fit's History pattern (`web/fit`).
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { HomeActivity } from '@tenshu/sdk/home'
import { PageFailure, useActivity } from '../lib/activity'
import { useNow } from '../lib/clock'
import { FILTERS, withDays, type Filter } from '../lib/days'
import { clockText, outcomeText } from '../lib/format'
import { ROW_HEIGHT, visibleRange } from '../lib/virtual'
import { EmptyState, Loading, ReadFailure, Stopped } from './ui'

// Ask for the next page when this many loaded rows remain below the viewport.
const PREFETCH_ROWS = 20
// jsdom (and a not-yet-laid-out element) reports 0; render as if the viewport were this tall.
const FALLBACK_VIEWPORT = 640

export function ActivityView() {
  const { t } = useTranslation()
  const [filter, setFilter] = useState<Filter>('all')
  return (
    <div>
      <div role="group" aria-label={t('activity.filter')} className="mb-4 flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={filter === f}
            onClick={() => setFilter(f)}
            className={`rounded-full border px-3 py-1 text-xs font-medium ${
              filter === f ? 'border-primary bg-primary/10 text-primary' : 'border-line text-text-2 hover:text-text'
            }`}
          >
            {t(`activity.filters.${f}`)}
          </button>
        ))}
      </div>
      {/* Nothing records device events or voice: no read could answer, so none is made. */}
      {filter === 'device' ? (
        <EmptyState title={t('activity.emptyTitle')} body={t('activity.emptyDevices')} />
      ) : filter === 'voice' ? (
        <EmptyState title={t('activity.emptyTitle')} body={t('activity.emptyVoice')} />
      ) : (
        <Feed />
      )}
    </div>
  )
}

function Feed() {
  const { t, i18n } = useTranslation()
  const activity = useActivity()
  const [view, setView] = useState({ scrollTop: 0, viewport: FALLBACK_VIEWPORT })
  const { scrollTop, viewport } = view
  const scroller = useRef<HTMLDivElement>(null)
  // Today / Yesterday move at midnight: the render clock's minute tick re-labels them (no read).
  const now = useNow(null)
  const items = useMemo(() => withDays(t, i18n.language, activity.rows, now), [t, i18n.language, activity.rows, now])
  const hasRows = items.length > 0
  useLayoutEffect(() => {
    const height = scroller.current?.clientHeight
    if (height) setView((v) => (v.viewport === height ? v : { ...v, viewport: height }))
  }, [hasRows])
  // The head was re-read: back to the top, where the re-read head is. Left scrolled deep, the window
  // would sit past the one page that remains and ask for the next page at once.
  const pageCount = useRef(activity.pageCount)
  useLayoutEffect(() => {
    if (activity.pageCount < pageCount.current) {
      if (scroller.current) scroller.current.scrollTop = 0
      setView((v) => (v.scrollTop === 0 ? v : { ...v, scrollTop: 0 }))
    }
    pageCount.current = activity.pageCount
  }, [activity.pageCount])
  const { first, last } = visibleRange(scrollTop, viewport, items.length)

  const nearEnd = items.length > 0 && last >= items.length - PREFETCH_ROWS
  const { fetchMore, hasMore } = activity
  useEffect(() => {
    if (nearEnd && hasMore) fetchMore()
  }, [nearEnd, hasMore, fetchMore, items.length])

  const pageFailure = activity.error instanceof PageFailure ? activity.error.failure : null

  return (
    <div>
      <Stopped live={activity.changes} />
      {activity.isPending ? (
        <Loading />
      ) : activity.error && activity.rows.length === 0 ? (
        <ReadFailure failure={pageFailure} error={activity.error} onRetry={activity.retry} />
      ) : activity.rows.length === 0 ? (
        <EmptyState title={t('activity.emptyTitle')} body={t('activity.empty')} />
      ) : (
        <>
          <div
            ref={scroller}
            onScroll={(e) =>
              setView({ scrollTop: e.currentTarget.scrollTop, viewport: e.currentTarget.clientHeight || FALLBACK_VIEWPORT })
            }
            className="h-[70vh] overflow-y-auto rounded-lg border border-line"
            data-testid="activity-scroller"
          >
            <ul
              aria-label={t('nav.activity')}
              data-loaded={activity.rows.length}
              className="relative"
              style={{ height: items.length * ROW_HEIGHT }}
            >
              {items.slice(first, last + 1).map((item, i) => {
                const top = (first + i) * ROW_HEIGHT
                return item.type === 'day' ? (
                  <li
                    key={`day:${item.key}`}
                    className="absolute inset-x-0 flex items-end px-4 pb-2 text-xs font-semibold uppercase tracking-wide text-text-2"
                    style={{ top, height: ROW_HEIGHT }}
                    data-testid="day-header"
                  >
                    {item.label}
                  </li>
                ) : (
                  <EventRow key={item.event.firingId} event={item.event} top={top} />
                )
              })}
            </ul>
          </div>
          {activity.isFetchingMore && <Loading />}
          {/* A later page failed: the rows already read stay, and the failure says why no more come. */}
          {activity.error && activity.rows.length > 0 ? (
            <div className="mt-3">
              <ReadFailure
                failure={pageFailure}
                error={activity.error}
                onRetry={activity.retryMore}
                retryDisabled={activity.isRetrying}
                // A bad cursor will never read: its one way on is the head, and the button says so.
                retryLabel={pageFailure?.kind === 'error_home_bad_cursor' ? t('activity.startOver') : undefined}
              />
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}

function EventRow({ event, top }: { event: HomeActivity; top: number }) {
  const { t, i18n } = useTranslation()
  return (
    <li
      className="absolute inset-x-0 flex items-center justify-between gap-3 border-b border-line px-4"
      style={{ top, height: ROW_HEIGHT }}
      data-testid="activity-row"
      data-id={event.firingId}
    >
      <span className="min-w-0">
        {/* ⚠ A deleted routine leaves its firings behind; the feed keeps them and says so. */}
        <span className="block truncate text-sm font-medium text-text">
          {event.routineName.trim() !== '' ? event.routineName : t('activity.deletedRoutine')}
        </span>
        <span className="block truncate text-xs text-text-2">{outcomeText(t, event)}</span>
      </span>
      <span className="shrink-0 text-xs text-text-2">{clockText(i18n.language, event.claimedAtMs)}</span>
    </li>
  )
}
