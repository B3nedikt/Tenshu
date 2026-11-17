import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ContactInviteItem, ContactItem } from '@tenshu/sdk/contact'
import type { MemberItem } from '@tenshu/sdk/membership'
import {
  connectedContacts,
  grantMembership,
  listContactInvites,
  realmMembers,
  removeMember,
  revokeContactInvite,
  shareFolder,
  sharedFolders,
} from '../lib/proxy'
import { displayFingerprint } from '../lib/contacts'
import { queryKeys } from '../lib/queryKeys'
import { ConfirmDialog } from './Dialog'
import { ContactCompletionDialog } from './ContactCompletionDialog'
import { InviteDialog, SHARE_FOLDER } from './InviteDialog'

// A folder's Share screen — the one place a folder is shared from (drive-manage-access-ux.md §11;
// MB-4, GR-4/GR-6). Lists the realm's members, adds a connected contact, invites someone new, and
// removes a member.
//
// ⚠ The folder is shared on the FIRST ADD, not on opening this screen: adding a contact or inviting
// someone runs `shareFolder` first when the folder backs no realm yet. Before sharing, the screen
// must KNOW the folder is unshared — a failed or pending `sharedFolders` read is never taken as
// "not shared", because sharing on that guess mints a second realm for an already-shared folder.
//
// This folder's invites (`SHARE_FOLDER`, labelled with the folder name) live here, not in the
// Contacts app — Contacts lists bare invites only. A completed one whose contact is not a member yet
// is offered as "Add to folder": the handshake only connects two people, the grant is separate.
export function ManageAccessView({
  folderPath,
  folderName,
  realmId: knownRealmId = null,
  backLabel = 'Back',
  onBack,
}: {
  folderPath: string
  folderName: string
  // The realm this folder backs, when the caller already knows it (the Shared tab).
  realmId?: string | null
  backLabel?: string
  onBack: () => void
}) {
  const queryClient = useQueryClient()
  const [granting, setGranting] = useState(false)
  const [inviting, setInviting] = useState(false)
  const [removing, setRemoving] = useState<MemberItem | null>(null)
  const [revoking, setRevoking] = useState<ContactInviteItem | null>(null)
  const [completing, setCompleting] = useState<ContactInviteItem | null>(null)
  const [createdRealmId, setCreatedRealmId] = useState<string | null>(null)
  // The in-flight share, so a second add while the first is sharing awaits it instead of sharing again.
  const sharingRef = useRef<Promise<string> | null>(null)

  const sharedQuery = useQuery({
    queryKey: queryKeys.sharedFolders(),
    queryFn: () => sharedFolders(),
    enabled: knownRealmId === null,
  })
  const lookedUpRealmId =
    sharedQuery.data?.find((f) => joinPath(f.parentPath, f.name) === folderPath)?.sharedRealmId ?? null
  const realmId = knownRealmId ?? createdRealmId ?? lookedUpRealmId

  const membersQuery = useQuery({
    queryKey: queryKeys.realmMembers(realmId ?? ''),
    queryFn: () => realmMembers(realmId as string),
    enabled: realmId !== null,
  })
  const contactsQuery = useQuery({
    queryKey: queryKeys.contacts(),
    queryFn: () => connectedContacts(),
  })
  // Polled so a fresh REDEEMED row (the invitee responded) surfaces without a refresh.
  const invitesQuery = useQuery({
    queryKey: queryKeys.contactInvites(),
    queryFn: () => listContactInvites(),
    refetchInterval: 5000,
  })

  const members = membersQuery.data ?? []
  const memberFingerprints = new Set(members.map((m) => m.contactFingerprint).filter(Boolean))
  const contacts = contactsQuery.data ?? []
  const grantable = contacts.filter(
    (c: ContactItem) => c.grantable && !memberFingerprints.has(c.fingerprint),
  )
  const folderInvites = (invitesQuery.data ?? []).filter(
    (i) => i.intentKind === SHARE_FOLDER && i.intentLabel === folderName,
  )
  const openInvites = folderInvites.filter((i) => i.status === 'PENDING' || i.status === 'REDEEMED')
  // Connected through this folder's invite, but not added yet: the deferred grant offer.
  const offers = folderInvites.flatMap((invite) => {
    if (invite.status !== 'COMPLETED' || memberFingerprints.has(invite.claimedFingerprint)) return []
    const contact = grantable.find((c) => c.fingerprint === invite.claimedFingerprint)
    return contact ? [{ invite, contact }] : []
  })
  // ⚠ The open dialog renders the LATEST row, not the snapshot taken on click: a claim replaced
  // under it must re-render the code and drop the tick (threat model T-CI-10).
  const completingInvite = completing
    ? (folderInvites.find((i) => i.inviteId === completing.inviteId) ?? completing)
    : null

  // The realm to add people to — sharing the folder into a fresh one first if it backs none.
  async function ensureRealm(): Promise<string> {
    if (realmId) return realmId
    if (sharingRef.current) return sharingRef.current
    const run = (async () => {
      // Unknown is not "not shared": settle the lookup before deciding to share.
      if (!sharedQuery.isSuccess) {
        const lookup = await sharedQuery.refetch()
        if (!lookup.isSuccess) throw new Error(`Couldn't check whether “${folderName}” is already shared.`)
        const found = lookup.data.find((f) => joinPath(f.parentPath, f.name) === folderPath)
        if (found) {
          setCreatedRealmId(found.sharedRealmId)
          return found.sharedRealmId
        }
      }
      const result = await shareFolder(folderPath)
      // ⚠⚠ Naming the step is not cosmetic: 'share' and 'mount' both mean a shared realm was
      // already created on the relay, so the user's next attempt is not starting from nothing.
      if (!result.ok) {
        const where = result.error.kind === 'realm' ? '' : ' (a shared realm was already created)'
        throw new Error(`Couldn't share “${folderName}”${where}.`)
      }
      setCreatedRealmId(result.item.sharedRealmId)
      queryClient.invalidateQueries({ queryKey: queryKeys.sharedFolders() })
      return result.item.sharedRealmId
    })()
    sharingRef.current = run
    try {
      return await run
    } finally {
      sharingRef.current = null
    }
  }

  const grantMutation = useMutation({
    mutationFn: async (contact: ContactItem) => {
      const realm = await ensureRealm()
      const ok = await grantMembership(realm, contact.personId, folderName)
      if (!ok) throw new Error(`Couldn't add ${contact.displayName || 'this contact'}.`)
      return realm
    },
    onSuccess: (realm) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.realmMembers(realm) })
      setGranting(false)
    },
  })

  const inviteMutation = useMutation({
    mutationFn: () => ensureRealm(),
    onSuccess: () => setInviting(true),
  })

  const busy = grantMutation.isPending || inviteMutation.isPending
  const error = grantMutation.error ?? inviteMutation.error

  async function onRemove(member: MemberItem): Promise<string | null> {
    if (!realmId) return null
    const ok = await removeMember(realmId, member.memberId)
    if (!ok) return "Couldn't remove this member."
    queryClient.invalidateQueries({ queryKey: queryKeys.realmMembers(realmId) })
    return null
  }

  async function onRevoke(invite: ContactInviteItem): Promise<string | null> {
    const ok = await revokeContactInvite(invite.inviteId)
    if (!ok) return "Couldn't cancel this invite."
    queryClient.invalidateQueries({ queryKey: queryKeys.contactInvites() })
    return null
  }

  return (
    <div className="space-y-4">
      <nav className="flex items-center gap-1 text-sm">
        <button type="button" onClick={onBack} className="text-text-2 hover:text-text">
          {backLabel}
        </button>
        <span className="text-text-3">/</span>
        <span className="text-text">Share “{folderName}”</span>
      </nav>

      <div className="flex items-center justify-between gap-2">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-text-3">
          People with access
        </h2>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() => inviteMutation.mutate()}
            className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3 disabled:opacity-50"
          >
            Invite someone
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => setGranting((g) => !g)}
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
          >
            Add people
          </button>
        </div>
      </div>

      {busy && !realmId && <p className="text-sm text-text-2">Sharing this folder…</p>}
      {error && <p className="text-sm text-danger">{error.message}</p>}

      {granting && (
        <div className="overflow-hidden rounded-xl border border-line bg-surface">
          {contactsQuery.isPending ? (
            <Message>Loading contacts…</Message>
          ) : grantable.length === 0 ? (
            <Message>No contacts left to add. Invite someone new instead.</Message>
          ) : (
            <ul className="divide-y divide-line">
              {grantable.map((contact) => (
                <li key={contact.personId} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="min-w-0 flex-1 truncate text-sm text-text">
                    {contact.displayName || 'Unnamed'}
                  </span>
                  <button
                    type="button"
                    disabled={busy}
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

      {(openInvites.length > 0 || offers.length > 0) && (
        <div className="overflow-hidden rounded-xl border border-line">
          <ul className="divide-y divide-line">
            {offers.map(({ invite, contact }) => (
              <li key={invite.inviteId} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-text">
                    {contact.displayName || 'Your contact'} is connected
                  </p>
                  <p className="truncate text-xs text-text-3">Not added to this folder yet</p>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => grantMutation.mutate(contact)}
                  className="shrink-0 rounded-lg bg-primary px-2.5 py-1 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
                >
                  Add to folder
                </button>
              </li>
            ))}
            {openInvites.map((invite) => (
              <InviteRow
                key={invite.inviteId}
                invite={invite}
                onConfirm={() => setCompleting(invite)}
                onCancel={() => setRevoking(invite)}
              />
            ))}
          </ul>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-line">
        {realmId === null ? (
          sharedQuery.isPending && knownRealmId === null ? (
            <Message>Loading…</Message>
          ) : (
            <Message>Only you have access. Add people or invite someone to share this folder.</Message>
          )
        ) : membersQuery.isPending ? (
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

      {inviting && (
        <InviteDialog
          intentKind={SHARE_FOLDER}
          intentLabel={folderName}
          onClose={() => {
            setInviting(false)
            queryClient.invalidateQueries({ queryKey: queryKeys.contactInvites() })
          }}
        />
      )}
      {completingInvite && (
        <ContactCompletionDialog
          invite={completingInvite}
          onClose={() => setCompleting(null)}
          onDone={() => {
            setCompleting(null)
            queryClient.invalidateQueries({ queryKey: queryKeys.contacts() })
            queryClient.invalidateQueries({ queryKey: queryKeys.contactInvites() })
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

function joinPath(parentPath: string, name: string): string {
  return parentPath === '/' ? `/${name}` : `${parentPath}/${name}`
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
