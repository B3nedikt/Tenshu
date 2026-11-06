import { useQuery } from '@tanstack/react-query'
import { privacyPolicyLabel, privacyPolicyUrl } from '@tenshu/web-tokens/legal'
import { APEX, storageStatus } from '../lib/proxy'
import { formatBytes } from '../lib/paths'
import { queryKeys } from '../lib/queryKeys'

// Minimal web Drive settings. Most of the native Settings surface is either
// N-A on web (offline toggle, synced-folders auto-backup — see drive-web-app.md
// §3) or lives in the Tenshu apex control plane (account, devices, recovery),
// so this page is intentionally thin: what the app is, where it's connected,
// and a hand-off to the apex for account management.
export function SettingsView() {
  return (
    <div className="space-y-5">
      <Section title="About">
        <p className="text-sm text-text-2">
          Drive stores your files end-to-end encrypted. Only your devices can read them — the
          relay only ever sees ciphertext.
        </p>
        {/* The in-app privacy policy (the native apps link it from Settings too). Web Drive has
            no translations, so the browser's language picks the page and the label alike. */}
        <a
          href={privacyPolicyUrl(browserLanguage())}
          target="_blank"
          rel="noreferrer"
          className="mt-3 inline-block text-sm font-medium text-text underline-offset-4 hover:underline"
        >
          {privacyPolicyLabel(browserLanguage())}
        </a>
      </Section>

      <StorageSection />

      <Section title="Account">
        <p className="text-sm text-text-2">
          Your account, devices, and recovery phrase are managed in Tenshu.
        </p>
        <a
          href={APEX}
          target="_blank"
          rel="noreferrer"
          className="mt-3 inline-block rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
        >
          Manage account in Tenshu
        </a>
      </Section>
    </div>
  )
}

// Read-only storage-provision summary (web-storage-status.md). Display only —
// the upload/share gate that reads the same status lands once it's backed by a
// real entitlement. `hasStorage: false` (a device with no storage plan) shows a
// hand-off to the apex; a bound plan shows "used of limit".
function StorageSection() {
  const { data, isPending, isError } = useQuery({
    queryKey: queryKeys.storageStatus(),
    queryFn: () => storageStatus(),
  })

  return (
    <Section title="Storage">
      {isPending ? (
        <p className="text-sm text-text-2">Checking…</p>
      ) : isError || !data || !data.hasStorage ? (
        <p className="text-sm text-text-2">
          No storage plan on this device. Manage your plan in Tenshu.
        </p>
      ) : (
        <p className="text-sm text-text">
          {formatBytes(data.usedBytes)} of {formatBytes(data.limitBytes)} used
        </p>
      )}
    </Section>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-line p-4">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
        {title}
      </h2>
      {children}
    </section>
  )
}

function browserLanguage(): string | null {
  return typeof navigator === 'undefined' ? null : navigator.language
}
