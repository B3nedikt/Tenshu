import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ContactInviteItem, ContactItem } from '@tenshu/sdk/contact'
import { connectedContacts, listContactInvites, revokeContactInvite } from '../lib/proxy'
import { displayFingerprint } from '../lib/contacts'
import { queryKeys } from '../lib/queryKeys'
import { InviteDialog } from './InviteDialog'
import { ContactCompletionDialog } from './ContactCompletionDialog'
import { ScanInviteFlow } from './ScanInviteFlow'
import { ConfirmDialog } from './Dialog'

// The MB-1 *People* surface: connected contacts + the invite flow. The inviter
// mints a link (InviteDialog), the joiner opens it on the apex (redeem-on-open),
// and the inviter confirms the responder here (a fresh `REDEEMED` invite raises
// the completion prompt). Contacts are born `UNVERIFIED` (TOFU); the verify
// upgrade is MB-5. The connected list is also what MB-2's grant picker consumes.
export function PeopleView() {
  const queryClient = useQueryClient()
  const [inviting, setInviting] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [completing, setCompleting] = useState<ContactInviteItem | null>(null)
  const [revoking, setRevoking] = useState<ContactInviteItem | null>(null)

  const contactsQuery = useQuery({
    queryKey: queryKeys.contacts(),
    queryFn: () => connectedContacts(),
  })
  // Poll the invites so a fresh REDEEMED row (the responder arrived) surfaces
  // without a manual refresh — the snapshot + refetch shape (doc §6).
  const invitesQuery = useQuery({
    queryKey: queryKeys.contactInvites(),
    queryFn: () => listContactInvites(),
    refetchInterval: 5000,
  })

  const contacts = contactsQuery.data ?? []
  const invites = invitesQuery.data ?? []
  const pending = invites.filter((i) => i.status === 'PENDING' || i.status === 'REDEEMED')
  // ⚠ The open dialog renders the LATEST row, not the snapshot taken on click: a claim replaced
  // under it must re-render the code and drop the tick (threat model T-CI-10).
  const completingInvite = completing
    ? (invites.find((i) => i.inviteId === completing.inviteId) ?? completing)
    : null

  function refetchAll() {
    queryClient.invalidateQueries({ queryKey: queryKeys.contacts() })
    queryClient.invalidateQueries({ queryKey: queryKeys.contactInvites() })
  }

  async function onRevoke(invite: ContactInviteItem): Promise<string | null> {
    const ok = await revokeContactInvite(invite.inviteId)
    if (!ok) return "Couldn't cancel this invite."
    queryClient.invalidateQueries({ queryKey: queryKeys.contactInvites() })
    return null
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-text-2">People you&rsquo;ve connected with.</p>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setScanning(true)}
            className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
          >
            Scan invite
          </button>
          <button
            type="button"
            onClick={() => setInviting(true)}
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
          >
            Invite someone
          </button>
        </div>
      </div>

      {pending.length > 0 && (
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
            Invites
          </h2>
          <div className="overflow-hidden rounded-xl border border-line">
            <ul className="divide-y divide-line">
              {pending.map((invite) => (
                <InviteRow
                  key={invite.inviteId}
                  invite={invite}
                  onConfirm={() => setCompleting(invite)}
                  onCancel={() => setRevoking(invite)}
                />
              ))}
            </ul>
          </div>
        </section>
      )}

      <section>
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
          Contacts
        </h2>
        <div className="overflow-hidden rounded-xl border border-line">
          {contactsQuery.isPending ? (
            <Message>Loading…</Message>
          ) : contactsQuery.isError ? (
            <Message tone="error">{String(contactsQuery.error)}</Message>
          ) : contacts.length === 0 ? (
            <Message>No contacts yet. Invite someone to get started.</Message>
          ) : (
            <ul className="divide-y divide-line">
              {contacts.map((contact) => (
                <ContactRow key={contact.personId} contact={contact} />
              ))}
            </ul>
          )}
        </div>
      </section>

      {inviting && <InviteDialog onClose={() => setInviting(false)} />}
      {completingInvite && (
        <ContactCompletionDialog
          invite={completingInvite}
          onClose={() => setCompleting(null)}
          onDone={() => {
            setCompleting(null)
            refetchAll()
          }}
        />
      )}
      {revoking && (
        <ConfirmDialog
          title="Cancel invite"
          confirmLabel="Cancel invite"
          message="Cancel this invite? The link will stop working."
          onConfirm={() => onRevoke(revoking)}
          onClose={() => setRevoking(null)}
        />
      )}
      {scanning && (
        <ScanInviteFlow
          onClose={() => setScanning(false)}
          onDone={() => {
            setScanning(false)
            refetchAll()
          }}
        />
      )}
    </div>
  )
}

function InviteRow({
  invite,
  onConfirm,
  onCancel,
}: {
  invite: ContactInviteItem
  onConfirm: () => void
  onCancel: () => void
}) {
  const redeemed = invite.status === 'REDEEMED'
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        {redeemed ? (
          <>
            <p className="truncate text-sm text-text">
              “{invite.claimedName || 'Someone'}” responded
            </p>
            <p className="truncate font-mono text-xs text-text-3">
              {displayFingerprint(invite.claimedFingerprint)}
            </p>
          </>
        ) : (
          <>
            <p className="truncate text-sm text-text">Waiting for a response…</p>
            <p className="truncate text-xs text-text-3">Invite sent</p>
          </>
        )}
      </div>
      {redeemed ? (
        <button
          type="button"
          onClick={onConfirm}
          className="shrink-0 rounded-lg bg-primary px-2.5 py-1 text-sm font-medium text-on-primary hover:bg-primary/90"
        >
          Confirm
        </button>
      ) : (
        <button
          type="button"
          onClick={onCancel}
          className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-danger hover:bg-danger/10"
        >
          Cancel
        </button>
      )}
    </li>
  )
}

function ContactRow({ contact }: { contact: ContactItem }) {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 truncate text-sm text-text">
          {contact.displayName || 'Unnamed'}
          {contact.verified && (
            <span
              title="Verified in person via the security code"
              aria-label="Verified"
              className="text-success"
            >
              ✓
            </span>
          )}
        </p>
        <p className="truncate font-mono text-xs text-text-3">
          {displayFingerprint(contact.fingerprint)}
        </p>
      </div>
      {contact.verified ? (
        <span className="shrink-0 rounded-full bg-success/10 px-2 py-0.5 text-xs text-success">
          Verified
        </span>
      ) : (
        !contact.grantable && (
          <span
            title="This contact predates folder sharing — re-invite to share folders."
            className="shrink-0 rounded-full bg-surface-3 px-2 py-0.5 text-xs text-text-2"
          >
            Contact only
          </span>
        )
      )}
    </li>
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
