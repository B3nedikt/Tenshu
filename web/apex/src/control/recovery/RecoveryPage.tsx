// /control/settings/recovery — Settings → Recovery section per
// `web-control-plane.md` §4.6 + `control-ia.md` §7.2. Phase B v1 surfaces:
//
//  - Status panel: green/amber from the IDB `verifiedAtMs` mark Phase A
//    already wires into RecoveryBanner (`recoveryStatus.ts`).
//  - Manifest summary: hard-coded "Master identity + Drive files" for v1.
//    The editable per-volume manifest from threat-model §11.1 needs a
//    `Recovery.getManifest()` / `setManifest()` SDK pair that doesn't
//    exist yet — tracked as a Track A follow-up. The hard-coding is
//    accurate to the protocol's default at signup.
//  - Test recovery: full-phrase entry → `Session.unlockWithPhrase`. The
//    spec's "3 random words" UX needs `Recovery.revealPhrase()` SDK work
//    so the apex can pick word positions; full-phrase is the honest v1.
//  - Re-display recovery phrase: gated by the §4.6.1 dedicated WebAuthn
//    re-challenge primitive; the actual reveal panel waits on Track A
//    extending the master-key wrap blob to carry the BIP-39 entropy.
//  - Lost-everything explainer: links to `/recovery` per §4.6.
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { getRecoveryStatus, type RecoveryStatus } from '../recoveryStatus'
import { TestRecoveryDialog } from './TestRecoveryDialog'
import { RevealPhraseDialog } from './RevealPhraseDialog'

type ActiveDialog = 'none' | 'test' | 'reveal'

interface ManifestEntry {
  readonly id: 'masterIdentity' | 'driveFiles'
  readonly required: boolean
}

// v1 manifest reflects the protocol's default at signup per
// `sync/threat-model.md` §11.1. Master identity is always covered (it's
// what the recovery code derives directly); per-volume opt-ins are
// stubbed to "Drive files" because Drive is the only first-party volume
// today. This is hard-coded until the manifest read/write SDK ops land.
const V1_MANIFEST: readonly ManifestEntry[] = [
  { id: 'masterIdentity', required: true },
  { id: 'driveFiles', required: false },
]

export function RecoveryPage() {
  const { t } = useTranslation('control')
  const [status, setStatus] = useState<RecoveryStatus | null>(null)
  const [active, setActive] = useState<ActiveDialog>('none')

  useEffect(() => {
    let cancelled = false
    void getRecoveryStatus().then(s => {
      if (!cancelled) setStatus(s)
    })
    return () => {
      cancelled = true
    }
  }, [])

  function refreshStatus() {
    void getRecoveryStatus().then(setStatus)
  }

  return (
    <div>
      <h1 className="text-2xl font-medium">{t('recovery.page.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('recovery.page.subtitle')}</p>

      <section className="mt-8">
        <StatusPanel status={status} />
      </section>

      {/* §7.4 primary capture CTA. The deferred phrase-capture flow lives on
          its own route (full-card show + confirm) rather than a dialog. */}
      <section className="mt-8">
        <div className="flex flex-col gap-4 rounded-md border border-line bg-surface p-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-sm font-medium text-text">
              {t('recovery.page.setup.title')}
            </p>
            <p className="mt-1 text-xs text-text-2">{t('recovery.page.setup.description')}</p>
          </div>
          <Link
            to="/control/settings/recovery/setup"
            className="inline-flex shrink-0 items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
          >
            {t('recovery.page.setup.cta')}
          </Link>
        </div>
      </section>

      <section className="mt-8">
        <SectionHeader>{t('recovery.page.manifestTitle')}</SectionHeader>
        <ul className="mt-3 divide-y divide-line rounded-md border border-line">
          {V1_MANIFEST.map(entry => (
            <li key={entry.id} className="flex items-start justify-between gap-4 px-4 py-3">
              <div>
                <p className="text-sm font-medium text-text">
                  {t(`recovery.page.manifest.${entry.id}`)}
                </p>
                <p className="mt-0.5 text-xs text-text-2">
                  {t(`recovery.page.manifest.${entry.id}Description`)}
                </p>
              </div>
              <span className="shrink-0 rounded-full border border-line px-2 py-0.5 text-[11px] font-medium text-text-2">
                {entry.required ? t('recovery.page.manifest.always') : t('recovery.page.manifest.defaultOn')}
              </span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-text-2">{t('recovery.page.manifestNote')}</p>
      </section>

      <section className="mt-8">
        <SectionHeader>{t('recovery.page.actions')}</SectionHeader>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <ActionCard
            title={t('recovery.page.test.title')}
            description={t('recovery.page.test.description')}
            cta={t('recovery.page.test.cta')}
            onClick={() => setActive('test')}
          />
          <ActionCard
            title={t('recovery.page.reveal.title')}
            description={t('recovery.page.reveal.description')}
            cta={t('recovery.page.reveal.cta')}
            onClick={() => setActive('reveal')}
          />
        </div>
      </section>

      <section className="mt-10 rounded-md border border-line bg-surface p-4 text-sm">
        <p className="font-medium text-text">{t('recovery.page.lostEverything.title')}</p>
        <p className="mt-1 text-text-2">{t('recovery.page.lostEverything.body')}</p>
        <p className="mt-3">
          <Link
            to="/recovery"
            className="text-sm font-medium text-text underline-offset-4 hover:underline"
          >
            {t('recovery.page.lostEverything.openLink')}
          </Link>
        </p>
      </section>

      {active === 'test' && (
        <TestRecoveryDialog
          onClose={() => setActive('none')}
          onVerified={refreshStatus}
        />
      )}
      {active === 'reveal' && (
        <RevealPhraseDialog onClose={() => setActive('none')} />
      )}
    </div>
  )
}

function SectionHeader({ children }: { readonly children: React.ReactNode }) {
  return (
    <h2 className="text-sm font-semibold uppercase tracking-wide text-text-2">{children}</h2>
  )
}

function StatusPanel({ status }: { readonly status: RecoveryStatus | null }) {
  const { t } = useTranslation('control')
  if (status === null) {
    return (
      <div className="rounded-md border border-line p-4 text-sm text-text-2">
        {t('recovery.page.loading')}
      </div>
    )
  }
  if (status.kind === 'verified') {
    return (
      <div className="rounded-md border border-success/30 bg-success/10 p-4">
        <p className="text-sm font-medium text-success">
          {t('recovery.page.verified')}
        </p>
        <p className="mt-1 text-sm text-success">
          {t('recovery.page.verifiedBody', { date: new Date(status.verifiedAtMs).toLocaleDateString() })}
        </p>
      </div>
    )
  }
  const isStale = status.kind === 'stale'
  return (
    <div className="rounded-md border border-warning/30 bg-warning/10 p-4">
      <p className="text-sm font-medium text-warning">
        {isStale ? t('recovery.page.reverify') : t('recovery.page.verify')}
      </p>
      <p className="mt-1 text-sm text-warning">
        {isStale
          ? t('recovery.page.reverifyBody', { date: new Date(status.verifiedAtMs).toLocaleDateString() })
          : t('recovery.page.verifyBody')}
      </p>
    </div>
  )
}

function ActionCard({
  title,
  description,
  cta,
  onClick,
}: {
  readonly title: string
  readonly description: string
  readonly cta: string
  readonly onClick: () => void
}) {
  return (
    <div className="flex flex-col rounded-md border border-line bg-surface p-4">
      <p className="text-sm font-medium text-text">{title}</p>
      <p className="mt-1 text-xs text-text-2">{description}</p>
      <div className="mt-4">
        <button
          type="button"
          onClick={onClick}
          className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium text-text shadow-sm transition-colors hover:bg-surface-3"
        >
          {cta}
        </button>
      </div>
    </div>
  )
}
