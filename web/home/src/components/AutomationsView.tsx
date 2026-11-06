// The Automations tab, as the iOS read-only list (`home-ios` `AutomationsView.swift`): each routine
// native lists, with "trigger · first action" and an On/Off label.
//
// ⚠⚠ READ-ONLY, decided. The On/Off is a LABEL reporting the synced `enabled` field, never a switch;
// there is no edit, remove, run-now or create. ⚠ Which routines show is native's choice, not run A's
// (`lib/automations.ts` → `visibleAutomations`).
import { useTranslation } from 'react-i18next'
import { automationSummary, visibleAutomations, type ListedAutomation } from '../lib/automations'
import { useAutomations, useDevices } from '../lib/live'
import type { HomeDevice } from '@tenshu/sdk/home'
import { EmptyState, Loading, ReadFailure, Stopped } from './ui'

export function AutomationsView() {
  const automations = useAutomations()
  const devices = useDevices()
  // Names for the summary; a failed devices read degrades a name to "Unknown device", as native.
  const household = devices.data?.ok ? devices.data.devices : []

  return (
    <div>
      <Stopped live={automations} />
      {automations.isPending ? (
        <Loading />
      ) : automations.error ? (
        <ReadFailure error={automations.error} onRetry={automations.retry} />
      ) : automations.data && !automations.data.ok ? (
        <ReadFailure failure={automations.data.error} onRetry={automations.retry} />
      ) : automations.data?.ok ? (
        <AutomationList listed={visibleAutomations(automations.data.automations)} household={household} />
      ) : null}
    </div>
  )
}

function AutomationList({ listed, household }: { listed: ListedAutomation[]; household: readonly HomeDevice[] }) {
  const { t } = useTranslation()
  if (listed.length === 0) return <EmptyState title={t('automations.emptyTitle')} body={t('automations.emptyBody')} />
  return (
    <ul aria-label={t('nav.automations')} className="divide-y divide-line rounded-lg border border-line">
      {listed.map((a) => (
        <li key={a.routineId} className="flex items-center justify-between gap-3 px-4 py-3" data-testid="automation-row" data-id={a.routineId}>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-text">{a.name}</span>
            <span className="block truncate text-xs text-text-2">{automationSummary(t, a, household)}</span>
          </span>
          <span className={`shrink-0 text-xs font-medium ${a.enabled ? 'text-primary' : 'text-text-2'}`} data-testid="enabled-label">
            {a.enabled ? t('automations.on') : t('automations.off')}
          </span>
        </li>
      ))}
    </ul>
  )
}
