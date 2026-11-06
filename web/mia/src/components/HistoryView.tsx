// History (`home-and-mia-apps.md` §4; native `HistoryScreen` + `ConversationDetailScreen`): the
// conversations, newest first, and a read-only thread for each.
//
// ⚠⚠ In memory only (MQ-D8, `mia-query-layer.md` §7.6): the history lives in this browser's Tenshu
// runtime and is gone on sign-out or lock, exactly as natively — never stored, so there is nothing
// to delete. The screen says so plainly, because a web user expects a chat history to persist.
// One conversation per session (`MiaBook.kt` rule 2), so the list holds at most one row today.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useHistory } from '../lib/live'
import { Turns } from './Turns'
import { Button, Card, Loading, ReadFailure, StreamEnded } from './ui'

export function HistoryView() {
  const { t, i18n } = useTranslation()
  const history = useHistory()
  const [open, setOpen] = useState<string | null>(null)

  const note = (
    <p className="rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-text-2" data-testid="memory-note">
      {t('history.memoryNote')}
    </p>
  )

  let body
  if (history.isPending) body = <Loading />
  else if (history.error) body = <ReadFailure error={history.error} onRetry={history.retry} />
  else if (history.data && !history.data.ok) body = <ReadFailure failure={history.data.error} onRetry={history.retry} />
  else {
    const conversations = history.data?.ok ? history.data.conversations : []
    const current = open ? conversations.find((c) => c.conversationId === open) : undefined
    if (current) {
      body = (
        <div>
          <Button size="sm" variant="ghost" className="mb-3" onClick={() => setOpen(null)}>
            ← {t('common.back')}
          </Button>
          <h2 className="mb-4 text-lg font-semibold text-text">{current.title}</h2>
          <Turns conversationId={current.conversationId} turnCount={current.turnCount} />
        </div>
      )
    } else if (conversations.length === 0) {
      body = <p className="text-sm text-text-2">{t('history.empty')}</p>
    } else {
      const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' })
      body = (
        <ul className="space-y-2">
          {conversations.map((c) => (
            <li key={c.conversationId}>
              <button type="button" className="block w-full text-left" onClick={() => setOpen(c.conversationId)}>
                <Card className="hover:border-line-strong">
                  <p className="text-sm font-medium text-text">{c.title}</p>
                  <p className="mt-1 text-xs text-text-2">
                    {t('history.meta', { date: date.format(new Date(c.startedAtMs)), count: c.turnCount })}
                  </p>
                </Card>
              </button>
            </li>
          ))}
        </ul>
      )
    }
  }

  return (
    <section aria-label={t('tabs.history')} className="space-y-4">
      {note}
      <StreamEnded live={history} />
      {body}
    </section>
  )
}
