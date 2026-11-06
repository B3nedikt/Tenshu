// History: every workout, newest first, over the SDK's keyset pages (`lib/history.ts`).
//
// ⚠⚠ Virtualised: a history can be ~4.5k rows, so only the rows in (and just around) the viewport
// are in the DOM, at a fixed row height, and the next page is requested as the window nears the end
// of what is loaded. ⚠ No virtualisation dependency: a fixed-height window is thirty lines, and a
// library would be the bundle's biggest dependency after React.
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { FitWorkout } from '@tenshu/sdk/fit'
import { clockText, dateTimeText, distanceText } from '../lib/format'
import { PageFailure, useHistory } from '../lib/history'
import type { Open } from '../lib/nav'
import { useUnits } from '../lib/units'
import { ROW_HEIGHT, visibleRange } from '../lib/virtual'
import { workoutTitle } from '../lib/workout'
import { ImportPanel } from './ImportPanel'
import { Loading, ReadFailure, Stopped } from './ui'

// Ask for the next page when this many loaded rows remain below the viewport.
const PREFETCH_ROWS = 20
// jsdom (and a not-yet-laid-out element) reports 0; render as if the viewport were this tall.
const FALLBACK_VIEWPORT = 640

export function HistoryView({ onOpen }: { onOpen: Open }) {
  const { t } = useTranslation()
  const history = useHistory()
  // The window: where the list is scrolled to and how tall it is, both read from the scroll event.
  const [view, setView] = useState({ scrollTop: 0, viewport: FALLBACK_VIEWPORT })
  const { scrollTop, viewport } = view
  const scroller = useRef<HTMLDivElement>(null)
  const hasRows = history.rows.length > 0
  // The real height once the list is laid out (a tall window shows more than the fallback's rows).
  useLayoutEffect(() => {
    const height = scroller.current?.clientHeight
    if (height) setView((v) => (v.viewport === height ? v : { ...v, viewport: height }))
  }, [hasRows])
  // The head was re-read (`refetchHead`): back to the top, where the re-read head is. Left scrolled
  // deep, the window would sit past the one page that remains, and ask for the next page at once.
  const pageCount = useRef(history.pageCount)
  useLayoutEffect(() => {
    if (history.pageCount < pageCount.current) {
      if (scroller.current) scroller.current.scrollTop = 0
      setView((v) => (v.scrollTop === 0 ? v : { ...v, scrollTop: 0 }))
    }
    pageCount.current = history.pageCount
  }, [history.pageCount])
  const { first, last } = visibleRange(scrollTop, viewport, history.rows.length)

  const nearEnd = history.rows.length > 0 && last >= history.rows.length - PREFETCH_ROWS
  const { fetchMore, hasMore } = history
  useEffect(() => {
    if (nearEnd && hasMore) fetchMore()
  }, [nearEnd, hasMore, fetchMore, history.rows.length])

  const pageFailure = history.error instanceof PageFailure ? history.error.failure : null

  return (
    <div>
      <ImportPanel />
      <Stopped live={history.changes} />
      {history.isPending ? (
        <Loading />
      ) : history.error && history.rows.length === 0 ? (
        <ReadFailure failure={pageFailure} error={history.error} onRetry={history.retry} />
      ) : history.rows.length === 0 ? (
        <p className="mt-4 text-sm text-text-2">{t('history.empty')}</p>
      ) : (
        <>
          <div
            ref={scroller}
            onScroll={(e) =>
              setView({ scrollTop: e.currentTarget.scrollTop, viewport: e.currentTarget.clientHeight || FALLBACK_VIEWPORT })
            }
            className="mt-4 h-[70vh] overflow-y-auto rounded-lg border border-line"
            data-testid="history-scroller"
          >
            <ul
              aria-label={t('history.title')}
              aria-rowcount={history.rows.length}
              data-loaded={history.rows.length}
              className="relative"
              style={{ height: history.rows.length * ROW_HEIGHT }}
            >
              {history.rows.slice(first, last + 1).map((w, i) => (
                <WorkoutRow key={w.workoutId} workout={w} top={(first + i) * ROW_HEIGHT} onOpen={onOpen} />
              ))}
            </ul>
          </div>
          {history.isFetchingMore && <Loading />}
          {/* A later page failed: the rows already read stay, and the failure says why no more come. */}
          {history.error && history.rows.length > 0 ? (
            <div className="mt-3">
              <ReadFailure
                failure={pageFailure}
                error={history.error}
                onRetry={history.retryMore}
                retryDisabled={history.isRetrying}
                // A bad cursor will never read: its one way on is the head, and the button says so.
                retryLabel={pageFailure?.kind === 'error_fit_bad_cursor' ? t('history.startOver') : undefined}
              />
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}

function WorkoutRow({ workout, top, onOpen }: { workout: FitWorkout; top: number; onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const facts = [
    clockText(workout.durationSec),
    workout.distanceM !== null && workout.distanceM > 0 ? distanceText(t, workout.distanceM, units) : null,
    t(`source.${workout.source}`),
  ].filter((v): v is string => v !== null)
  return (
    <li className="absolute inset-x-0" style={{ top, height: ROW_HEIGHT }} data-testid="workout-row" data-id={workout.workoutId}>
      <button
        type="button"
        onClick={() => onOpen(workout.workoutId)}
        className="flex h-full w-full items-center justify-between gap-3 border-b border-line px-4 text-left hover:bg-surface-2"
      >
        <span className="min-w-0">
          <span className="block truncate text-sm font-medium text-text">{workoutTitle(t, workout)}</span>
          <span className="block truncate text-xs text-text-2">{dateTimeText(i18n.language, workout.startMs)}</span>
        </span>
        <span className="shrink-0 text-xs text-text-2">{facts.join(' · ')}</span>
      </button>
    </li>
  )
}
