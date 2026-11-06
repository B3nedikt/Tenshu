// A conversation's turns, oldest at the top, read a keyset page at a time (newest first on the
// wire, `mia.turnsPage`). Shared by the chat and the read-only History thread — native's
// `ConversationDetailScreen` is "the chat renderer without an input row" too.
//
// ⚠ Refetched when the conversation's `turnCount` moves (the history push): TanStack refetches an
// infinite query page by page from the head, recomputing each cursor from the fresh pages, so a turn
// appended between two reads neither repeats nor drops a turn (keyset, `MiaBook.kt`).
import { useEffect, useRef } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { MiaTurn, TurnsPageResult } from '@tenshu/sdk/mia'
import { turnsPage } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { Answer, Question } from './Answer'
import { Button, Loading, ReadFailure } from './ui'

export function Turns({ conversationId, turnCount }: { conversationId: string; turnCount: number }) {
  const { t } = useTranslation()
  const query = useInfiniteQuery({
    queryKey: queryKeys.turns(conversationId),
    queryFn: ({ pageParam }) => turnsPage(conversationId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last: TurnsPageResult) => (last.ok ? (last.nextCursor ?? undefined) : undefined),
  })
  const { refetch } = query

  // `turnCount` is the push's "something changed"; the first render already fetched.
  const seen = useRef(turnCount)
  useEffect(() => {
    if (seen.current === turnCount) return
    seen.current = turnCount
    void refetch()
  }, [turnCount, refetch])

  if (query.isPending) return <Loading />
  if (query.error) return <ReadFailure error={query.error} onRetry={() => void refetch()} />
  const failed = query.data.pages.find((p) => !p.ok)
  if (failed && !failed.ok) return <ReadFailure failure={failed.error} onRetry={() => void refetch()} />

  // Newest first on the wire; read top to bottom here.
  const turns: MiaTurn[] = query.data.pages.flatMap((p) => (p.ok ? p.turns : [])).reverse()
  return (
    <div className="space-y-3">
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button
            size="sm"
            variant="ghost"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? t('common.loading') : t('chat.earlier')}
          </Button>
        </div>
      )}
      <ol aria-label={t('chat.turns')} className="space-y-3">
        {turns.map((turn) => (
          <li key={turn.turnId} className="space-y-2" data-turn={turn.turnId}>
            <Question utterance={turn.utterance} />
            <Answer answer={turn.answer} />
          </li>
        ))}
      </ol>
    </div>
  )
}
