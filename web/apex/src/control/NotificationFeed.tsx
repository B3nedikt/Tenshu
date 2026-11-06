// Notifications feed for /control/home. Subscribes to the SDK's
// `Notifications.observe` stream — each emission delivers the full
// snapshot, not deltas (see tenshu-sdk-js/.../Notifications.kt). For
// Phase A the feed renders the snapshot in reverse-chronological order
// with mark-all-read + clear-individual UI affordances; the SDK doesn't
// yet expose mark/clear ops, so those are local-state-only filters
// today. The Phase B notification-service work wires the real ops.
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Notifications, type Notification } from '@tenshu/sdk/notifications'

const PAGE_SIZE = 20

export function NotificationFeed() {
  const { t } = useTranslation('control')
  const [items, setItems] = useState<readonly Notification[]>([])
  const [hiddenIds, setHiddenIds] = useState<ReadonlySet<string>>(new Set())
  const [pageCount, setPageCount] = useState(1)
  useEffect(() => Notifications.observe(setItems), [])
  const visible = useMemo(() => {
    const sorted = [...items].sort((a, b) => b.timestamp - a.timestamp)
    return sorted.filter(n => !hiddenIds.has(n.uuid))
  }, [items, hiddenIds])
  const shown = visible.slice(0, pageCount * PAGE_SIZE)
  const hasMore = visible.length > shown.length
  function markAllRead() {
    setHiddenIds(new Set(items.map(n => n.uuid)))
  }
  function clear(uuid: string) {
    setHiddenIds(prev => {
      const next = new Set(prev)
      next.add(uuid)
      return next
    })
  }
  if (items.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
        {t('notificationFeed.emptyAll')}
      </div>
    )
  }
  if (visible.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
        {t('notificationFeed.emptyCaughtUp')}
      </div>
    )
  }
  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm text-text-2">
          {t('notificationFeed.count', { count: visible.length })}
        </p>
        <button
          type="button"
          onClick={markAllRead}
          className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t('notificationFeed.markAllRead')}
        </button>
      </div>
      <ul className="divide-y divide-line rounded-md border border-line">
        {shown.map(n => (
          <li key={n.uuid} className="grid grid-cols-[1fr_max-content_max-content] items-center gap-3 px-4 py-3 text-sm">
            <span className="font-mono text-xs text-text-2 truncate">
              {n.uuid}
            </span>
            <span className="font-mono text-xs text-text-2">
              {formatRelative(t, n.timestamp)}
            </span>
            <button
              type="button"
              onClick={() => clear(n.uuid)}
              aria-label={t('notificationFeed.clearAriaLabel')}
              className="rounded p-1 text-text-3 hover:bg-surface-3 hover:text-text-2"
            >
              ✕
            </button>
          </li>
        ))}
      </ul>
      {hasMore && (
        <div className="mt-3 text-center">
          <button
            type="button"
            onClick={() => setPageCount(c => c + 1)}
            className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
          >
            {t('notificationFeed.showOlder')}
          </button>
        </div>
      )}
    </div>
  )
}

function formatRelative(t: TFunction, timestamp: number): string {
  const diff = Date.now() - timestamp
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return t('notificationFeed.relative.justNow')
  if (diff < hour) return t('notificationFeed.relative.minutes', { value: Math.floor(diff / minute) })
  if (diff < day) return t('notificationFeed.relative.hours', { value: Math.floor(diff / hour) })
  if (diff < 30 * day) return t('notificationFeed.relative.days', { value: Math.floor(diff / day) })
  return new Date(timestamp).toLocaleDateString()
}
