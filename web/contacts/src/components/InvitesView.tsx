// Invitations — native `InviteSheet` + the outstanding list + `ConfirmContactDialog`, for BARE
// invites only (`intentKind === ''`; Drive owns SHARE_FOLDER invites end to end, §9.1).
//
// Create shows the one-time link (and its QR) once; outstanding invites can be revoked; an accepted
// invite waits under "Accepted" until the user saves the person (or declines) via
// `ConfirmInviteDialog`. The invite reads are snapshots, refetched while this view is open — the
// same snapshot + refetch the handshake uses everywhere (run A kept them that way).
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { InviteConfirmation } from '@tenshu/sdk/contact'
import { createInvite, outstandingInvites, pendingConfirmations, revokeInvite } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { Button, ErrorText, OutcomeText } from './ui'
import { attempt, type Outcome } from '../lib/outcome'
import { QrImage } from './QrImage'
import { ConfirmInviteDialog } from './ConfirmInviteDialog'

const REFRESH_MS = 5_000

export function InvitesView() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [link, setLink] = useState<string | null>(null)
  const [mintOutcome, setMintOutcome] = useState<Outcome>(null)
  const [copied, setCopied] = useState(false)
  const [confirming, setConfirming] = useState<InviteConfirmation | null>(null)

  const outstanding = useQuery({
    queryKey: queryKeys.outstandingInvites(),
    queryFn: outstandingInvites,
    refetchInterval: REFRESH_MS,
  })
  const accepted = useQuery({
    queryKey: queryKeys.pendingConfirmations(),
    queryFn: pendingConfirmations,
    refetchInterval: REFRESH_MS,
  })

  const mint = useMutation({
    mutationFn: async () => {
      setMintOutcome(null)
      setCopied(false)
      let minted: string | null = null
      const outcome = await attempt(async () => {
        const r = await createInvite()
        if (r.ok) minted = r.link
        return r
      })
      setMintOutcome(outcome)
      setLink(minted)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.outstandingInvites() }),
  })

  const revoke = useMutation({
    mutationFn: (inviteId: string) => revokeInvite(inviteId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.outstandingInvites() }),
  })

  async function copy() {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <section className="space-y-6">
      <div className="space-y-3 rounded-xl border border-line p-5">
        <h2 className="text-base font-semibold text-text">{t('invite.title')}</h2>
        <p className="text-sm text-text-2">{t('invite.intro')}</p>
        <Button disabled={mint.isPending} onClick={() => mint.mutate()}>
          {mint.isPending ? t('invite.creating') : t('invite.create')}
        </Button>
        <OutcomeText outcome={mintOutcome} />
        {link && (
          <div className="space-y-3">
            <QrImage value={link} alt={t('invite.qrDescription')} />
            <p className="text-sm text-text-2">{t('invite.linkCaption')}</p>
            <div className="flex gap-2">
              <input
                readOnly
                aria-label={t('invite.linkLabel')}
                value={link}
                className="min-w-0 flex-1 rounded-lg border border-line px-3 py-1.5 font-mono text-xs text-text"
                onFocus={(e) => e.currentTarget.select()}
              />
              <Button variant="outline" onClick={copy}>{copied ? t('invite.copied') : t('invite.copy')}</Button>
            </div>
            <p className="text-xs text-warning">{t('invite.oneSessionWarning')}</p>
          </div>
        )}
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text">{t('invite.acceptedTitle')}</h3>
        {accepted.isPending ? (
          <p className="text-sm text-text-3">{t('common.loading')}</p>
        ) : accepted.error ? (
          <ErrorText error={accepted.error} />
        ) : accepted.data && !accepted.data.ok ? (
          <ErrorText failure={accepted.data.error} />
        ) : accepted.data && accepted.data.confirmations.length > 0 ? (
          <ul className="divide-y divide-line rounded-xl border border-line">
            {accepted.data.confirmations.map((c) => (
              <li key={c.inviteId} className="flex items-center justify-between gap-3 px-4 py-3">
                <span className="text-sm text-text">
                  {t('confirm.banner', { name: c.claimedName || t('confirm.unnamed') })}
                </span>
                <Button size="sm" onClick={() => setConfirming(c)}>{t('confirm.open')}</Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-text-2">{t('invite.noneAccepted')}</p>
        )}
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text">{t('invite.outstandingTitle')}</h3>
        {outstanding.isPending ? (
          <p className="text-sm text-text-3">{t('common.loading')}</p>
        ) : outstanding.error ? (
          <ErrorText error={outstanding.error} />
        ) : outstanding.data && !outstanding.data.ok ? (
          <ErrorText failure={outstanding.data.error} />
        ) : outstanding.data && outstanding.data.invites.length > 0 ? (
          <ul className="divide-y divide-line rounded-xl border border-line">
            {outstanding.data.invites.map((invite) => (
              <li key={invite.inviteId} className="flex items-center justify-between gap-3 px-4 py-3">
                <span className="text-sm text-text-2">
                  {t('invite.createdAt', { date: new Date(invite.createdAtMillis) })}
                  {invite.expiresAtMillis > 0 && (
                    <> · {t('invite.expiresAt', { date: new Date(invite.expiresAtMillis) })}</>
                  )}
                </span>
                <Button
                  size="sm"
                  variant="danger-ghost"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(invite.inviteId)}
                >
                  {t('invite.revoke')}
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-text-2">{t('invite.noneOutstanding')}</p>
        )}
        {revoke.data === false && <ErrorText failure={{ kind: 'error_generic' }} />}
        {revoke.error && <ErrorText error={revoke.error} />}
      </div>

      {confirming && (
        <ConfirmInviteDialog
          confirmation={confirming}
          onClose={() => {
            setConfirming(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.pendingConfirmations() })
          }}
        />
      )}
    </section>
  )
}
