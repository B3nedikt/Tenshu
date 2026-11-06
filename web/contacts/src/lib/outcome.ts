// What a write reports back to the dialog that ran it, and the petname fallback — plain functions,
// kept out of `components/ui.tsx` so that file exports components only (fast refresh).
import { useTranslation } from 'react-i18next'
import type { Contact } from '@tenshu/sdk/contact'
import type { Failure } from './errors'

// What a mutation reports back to a dialog: `null` on success, else what to show.
export type Outcome = { failure: Failure } | { error: unknown } | null

export async function attempt(run: () => Promise<{ ok: true } | { ok: false; error: Failure }>): Promise<Outcome> {
  try {
    const result = await run()
    return result.ok ? null : { failure: result.error }
  } catch (error) {
    return { error }
  }
}

// The petname, or the app's own "unnamed" — `''` is a legitimate stored value (run A).
export function useContactName(): (contact: Pick<Contact, 'displayName'>) => string {
  const { t } = useTranslation()
  return (contact) => contact.displayName.trim() || t('common.unnamed')
}

