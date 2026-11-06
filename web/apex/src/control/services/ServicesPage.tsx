// §6.1 Services tab per `docs/proposals/onboarding-low-friction.md`, restructured
// per commerce CE-4 / D5 (see `servicesCardState.ts` for the reasoning).
//
// ⚠⚠ The card has two halves that answer different questions, and only one of
// them is about this device's session. Storage is read from the synced
// entitlement and shown ALWAYS — a paired device that never signed in here still
// has a real plan and real usage. Identity is shown only with a session, because
// it is what decides whether identity actions can be offered.
//
// The previous version gated the whole card on `account === null` and read its
// figures off the local mock, so signing in replaced live figures with invented
// ones. Drive's own Settings, reading the same `Storage.status()` in the same
// browser, disagreed with it — and Drive was right.
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { IdentityMethod } from './publisherClient'
import { usePublisherAccount, usePublisherClient } from './publisherContext'
import { useStorageStatus } from './storageStatusContext'
import { readPersistence, type PersistenceState } from '../../lib/persistentStorage'
import {
  servicesCardState,
  signedInHere,
  storageProgressPct,
  storageSectionState,
  type ServicesCardState,
  type SyncedStorage,
} from './servicesCardState'
import { formatStorageBytes } from '../../lib/format'
import { ACCOUNT_DELETION_HREF } from '../../landing/marketingNav'

export function ServicesPage() {
  const { t } = useTranslation('control')
  const account = usePublisherAccount()
  const client = usePublisherClient()
  const [revision, setRevision] = useState(0)
  const { status, settled } = useStorageStatus(revision)
  // A signed-in device asks commerce for its current plan whenever this page
  // opens: that is what brings a purchase, a renewal or a lapse to the card.
  useEffect(() => {
    if (account === null) return
    let cancelled = false
    void client.syncPlan().then(result => {
      if (!cancelled && result.kind === 'synced') setRevision(r => r + 1)
    })
    return () => {
      cancelled = true
    }
  }, [client, account])
  const state = servicesCardState(account, status)
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-4 text-2xl font-semibold tracking-tight">{t('services.page.title')}</h1>
      <TenshuRelayCard state={state} settled={settled} />
      {/* The public deletion page, outside the runtime: it works by emailed code, signed in or not. */}
      <p className="mt-4 text-sm">
        <Link to={ACCOUNT_DELETION_HREF} className="text-text-2 underline-offset-4 hover:text-danger hover:underline">
          {t('services.page.deleteAccount')}
        </Link>
      </p>
    </div>
  )
}

function TenshuRelayCard({
  state,
  settled,
}: {
  readonly state: ServicesCardState
  readonly settled: boolean
}) {
  const { t } = useTranslation('control')
  const signedIn = signedInHere(state)
  return (
    <section className="rounded-lg border border-line bg-surface p-6">
      <h2 className="text-lg font-semibold">{t('services.page.tenshuRelayTitle')}</h2>
      <dl className="mt-4 space-y-3 text-sm">
        <StorageSection storage={state.storage} settled={settled} />
        {state.identity !== null && (
          <div>
            <dt className="text-text-2">{t('services.page.identity')}</dt>
            <dd className="text-text">{identityLabel(t, state.identity)}</dd>
          </div>
        )}
      </dl>
      {/* No session on this device: the plan above may still be real (a paired
          device), so the CTA is to sign in / sign up — never a claim about storage. */}
      {!signedIn && (
        <p className="mt-4 text-sm text-text-2">
          {state.storage === null
            ? t('services.page.emptyBody')
            : t('services.page.notSignedInBody')}
        </p>
      )}
      <div className="mt-6 flex flex-wrap gap-3">
        {signedIn ? (
          <>
            <Link
              to="/control/services/picker"
              className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
            >
              {t('services.page.getMoreStorage')}
            </Link>
            <ManageBillingButton />
            <Link
              to="/control/services/disconnect"
              className="rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface"
            >
              {t('services.page.disconnect')}
            </Link>
          </>
        ) : (
          <Link
            to="/control/services/picker"
            className="inline-flex items-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
          >
            {t('services.page.signUp')}
          </Link>
        )}
      </div>
    </section>
  )
}

/**
 * Opens Stripe's billing portal — where a customer cancels, changes card, or
 * resubscribes. A session is required, so this only renders when signed in.
 *
 * ⚠ Failures are reported in place rather than thrown away: `noSubscription`
 * (409) and `unavailable` (503) are ordinary answers, not errors, and a button
 * that silently does nothing is the worst of the three.
 */
function ManageBillingButton() {
  const { t } = useTranslation('control')
  const client = usePublisherClient()
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)

  async function open() {
    if (busy) return
    setBusy(true)
    setNote(null)
    const result = await client.openBillingPortal()
    setBusy(false)
    if (result.kind === 'ready') {
      window.location.assign(result.portalUrl)
      return
    }
    if (result.kind === 'noSubscription') {
      setNote(t('services.page.portalNoSubscription'))
    } else if (result.kind === 'unavailable') {
      setNote(t('services.page.portalUnavailable'))
    } else if (result.kind === 'unauthorized') {
      setNote(t('services.page.portalNoSubscription'))
    } else {
      setNote(result.message)
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={() => void open()}
        disabled={busy}
        className="rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface disabled:opacity-60"
      >
        {t('services.page.manageBilling')}
      </button>
      {note !== null && <p className="w-full text-sm text-text-2">{note}</p>}
    </>
  )
}

function StorageSection({
  storage,
  settled,
}: {
  readonly storage: SyncedStorage | null
  readonly settled: boolean
}) {
  const { t } = useTranslation('control')
  const section = storageSectionState(storage, settled)
  if (section.kind === 'checking') {
    return (
      <div>
        <dt className="text-text-2">{t('services.page.storage')}</dt>
        <dd className="text-text-2">{t('services.page.storageChecking')}</dd>
      </div>
    )
  }
  if (section.kind === 'none') {
    return (
      <div>
        <dt className="text-text-2">{t('services.page.storage')}</dt>
        <dd className="text-text-2">{t('services.page.storageNone')}</dd>
      </div>
    )
  }
  if (section.kind === 'provisioning') {
    return (
      <div>
        <dt className="text-text-2">{t('services.page.storage')}</dt>
        <dd className="text-text-2">{t('services.page.storageProvisioning')}</dd>
      </div>
    )
  }
  const { storage: figures } = section
  return (
    <>
      <div>
        <dt className="text-text-2">{t('services.page.plan')}</dt>
        <dd className="text-text">{figures.plan}</dd>
      </div>
      <div>
        <dt className="text-text-2">{t('services.page.storage')}</dt>
        <dd className="text-text">
          {t('services.page.storageLabel', {
            used: formatStorageBytes(figures.usedBytes),
            limit: formatStorageBytes(figures.limitBytes),
          })}
        </dd>
        <div className="mt-1 h-2 w-full rounded-full bg-surface-3">
          <div
            className="h-2 rounded-full bg-success"
            style={{ width: `${storageProgressPct(figures)}%` }}
          />
        </div>
      </div>
      <DeviceStorageRow />
    </>
  )
}

// W10a. The plan figures above describe the REALM's storage on the relay; this row describes
// whether *this browser* is allowed to keep its copy — a different question with a worse failure.
//
// ⚠ Renders nothing while the answer is unknown, following the same rule TryModeBanner states:
// where the signal has not arrived, say nothing rather than guess.
function DeviceStorageRow() {
  const { t } = useTranslation('control')
  const [state, setState] = useState<PersistenceState>('unknown')

  useEffect(() => {
    let cancelled = false
    void readPersistence().then((s) => {
      if (!cancelled) setState(s)
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (state === 'unknown') return null
  return (
    <div>
      <dt className="text-text-2">{t('services.page.deviceStorage')}</dt>
      <dd className={state === 'persisted' ? 'text-text' : 'text-warning'}>
        {state === 'persisted'
          ? t('services.page.deviceStoragePersisted')
          : t('services.page.deviceStorageEvictable')}
      </dd>
    </div>
  )
}

// Email OTP is the only way to hold a session, so this is a single case rather
// than a switch. `lifetimeStore` and `reiKey` went with the tier and the plan key.
function identityLabel(t: TFunction, method: IdentityMethod): string {
  return t('services.page.identityEmail', { address: method.address })
}
