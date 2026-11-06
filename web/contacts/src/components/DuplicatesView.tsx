// Possible duplicates — native `MergeViewModel`: pairs derived from the live book on every
// emission, never stored, and nothing written until the user confirms one pair (D7).
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useContacts } from '../lib/live'
import { findMergeCandidates, type MergeCandidate } from '../lib/contacts'
import { Button, ErrorText } from './ui'
import { useContactName } from '../lib/outcome'
import { MergeDialog } from './MergeDialog'

export function DuplicatesView() {
  const { t } = useTranslation()
  const live = useContacts()
  const nameOf = useContactName()
  const [reviewing, setReviewing] = useState<MergeCandidate | null>(null)

  if (live.isPending) return <p className="text-sm text-text-3">{t('common.loading')}</p>
  const result = live.data
  if (!result) return <ErrorText error={live.error} />
  if (!result.ok) return <ErrorText failure={result.error} />

  const candidates = findMergeCandidates(result.contacts)
  // A pair whose contacts changed underneath (merged elsewhere, one deleted) stops being
  // reviewable — the same guard the native VM keeps.
  const stillThere =
    reviewing &&
    candidates.find(
      (c) =>
        new Set([c.first.personId, c.second.personId]).has(reviewing.first.personId) &&
        new Set([c.first.personId, c.second.personId]).has(reviewing.second.personId),
    )

  if (candidates.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-line p-8 text-center">
        <p className="font-medium text-text">{t('merge.emptyTitle')}</p>
        <p className="mt-1 text-sm text-text-2">{t('merge.emptyBody')}</p>
      </div>
    )
  }

  return (
    <section className="space-y-3">
      <p className="text-sm text-text-2">{t('merge.count', { count: candidates.length })}</p>
      <ul className="divide-y divide-line rounded-xl border border-line">
        {candidates.map((c) => (
          <li key={`${c.first.personId}|${c.second.personId}`} className="flex items-center justify-between gap-3 px-4 py-3">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-text">
                {t('merge.pair', { first: nameOf(c.first), second: nameOf(c.second) })}
              </p>
              <p className="text-xs text-text-3">
                {c.reasons.map((r) => t(`merge.reason.${r}`)).join(' · ')}
                {c.reasons.length === 1 && c.reasons[0] === 'name' && ` ${t('merge.nameOnlyNote')}`}
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={() => setReviewing(c)}>{t('merge.review')}</Button>
          </li>
        ))}
      </ul>
      {reviewing && stillThere && (
        <MergeDialog first={stillThere.first} second={stillThere.second} onClose={() => setReviewing(null)} />
      )}
    </section>
  )
}
