// The contact-info editor — native `ContactDetailsEditor` (Q6): phones, emails, addresses,
// organisation and note. The save MERGES into the stored record, so every vCard property this
// form does not model survives — the note under the form says so.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Contact, ContactDetails, ContactField } from '@tenshu/sdk/contact'
import { saveContactDetails } from '../lib/proxy'
import { cleanDetails } from '../lib/contacts'
import { Button, Dialog, OutcomeText } from './ui'
import { attempt, type Outcome } from '../lib/outcome'

type ListKey = 'phones' | 'emails' | 'addresses'

const DEFAULT_LABEL: Record<ListKey, string> = { phones: 'mobile', emails: 'home', addresses: 'home' }

export function DetailsEditor({ contact, onClose }: { contact: Contact; onClose: () => void }) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState<ContactDetails>(() => ({
    phones: contact.details.phones.map((f) => ({ ...f })),
    emails: contact.details.emails.map((f) => ({ ...f })),
    addresses: contact.details.addresses.map((f) => ({ ...f })),
    organisation: contact.details.organisation,
    note: contact.details.note,
  }))
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  const setField = (key: ListKey, index: number, patch: Partial<ContactField>) =>
    setDraft((d) => ({ ...d, [key]: d[key].map((f, i) => (i === index ? { ...f, ...patch } : f)) }))
  const add = (key: ListKey) =>
    setDraft((d) => ({ ...d, [key]: [...d[key], { label: DEFAULT_LABEL[key], value: '' }] }))
  const remove = (key: ListKey, index: number) =>
    setDraft((d) => ({ ...d, [key]: d[key].filter((_, i) => i !== index) }))

  async function save() {
    setBusy(true)
    const r = await attempt(() => saveContactDetails(contact.personId, cleanDetails(draft)))
    setBusy(false)
    if (r) setOutcome(r)
    else onClose()
  }

  const section = (key: ListKey, title: string, addLabel: string, multiline = false) => (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium text-text">{title}</legend>
      {draft[key].map((field, i) => (
        <div key={i} className="flex gap-2">
          <input
            aria-label={t('edit.labelFor', { field: title })}
            className="w-24 rounded-lg border border-line px-2 py-1.5 text-sm text-text"
            value={field.label}
            onChange={(e) => setField(key, i, { label: e.target.value })}
          />
          {multiline ? (
            <textarea
              aria-label={title}
              rows={2}
              className="min-w-0 flex-1 rounded-lg border border-line px-3 py-1.5 text-sm text-text"
              value={field.value}
              onChange={(e) => setField(key, i, { value: e.target.value })}
            />
          ) : (
            <input
              aria-label={title}
              type={key === 'emails' ? 'email' : key === 'phones' ? 'tel' : 'text'}
              className="min-w-0 flex-1 rounded-lg border border-line px-3 py-1.5 text-sm text-text"
              value={field.value}
              onChange={(e) => setField(key, i, { value: e.target.value })}
            />
          )}
          <Button size="sm" variant="ghost" aria-label={t('edit.remove')} onClick={() => remove(key, i)}>
            ×
          </Button>
        </div>
      ))}
      <Button size="sm" variant="ghost" onClick={() => add(key)}>+ {addLabel}</Button>
    </fieldset>
  )

  return (
    <Dialog title={t('edit.title')} onClose={onClose}>
      <div className="max-h-[70vh] space-y-4 overflow-y-auto pr-1">
        {section('phones', t('info.phone'), t('edit.addPhone'))}
        {section('emails', t('info.email'), t('edit.addEmail'))}
        {section('addresses', t('info.address'), t('edit.addAddress'), true)}
        <label className="block text-sm">
          <span className="text-text-2">{t('info.organisation')}</span>
          <input
            className="mt-1 w-full rounded-lg border border-line px-3 py-1.5 text-sm text-text"
            value={draft.organisation}
            onChange={(e) => setDraft((d) => ({ ...d, organisation: e.target.value }))}
          />
        </label>
        <label className="block text-sm">
          <span className="text-text-2">{t('info.note')}</span>
          <textarea
            rows={3}
            className="mt-1 w-full rounded-lg border border-line px-3 py-1.5 text-sm text-text"
            value={draft.note}
            onChange={(e) => setDraft((d) => ({ ...d, note: e.target.value }))}
          />
        </label>
        <p className="text-xs text-text-3">{t('edit.preservesNote')}</p>
        <OutcomeText outcome={outcome} />
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
        <Button disabled={busy} onClick={save}>{busy ? t('common.working') : t('common.save')}</Button>
      </div>
    </Dialog>
  )
}
