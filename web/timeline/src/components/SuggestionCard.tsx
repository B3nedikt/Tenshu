// The "read as a trip?" interlude. Accept binds the suggested moments into a trip and opens it;
// dismiss hides it for this tab (per tab, never synced — a reload offers it again, §Web scope 4c).
// ⚠ Both name the suggestion id the UI showed: if the suggestion changed underneath, the SDK
// refuses with `error_timeline_suggestion_gone` instead of acting on a different one — and the
// card then asks the feed again rather than retrying the stale id.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TimelineTripSuggestion } from '@tenshu/sdk/timeline'
import { acceptTripSuggestion, dismissTripSuggestion } from '../lib/proxy'
import { attempt, type Outcome } from '../lib/outcome'
import { Button, OutcomeText } from './ui'

export function SuggestionCard({
  suggestion,
  onAccepted,
  onResolved,
  onRefresh,
}: {
  suggestion: TimelineTripSuggestion
  onAccepted: (tripId: string) => void
  // The id is settled either way (accepted or dismissed): the feed may not re-emit at once.
  onResolved: (suggestionId: string) => void
  onRefresh: () => void
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function run(action: 'accept' | 'dismiss') {
    setBusy(true)
    setOutcome(null)
    const result =
      action === 'accept'
        ? await attempt(
            () => acceptTripSuggestion(suggestion.suggestionId),
            (r) => {
              onResolved(suggestion.suggestionId)
              onAccepted(r.tripId)
            },
          )
        : await attempt(
            () => dismissTripSuggestion(suggestion.suggestionId),
            () => onResolved(suggestion.suggestionId),
          )
    setBusy(false)
    if (result) {
      setOutcome(result)
      if ('failure' in result && result.failure.kind === 'error_timeline_suggestion_gone') onRefresh()
    }
  }

  return (
    <section aria-label={t('suggestion.label')} className="mb-6 rounded-xl border border-line bg-surface-2 px-4 py-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-primary">{t('suggestion.label')}</p>
      <p className="mt-1 text-base font-medium text-text">{suggestion.title || t('suggestion.untitled')}</p>
      <p className="mt-0.5 text-sm text-text-2">
        {[
          suggestion.dateRange,
          t('suggestion.days', { count: suggestion.daysSpan }),
          t('suggestion.places', { count: suggestion.placesCount }),
        ]
          .filter(Boolean)
          .join(' · ')}
      </p>
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void run('dismiss')}>
          {t('suggestion.dismiss')}
        </Button>
        <Button size="sm" disabled={busy} onClick={() => void run('accept')}>
          {t('suggestion.accept')}
        </Button>
      </div>
      <div className="mt-2">
        <OutcomeText outcome={outcome} />
      </div>
    </section>
  )
}
