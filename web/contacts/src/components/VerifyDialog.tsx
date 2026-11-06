// Re-verification — native Q2a compare-and-confirm: fetch the six digits for ONE key, the user reads
// them to the contact over a channel they already trust, and only an explicit "they match" marks
// the key verified.
//
// ⚠⚠ Threat model A24. `setVerified(…, true)` is reachable from exactly one button, which is not
// rendered until digits are on screen — confirming nothing is how a key ends up verified by a user
// who compared nothing. An empty code (unknown key, locked realm) renders as its absence.
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { contactSas, setVerified } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { displayFingerprint, formatSas } from '../lib/contacts'
import { Button, Dialog, ErrorText, OutcomeText } from './ui'
import { attempt, type Outcome } from '../lib/outcome'

export function VerifyDialog({
  fingerprint,
  name,
  onClose,
}: {
  fingerprint: string
  name: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const sas = useQuery({
    queryKey: queryKeys.sas(fingerprint),
    queryFn: () => contactSas(fingerprint),
    // The code is derived from two keys and never changes for a pair; but a locked realm answers
    // '' and must be asked again next time, so no long cache.
    staleTime: 0,
    gcTime: 0,
    // ⚠ Reading the digits to someone over the phone means switching windows; a focus refetch would
    // make the code flicker, and a refetch that throws would leave the old digits beside an error.
    refetchOnWindowFocus: false,
  })
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const [unchanged, setUnchanged] = useState(false)

  // ⚠ Only a CURRENT, successful answer counts: react-query keeps the previous `data` when a refetch
  // throws, and "They match" must never sit beside an error for a code no longer on screen.
  const code = !sas.error && sas.data?.ok ? sas.data.sas : ''

  async function confirm() {
    if (!code) return
    setBusy(true)
    setUnchanged(false)
    let changed = true
    const r = await attempt(async () => {
      const result = await setVerified(fingerprint, true)
      if (result.ok) changed = result.changed
      return result
    })
    setBusy(false)
    if (r) setOutcome(r)
    else if (!changed) setUnchanged(true)
    else onClose()
  }

  return (
    <Dialog title={t('verify.title')} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-text-2">{t('verify.body', { name })}</p>
        <div className="rounded-xl border border-line bg-surface px-4 py-5 text-center">
          {sas.isPending ? (
            <p className="text-sm text-text-3">{t('verify.loading')}</p>
          ) : sas.error ? (
            <ErrorText error={sas.error} />
          ) : sas.data && !sas.data.ok ? (
            <ErrorText failure={sas.data.error} />
          ) : code ? (
            <p className="font-mono text-3xl tracking-[0.3em] text-text" data-testid="sas-code">{formatSas(code)}</p>
          ) : (
            <p className="text-sm text-text-2">{t('verify.unavailable')}</p>
          )}
          <p className="mt-2 font-mono text-xs text-text-3">{displayFingerprint(fingerprint)}</p>
        </div>
        <OutcomeText outcome={outcome} />
        {unchanged && <p role="status" className="text-sm text-text-2">{t('verify.unchanged')}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>{t('verify.noMatch')}</Button>
          {code && (
            <Button disabled={busy} onClick={confirm}>
              {busy ? t('common.working') : t('verify.confirm')}
            </Button>
          )}
        </div>
      </div>
    </Dialog>
  )
}
