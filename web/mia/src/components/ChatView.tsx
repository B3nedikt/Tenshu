// The chat (`home-and-mia-apps.md` §4; native `ChatScreen`): the session's conversation and a text
// input. Text only — no mic (web-app-suite.md §2), no confirmation cell (every action is withheld in
// a tab, so none can arrive).
//
// ⚠ The input is bounded to the gateway's limit (500, trimmed). A question Mia refused before asking
// (blank, too long, a sign-out mid-question) is an error by kind under the input, and the text stays
// so it can be sent again; a question Mia asked is a turn, whatever it answered.
import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ask, MAX_UTTERANCE_CHARS } from '../lib/proxy'
import { useHistory } from '../lib/live'
import { queryKeys } from '../lib/queryKeys'
import type { Failure } from '../lib/errors'
import { Turns } from './Turns'
import { Button, ErrorText, Loading, ReadFailure, StreamEnded } from './ui'

type Refusal = { failure: Failure } | { error: unknown } | null

// Shown as a remaining count once the question gets this close to the bound.
const COUNT_FROM = MAX_UTTERANCE_CHARS - 100

export function ChatView() {
  const { t } = useTranslation()
  const history = useHistory()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [refusal, setRefusal] = useState<Refusal>(null)

  const replying = history.data?.ok === true && history.data.replying
  const conversation = history.data?.ok === true ? (history.data.conversations[0] ?? null) : null
  const blank = text.trim() === ''

  async function send(e: FormEvent) {
    e.preventDefault()
    if (blank || sending) return
    setSending(true)
    setRefusal(null)
    const sent = text
    try {
      const result = await ask(sent)
      if (result.ok) {
        // ⚠ Only if it is still what was sent: the input stays editable while Mia answers, and a
        // draft of the next question must not be wiped by the previous answer arriving.
        setText((current) => (current === sent ? '' : current))
        void queryClient.invalidateQueries({ queryKey: queryKeys.turns(result.conversationId) })
      } else {
        setRefusal({ failure: result.error })
      }
    } catch (error) {
      setRefusal({ error })
    } finally {
      setSending(false)
    }
  }

  return (
    <section aria-label={t('tabs.chat')} className="space-y-4">
      <StreamEnded live={history} />
      {history.isPending ? (
        <Loading />
      ) : history.error ? (
        <ReadFailure error={history.error} onRetry={history.retry} />
      ) : history.data && !history.data.ok ? (
        <ReadFailure failure={history.data.error} onRetry={history.retry} />
      ) : conversation ? (
        <Turns conversationId={conversation.conversationId} turnCount={conversation.turnCount} />
      ) : (
        <div className="py-8 text-center">
          <h2 className="text-lg font-semibold text-text">{t('chat.greetingTitle')}</h2>
          <p className="mx-auto mt-2 max-w-md text-sm text-text-2">{t('chat.greetingBody')}</p>
        </div>
      )}

      {(replying || sending) && (
        <p role="status" className="text-sm text-text-2">
          {t('chat.thinking')}
        </p>
      )}

      <form onSubmit={(e) => void send(e)} className="space-y-2" noValidate>
        <div className="flex items-center gap-2">
          <label htmlFor="mia-input" className="sr-only">
            {t('chat.inputLabel')}
          </label>
          <input
            id="mia-input"
            type="text"
            autoComplete="off"
            value={text}
            maxLength={MAX_UTTERANCE_CHARS}
            placeholder={t('chat.inputHint')}
            onChange={(e) => setText(e.target.value)}
            className="min-w-0 flex-1 rounded-lg border border-line bg-surface px-3 py-2 text-sm text-text placeholder:text-text-3 focus:border-primary focus:outline-none"
          />
          <Button type="submit" disabled={blank || sending}>
            {t('chat.send')}
          </Button>
        </div>
        {text.length > COUNT_FROM && (
          <p className="text-xs text-text-2" data-testid="remaining">
            {t('chat.remaining', { count: MAX_UTTERANCE_CHARS - text.length })}
          </p>
        )}
        {refusal && <ErrorText failure={'failure' in refusal ? refusal.failure : null} error={'error' in refusal ? refusal.error : null} />}
        <p className="text-xs text-text-3">{t('chat.lookOnly')}</p>
      </form>
    </section>
  )
}
