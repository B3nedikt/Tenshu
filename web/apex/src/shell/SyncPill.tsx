// The top bar's sync state (docs/proposals/web-apex-home.md §3.4, H5b) — the tray's sync line on the
// web: a labelled pill on the overview, a dot inside an app, and on click the last sync, what is
// waiting, "Sync now" and Pause/Resume.
//
// ⚠ Pause is THIS BROWSER's switch (§4.7): device-local, persisted per browser, never synced. While
// paused the pill says "Paused" whatever the last row said, and Resume replaces Sync now — a kick
// cannot get past a pause.
// ⚠ Before the runtime has produced a sync row the pill says "Checking", never "Synced".
import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useStatusSource, type SyncRow } from './statusContext'
import { syncView, type Tone } from './syncView'
import { usePopover } from './usePopover'

const DOT: Record<Tone, string> = {
  ok: 'bg-success',
  busy: 'bg-primary animate-pulse',
  warn: 'bg-warning',
  muted: 'bg-text-3',
}

export function SyncPill({ compact }: { readonly compact: boolean }) {
  const { t, i18n } = useTranslation('control')
  const source = useStatusSource()
  const { open, toggle, triggerRef, panelRef } = usePopover()
  const panelId = useId()
  const [row, setRow] = useState<SyncRow | null>(null)
  const [lost, setLost] = useState(false)
  const [requested, setRequested] = useState<'idle' | 'pending' | 'sent' | 'failed'>('idle')
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (source === null) return
    return source.observeSync(
      next => {
        setLost(false)
        setRow(next)
      },
      () => setLost(true),
    )
  }, [source])

  useEffect(() => {
    if (source === null) return
    return source.observePaused(setPaused)
  }, [source])

  const view = syncView(row, lost, paused)
  const label = t(`sync.state.${view.key}`)

  async function syncNow() {
    // One kick at a time: a double click must not send two.
    if (source === null || requested === 'pending') return
    setRequested('pending')
    setRequested((await source.syncNow().catch(() => false)) ? 'sent' : 'failed')
  }

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => {
          // The last "Sync requested." belongs to the last time the panel was open.
          if (!open && requested !== 'pending') setRequested('idle')
          toggle()
        }}
        aria-label={t('sync.ariaLabel', { state: label })}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className={
          compact
            ? 'grid h-9 w-9 place-items-center rounded-m hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong'
            : 'flex h-8 items-center gap-2 rounded-pill border border-glass-line bg-glass px-3 text-sm font-medium text-text-2 shadow-glass hover:text-text focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong'
        }
      >
        <span aria-hidden className={`h-2 w-2 rounded-full ${DOT[view.tone]}`} />
        {/* On a phone the label would push the bar past the screen; the aria-label still says it. */}
        {!compact && <span aria-hidden className="max-sm:hidden">{label}</span>}
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="dialog"
          aria-label={t('sync.title')}
          className="absolute right-0 top-full z-30 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-lg border border-line bg-surface p-4 text-sm shadow-lg"
        >
          <p className="flex items-center gap-2 font-medium text-text">
            <span aria-hidden className={`h-2 w-2 rounded-full ${DOT[view.tone]}`} />
            {label}
          </p>
          {paused && <p className="mt-2 text-text-2">{t('sync.pausedNote')}</p>}
          {row !== null && !lost && (
            <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-text-2">
              <dt>{t('sync.lastSynced')}</dt>
              <dd className="text-right text-text">
                {row.lastSyncedAtMillis > 0
                  ? new Date(row.lastSyncedAtMillis).toLocaleString(i18n.language, {
                      dateStyle: 'medium',
                      timeStyle: 'short',
                    })
                  : t('sync.never')}
              </dd>
              <dt>{t('sync.pending')}</dt>
              <dd className="text-right text-text">{row.pendingBlockCount}</dd>
            </dl>
          )}
          {/* The buttons may wrap: German "Pausieren" + "Jetzt synchronisieren" overflow one row beside Devices. */}
          <div className="mt-4 flex items-center justify-between gap-2">
            <Link to="/control/data/devices" className="shrink-0 text-primary hover:underline">
              {t('sync.devices')}
            </Link>
            <div className="flex flex-wrap items-center justify-end gap-2">
              {paused ? (
                <button
                  type="button"
                  onClick={() => source?.setPaused(false)}
                  disabled={source === null}
                  className="rounded-pill bg-primary px-4 py-1.5 font-semibold text-on-primary hover:opacity-90 disabled:opacity-50"
                >
                  {t('sync.resume')}
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      setRequested('idle')
                      source?.setPaused(true)
                    }}
                    disabled={source === null}
                    className="rounded-pill border border-line px-3 py-1.5 font-semibold text-text hover:bg-surface-2 disabled:opacity-50"
                  >
                    {t('sync.pause')}
                  </button>
                  <button
                    type="button"
                    onClick={() => void syncNow()}
                    disabled={source === null}
                    aria-disabled={requested === 'pending' || undefined}
                    className="rounded-pill bg-primary px-4 py-1.5 font-semibold text-on-primary hover:opacity-90 disabled:opacity-50"
                  >
                    {t('sync.now')}
                  </button>
                </>
              )}
            </div>
          </div>
          {!paused && (requested === 'sent' || requested === 'failed') && (
            <p role="status" className="mt-2 text-xs text-text-2">
              {requested === 'sent' ? t('sync.requested') : t('sync.requestFailed')}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
