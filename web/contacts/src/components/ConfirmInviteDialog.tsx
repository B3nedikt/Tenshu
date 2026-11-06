// Save someone who accepted your invitation — native `ConfirmContactViewModel` /
// `ConfirmContactDialog`, for bare invites.
//
// Trust rules this must not break (`contact-service-identity-model.md` §4.2):
//  - the claimed name is a CLAIM, rendered quoted; the petname is the user's (pre-filled once);
//  - linking to an existing contact re-points the key and NEVER transfers trust;
//  - `sasConfirmed` comes only from the explicit "the codes match" tick (threat model A24);
//  - `shownFingerprint` is the RAW claimed fingerprint this prompt displayed (T-CI-10) — the
//    proxy stores the reply only if it is still that key.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { CompleteResult, InviteConfirmation } from '@tenshu/sdk/contact'
import { useContacts, useOwnProfile } from '../lib/live'
import { completeHandshake, setOwnDisplayName } from '../lib/proxy'
import { displayFingerprint, formatSas } from '../lib/contacts'
import { failureMessage } from '../lib/errors'
import { Button, Dialog, ErrorText, Field } from './ui'
import { useContactName } from '../lib/outcome'

// Handshake doc §5: self-asserted names cap at 64 chars.
const MAX_NAME_LENGTH = 64

export function ConfirmInviteDialog({
  confirmation,
  onClose,
}: {
  confirmation: InviteConfirmation
  onClose: () => void
}) {
  const { t } = useTranslation()
  const nameOf = useContactName()
  const contacts = useContacts()
  const profile = useOwnProfile()
  const [petname, setPetname] = useState(confirmation.claimedName)
  // `null` until the user types, so the stored own name fills in when it arrives.
  const [ownNameDraft, setOwnNameDraft] = useState<string | null>(null)
  const ownName = ownNameDraft ?? profile.data?.displayName ?? ''
  const [sasConfirmed, setSasConfirmed] = useState(false)
  const [linkTo, setLinkTo] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  const hasSas = confirmation.claimedSas !== ''
  const canSave =
    !busy &&
    ownName.trim() !== '' &&
    confirmation.claimedFingerprint !== '' &&
    (linkTo !== '' || petname.trim() !== '')
  const others = contacts.data?.ok ? contacts.data.contacts : []

  async function submit(accept: boolean) {
    if (busy || (accept && !canSave)) return
    setBusy(true)
    setMessage(null)
    setError(null)
    try {
      const name = ownName.trim()
      if (accept && name) await setOwnDisplayName(name)
      const result = await completeHandshake({
        inviteId: confirmation.inviteId,
        accept,
        shownFingerprint: confirmation.claimedFingerprint,
        personId: linkTo,
        newPersonName: linkTo ? '' : petname.trim(),
        selfAssertedName: name,
        // Only on accept, and only from the explicit tick.
        sasConfirmed: accept && hasSas && sasConfirmed,
      })
      if (result.outcome === 'completed') {
        onClose()
        return
      }
      setMessage(completionCopy(result, accept))
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  function completionCopy(result: CompleteResult, accept: boolean): string {
    switch (result.outcome) {
      // ⚠ For 'responseChanged' the remedy IS the message — a retry refuses the same way.
      case 'responseChanged':
        return t('errors.error_invite_response_changed')
      case 'codeNotShown':
        return t('errors.error_invite_code_not_shown')
      default:
        return result.message
          ? failureMessage(t, { kind: result.message })
          : accept
            ? t('confirm.failedAccept')
            : t('confirm.failedDecline')
    }
  }

  const claimed = confirmation.claimedName || t('confirm.unnamed')

  return (
    <Dialog title={t('confirm.header', { name: claimed })} onClose={onClose}>
      <div className="max-h-[75vh] space-y-4 overflow-y-auto pr-1">
        <div className="space-y-1 text-sm text-text-2">
          <p>{t('confirm.claimNote')}</p>
          <p className="font-mono text-xs text-text">{displayFingerprint(confirmation.claimedFingerprint)}</p>
        </div>

        {hasSas && (
          <div className="space-y-2 rounded-xl border border-line bg-surface px-4 py-4 text-center">
            <p className="text-sm text-text-2">{t('confirm.sasNote')}</p>
            <p className="font-mono text-3xl tracking-[0.3em] text-text">{formatSas(confirmation.claimedSas)}</p>
            <label className="flex items-center justify-center gap-2 text-sm text-text">
              <input type="checkbox" checked={sasConfirmed} onChange={(e) => setSasConfirmed(e.target.checked)} />
              {t('confirm.sasCheck')}
            </label>
          </div>
        )}

        {linkTo === '' && (
          <Field label={t('confirm.petnameLabel')} value={petname} onChange={(e) => setPetname(e.target.value)} />
        )}

        <label className="block text-sm">
          <span className="text-text-2">{t('confirm.linkExisting')}</span>
          <select
            className="mt-1 w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-text"
            value={linkTo}
            onChange={(e) => setLinkTo(e.target.value)}
          >
            <option value="">{t('confirm.newContact')}</option>
            {others.map((c) => (
              <option key={c.personId} value={c.personId}>{nameOf(c)}</option>
            ))}
          </select>
        </label>

        <div>
          <Field
            label={t('confirm.ownNameLabel')}
            value={ownName}
            maxLength={MAX_NAME_LENGTH}
            onChange={(e) => setOwnNameDraft(e.target.value.slice(0, MAX_NAME_LENGTH))}
          />
          <p className="mt-1 text-xs text-text-3">{t('confirm.ownNameNote')}</p>
        </div>

        {message && <p role="alert" className="text-sm text-danger">{message}</p>}
        {error !== null && <ErrorText error={error} />}

        <div className="flex justify-between gap-2">
          <Button variant="danger-ghost" disabled={busy} onClick={() => submit(false)}>
            {t('confirm.decline')}
          </Button>
          <Button disabled={!canSave} onClick={() => submit(true)}>
            {busy ? t('common.working') : t('confirm.save')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
