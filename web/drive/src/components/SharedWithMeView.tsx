import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ContactItem } from '@tenshu/sdk/contact'
import type { MemberItem, PendingGrantItem } from '@tenshu/sdk/membership'
import type { InboundRealmItem } from '@tenshu/sdk/sharedFiles'
import {
  acceptGrant,
  connectedContacts,
  declineGrant,
  pollPendingGrants,
  realmMembers,
  sharedWithMe,
} from '../lib/proxy'
import { displayFingerprint } from '../lib/contacts'
import { queryKeys } from '../lib/queryKeys'
import { SharedFolderBrowser } from './SharedFolderBrowser'

// The MB-2 *inbound* surface (drive-web-mode-b-sharing.md §7, membership-driven).
// Sweeps grantable contacts' addresses for waiting grants (`pollPendingGrants`)
// and renders accept/decline cards. Accepting joins the realm and shows its
// member roster as confirmation — the file browser for the shared folder is
// MB-3 (mount stays optional/deferred per §7). The grantor's petname is
// resolved from the connected-contacts list by fingerprint.
export function SharedWithMeView() {
  const queryClient = useQueryClient()
  const [joined, setJoined] = useState<{ grantor: string; realmId: string } | null>(null)
  const [viewing, setViewing] = useState<InboundRealmItem | null>(null)

  const contactsQuery = useQuery({
    queryKey: queryKeys.contacts(),
    queryFn: () => connectedContacts(),
  })
  // The realms this member belongs to — the browsable "Shared folders" (MB-3).
  const realmsQuery = useQuery({
    queryKey: queryKeys.sharedRealms(),
    queryFn: () => sharedWithMe(),
  })
  // Manual poll — pending-grant discovery is an explicit relay sweep, not a
  // cheap snapshot, so it runs on mount + on demand rather than on an interval.
  const pendingQuery = useQuery({
    queryKey: queryKeys.pendingGrants(),
    queryFn: () => pollPendingGrants(),
  })

  const contacts = contactsQuery.data ?? []
  const pending = pendingQuery.data ?? []
  const realms = realmsQuery.data ?? []

  function petname(fingerprint: string): string {
    const match = contacts.find((c: ContactItem) => c.fingerprint === fingerprint)
    return match?.displayName || 'A contact'
  }

  function realmLabel(realm: InboundRealmItem): string {
    return realm.name || 'Shared folder'
  }

  // Browsing a realm takes over the whole tab.
  if (viewing) {
    return (
      <SharedFolderBrowser
        realmId={viewing.sharedRealmId}
        realmName={realmLabel(viewing)}
        onBack={() => setViewing(null)}
      />
    )
  }

  async function onAccept(grant: PendingGrantItem) {
    const realmId = await acceptGrant(grant.grantorFingerprint)
    queryClient.invalidateQueries({ queryKey: queryKeys.pendingGrants() })
    queryClient.invalidateQueries({ queryKey: queryKeys.sharedRealms() })
    if (realmId) setJoined({ grantor: petname(grant.grantorFingerprint), realmId })
  }

  async function onDecline(grant: PendingGrantItem) {
    await declineGrant(grant.grantorFingerprint)
    queryClient.invalidateQueries({ queryKey: queryKeys.pendingGrants() })
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <p className="text-sm text-text-2">Folders other people have shared with you.</p>
        <button
          type="button"
          onClick={() => queryClient.invalidateQueries({ queryKey: queryKeys.pendingGrants() })}
          disabled={pendingQuery.isFetching}
          className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3 disabled:opacity-50"
        >
          {pendingQuery.isFetching ? 'Checking…' : 'Check for shares'}
        </button>
      </div>

      {pending.length > 0 && (
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
            Pending
          </h2>
          <div className="overflow-hidden rounded-xl border border-line">
            <ul className="divide-y divide-line">
              {pending.map((grant) => (
                <PendingGrantRow
                  key={grant.grantorFingerprint}
                  grant={grant}
                  grantor={petname(grant.grantorFingerprint)}
                  onAccept={() => onAccept(grant)}
                  onDecline={() => onDecline(grant)}
                />
              ))}
            </ul>
          </div>
        </section>
      )}

      <section>
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
          Shared folders
        </h2>
        <div className="overflow-hidden rounded-xl border border-line">
          {realmsQuery.isPending ? (
            <Message>Loading…</Message>
          ) : realmsQuery.isError ? (
            <Message tone="error">{String(realmsQuery.error)}</Message>
          ) : realms.length === 0 ? (
            <Message>Nothing shared with you yet.</Message>
          ) : (
            <ul className="divide-y divide-line">
              {realms.map((realm) => (
                <li key={realm.sharedRealmId}>
                  <button
                    type="button"
                    onClick={() => setViewing(realm)}
                    className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface-3"
                  >
                    <span aria-hidden className="text-text-3">
                      📁
                    </span>
                    <span className="min-w-0 flex-1 truncate text-sm text-text">
                      {realmLabel(realm)}
                    </span>
                    <span className="shrink-0 text-xs text-text-3">Open</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      {joined && (
        <JoinedRealm
          grantor={joined.grantor}
          realmId={joined.realmId}
          onClose={() => setJoined(null)}
        />
      )}
    </div>
  )
}

function PendingGrantRow({
  grant,
  grantor,
  onAccept,
  onDecline,
}: {
  grant: PendingGrantItem
  grantor: string
  onAccept: () => Promise<void>
  onDecline: () => Promise<void>
}) {
  const [busy, setBusy] = useState<'accept' | 'decline' | null>(null)

  async function run(which: 'accept' | 'decline', fn: () => Promise<void>) {
    setBusy(which)
    try {
      await fn()
    } finally {
      setBusy(null)
    }
  }

  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm text-text">
          <strong>{grantor}</strong> shared a folder with you
        </p>
        <p className="truncate font-mono text-xs text-text-3">
          {displayFingerprint(grant.grantorFingerprint)}
        </p>
      </div>
      <button
        type="button"
        disabled={busy !== null}
        onClick={() => run('decline', onDecline)}
        className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-danger hover:bg-danger/10 disabled:opacity-50"
      >
        {busy === 'decline' ? 'Declining…' : 'Decline'}
      </button>
      <button
        type="button"
        disabled={busy !== null}
        onClick={() => run('accept', onAccept)}
        className="shrink-0 rounded-lg bg-primary px-2.5 py-1 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
      >
        {busy === 'accept' ? 'Joining…' : 'Accept'}
      </button>
    </li>
  )
}

// After accepting, confirm the join by showing the realm's member roster. The
// MB-3 file browser has shipped — the realm is browsable from the list above
// via [SharedFolderBrowser]; this panel is the immediate post-accept receipt.
function JoinedRealm({
  grantor,
  realmId,
  onClose,
}: {
  grantor: string
  realmId: string
  onClose: () => void
}) {
  const { data, isPending } = useQuery({
    queryKey: queryKeys.realmMembers(realmId),
    queryFn: () => realmMembers(realmId),
  })
  const members = data ?? []

  return (
    <section className="rounded-xl border border-success/30 bg-success/10 p-4">
      <div className="flex items-start justify-between">
        <p className="text-sm text-success">
          You joined the folder <strong>{grantor}</strong> shared. Its files will appear here in a
          future update.
        </p>
        <button
          type="button"
          onClick={onClose}
          className="shrink-0 rounded-lg px-2 py-1 text-sm font-medium text-success hover:bg-success/10"
        >
          Dismiss
        </button>
      </div>
      <div className="mt-3">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-success">Members</h3>
        {isPending ? (
          <p className="mt-1 text-sm text-success">Loading…</p>
        ) : members.length === 0 ? (
          <p className="mt-1 text-sm text-success">Just you, for now.</p>
        ) : (
          <ul className="mt-1 space-y-0.5">
            {members.map((m: MemberItem) => (
              <li key={m.memberId} className="text-sm text-success">
                {m.displayName || 'Unnamed'}
                {m.role === 'owner' && <span className="text-success"> · owner</span>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

function Message({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode
  tone?: 'muted' | 'error'
}) {
  return (
    <p
      className={`px-4 py-8 text-center text-sm ${
        tone === 'error' ? 'text-danger' : 'text-text-3'
      }`}
    >
      {children}
    </p>
  )
}
