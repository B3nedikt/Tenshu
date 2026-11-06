import { useState, type FormEvent } from 'react'
import type { ContactInviteItem } from '@tenshu/sdk/contact'
import { completeHandshake } from '../lib/proxy'
import {
  completionFailureCopy,
  displayFingerprint,
  formatSas,
  readOwnDisplayName,
  writeOwnDisplayName,
} from '../lib/contacts'
import { Dialog } from './Dialog'

// The inviter-side completion prompt (drive-web-mode-b-sharing.md §2/§5; handshake
// doc §5.3). A `REDEEMED` invite means someone opened the link and responded —
// the responder's self-asserted name is a *claim* (rendered quoted) and the
// fingerprint is shown for eyeballing. Accepting mints an `UNVERIFIED` (TOFU)
// contact; ticking the §5.3 SAS match (MB-5) mints it `VERIFIED` instead.
export function ContactCompletionDialog({
  invite,
  onClose,
  onDone,
}: {
  invite: ContactInviteItem
  onClose: () => void
  onDone: () => void
}) {
  const [name, setName] = useState(invite.claimedName.trim() || 'New contact')
  const [ownName, setOwnName] = useState(readOwnDisplayName())
  // ⚠⚠ A tick is about the claim it was made against, so it is stored AS that fingerprint and the
  // flag is derived during render. A claim that moves under the open dialog (a second inviter
  // device's write arriving by merge — threat model T-CI-10) is unticked in the same render that
  // shows it; an effect would reset it one commit late.
  const [confirmedFingerprint, setConfirmedFingerprint] = useState<string | null>(null)
  const sasConfirmed =
    invite.claimedFingerprint !== '' && confirmedFingerprint === invite.claimedFingerprint
  const [busy, setBusy] = useState<'accept' | 'decline' | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function accept(event: FormEvent) {
    event.preventDefault()
    const saveAs = name.trim()
    const mine = ownName.trim()
    // No accept before the claim has loaded: the proxy refuses an empty shownFingerprint (T-CI-10).
    if (!saveAs || !mine || !invite.claimedFingerprint) return
    setBusy('accept')
    setError(null)
    writeOwnDisplayName(mine)
    const result = await completeHandshake({
      inviteId: invite.inviteId,
      accept: true,
      shownFingerprint: invite.claimedFingerprint,
      newPersonName: saveAs,
      selfAssertedName: mine,
      sasConfirmed,
    })
    if (result.outcome === 'completed') onDone()
    else {
      setError(completionFailureCopy(result, 'accept'))
      setBusy(null)
    }
  }

  async function decline() {
    setBusy('decline')
    setError(null)
    const result = await completeHandshake({
      inviteId: invite.inviteId,
      accept: false,
      shownFingerprint: invite.claimedFingerprint,
    })
    if (result.outcome === 'completed') onDone()
    else {
      setError(completionFailureCopy(result, 'decline'))
      setBusy(null)
    }
  }

  return (
    <Dialog title="Confirm connection" onClose={onClose}>
      <form onSubmit={accept} className="space-y-4">
        <div className="rounded-lg bg-surface px-3 py-2 text-sm">
          <p className="text-text-2">
            Someone responded to your invite, calling themselves{' '}
            <strong className="text-text">“{invite.claimedName || 'unnamed'}”</strong>.
          </p>
          <p className="mt-1 font-mono text-xs text-text-2">
            {displayFingerprint(invite.claimedFingerprint)}
          </p>
        </div>

        <label className="block text-sm">
          <span className="text-text-2">Save them as</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="mt-1 w-full rounded-lg border border-line px-3 py-1.5 text-sm outline-none focus:border-line-strong"
          />
        </label>

        <label className="block text-sm">
          <span className="text-text-2">Your name (they&rsquo;ll see this)</span>
          <input
            autoFocus={!ownName}
            value={ownName}
            onChange={(e) => setOwnName(e.target.value)}
            className="mt-1 w-full rounded-lg border border-line px-3 py-1.5 text-sm outline-none focus:border-line-strong"
          />
        </label>

        {invite.claimedSas && (
          <div className="rounded-lg border border-line px-3 py-2.5 text-sm">
            <p className="text-text-2">
              If you&rsquo;re together in person, check they see the same code:
            </p>
            <p className="my-1.5 text-center font-mono text-2xl tracking-[0.3em] text-text">
              {formatSas(invite.claimedSas)}
            </p>
            <label className="flex items-center gap-2 text-text-2">
              <input
                type="checkbox"
                checked={sasConfirmed}
                onChange={(e) => setConfirmedFingerprint(e.target.checked ? invite.claimedFingerprint : null)}
                className="size-4 rounded border-line"
              />
              The codes match (adds them as verified)
            </label>
          </div>
        )}

        {error && <p className="text-sm text-danger">{error}</p>}

        <div className="flex justify-between gap-2">
          <button
            type="button"
            disabled={busy !== null}
            onClick={decline}
            className="rounded-lg px-3 py-1.5 text-sm font-medium text-danger hover:bg-danger/10 disabled:opacity-50"
          >
            {busy === 'decline' ? 'Declining…' : 'Decline'}
          </button>
          <button
            type="submit"
            disabled={busy !== null || !name.trim() || !ownName.trim() || !invite.claimedFingerprint}
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
          >
            {busy === 'accept'
              ? 'Connecting…'
              : sasConfirmed
                ? 'Add verified contact'
                : 'Add contact'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
