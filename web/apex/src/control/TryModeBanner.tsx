// §8.2 Try-mode banner per
// `docs/proposals/onboarding-low-friction.md`. Persistent across the
// control plane while this browser's data really is local-only.
//
// ⚠⚠ **Gated on the synced entitlement, NOT on the commerce session.** It used to
// render on `usePublisherAccount() === null` alone, and that is a claim about
// *identity* used to assert a fact about *data location*. The two diverge the moment
// a browser pairs into an existing realm: the commerce session is device-local PII
// that by House Rule #1 can never sync, so a paired, funded, actively-syncing browser
// has no account here and was told "Your data lives only in this browser" — which it
// could have checked, and which was false.
//
// The shape follows `SyncBannerState.evaluate` (drive-shared-logic), which is the
// reference for this decision on the three native platforms: derive from sync-state
// signals, and where the signal has not arrived, **say nothing** rather than guess.
// Its `Disconnected` state exists for exactly this reason — "the app was asserting
// the user has no files at a moment when it could not ask".
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { usePublisherAccount } from './services/publisherContext'
import { useStorageStatus } from './services/storageStatusContext'

export function TryModeBanner() {
  const { t } = useTranslation('control')
  const account = usePublisherAccount()
  const { status, settled } = useStorageStatus()

  // Not heard back yet → assert nothing. The KMP evaluator's `storage == null -> null`.
  if (!settled) return null
  // A funded realm: files reach the relay, whatever this browser's sign-in state is.
  // This is the case the old gate got wrong, and it is the common one for a paired device.
  if (status?.hasStorage === true) return null
  // Signed in here but no storage yet — the account flow is already under way, and the
  // Services card is where that story continues. Preserves the previous behaviour.
  if (account !== null) return null
  return (
    <div
      role="status"
      className="border-b border-warning/30 bg-warning/10 px-4 py-3"
    >
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-warning">
            {t('tryMode.title')}
          </p>
          <p className="text-xs text-warning">
            {t('tryMode.body')}
          </p>
        </div>
        <Link
          to="/control/services/picker"
          className="rounded-md bg-warning px-3 py-1.5 text-xs font-medium text-white shadow-sm hover:bg-warning/90"
        >
          {t('tryMode.cta')}
        </Link>
      </div>
    </div>
  )
}
