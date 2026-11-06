// Subscribed feeds, as ROWS (calendar-app.md §Web scope W2, decided): list, add, pause, remove.
// ⚠⚠ This browser never fetches a feed — the user's phone and desktop do, and the events reach
// this browser through ordinary sync. Adding one here is consent to THOSE devices fetching it, so
// the add dialog says so before the write.
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { CalendarSubscription } from '@tenshu/sdk/calendar'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { useCalendars, useSubscriptions } from '../lib/live'
import { attempt, type Outcome } from '../lib/outcome'
import { addSubscription, removeSubscription, setSubscriptionEnabled } from '../lib/proxy'
import { Button, ConfirmDialog, Dialog, Field, Loading, OutcomeText, ReadError } from './ui'

export function SubscriptionsView() {
  const { t, i18n } = useTranslation()
  const subscriptions = useSubscriptions()
  const calendars = useCalendars()
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<CalendarSubscription | null>(null)
  const [outcome, setOutcome] = useState<Outcome>(null)

  const list = subscriptions.data?.ok ? subscriptions.data.subscriptions : null
  const names = new Map(calendars.data?.ok ? calendars.data.calendars.map((c) => [c.calendarId, c.name]) : [])
  const fetched = (ms: number) =>
    ms === 0
      ? t('subscriptions.neverFetched')
      : t('subscriptions.lastFetched', {
          when: new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(ms)),
        })

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text">{t('subscriptions.title')}</h2>
        <Button onClick={() => setAdding(true)}>{t('subscriptions.add')}</Button>
      </div>
      <p className="rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text-2">{t('subscriptions.fetchedBy')}</p>
      <StreamNotice live={subscriptions} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      <ReadError live={subscriptions} />
      <OutcomeText outcome={outcome} />
      {!list && !subscriptions.error && !(subscriptions.data && !subscriptions.data.ok) && <Loading />}
      {list && list.length === 0 && <p className="text-sm text-text-2">{t('subscriptions.empty')}</p>}
      {list && list.length > 0 && (
        <ul className="divide-y divide-line rounded-lg border border-line">
          {list.map((s) => (
            <li key={s.subscriptionId} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-text">{names.get(s.calendarId) ?? s.url}</p>
                <p className="truncate text-xs text-text-3">{s.url}</p>
                <p className="text-xs text-text-3">
                  {s.enabled ? fetched(s.lastFetchedEpochMs) : t('subscriptions.paused')}
                </p>
              </div>
              <Button
                size="sm"
                variant="outline"
                onClick={async () => setOutcome(await attempt(() => setSubscriptionEnabled(s.subscriptionId, !s.enabled)))}
              >
                {s.enabled ? t('subscriptions.pause') : t('subscriptions.resume')}
              </Button>
              <Button size="sm" variant="danger-ghost" onClick={() => setRemoving(s)}>
                {t('subscriptions.remove')}
              </Button>
            </li>
          ))}
        </ul>
      )}

      {adding && <AddSubscriptionDialog onClose={() => setAdding(false)} />}
      {removing && (
        <ConfirmDialog
          title={t('subscriptions.removeTitle')}
          confirmLabel={t('subscriptions.remove')}
          onConfirm={() => attempt(() => removeSubscription(removing.subscriptionId))}
          onClose={() => setRemoving(null)}
        >
          <p>{t('subscriptions.removeBody', { name: names.get(removing.calendarId) ?? removing.url })}</p>
        </ConfirmDialog>
      )}
    </section>
  )
}

function AddSubscriptionDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const [url, setUrl] = useState('')
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!url.trim()) return
    setBusy(true)
    setOutcome(null)
    const result = await attempt(() => addSubscription(url.trim(), name.trim()))
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={t('subscriptions.addTitle')} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Field
          autoFocus
          type="url"
          label={t('subscriptions.url')}
          placeholder="webcal://…"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
        />
        <Field label={t('subscriptions.name')} value={name} onChange={(e) => setName(e.target.value)} />
        <p className="text-sm text-text-2">{t('subscriptions.consent')}</p>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={busy || !url.trim()}>
            {busy ? t('common.working') : t('subscriptions.addConfirm')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
