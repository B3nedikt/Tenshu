// History: every workout, newest first, over the SDK's keyset pages (`lib/history.ts`) — as native,
// a summary at the top of each week and a header for each day (`lib/historyRows.ts`,
// fit-web-parity.md §2.4).
//
// ⚠⚠ Virtualised: a history can be ~4.5k rows, so only the rows in (and just around) the viewport
// are in the DOM, each at its kind's fixed height, and the next page is requested as the window nears
// the end of what is loaded. ⚠ No virtualisation dependency: the window is a binary search over the
// rows' tops, and a library would be the bundle's biggest dependency after React.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { FitWorkout } from '@tenshu/sdk/fit'
import { clockText, dateLabel, distanceText } from '../lib/format'
import { historyLayout, visibleRows, WORKOUT_HEIGHT, type HistoryRow } from '../lib/historyRows'
import { PageFailure, useHistory } from '../lib/history'
import type { Open } from '../lib/nav'
import { useUnits } from '../lib/units'
import { workoutTitle } from '../lib/workout'
import { ImportPanel } from './ImportPanel'
import { Loading, ReadFailure, Stopped } from './ui'

// Ask for the next page when this many loaded workouts remain below the viewport.
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
  const layout = useMemo(() => historyLayout(history.rows), [history.rows])
  const { first, last } = visibleRows(layout, scrollTop, viewport)
  // The last workout drawn, in workout terms (headers do not count towards the prefetch distance).
  const lastWorkout = layout.rows
    .slice(first, last + 1)
    .reduce((m, r) => (r.kind === 'workout' ? Math.max(m, r.index) : m), -1)

  const nearEnd = history.rows.length > 0 && lastWorkout >= history.rows.length - PREFETCH_ROWS
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
              style={{ height: layout.height }}
            >
              {layout.rows.slice(first, last + 1).map((row, i) => (
                <Row key={row.key} row={row} top={layout.tops[first + i]!} onOpen={onOpen} />
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

function Row({ row, top, onOpen }: { row: HistoryRow; top: number; onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  if (row.kind === 'workout') return <WorkoutRow workout={row.workout} top={top} onOpen={onOpen} />
  if (row.kind === 'day')
    return (
      <li className="absolute inset-x-0 flex items-end px-4 pb-1 text-xs font-semibold text-text-2" style={{ top, height: 32 }} data-testid="day-header">
        {dateLabel(i18n.language, row.date, 'readout')}
      </li>
    )
  // The week's summary (native `HistoryScreen`'s): workouts, distance, time.
  const facts = [
    t('history.weekCount', { count: row.count }),
    row.distanceM > 0 ? distanceText(t, row.distanceM, units) : null,
    clockText(row.durationSec),
  ].filter((v): v is string => v !== null)
  return (
    <li className="absolute inset-x-0 flex items-center justify-between gap-3 border-b border-line bg-surface-2 px-4" style={{ top, height: 52 }} data-testid="week-summary">
      <span className="text-sm font-semibold text-text">{t('history.weekOf', { date: dateLabel(i18n.language, row.weekStart, 'readout') })}</span>
      <span className="text-xs tabular-nums text-text-2">{facts.join(' · ')}</span>
    </li>
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
    <li className="absolute inset-x-0" style={{ top, height: WORKOUT_HEIGHT }} data-testid="workout-row" data-id={workout.workoutId}>
      <button
        type="button"
        onClick={() => onOpen(workout.workoutId)}
        className="flex h-full w-full items-center justify-between gap-3 border-b border-line px-4 text-left hover:bg-surface-2"
      >
        <span className="min-w-0">
          <span className="block truncate text-sm font-medium text-text">{workoutTitle(t, workout)}</span>
          {/* The day is the header's for the eye; a screen reader moving row by row hears it here. */}
          <span className="block truncate text-xs text-text-2">
            <span className="sr-only">{dateLabel(i18n.language, workout.startLocal.slice(0, 10), 'readout')}, </span>
            {timeOf(i18n.language, workout.startLocal)}
          </span>
        </span>
        <span className="shrink-0 text-xs text-text-2">{facts.join(' · ')}</span>
      </button>
    </li>
  )
}

// The day is the header's: a row says its time, in the workout's own wall clock.
function timeOf(locale: string, startLocal: string): string {
  const [h, m] = startLocal.slice(11, 16).split(':').map(Number) as [number, number]
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(Date.UTC(2000, 0, 1, h, m))
}

