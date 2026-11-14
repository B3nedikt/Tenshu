// The top bar's bell (docs/proposals/web-apex-home.md §3.4, D3): what needs action, then the last
// four notifications, like the desktop tray's popover — a short list, not a second feed. The full
// history stays Control's notification page.
//
// - The badge counts what NEEDS ACTION (today: an unverified or stale recovery phrase — the web has
//   no device-join approval surface yet). Plain notifications are not "unread" on the web: there is
//   no read state to count.
// - ⚠ A failed notification read says so; it is never shown as "nothing new".
import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { getRecoveryStatus, type RecoveryStatus } from '../control/recoveryStatus'
import { BellIcon } from './icons'
import { useStatusSource, type RecentEvent } from './statusContext'
import { usePopover } from './usePopover'

const RECENT = 4

export function Bell() {
  const { t, i18n } = useTranslation('control')
  const source = useStatusSource()
  const { open, toggle, close, triggerRef, panelRef } = usePopover()
  const panelId = useId()
  const [recovery, setRecovery] = useState<RecoveryStatus | null>(null)
  const [events, setEvents] = useState<readonly RecentEvent[] | null | 'loading'>('loading')

  useEffect(() => {
    let live = true
    void getRecoveryStatus().then(s => {
      if (live) setRecovery(s)
    })
    return () => {
      live = false
    }
  }, [])

  useEffect(() => {
    if (source === null) return
    return source.observeRecent(RECENT, setEvents)
  }, [source])

  const needsRecovery = recovery !== null && recovery.kind !== 'verified'
  const actionCount = needsRecovery ? 1 : 0
  const label =
    actionCount > 0 ? t('bell.ariaLabelWithCount', { count: actionCount }) : t('bell.ariaLabel')

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        aria-label={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className="relative rounded-m p-2 text-text-2 hover:bg-well hover:text-text focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
      >
        <BellIcon size={20} />
        {actionCount > 0 && (
          <span
            aria-hidden
            className="absolute right-1 top-1 grid h-4 min-w-4 place-items-center rounded-pill bg-attention px-1 text-[10px] font-bold text-on-attention"
          >
            {actionCount}
          </span>
        )}
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="dialog"
          aria-label={t('bell.title')}
          className="absolute right-0 top-full z-30 mt-2 w-80 max-w-[calc(100vw-2rem)] rounded-lg border border-line bg-surface p-3 shadow-lg"
        >
          {needsRecovery && (
            <section aria-labelledby={`${panelId}-action`} className="mb-3">
              <h3 id={`${panelId}-action`} className="px-1 text-xs font-semibold uppercase tracking-wide text-text-3">
                {t('bell.needsAction')}
              </h3>
              <Link
                to="/control/settings/recovery"
                onClick={() => close(false)}
                className="mt-1 flex items-center gap-2 rounded-md px-2 py-2 text-sm text-text hover:bg-surface-2"
              >
                <span aria-hidden className="h-2 w-2 shrink-0 rounded-full bg-warning" />
                {recovery?.kind === 'stale' ? t('recoveryBanner.titleReverify') : t('recoveryBanner.titleVerify')}
              </Link>
            </section>
          )}
          <section aria-labelledby={`${panelId}-recent`}>
            <h3 id={`${panelId}-recent`} className="px-1 text-xs font-semibold uppercase tracking-wide text-text-3">
              {t('bell.recent')}
            </h3>
            {events === 'loading' ? (
              <p className="px-2 py-3 text-sm text-text-2">{t('shell.loading')}</p>
            ) : events === null ? (
              <p role="status" className="px-2 py-3 text-sm text-text-2">
                {t('bell.failed')}
              </p>
            ) : events.length === 0 ? (
              <p className="px-2 py-3 text-sm text-text-2">{t('bell.empty')}</p>
            ) : (
              <ul className="mt-1">
                {events.map(e => (
                  <li key={e.id} className="flex items-baseline gap-3 rounded-md px-2 py-2 text-sm">
                    <span className="min-w-0 flex-1 truncate text-text">{eventText(t, e)}</span>
                    <span className="shrink-0 text-xs text-text-3">
                      {eventTime(i18n.language, e.timestamp)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <div className="mt-2 border-t border-line pt-2">
            <Link
              to="/control/home"
              onClick={() => close(false)}
              className="block rounded-md px-2 py-2 text-sm text-primary hover:bg-surface-2"
            >
              {t('bell.all')}
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}

const KINDS = [
  'file_added',
  'folder_added',
  'files_added',
  'file_deleted',
  'folder_deleted',
  'change_lost',
  'app_registered',
  'app_removed',
] as const

/** The tray's wording, by kind. File kinds name the last path segment; an unknown kind reads generic. */
function eventText(t: TFunction, e: RecentEvent): string {
  const isFile = e.kind.includes('file') || e.kind.includes('folder') || e.kind === 'change_lost'
  const trimmed = e.name.replace(/\/+$/, '')
  const name = isFile ? trimmed.slice(trimmed.lastIndexOf('/') + 1) || e.name : e.name
  const kind = (KINDS as readonly string[]).includes(e.kind) ? e.kind : 'other'
  return t(`bell.event.${kind}`, { name, count: e.otherCount })
}

/** "14:03" today; a date once it is older — four days ago must not read like this afternoon. */
function eventTime(language: string, ms: number): string {
  const d = new Date(ms)
  const sameDay = d.toDateString() === new Date().toDateString()
  return sameDay
    ? d.toLocaleTimeString(language, { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString(language, { day: 'numeric', month: 'short' })
}
