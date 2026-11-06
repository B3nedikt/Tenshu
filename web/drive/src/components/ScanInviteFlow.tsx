import { useState, type FormEvent } from 'react'
import {
  awaitInviteAck,
  pendingRedemptions,
  redeemContactInvite,
} from '../lib/proxy'
import { readOwnDisplayName, writeOwnDisplayName } from '../lib/contacts'
import { Dialog } from './Dialog'
import { QrScanner } from './QrScanner'
import { SasConfirmPanel } from './SasConfirmPanel'

// The joiner-side QR scan → verify flow (drive-web-mode-b-sharing.md §4/§5,
// MB-5). Scan the inviter's QR → name → redeem (the link's K_link rode the QR,
// never the relay) → compare the §5.3 6-digit SAS in person → await the
// inviter's confirmation. Confirming the SAS mints the contact VERIFIED; the
// underlying ops are the same MB-1 ones (redeem / pendingRedemptions / awaitAck).
type Step =
  | { kind: 'scan' }
  | { kind: 'name'; link: string }
  | { kind: 'redeeming' }
  | { kind: 'sas'; inviteId: string; sas: string; peerFingerprint: string }
  | { kind: 'awaiting' }
  | { kind: 'done'; status: string; peerName: string }
  | { kind: 'error'; message: string }

export function ScanInviteFlow({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [step, setStep] = useState<Step>({ kind: 'scan' })
  const [name, setName] = useState(readOwnDisplayName())

  async function redeem(event: FormEvent) {
    event.preventDefault()
    if (step.kind !== 'name' || !name.trim()) return
    const link = step.link
    const mine = name.trim()
    writeOwnDisplayName(mine)
    setStep({ kind: 'redeeming' })

    const result = await redeemContactInvite(link, mine)
    if (result.outcome !== 'redeemed' || !result.inviteId) {
      setStep({
        kind: 'error',
        message:
          result.outcome === 'alreadyUsed'
            ? 'This invite has already been used. Ask for a fresh one.'
            : result.outcome === 'gone'
              ? 'This invite has expired or been cancelled.'
              : "Couldn't redeem this invite.",
      })
      return
    }

    // The SAS + peer fingerprint land in the pending redemption row.
    const pending = await pendingRedemptions()
    const row = pending.find((p) => p.inviteId === result.inviteId)
    if (row && row.sas) {
      setStep({ kind: 'sas', inviteId: result.inviteId, sas: row.sas, peerFingerprint: row.peerFingerprint })
    } else {
      // No SAS available (locked realm) — fall back to the unverified close.
      await finish(result.inviteId, false)
    }
  }

  async function finish(inviteId: string, sasConfirmed: boolean) {
    setStep({ kind: 'awaiting' })
    const ack = await awaitInviteAck(inviteId, sasConfirmed)
    setStep({ kind: 'done', status: ack.status, peerName: ack.peerName })
  }

  return (
    <Dialog title="Scan an invite" onClose={onClose}>
      {step.kind === 'scan' && (
        <QrScanner
          onResult={(text) => setStep({ kind: 'name', link: text })}
          onError={(message) => setStep({ kind: 'error', message })}
        />
      )}

      {step.kind === 'name' && (
        <form onSubmit={redeem} className="space-y-4">
          <p className="text-sm text-text-2">Enter the name they&rsquo;ll see for you.</p>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label="Your name"
            className="w-full rounded-lg border border-line px-3 py-1.5 text-sm outline-none focus:border-line-strong"
          />
          <div className="flex justify-end">
            <button
              type="submit"
              disabled={!name.trim()}
              className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
            >
              Connect
            </button>
          </div>
        </form>
      )}

      {step.kind === 'redeeming' && <p className="text-sm text-text-3">Connecting…</p>}

      {step.kind === 'sas' && (
        <SasConfirmPanel
          sas={step.sas}
          peerFingerprint={step.peerFingerprint}
          onConfirm={() => finish(step.inviteId, true)}
          onReject={() => finish(step.inviteId, false)}
        />
      )}

      {step.kind === 'awaiting' && (
        <p className="text-sm text-text-3">Waiting for them to confirm…</p>
      )}

      {step.kind === 'done' && (
        <div className="space-y-3">
          <p className="text-sm text-text">
            {step.status === 'ACCEPTED' ? (
              <>
                You&rsquo;re connected{step.peerName ? <> with <strong>{step.peerName}</strong></> : null}.
              </>
            ) : step.status === 'REJECTED' ? (
              'They declined the connection.'
            ) : (
              'Your response was sent. You’ll be connected once they confirm.'
            )}
          </p>
          <div className="flex justify-end">
            <button
              type="button"
              onClick={onDone}
              className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
            >
              Done
            </button>
          </div>
        </div>
      )}

      {step.kind === 'error' && (
        <div className="space-y-3">
          <p className="text-sm text-danger">{step.message}</p>
          <div className="flex justify-end">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface-3"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </Dialog>
  )
}
