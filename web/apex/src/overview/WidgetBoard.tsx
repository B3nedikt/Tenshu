// The overview's widgets (docs/proposals/web-apex-home.md §4.12): "Your apps" (the dock) and one
// preview card per app, in the user's order, on a 6-column grid — the dock takes a full row, a wide
// card half, a narrow one a third (`packSpans`); everything stacks on a phone.
//
// In edit mode (`/overview/customize`) every widget wiggles and carries a − badge, and can be moved:
// drag one onto another (fine pointers), or the ← / → buttons on each. ⚠ Not a follow-up: drag alone
// fails WCAG 2.1.1, and HTML drag-and-drop does nothing on touch. Content is `inert` while editing:
// a click never navigates mid-edit. Reduced motion: no wiggle, a dashed outline instead.
// - Removing writes at once, with an Undo toast and no confirm — nothing is lost, and it never
//   uninstalls (the dock's "Your apps" dialog does that).
// - While a write is in flight every control refuses (aria-disabled, NOT `disabled`: disabling the
//   button just pressed would drop keyboard focus mid-action). A failed write says so; nothing moves.
// - Every change is announced; focus follows it (the pressed button after a move, the neighbour's
//   after a removal).
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { HostedApp } from '../apps/registry'
import { AppDock } from './AppDock'
import { GlanceCard } from './glance/GlanceCard'
import { scopedCall, useGlanceGateway, type GatewayCall } from './glance/glanceGateway'
import { packSpans, SPAN_CLASS } from './glance/packSpans'
import { useWidgets } from './useWidgets'
import {
  ADD_WIDGET_ID,
  widgetLabel,
  withWidgetAdded,
  withWidgetMovedBy,
  withWidgetMovedTo,
  withWidgetRemoved,
  type Widget,
} from './widgets'

const UNDO_MS = 10_000
const controlId = (key: string, control: 'remove' | 'earlier' | 'later') =>
  `widget-${key.replace(/[^a-z0-9-]/gi, '-')}-${control}`

interface UndoEntry {
  readonly key: string
  readonly label: string
  readonly index: number
}

export function WidgetBoard({ editing }: { readonly editing: boolean }) {
  const { t } = useTranslation('control')
  const gateway = useGlanceGateway()
  const { widgets: all, installed, stored, pending, apply } = useWidgets()
  // A card needs the gateway that loads it; without one (no runtime) there are only the widgets
  // that draw on their own.
  const widgets = gateway === null ? all.filter(w => w.kind === 'apps') : all
  const spans = packSpans(widgets.map(w => (w.kind === 'apps' ? 'full' : w.app.glance!.size)))
  const [announcement, setAnnouncement] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [undo, setUndo] = useState<UndoEntry | null>(null)
  const [dragging, setDragging] = useState<string | null>(null)
  const focusTarget = useRef<string | null>(null)

  useEffect(() => {
    if (undo === null) return
    const timer = setTimeout(() => setUndo(null), UNDO_MS)
    return () => clearTimeout(timer)
  }, [undo])

  // Re-applied every render while a write is in flight (the reorder render moves the node and blurs
  // it), cleared once it has settled.
  useEffect(() => {
    const id = focusTarget.current
    if (id === null) return
    const el = document.getElementById(id)
    if (el !== null && document.activeElement !== el) el.focus()
    if (!pending) focusTarget.current = null
  })

  const canEdit = editing && stored !== null

  async function commit(next: readonly string[], announce: string, focusId: string): Promise<boolean> {
    setError(null)
    focusTarget.current = focusId
    const ok = await apply(next)
    if (ok) setAnnouncement(announce)
    else setError(t('customize.writeFailed'))
    return ok
  }

  async function remove(widget: Widget, i: number) {
    if (pending || stored === null) return
    const label = widgetLabel(t, widget)
    const neighbour = widgets[i + 1] ?? widgets[i - 1]
    const focusId = neighbour === undefined ? ADD_WIDGET_ID : controlId(neighbour.key, 'remove')
    if (await commit(withWidgetRemoved(stored, installed, widget.key), t('customize.removed', { widget: label }), focusId)) {
      setUndo({ key: widget.key, label, index: i })
    }
  }

  async function restore(entry: UndoEntry) {
    if (pending || stored === null) return
    // The toast goes only once the undo is saved: a failed one keeps its button to retry.
    if (
      await commit(
        withWidgetAdded(stored, installed, entry.key, entry.index),
        t('customize.restored', { widget: entry.label }),
        controlId(entry.key, 'remove'),
      )
    ) {
      setUndo(null)
    }
  }

  function move(widget: Widget, i: number, delta: number) {
    if (pending || stored === null || i + delta < 0 || i + delta >= widgets.length) return
    void commit(
      withWidgetMovedBy(stored, installed, widget.key, delta),
      t('customize.moved', { widget: widgetLabel(t, widget), position: i + delta + 1, count: widgets.length }),
      controlId(widget.key, delta < 0 ? 'earlier' : 'later'),
    )
  }

  function drop(target: Widget) {
    const key = dragging
    setDragging(null)
    if (pending || stored === null || key === null || key === target.key) return
    const widget = widgets.find(w => w.key === key)
    if (widget === undefined) return
    void commit(
      withWidgetMovedTo(stored, installed, key, target.key),
      t('customize.moved', {
        widget: widgetLabel(t, widget),
        position: widgets.indexOf(target) + 1,
        count: widgets.length,
      }),
      controlId(key, 'remove'),
    )
  }

  const moveButton =
    'grid h-8 w-8 place-items-center rounded-full border border-glass-line bg-surface text-text-2 shadow-glass hover:bg-surface-3 aria-disabled:cursor-default aria-disabled:opacity-30'

  return (
    <>
      <div className="grid gap-5 lg:grid-cols-6">
        {widgets.map((widget, i) => {
          const span = SPAN_CLASS[spans[i]!] ?? ''
          const content = <WidgetContent widget={widget} gateway={gateway} />
          if (!canEdit) {
            return (
              <div key={widget.key} className={span}>
                {content}
              </div>
            )
          }
          const label = widgetLabel(t, widget)
          return (
            <div
              key={widget.key}
              role="group"
              aria-label={label}
              draggable={!pending}
              onDragStart={e => {
                e.dataTransfer.effectAllowed = 'move'
                e.dataTransfer.setData('text/plain', widget.key)
                setDragging(widget.key)
              }}
              onDragOver={e => {
                if (dragging !== null && dragging !== widget.key) e.preventDefault()
              }}
              onDrop={e => {
                e.preventDefault()
                drop(widget)
              }}
              onDragEnd={() => setDragging(null)}
              data-dragging={dragging === widget.key || undefined}
              className={`relative cursor-grab data-[dragging]:opacity-40 ${span}`}
            >
              <div
                inert
                // Staggered, so the board does not wiggle in lockstep.
                style={{ animationDelay: `${(i % 3) * -0.13}s` }}
                className={`widget-wiggle h-full rounded-[22px] select-none ${
                  // The dock sits bare on the wallpaper; while editing it gets a card's edge, so the
                  // − has a corner to sit on and the widget reads as one thing to move.
                  widget.kind === 'apps' ? 'border border-glass-line bg-glass px-5 pb-4 pt-5 shadow-glass' : ''
                }`}
              >
                {content}
              </div>
              <button
                type="button"
                id={controlId(widget.key, 'remove')}
                aria-disabled={pending || undefined}
                onClick={() => void remove(widget, i)}
                aria-label={t('customize.removeAriaLabel', { widget: label })}
                className="absolute -left-2 -top-2 z-10 grid h-8 w-8 place-items-center rounded-full border-2 border-surface bg-text text-lg font-bold leading-none text-surface shadow-glass hover:opacity-90 aria-disabled:opacity-50"
              >
                <span aria-hidden>−</span>
              </button>
              <div className="absolute -top-3 right-3 z-10 flex gap-1.5">
                <button
                  type="button"
                  id={controlId(widget.key, 'earlier')}
                  aria-disabled={pending || i === 0 || undefined}
                  onClick={() => move(widget, i, -1)}
                  aria-label={t('customize.moveEarlierAriaLabel', { widget: label })}
                  className={moveButton}
                >
                  <span aria-hidden>←</span>
                </button>
                <button
                  type="button"
                  id={controlId(widget.key, 'later')}
                  aria-disabled={pending || i === widgets.length - 1 || undefined}
                  onClick={() => move(widget, i, 1)}
                  aria-label={t('customize.moveLaterAriaLabel', { widget: label })}
                  className={moveButton}
                >
                  <span aria-hidden>→</span>
                </button>
              </div>
            </div>
          )
        })}
      </div>
      {editing && widgets.length === 0 && stored !== null && (
        <p className="py-10 text-center text-sm text-text-2">{t('customize.empty')}</p>
      )}
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
          <span>{t('customize.removedToast', { widget: undo.label })}</span>
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
    </>
  )
}

function WidgetContent({ widget, gateway }: { readonly widget: Widget; readonly gateway: GatewayCall | null }): ReactNode {
  if (widget.kind === 'apps') return <AppsWidget />
  return gateway === null ? null : <ScopedCard app={widget.app} gateway={gateway} />
}

function AppsWidget() {
  const { t } = useTranslation('control')
  return (
    <section aria-labelledby="overview-apps" className="flex flex-col gap-4">
      <h2 id="overview-apps" className="text-xl font-semibold tracking-tight text-text">
        {t('overview.appsHeading')}
      </h2>
      <AppDock />
    </section>
  )
}

function ScopedCard({ app, gateway }: { readonly app: HostedApp; readonly gateway: GatewayCall }) {
  // Stable per app, so the card's load effect runs once rather than on every render.
  const call = useMemo(() => scopedCall(app, gateway), [app, gateway])
  return <GlanceCard app={app} glance={app.glance!} call={call} spanClass="h-full" />
}
