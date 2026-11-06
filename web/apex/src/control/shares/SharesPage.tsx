// /control/data/shares — Mode-A share inventory + revoke
// (`control-ia.md` §5.3). The authenticated realm-owner view over their own
// outbound link shares: newest-first, revoked rows kept with a badge, per-row
// revoke behind a confirm. Binds to the authenticated `Shares` SDK surface
// (`@tenshu/sdk/share`) — distinct from the anonymous recipient viewer at
// `/s/:shareId` (`ShareViewer`).
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Shares, type ShareListItem } from '@tenshu/sdk/share'

export function SharesPage() {
  const { t } = useTranslation('control')
  const [shares, setShares] = useState<readonly ShareListItem[] | null>(null)
  const [pendingRevoke, setPendingRevoke] = useState<ShareListItem | null>(null)
  const [revoking, setRevoking] = useState(false)

  const load = useCallback(() => {
    void Shares.list()
      .then(setShares)
      .catch(() => setShares([]))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const confirmRevoke = useCallback(async () => {
    const target = pendingRevoke
    if (!target) return
    setRevoking(true)
    try {
      await Shares.revoke(target.shareId)
    } finally {
      setRevoking(false)
      setPendingRevoke(null)
      load()
    }
  }, [pendingRevoke, load])

  return (
    <div>
      <h1 className="text-2xl font-medium">{t('shares.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('shares.subtitle')}</p>

      <p className="mt-6 text-sm text-text-2">
        {shares === null ? t('shares.loading') : t('shares.count', { count: shares.length })}
      </p>

      <SharesList shares={shares} onRevoke={setPendingRevoke} />

      {pendingRevoke && (
        <RevokeDialog
          share={pendingRevoke}
          busy={revoking}
          onConfirm={confirmRevoke}
          onCancel={() => setPendingRevoke(null)}
        />
      )}
    </div>
  )
}

function SharesList({
  shares,
  onRevoke,
}: {
  readonly shares: readonly ShareListItem[] | null
  readonly onRevoke: (share: ShareListItem) => void
}) {
  const { t } = useTranslation('control')
  if (shares === null) {
    return (
      <ul className="mt-4 divide-y divide-line rounded-md border border-line">
        {[0, 1].map(i => (
          <li key={i} className="px-4 py-4">
            <div className="h-4 w-1/3 animate-pulse rounded bg-surface-3" />
            <div className="mt-2 h-3 w-2/3 animate-pulse rounded bg-surface-3" />
          </li>
        ))}
      </ul>
    )
  }
  if (shares.length === 0) {
    return (
      <p className="mt-6 rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
        {t('shares.empty')}
      </p>
    )
  }
  return (
    <ul className="mt-4 divide-y divide-line rounded-md border border-line">
      {shares.map(s => (
        <li key={s.shareId}>
          <ShareRow share={s} onRevoke={onRevoke} />
        </li>
      ))}
    </ul>
  )
}

function ShareRow({
  share,
  onRevoke,
}: {
  readonly share: ShareListItem
  readonly onRevoke: (share: ShareListItem) => void
}) {
  const { t } = useTranslation('control')
  return (
    <div className="flex items-center gap-4 px-4 py-4">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <p className="truncate text-sm font-medium text-text">
            {share.fileName}
          </p>
          {share.revoked && (
            <span className="shrink-0 rounded-full bg-danger/10 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-danger">
              {t('shares.revokedBadge')}
            </span>
          )}
        </div>
        <p className="mt-0.5 truncate text-[11px] text-text-2">
          {t('shares.sharedPrefix')}
          {formatDate(share.createdAtMillis)} · {capsLine(t, share)}
        </p>
      </div>
      {!share.revoked && (
        <button
          type="button"
          onClick={() => onRevoke(share)}
          className="shrink-0 rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text-2 transition-colors hover:bg-surface-3"
        >
          {t('shares.revoke')}
        </button>
      )}
    </div>
  )
}

function RevokeDialog({
  share,
  busy,
  onConfirm,
  onCancel,
}: {
  readonly share: ShareListItem
  readonly busy: boolean
  readonly onConfirm: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('shares.revokeDialog.ariaLabel')}
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
    >
      <div className="w-full max-w-sm rounded-lg bg-surface p-6 shadow-xl">
        <h2 className="text-lg font-medium">{t('shares.revokeDialog.title')}</h2>
        <p className="mt-2 text-sm text-text-2">
          {t('shares.revokeDialog.body', { fileName: share.fileName })}
        </p>
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="rounded-md px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface-3 disabled:opacity-50"
          >
            {t('shares.revokeDialog.cancel')}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="rounded-md bg-danger px-3 py-1.5 text-sm font-medium text-white hover:bg-danger/90 disabled:opacity-50"
          >
            {busy ? t('shares.revokeDialog.inFlight') : t('shares.revokeDialog.confirm')}
          </button>
        </div>
      </div>
    </div>
  )
}

function formatDate(ms: number): string {
  return new Date(ms).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function capsLine(t: TFunction, share: ShareListItem): string {
  const parts: string[] = []
  if (share.maxOpens != null) {
    parts.push(t('shares.caps.opens', { count: share.maxOpens }))
  }
  if (share.expiresAtMillis != null) {
    parts.push(t('shares.caps.expires', { date: formatDate(share.expiresAtMillis) }))
  }
  return parts.length === 0 ? t('shares.caps.none') : parts.join(' · ')
}
