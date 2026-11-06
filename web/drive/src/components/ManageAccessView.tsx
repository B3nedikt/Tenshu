import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ContactItem } from '@tenshu/sdk/contact'
import type { MemberItem } from '@tenshu/sdk/membership'
import { connectedContacts, grantMembership, realmMembers, removeMember } from '../lib/proxy'
import { displayFingerprint } from '../lib/contacts'
import { queryKeys } from '../lib/queryKeys'
import { ConfirmDialog } from './Dialog'

// Manage access to a shared folder (drive-web-mode-b-sharing.md MB-4; GR-4/GR-6).
// Lists the realm's members, grants a connected contact access, and removes a
// member. Grant/remove ride the Membership SDK ops shipped in MB-2 — MB-4 just
// gives them a realm to act on. A contact already in the roster (matched by
// fingerprint, D7 dedupe) is hidden from the picker.
export function ManageAccessView({
  realmId,
  folderName,
  onBack,
}: {
  realmId: string
  folderName: string
  onBack: () => void
}) {
  const queryClient = useQueryClient()
  const [granting, setGranting] = useState(false)
  const [removing, setRemoving] = useState<MemberItem | null>(null)

  const membersQuery = useQuery({
    queryKey: queryKeys.realmMembers(realmId),
    queryFn: () => realmMembers(realmId),
  })
  const contactsQuery = useQuery({
    queryKey: queryKeys.contacts(),
    queryFn: () => connectedContacts(),
  })

  const members = membersQuery.data ?? []
  const memberFingerprints = new Set(members.map((m) => m.contactFingerprint).filter(Boolean))
  const grantable = (contactsQuery.data ?? []).filter(
    (c: ContactItem) => c.grantable && !memberFingerprints.has(c.fingerprint),
  )

  const grantMutation = useMutation({
    mutationFn: async (contact: ContactItem) => {
      const ok = await grantMembership(realmId, contact.personId, folderName)
      if (!ok) throw new Error(`Couldn't add ${contact.displayName || 'this contact'}.`)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.realmMembers(realmId) })
      setGranting(false)
    },
  })

  async function onRemove(member: MemberItem): Promise<string | null> {
    const ok = await removeMember(realmId, member.memberId)
    if (!ok) return "Couldn't remove this member."
    queryClient.invalidateQueries({ queryKey: queryKeys.realmMembers(realmId) })
    return null
  }

  return (
    <div className="space-y-4">
      <nav className="flex items-center gap-1 text-sm">
        <button type="button" onClick={onBack} className="text-text-2 hover:text-text">
          Shared
        </button>
        <span className="text-text-3">/</span>
        <span className="text-text">{folderName}</span>
      </nav>

      <div className="flex items-center justify-between">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-text-3">People</h2>
        <button
          type="button"
          onClick={() => setGranting((g) => !g)}
          className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
        >
          Add people
        </button>
      </div>

      {grantMutation.isError && (
        <p className="text-sm text-danger">{grantMutation.error.message}</p>
      )}

      {granting && (
        <div className="overflow-hidden rounded-xl border border-line bg-surface">
          {contactsQuery.isPending ? (
            <Message>Loading contacts…</Message>
          ) : grantable.length === 0 ? (
            <Message>No contacts left to add. Connect with someone in People first.</Message>
          ) : (
            <ul className="divide-y divide-line">
              {grantable.map((contact) => (
                <li key={contact.personId} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="min-w-0 flex-1 truncate text-sm text-text">
                    {contact.displayName || 'Unnamed'}
                  </span>
                  <button
                    type="button"
                    disabled={grantMutation.isPending}
                    onClick={() => grantMutation.mutate(contact)}
                    className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-text hover:bg-surface-3 disabled:opacity-50"
                  >
                    Add
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-line">
        {membersQuery.isPending ? (
          <Message>Loading…</Message>
        ) : membersQuery.isError ? (
          <Message tone="error">{String(membersQuery.error)}</Message>
        ) : members.length === 0 ? (
          <Message>Just you, for now. Add people to share this folder.</Message>
        ) : (
          <ul className="divide-y divide-line">
            {members.map((member) => (
              <li key={member.memberId} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-text">
                    {member.displayName || 'Unnamed'}
                    {member.role === 'owner' && <span className="text-text-3"> · owner</span>}
                  </p>
                  {member.contactFingerprint && (
                    <p className="truncate font-mono text-xs text-text-3">
                      {displayFingerprint(member.contactFingerprint)}
                    </p>
                  )}
                </div>
                {member.role !== 'owner' && (
                  <button
                    type="button"
                    onClick={() => setRemoving(member)}
                    className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-danger hover:bg-danger/10"
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {removing && (
        <ConfirmDialog
          title="Remove member"
          confirmLabel="Remove"
          message={
            <>
              Remove “<strong>{removing.displayName || 'this member'}</strong>” from{' '}
              {folderName}? They&rsquo;ll lose access.
            </>
          }
          onConfirm={() => onRemove(removing)}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
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
