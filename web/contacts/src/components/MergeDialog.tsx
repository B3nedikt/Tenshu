// Combine two contacts (D7) — native `MergeScreen`'s confirmation. Reached from a duplicate
// suggestion or from a contact's "Merge with…".
//
// ⚠⚠ There is no un-merge. The dialog names both people, asks the one question that changes the
// outcome (which one survives — its petname is what remains), and says it cannot be undone.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Contact } from '@tenshu/sdk/contact'
import { mergeContacts } from '../lib/proxy'
import { preferredTarget } from '../lib/contacts'
import { Button, Dialog, OutcomeText } from './ui'
import { attempt, useContactName, type Outcome } from '../lib/outcome'

export function MergeDialog({
  first,
  second,
  choices,
  onClose,
  onMerged,
}: {
  first: Contact
  // A fixed pair (a duplicate suggestion), or pick the other from `choices` (from a detail page).
  second?: Contact
  choices?: Contact[]
  onClose: () => void
  onMerged?: (targetPersonId: string) => void
}) {
  const { t } = useTranslation()
  const nameOf = useContactName()
  const [otherId, setOtherId] = useState(second?.personId ?? '')
  const other = second ?? choices?.find((c) => c.personId === otherId)
  const [keepId, setKeepId] = useState<string | null>(
    second ? preferredTarget({ first, second, reasons: ['name'] }) : null,
  )
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  const keep = other && keepId ? (keepId === first.personId ? first : keepId === other.personId ? other : null) : null
  const fold = keep && other ? (keep.personId === first.personId ? other : first) : null

  async function confirm() {
    if (!keep || !fold) return
    setBusy(true)
    const r = await attempt(() => mergeContacts(fold.personId, keep.personId))
    setBusy(false)
    if (r) {
      setOutcome(r)
      return
    }
    onMerged?.(keep.personId)
    onClose()
  }

  return (
    <Dialog title={t('merge.dialogTitle')} onClose={onClose}>
      <div className="space-y-4">
        {!second && choices && (
          <label className="block text-sm">
            <span className="text-text-2">{t('merge.with')}</span>
            <select
              className="mt-1 w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-text"
              value={otherId}
              onChange={(e) => {
                setOtherId(e.target.value)
                const picked = choices.find((c) => c.personId === e.target.value)
                setKeepId(picked ? preferredTarget({ first, second: picked, reasons: ['name'] }) : null)
              }}
            >
              <option value="">{t('relink.choose')}</option>
              {choices.map((c) => (
                <option key={c.personId} value={c.personId}>{nameOf(c)}</option>
              ))}
            </select>
          </label>
        )}

        {other && (
          <fieldset className="space-y-2">
            <legend className="text-sm text-text-2">{t('merge.keepLabel')}</legend>
            {[first, other].map((c) => (
              <label key={c.personId} className="flex items-center gap-2 text-sm text-text">
                <input
                  type="radio"
                  name="keep"
                  checked={keepId === c.personId}
                  onChange={() => setKeepId(c.personId)}
                />
                {nameOf(c)}
              </label>
            ))}
          </fieldset>
        )}
        {keep && fold && (
          <p className="text-sm text-text-2">
            {t('merge.summary', { fold: nameOf(fold), keep: nameOf(keep) })}
          </p>
        )}
        <p className="text-sm font-medium text-warning">{t('merge.noUndo')}</p>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="danger" disabled={!keep || busy} onClick={confirm}>
            {busy ? t('common.working') : t('merge.confirm')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
