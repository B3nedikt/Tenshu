// The places list (`place.list.observe`): every surviving place (a folded one is its survivor's,
// §Web scope 5c), with local search, and the map with a pin per place, anchored on one of them
// (`lib/anchor.ts`). ⚠ Search is the list's own
// (TS only, no egress — §Web scope 1); a native app would also search the world, so a query says
// that is coming rather than pretending the world has nothing.
import { useCallback, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { StreamNotice } from '@tenshu/app-kit/ui'
import type { MapCamera } from '@tenshu/map-kit'
import type { MapsPlace } from '@tenshu/sdk/maps'
import { usePlaces } from '../lib/live'
import { chooseAnchor, position } from '../lib/anchor'
import { filterIndex, placeLabel, roleName, searchIndex } from '../lib/format'
import { placeRecency } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import type { Open } from '../lib/nav'
import { OfflineMapsNote } from './OfflineMapsNote'
import { PlacesMap } from './PlacesMap'
import { Loading, ReadFailure } from './ui'

// ⚠ [active] false: the list is mounted but hidden behind an open place, so its search and scroll
// survive Back — it stops measuring until it is shown again.
export function PlacesView({ onOpen, active = true }: { onOpen: Open; active?: boolean }) {
  const { t } = useTranslation()
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
        <div className="rounded-lg border border-line bg-surface-2 px-4 py-4">
          <p className="text-sm font-medium text-text">{t('places.emptyTitle')}</p>
          <p className="mt-1 text-sm text-text-2">{t('places.emptyBody')}</p>
        </div>
      ) : (
        <PlacesBody places={result.places} onOpen={onOpen} active={active} />
      )}
      <OfflineMapsNote />
    </div>
  )
}

// One row's height. ⚠ Fixed (each line truncates), so the window is arithmetic, not measurement.
export const ROW_PX = 56
// Rows rendered beyond the viewport on each side.
const OVERSCAN = 10

// The rows in (or near) the viewport. The list scrolls with the page, so the window is the page's
// viewport against the list's own top. ⚠ Only while [active]: a hidden list measures as top 0.
function useWindowRange(list: RefObject<HTMLElement | null>, count: number, active: boolean) {
  const [range, setRange] = useState({ start: 0, end: Math.min(count, 2 * OVERSCAN) })
  useLayoutEffect(() => {
    if (!active) return
    function update() {
      const el = list.current
      if (!el) return
      const top = el.getBoundingClientRect().top
      const viewport = window.innerHeight || 800
      const start = Math.min(count, Math.max(0, Math.floor(-top / ROW_PX) - OVERSCAN))
      const end = Math.min(count, Math.max(start, Math.ceil((viewport - top) / ROW_PX) + OVERSCAN))
      setRange((r) => (r.start === start && r.end === end ? r : { start, end }))
    }
    update()
    window.addEventListener('scroll', update, { passive: true })
    window.addEventListener('resize', update)
    return () => {
      window.removeEventListener('scroll', update)
      window.removeEventListener('resize', update)
    }
  }, [list, count, active])
  return range
}

function PlacesBody({ places, onOpen, active }: { places: readonly MapsPlace[]; onOpen: Open; active: boolean }) {
  const { t, i18n } = useTranslation()
  const [query, setQuery] = useState('')
  const language = i18n.language
  // ⚠ Sorted and folded ONCE per list and language; a keystroke only filters.
  const index = useMemo(() => searchIndex(t, language, places), [t, language, places])
  const shown = useMemo(() => filterIndex(index, query), [index, query])
  const searching = query.trim() !== ''
  // What the map is about: what the list shows, so a search moves to its matches; nothing matched → every place.
  const target = shown.length > 0 ? shown : index.places
  const choice = useMemo(() => chooseAnchor(target), [target])
  // A tie between clusters is broken by the last visit — read only then. ⚠ A failed read leaves the
  // first tied cluster in list order: it decides only where the map opens, and the list beside it
  // never depends on it, so there is nothing to say.
  const recency = useQuery({
    queryKey: queryKeys.recency(),
    queryFn: placeRecency,
    enabled: choice.needsRecency,
    staleTime: 5 * 60_000,
    retry: false,
  })
  const anchor = useMemo(
    () => (choice.needsRecency && recency.data ? chooseAnchor(target, recency.data).anchor : choice.anchor),
    [choice, recency.data, target],
  )
  const camera = useMemo<MapCamera>(
    () => ({ kind: 'bounds', points: target.map(position), ...(anchor ? { anchor: position(anchor) } : {}) }),
    [target, anchor],
  )
  const openPin = useCallback((id: string) => onOpen({ kind: 'place', id }), [onOpen])

  const list = useRef<HTMLUListElement>(null)
  const range = useWindowRange(list, shown.length, active)
  const start = Math.min(range.start, shown.length)
  const end = Math.min(range.end, shown.length)

  return (
    <>
      <PlacesMap places={shown} camera={camera} onPinClick={openPin} />

      <div>
        <label className="block text-sm">
          <span className="sr-only">{t('search.label')}</span>
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('search.placeholder')}
            aria-label={t('search.label')}
            className="block w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm text-text placeholder:text-text-2"
          />
        </label>
        {/* D4: world search is gated on forward geocoding, which does not exist — say so. */}
        {searching && <p className="mt-2 text-xs text-text-2">{t('search.worldComing')}</p>}
      </div>

      {shown.length === 0 ? (
        <p className="text-sm text-text-2">{t('search.noMatches', { query: query.trim() })}</p>
      ) : (
        // ⚠ Virtualised: only the rows near the viewport exist; the padding stands in for the rest.
        <ul
          ref={list}
          className="divide-y divide-line"
          aria-label={t('places.listLabel')}
          style={{ paddingTop: start * ROW_PX, paddingBottom: (shown.length - end) * ROW_PX }}
        >
          {shown.slice(start, end).map((place, i) => {
            const label = placeLabel(t, language, place)
            const role = roleName(t, place.role)
            return (
              <li key={place.placeId} aria-setsize={shown.length} aria-posinset={start + i + 1}>
                <button
                  type="button"
                  onClick={() => onOpen({ kind: 'place', id: place.placeId })}
                  style={{ height: ROW_PX }}
                  className="flex w-full items-center justify-between gap-3 px-2 text-left hover:bg-surface-2"
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-text">{label.title}</span>
                    {label.secondary && <span className="block truncate text-xs text-text-2">{label.secondary}</span>}
                  </span>
                  {role && (
                    <span className="shrink-0 rounded-full border border-line px-2 py-0.5 text-xs text-text-2">{role}</span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
