// One contact — native `ContactDetailScreen` + `ContactDetailViewModel`: the petname and the
// SDK's trust badge, the photo, the keyring (each key with the name it CLAIMS and its own rung,
// because trust lives on the key), the contact info, and every edit: rename, delete, edit info,
// remove photo, export, unlink / relink a key, verify / un-verify a key, merge.
//
// Derived from the live book (`useContacts`), as natively — so a delete or merge on another
// device simply makes this render "not found".
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Contact, ContactIdentity } from '@tenshu/sdk/contact'
import { useContacts } from '../lib/live'
import {
  clearAvatar,
  deleteContact,
  exportVcard,
  relinkIdentity,
  renameContact,
  setVerified,
  unlinkIdentity,
} from '../lib/proxy'
import { displayFingerprint, downloadVcard, vcardFileName } from '../lib/contacts'
import { Avatar, Button, ConfirmDialog, Dialog, ErrorText, OutcomeText, PromptDialog, TrustBadge } from './ui'
import { attempt, useContactName, type Outcome } from '../lib/outcome'
import { DetailsEditor } from './DetailsEditor'
import { VerifyDialog } from './VerifyDialog'
import { MergeDialog } from './MergeDialog'
import { StreamNotice } from './StreamNotice'

type Open =
  | { kind: 'rename' }
  | { kind: 'delete' }
  | { kind: 'edit' }
  | { kind: 'merge' }
  | { kind: 'unlink'; fingerprint: string }
  | { kind: 'relink'; fingerprint: string }
  | { kind: 'verify'; fingerprint: string }
  | null

export function ContactDetail({ personId, onBack }: { personId: string; onBack: () => void }) {
  const { t } = useTranslation()
  const live = useContacts()
  const nameOf = useContactName()
  const [open, setOpen] = useState<Open>(null)
  // The last inline action's result (remove photo, export, un-verify) — dialogs carry their own.
  const [outcome, setOutcome] = useState<Outcome>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const back = (
    <Button variant="ghost" size="sm" onClick={onBack}>
      ← {t('detail.back')}
    </Button>
  )

  if (live.isPending) return <p className="text-sm text-text-3">{t('common.loading')}</p>
  const result = live.data
  if (!result) return <div className="space-y-3">{back}<ErrorText error={live.error} /></div>
  if (!result.ok) return <div className="space-y-3">{back}<ErrorText failure={result.error} /></div>
  const contact = result.contacts.find((c) => c.personId === personId)
  if (!contact) {
    return (
      <div className="space-y-3">
        {back}
        <div className="rounded-xl border border-line p-6 text-center">
          <p className="font-medium text-text">{t('detail.missingTitle')}</p>
          <p className="mt-1 text-sm text-text-2">{t('detail.missingBody')}</p>
        </div>
      </div>
    )
  }

  async function inline(run: () => Promise<{ ok: true; changed?: boolean } | { ok: false; error: { kind: string } }>, unchanged?: string) {
    setNotice(null)
    let changed = true
    const result = await attempt(async () => {
      const r = await run()
      if (r.ok && r.changed === false) changed = false
      return r
    })
    setOutcome(result)
    if (!result && !changed && unchanged) setNotice(unchanged)
  }

  async function onExport() {
    setNotice(null)
    const result = await attempt(async () => {
      const r = await exportVcard(personId)
      if (r.ok) downloadVcard(r.vcard, vcardFileName(nameOf(contact!)))
      return r
    })
    setOutcome(result)
  }

  const others = result.contacts.filter((c) => c.personId !== personId)

  return (
    <article className="space-y-6">
      {back}
      <StreamNotice live={live} />

      <header className="flex items-start gap-4">
        <Avatar contact={contact} size={64} />
        <div className="min-w-0 flex-1 space-y-2">
          <h2 className="truncate text-xl font-semibold text-text">{nameOf(contact)}</h2>
          <TrustBadge trust={contact.trust} detail />
          <PhotoNote contact={contact} />
        </div>
      </header>

      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => setOpen({ kind: 'rename' })}>{t('detail.rename')}</Button>
        <Button variant="outline" size="sm" onClick={() => setOpen({ kind: 'edit' })}>{t('detail.editInfo')}</Button>
        <Button variant="outline" size="sm" onClick={onExport}>{t('detail.export')}</Button>
        {others.length > 0 && (
          <Button variant="outline" size="sm" onClick={() => setOpen({ kind: 'merge' })}>{t('detail.mergeWith')}</Button>
        )}
        {contact.avatar !== 'none' && (
          <Button variant="outline" size="sm" onClick={() => inline(() => clearAvatar(personId))}>
            {t('detail.removePhoto')}
          </Button>
        )}
        <Button variant="danger-ghost" size="sm" onClick={() => setOpen({ kind: 'delete' })}>{t('detail.delete')}</Button>
      </div>
      <OutcomeText outcome={outcome} />
      {notice && <p role="status" className="text-sm text-text-2">{notice}</p>}

      <Keyring
        contact={contact}
        canRelink={others.length > 0}
        onOpen={setOpen}
        onUnverify={(fp) => inline(() => setVerified(fp, false), t('verify.unchanged'))}
      />

      <Info contact={contact} />

      {open?.kind === 'rename' && (
        <PromptDialog
          title={t('rename.title')}
          supporting={t('rename.supporting')}
          label={t('common.name')}
          initialValue={contact.displayName}
          submitLabel={t('common.save')}
          onClose={() => setOpen(null)}
          onSubmit={(name) => attempt(() => renameContact(personId, name))}
        />
      )}
      {open?.kind === 'delete' && (
        <ConfirmDialog
          title={t('delete.title')}
          confirmLabel={t('delete.confirm')}
          onClose={() => setOpen(null)}
          onConfirm={async () => {
            const r = await attempt(() => deleteContact(personId))
            if (!r) onBack()
            return r
          }}
        >
          <p>{t('delete.body')}</p>
          {/* ⚠⚠ Q7b — deleting revokes nothing. A user deleting someone to cut them off would
              otherwise get the opposite, silently. */}
          <p className="font-medium text-warning">{t('delete.accessWarning')}</p>
        </ConfirmDialog>
      )}
      {open?.kind === 'edit' && (
        <DetailsEditor contact={contact} onClose={() => setOpen(null)} />
      )}
      {open?.kind === 'merge' && (
        <MergeDialog
          first={contact}
          choices={others}
          onClose={() => setOpen(null)}
          onMerged={(targetId) => {
            if (targetId !== personId) onBack()
          }}
        />
      )}
      {open?.kind === 'unlink' && (
        <ConfirmDialog
          title={t('unlink.title')}
          confirmLabel={t('unlink.confirm')}
          onClose={() => setOpen(null)}
          onConfirm={() => attempt(() => unlinkIdentity(open.fingerprint))}
        >
          <p>{t('unlink.body', { key: displayFingerprint(open.fingerprint) })}</p>
        </ConfirmDialog>
      )}
      {open?.kind === 'relink' && (
        <RelinkDialog fingerprint={open.fingerprint} choices={others} onClose={() => setOpen(null)} />
      )}
      {open?.kind === 'verify' && (
        <VerifyDialog
          fingerprint={open.fingerprint}
          name={nameOf(contact)}
          onClose={() => setOpen(null)}
        />
      )}
    </article>
  )
}

// Why there is no photo when the SDK withheld one — a withheld photo must not read as no photo.
function PhotoNote({ contact }: { contact: Contact }) {
  const { t } = useTranslation()
  if (contact.avatar === 'tooLarge') return <p className="text-xs text-text-3">{t('detail.photoTooLarge')}</p>
  if (contact.avatar === 'unknownFormat') return <p className="text-xs text-text-3">{t('detail.photoUnknownFormat')}</p>
  return null
}

function Keyring({
  contact,
  canRelink,
  onOpen,
  onUnverify,
}: {
  contact: Contact
  canRelink: boolean
  onOpen: (open: Open) => void
  onUnverify: (fingerprint: string) => void
}) {
  const { t } = useTranslation()
  return (
    <section aria-labelledby="keyring-title" className="space-y-2">
      <h3 id="keyring-title" className="text-sm font-semibold text-text">{t('detail.keysTitle')}</h3>
      {contact.identities.length === 0 ? (
        <p className="text-sm text-text-2">{t('detail.noKeys')}</p>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {contact.identities.map((identity) => (
            <KeyRow
              key={identity.fingerprint}
              identity={identity}
              canRelink={canRelink}
              onOpen={onOpen}
              onUnverify={onUnverify}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

function KeyRow({
  identity,
  canRelink,
  onOpen,
  onUnverify,
}: {
  identity: ContactIdentity
  canRelink: boolean
  onOpen: (open: Open) => void
  onUnverify: (fingerprint: string) => void
}) {
  const { t } = useTranslation()
  const fp = identity.fingerprint
  // The key's OWN rung, as text. ⚠ No checkmark here: the one checkmark is the contact's trust.
  const rung =
    identity.verification === 'VERIFIED'
      ? t('key.verified')
      : identity.verification === 'ATTESTED'
        ? t('key.attested')
        : t('key.unverified')
  return (
    <li className="space-y-2 px-4 py-3" data-fingerprint={fp}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-mono text-sm text-text">{displayFingerprint(fp)}</span>
        <span className="text-xs text-text-2" data-verification={identity.verification}>{rung}</span>
      </div>
      {identity.selfAssertedName && (
        // A claim — quoted, never the petname.
        <p className="text-xs text-text-3">{t('key.claimedName', { name: identity.selfAssertedName })}</p>
      )}
      <div className="flex flex-wrap gap-2">
        {identity.verification === 'UNVERIFIED' && (
          <Button size="sm" onClick={() => onOpen({ kind: 'verify', fingerprint: fp })}>{t('key.verify')}</Button>
        )}
        {identity.verification === 'VERIFIED' && (
          // Withdrawing trust is the safe direction — no confirmation, as natively.
          <Button size="sm" variant="ghost" onClick={() => onUnverify(fp)}>{t('key.unverify')}</Button>
        )}
        {identity.verification === 'ATTESTED' && (
          <span className="text-xs text-text-3">{t('key.attestedNote')}</span>
        )}
        {canRelink && (
          <Button size="sm" variant="ghost" onClick={() => onOpen({ kind: 'relink', fingerprint: fp })}>{t('key.relink')}</Button>
        )}
        <Button size="sm" variant="danger-ghost" onClick={() => onOpen({ kind: 'unlink', fingerprint: fp })}>{t('key.unlink')}</Button>
      </div>
    </li>
  )
}

function Info({ contact }: { contact: Contact }) {
  const { t } = useTranslation()
  const { phones, emails, addresses, organisation, note } = contact.details
  const empty = !phones.length && !emails.length && !addresses.length && !organisation && !note
  return (
    <section aria-labelledby="info-title" className="space-y-2">
      <h3 id="info-title" className="text-sm font-semibold text-text">{t('detail.infoTitle')}</h3>
      {empty ? (
        <p className="text-sm text-text-2">{t('detail.noInfo')}</p>
      ) : (
        <dl className="grid grid-cols-[minmax(6rem,auto)_1fr] gap-x-4 gap-y-1 text-sm">
          {phones.map((f, i) => (
            <Row key={`p${i}`} label={f.label || t('info.phone')} value={<a className="text-primary" href={`tel:${f.value}`}>{f.value}</a>} />
          ))}
          {emails.map((f, i) => (
            <Row key={`e${i}`} label={f.label || t('info.email')} value={<a className="text-primary" href={`mailto:${f.value}`}>{f.value}</a>} />
          ))}
          {addresses.map((f, i) => (
            <Row key={`a${i}`} label={f.label || t('info.address')} value={<span className="whitespace-pre-line">{f.value}</span>} />
          ))}
          {organisation && <Row label={t('info.organisation')} value={organisation} />}
          {note && <Row label={t('info.note')} value={<span className="whitespace-pre-line">{note}</span>} />}
        </dl>
      )}
    </section>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <>
      <dt className="text-text-3">{label}</dt>
      <dd className="min-w-0 break-words text-text">{value}</dd>
    </>
  )
}

// Re-point one key at another contact. ⚠ Verification stays on the key — the dialog says so,
// because "move the verified key onto this card" must not read as "verify this card".
function RelinkDialog({
  fingerprint,
  choices,
  onClose,
}: {
  fingerprint: string
  choices: Contact[]
  onClose: () => void
}) {
  const { t } = useTranslation()
  const nameOf = useContactName()
  const [target, setTarget] = useState('')
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function submit() {
    if (!target) return
    setBusy(true)
    const r = await attempt(() => relinkIdentity(fingerprint, target))
    setBusy(false)
    if (r) setOutcome(r)
    else onClose()
  }

  return (
    <Dialog title={t('relink.title')} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-text-2">{t('relink.body', { key: displayFingerprint(fingerprint) })}</p>
        <label className="block text-sm">
          <span className="text-text-2">{t('relink.target')}</span>
          <select
            className="mt-1 w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-text"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
          >
            <option value="">{t('relink.choose')}</option>
            {choices.map((c) => (
              <option key={c.personId} value={c.personId}>{nameOf(c)}</option>
            ))}
          </select>
        </label>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button disabled={!target || busy} onClick={submit}>{busy ? t('common.working') : t('relink.confirm')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
