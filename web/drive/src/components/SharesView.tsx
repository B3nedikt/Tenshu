import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ShareListItem } from '@tenshu/sdk/share'
import { listShares, revokeShare } from '../lib/proxy'
import { formatDate } from '../lib/paths'
import { queryKeys } from '../lib/queryKeys'
import { ConfirmDialog } from './Dialog'

// The realm-wide *My shares* list (Mode-A) — every outbound link, newest-first,
// with revoke. Revoked rows stay with a badge (they sync to every device). The
// URL fragment isn't stored on the synced Share, so the list can't re-show the
// copy-able link — only mint (ShareDialog) returns it.
export function SharesView() {
  const queryClient = useQueryClient()
  const { data, isPending, isError, error } = useQuery({
    queryKey: queryKeys.shares(),
    queryFn: () => listShares(),
  })
  const [revoking, setRevoking] = useState<ShareListItem | null>(null)

  async function onRevoke(item: ShareListItem): Promise<string | null> {
    const ok = await revokeShare(item.shareId)
    if (!ok) return "Couldn't revoke this link."
    queryClient.invalidateQueries({ queryKey: queryKeys.shares() })
    return null
  }

  const shares = data ?? []

  return (
    <div>
      <div className="overflow-hidden rounded-xl border border-line">
        {isPending ? (
          <Message>Loading…</Message>
        ) : isError ? (
          <Message tone="error">{String(error)}</Message>
        ) : shares.length === 0 ? (
          <Message>You haven&rsquo;t shared anything yet.</Message>
        ) : (
          <ul className="divide-y divide-line">
            {shares.map((item) => (
              <ShareRow key={item.shareId} item={item} onRevoke={() => setRevoking(item)} />
            ))}
          </ul>
        )}
      </div>

      {revoking && (
        <ConfirmDialog
          title="Revoke link"
          confirmLabel="Revoke"
          message={
            <>
              Revoke the share link for “<strong>{revoking.fileName}</strong>”? Anyone holding
              the link will lose access.
            </>
          }
          onConfirm={() => onRevoke(revoking)}
          onClose={() => setRevoking(null)}
        />
      )}
    </div>
  )
}

function ShareRow({ item, onRevoke }: { item: ShareListItem; onRevoke: () => void }) {
  const meta = [
    formatDate(item.createdAtMillis),
    item.expiresAtMillis ? `expires ${formatDate(item.expiresAtMillis)}` : null,
    item.maxOpens ? `${item.maxOpens} open${item.maxOpens === 1 ? '' : 's'} max` : null,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm text-text">{item.fileName}</p>
        <p className="truncate text-xs text-text-3">{meta}</p>
      </div>
      {item.revoked ? (
        <span className="shrink-0 rounded-full bg-surface-3 px-2 py-0.5 text-xs text-text-2">
          Revoked
        </span>
      ) : (
        <button
          type="button"
          onClick={onRevoke}
          className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-danger hover:bg-danger/10"
        >
          Revoke
        </button>
      )}
    </li>
  )
}

function Message({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode
  tone?: 'muted' | 'error'
}) {
  return (
    <p className={`px-4 py-8 text-center text-sm ${tone === 'error' ? 'text-danger' : 'text-text-3'}`}>
      {children}
    </p>
  )
}
