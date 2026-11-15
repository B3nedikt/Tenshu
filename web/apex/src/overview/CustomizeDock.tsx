// The dock in Customize mode (docs/proposals/web-apex-home.md §3.5.1, H3) — the only place the
// overview's layout changes, so a stray drag on the everyday page never rearranges it.
//
// - A − badge hides an app (Drive has none), with an Undo toast and no confirm — nothing is lost.
//   A second hide replaces the toast: the first app is then re-added from "Your apps".
// - Drag a tile onto another to reorder (fine pointers); every tile also has Move earlier / Move
//   later buttons. ⚠ Not a follow-up: drag alone fails WCAG 2.1.1, and HTML drag-and-drop does
//   nothing on touch — so the buttons hide behind hover ONLY for a fine, hovering pointer (an iPad is
//   wider than `sm` and still has neither).
// - Every change writes at once (`useShownAppsEditor`) and is announced in a live region. While a
//   write is in flight every control refuses (aria-disabled, NOT `disabled`: disabling the button
//   just pressed would drop keyboard focus mid-action). A failed write says so and nothing moves.
// - Focus follows the change: back to the pressed button after a move, to the neighbour after a hide.
// - Tiles are not links here: a click never navigates mid-edit.
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppIcon } from '../apps/AppIcon'
import { appLabel } from '../apps/appLabel'
import { HOSTED_APPS } from '../apps/registry'
import {
  ALWAYS_SHOWN,
  effectiveIds,
  shownApps,
  withMovedBy,
  withMovedTo,
  withRemoved,
  withRestored,
} from './shownApps'
import { useShownAppsEditor } from './useShownAppsEditor'
import { YourAppsDialog } from './YourAppsDialog'

const UNDO_MS = 10_000
const ADD_TILE_ID = 'customize-add'
const controlId = (appId: string, control: 'hide' | 'earlier' | 'later') => `customize-${appId}-${control}`

// Visible on touch and coarse pointers; behind hover/focus only where a fine pointer can hover.
const REVEAL_ON_HOVER =
  '[@media(hover:hover)_and_(pointer:fine)]:opacity-0 [@media(hover:hover)_and_(pointer:fine)]:group-hover:opacity-100 [@media(hover:hover)_and_(pointer:fine)]:group-focus-within:opacity-100'

interface UndoEntry {
  readonly appId: string
  readonly label: string
  readonly index: number
}

export function CustomizeDock() {
  const { t } = useTranslation('control')
  const { state, stored, pending, apply } = useShownAppsEditor()
  const [announcement, setAnnouncement] = useState('')
  const [undo, setUndo] = useState<UndoEntry | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  // The control to focus once the change has rendered. Re-applied on every render while a write is
  // in flight (the reorder render moves the node and blurs it), cleared once it has settled.
  const focusTarget = useRef<string | null>(null)

  useEffect(() => {
    if (undo === null) return
    const timer = setTimeout(() => setUndo(null), UNDO_MS)
    return () => clearTimeout(timer)
  }, [undo])

  useEffect(() => {
    const id = focusTarget.current
    if (id === null) return
    const el = document.getElementById(id)
    if (el !== null && document.activeElement !== el) el.focus()
    if (!pending) focusTarget.current = null
  })

  if (state.kind !== 'ready' || stored === null) {
    return (
      <p role="status" className="text-sm text-text-2">
        {state.kind === 'loading' ? t('shell.loading') : t('customize.unavailable')}
      </p>
    )
  }
  const current = stored
  const apps = shownApps(current, HOSTED_APPS)

  async function commit(next: readonly string[], announce: string, focusId: string): Promise<boolean> {
    setError(null)
    focusTarget.current = focusId
    const ok = await apply(next)
    if (ok) setAnnouncement(announce)
    else setError(t('customize.writeFailed'))
    return ok
  }

  async function hide(appId: string, label: string, i: number) {
    if (pending) return
    const neighbour = apps[i + 1] ?? apps[i - 1]
    const focusId = neighbour === undefined ? ADD_TILE_ID : controlId(neighbour.id, 'earlier')
    const index = effectiveIds(current).indexOf(appId)
    if (await commit(withRemoved(current, appId), t('customize.hidden', { app: label }), focusId)) {
      setUndo({ appId, label, index })
    }
  }

  async function restore(entry: UndoEntry) {
    if (pending) return
    // The toast goes only once the undo is saved: a failed one keeps its button to retry.
    if (
      await commit(
        withRestored(current, entry.appId, entry.index),
        t('customize.restored', { app: entry.label }),
        controlId(entry.appId, entry.appId === ALWAYS_SHOWN ? 'earlier' : 'hide'),
      )
    ) {
      setUndo(null)
    }
  }

  function move(appId: string, label: string, i: number, delta: number) {
    if (pending || i + delta < 0 || i + delta >= apps.length) return
    const next = withMovedBy(current, HOSTED_APPS, appId, delta)
    const position = shownApps(next, HOSTED_APPS).findIndex(app => app.id === appId) + 1
    void commit(
      next,
      t('customize.moved', { app: label, position, count: apps.length }),
      controlId(appId, delta < 0 ? 'earlier' : 'later'),
    )
  }

  function drop(targetId: string) {
    const appId = dragging
    setDragging(null)
    if (pending || appId === null || appId === targetId) return
    const app = apps.find(a => a.id === appId)
    const next = withMovedTo(current, HOSTED_APPS, appId, targetId)
    const position = shownApps(next, HOSTED_APPS).findIndex(a => a.id === appId) + 1
    void commit(
      next,
      t('customize.moved', { app: app ? appLabel(t, app) : appId, position, count: apps.length }),
      controlId(appId, 'earlier'),
    )
  }

  const moveButton =
    'grid h-8 w-8 place-items-center rounded-m border border-line text-text-2 hover:bg-surface-3 aria-disabled:cursor-default aria-disabled:opacity-30'

  return (
    <div className="flex flex-col gap-3">
      <ul className="grid grid-cols-4 gap-x-1 gap-y-4 sm:flex sm:flex-wrap sm:gap-4">
        {apps.map((app, i) => {
          const label = appLabel(t, app)
          const first = i === 0
          const last = i === apps.length - 1
          return (
            <li
              key={app.id}
              draggable={!pending}
              onDragStart={e => {
                e.dataTransfer.effectAllowed = 'move'
                e.dataTransfer.setData('text/plain', app.id)
                setDragging(app.id)
              }}
              onDragOver={e => {
                if (dragging !== null && dragging !== app.id) e.preventDefault()
              }}
              onDrop={e => {
                e.preventDefault()
                drop(app.id)
              }}
              onDragEnd={() => setDragging(null)}
              data-dragging={dragging === app.id || undefined}
              className="group relative flex cursor-grab flex-col items-center data-[dragging]:opacity-40 sm:w-28"
            >
              <div className="flex w-full flex-col items-center gap-2 rounded-[18px] px-0.5 py-1.5 text-text sm:gap-2.5 sm:border sm:border-glass-line sm:bg-glass sm:px-2 sm:pb-3 sm:pt-3.5 sm:shadow-glass">
                <AppIcon appId={app.id} label={label} size="lg" />
                <span className="max-w-full truncate text-xs font-medium sm:text-sm">{label}</span>
              </div>
              {app.id !== ALWAYS_SHOWN && (
                <button
                  type="button"
                  id={controlId(app.id, 'hide')}
                  aria-disabled={pending || undefined}
                  onClick={() => void hide(app.id, label, i)}
                  aria-label={t('customize.hideAriaLabel', { app: label })}
                  className="absolute -left-1.5 -top-1.5 grid h-7 w-7 place-items-center rounded-full border-2 border-surface bg-text text-base font-bold leading-none text-surface hover:opacity-90 aria-disabled:opacity-50"
                >
                  <span aria-hidden>−</span>
                </button>
              )}
              <div className={`mt-1.5 flex gap-1 ${REVEAL_ON_HOVER}`}>
                <button
                  type="button"
                  id={controlId(app.id, 'earlier')}
                  aria-disabled={pending || first || undefined}
                  onClick={() => move(app.id, label, i, -1)}
                  aria-label={t('customize.moveEarlierAriaLabel', { app: label })}
                  className={moveButton}
                >
                  <span aria-hidden>←</span>
                </button>
                <button
                  type="button"
                  id={controlId(app.id, 'later')}
                  aria-disabled={pending || last || undefined}
                  onClick={() => move(app.id, label, i, 1)}
                  aria-label={t('customize.moveLaterAriaLabel', { app: label })}
                  className={moveButton}
                >
                  <span aria-hidden>→</span>
                </button>
              </div>
            </li>
          )
        })}
        <li className="sm:w-28">
          <button
            type="button"
            id={ADD_TILE_ID}
            aria-disabled={pending || undefined}
            onClick={() => {
              if (!pending) setAdding(true)
            }}
            className="flex w-full flex-col items-center gap-2 rounded-[18px] px-0.5 py-1.5 text-text-2 hover:bg-glass aria-disabled:opacity-50 sm:gap-2.5 sm:border sm:border-dashed sm:border-text-3 sm:px-2 sm:pb-3 sm:pt-3.5"
          >
            <span aria-hidden className="grid h-16 w-16 place-items-center rounded-[16px] border-[1.5px] border-dashed border-text-3 sm:border-0">
              <svg viewBox="0 0 24 24" width={28} height={28} fill="none" stroke="currentColor" strokeWidth={1.7}>
                <path d="M12 5v14M5 12h14" />
              </svg>
            </span>
            <span className="text-xs font-medium sm:text-sm">{t('overview.addApp')}</span>
          </button>
        </li>
      </ul>
      {error !== null && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      {/* Every change is spoken: a reorder by button moves no focus to say it. */}
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {undo !== null && (
        <div
          role="status"
          className="fixed bottom-6 left-1/2 z-30 flex max-w-[calc(100vw-2rem)] -translate-x-1/2 items-center gap-4 rounded-pill bg-text py-2 pl-5 pr-2 text-sm text-surface shadow-lg"
        >
          <span>{t('customize.hiddenToast', { app: undo.label })}</span>
          <button
            type="button"
            aria-disabled={pending || undefined}
            onClick={() => void restore(undo)}
            className="shrink-0 rounded-pill bg-primary px-4 py-1.5 font-semibold text-on-primary hover:opacity-90 aria-disabled:opacity-50"
          >
            {t('customize.undo')}
          </button>
        </div>
      )}
      {adding && <YourAppsDialog onClose={() => setAdding(false)} />}
    </div>
  )
}
