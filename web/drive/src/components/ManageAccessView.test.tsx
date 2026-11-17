import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const realmMembers = vi.fn()
const connectedContacts = vi.fn()
const grantMembership = vi.fn()
const removeMember = vi.fn()
const listContactInvites = vi.fn()
const revokeContactInvite = vi.fn()
const completeHandshake = vi.fn()
const createContactInvite = vi.fn()
const shareFolder = vi.fn()
const sharedFolders = vi.fn()
vi.mock('../lib/proxy', () => ({
  realmMembers: (id: string) => realmMembers(id),
  connectedContacts: () => connectedContacts(),
  grantMembership: (r: string, p: string, l: string) => grantMembership(r, p, l),
  removeMember: (r: string, m: string) => removeMember(r, m),
  listContactInvites: () => listContactInvites(),
  revokeContactInvite: (id: string) => revokeContactInvite(id),
  completeHandshake: (args: unknown) => completeHandshake(args),
  createContactInvite: (k: string, l: string) => createContactInvite(k, l),
  shareFolder: (p: string) => shareFolder(p),
  sharedFolders: () => sharedFolders(),
}))

const { ManageAccessView } = await import('./ManageAccessView')

function invite(over: Record<string, unknown> = {}) {
  return {
    inviteId: 'inv-1',
    status: 'PENDING',
    intentKind: 'SHARE_FOLDER',
    intentLabel: 'Holiday Photos',
    createdAtMillis: Date.UTC(2024, 0, 1),
    expiresAtMillis: Date.UTC(2024, 0, 4),
    claimedName: '',
    claimedFingerprint: '',
    claimedSas: '',
    ...over,
  }
}

const carol = { personId: 'p-carol', displayName: 'Carol', fingerprint: 'fp-carol', grantable: true }

// A folder the Shared tab already knows backs `realm-1`.
function renderShared() {
  return renderWith({ realmId: 'realm-1' })
}

// A folder opened from Files: the screen has to find out itself whether it is shared.
function renderFromFiles() {
  return renderWith({})
}

function renderWith(props: { realmId?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ManageAccessView
        folderPath="/Holiday Photos"
        folderName="Holiday Photos"
        onBack={() => {}}
        {...props}
      />
    </QueryClientProvider>,
  )
  return queryClient
}

beforeEach(() => {
  realmMembers.mockResolvedValue([])
  connectedContacts.mockResolvedValue([])
  listContactInvites.mockResolvedValue([])
  sharedFolders.mockResolvedValue([])
  createContactInvite.mockResolvedValue('https://tenshu.app/i#link')
})

afterEach(() => {
  for (const fn of [
    realmMembers, connectedContacts, grantMembership, removeMember, listContactInvites,
    revokeContactInvite, completeHandshake, createContactInvite, shareFolder, sharedFolders,
  ]) fn.mockReset()
})

describe('ManageAccessView', () => {
  it('lists members with the owner badge', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm1', displayName: 'Me', contactFingerprint: '', role: 'owner' },
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    renderShared()

    await screen.findByText('Bob')
    expect(screen.getByText(/· owner/)).toBeInTheDocument()
  })

  it('grants a grantable contact not already a member', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    connectedContacts.mockResolvedValue([
      carol,
      // Bob is already a member (fingerprint match) — must be filtered out.
      { personId: 'p-bob', displayName: 'Bob', fingerprint: 'fp-bob', grantable: true },
    ])
    grantMembership.mockResolvedValueOnce(true)
    renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Add people' }))
    expect(screen.queryByText('Bob', { selector: 'span' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() =>
      expect(grantMembership).toHaveBeenCalledWith('realm-1', 'p-carol', 'Holiday Photos'),
    )
    expect(shareFolder).not.toHaveBeenCalled()
  })

  it('removes a non-owner member after confirmation', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    removeMember.mockResolvedValueOnce(true)
    renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    const dialog = screen.getByRole('dialog', { name: 'Remove member' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('realm-1', 'm2'))
  })

  // ── §11: the folder is shared on the first add, never on opening ─────────────────────────────

  it('opening an unshared folder shares nothing', async () => {
    renderFromFiles()

    expect(await screen.findByText(/Only you have access/)).toBeInTheDocument()
    expect(shareFolder).not.toHaveBeenCalled()
  })

  it('adding a contact to an unshared folder shares it first, then grants into the new realm', async () => {
    connectedContacts.mockResolvedValue([carol])
    shareFolder.mockResolvedValueOnce({
      ok: true,
      item: { sharedRealmId: 'realm-new', name: 'Holiday Photos', parentPath: '/' },
    })
    grantMembership.mockResolvedValueOnce(true)
    renderFromFiles()

    await screen.findByText(/Only you have access/)
    await userEvent.click(screen.getByRole('button', { name: 'Add people' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Add' }))

    await waitFor(() =>
      expect(grantMembership).toHaveBeenCalledWith('realm-new', 'p-carol', 'Holiday Photos'),
    )
    expect(shareFolder).toHaveBeenCalledTimes(1)
    expect(shareFolder).toHaveBeenCalledWith('/Holiday Photos')
  })

  it('an already-shared folder opened from Files is found, not shared again', async () => {
    sharedFolders.mockResolvedValue([
      { sharedRealmId: 'realm-1', name: 'Holiday Photos', parentPath: '/' },
    ])
    connectedContacts.mockResolvedValue([carol])
    grantMembership.mockResolvedValueOnce(true)
    renderFromFiles()

    await waitFor(() => expect(realmMembers).toHaveBeenCalledWith('realm-1'))
    await userEvent.click(screen.getByRole('button', { name: 'Add people' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Add' }))

    await waitFor(() =>
      expect(grantMembership).toHaveBeenCalledWith('realm-1', 'p-carol', 'Holiday Photos'),
    )
    expect(shareFolder).not.toHaveBeenCalled()
  })

  // ⚠ A failed "is it shared?" read is not "no": sharing on that guess mints a second realm.
  it('a failed lookup is asked again before sharing, and a second failure shares nothing', async () => {
    sharedFolders.mockRejectedValue(new Error('bridge down'))
    connectedContacts.mockResolvedValue([carol])
    renderFromFiles()

    await userEvent.click(await screen.findByRole('button', { name: 'Add people' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Add' }))

    expect(await screen.findByText(/Couldn.t check whether “Holiday Photos” is already shared/)).toBeInTheDocument()
    expect(sharedFolders.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(shareFolder).not.toHaveBeenCalled()
    expect(grantMembership).not.toHaveBeenCalled()
  })

  it('inviting from an unshared folder shares it first and mints a folder invite', async () => {
    shareFolder.mockResolvedValueOnce({
      ok: true,
      item: { sharedRealmId: 'realm-new', name: 'Holiday Photos', parentPath: '/' },
    })
    renderFromFiles()

    await screen.findByText(/Only you have access/)
    await userEvent.click(screen.getByRole('button', { name: 'Invite someone' }))

    expect(await screen.findByRole('dialog', { name: 'Invite someone' })).toBeInTheDocument()
    expect(shareFolder).toHaveBeenCalledWith('/Holiday Photos')
    await waitFor(() => expect(createContactInvite).toHaveBeenCalledWith('SHARE_FOLDER', 'Holiday Photos'))
  })

  it('a failed share surfaces the step and opens no invite', async () => {
    shareFolder.mockResolvedValueOnce({ ok: false, error: { kind: 'mount' } })
    renderFromFiles()

    await screen.findByText(/Only you have access/)
    await userEvent.click(screen.getByRole('button', { name: 'Invite someone' }))

    expect(await screen.findByText(/a shared realm was already created/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Invite someone' })).not.toBeInTheDocument()
    expect(createContactInvite).not.toHaveBeenCalled()
  })

  // ── this folder's invites ───────────────────────────────────────────────────────────────────

  it("lists only this folder's open invites", async () => {
    listContactInvites.mockResolvedValue([
      invite({ inviteId: 'mine' }),
      invite({ inviteId: 'other-folder', intentLabel: 'Work' }),
      invite({ inviteId: 'bare', intentKind: '', intentLabel: '' }),
    ])
    renderShared()

    expect(await screen.findByText('Waiting for a response…')).toBeInTheDocument()
    expect(screen.getAllByText('Waiting for a response…')).toHaveLength(1)
  })

  it('offers to add a contact who connected through this folder but is not a member yet', async () => {
    listContactInvites.mockResolvedValue([
      invite({ status: 'COMPLETED', claimedName: 'Carol', claimedFingerprint: 'fp-carol' }),
    ])
    connectedContacts.mockResolvedValue([carol])
    grantMembership.mockResolvedValueOnce(true)
    renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Add to folder' }))
    await waitFor(() =>
      expect(grantMembership).toHaveBeenCalledWith('realm-1', 'p-carol', 'Holiday Photos'),
    )
  })

  it('shows a REDEEMED invite as awaiting confirmation', async () => {
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    renderShared()

    expect(await screen.findByText(/“Bob” responded/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
  })

  it('completes the handshake from the confirmation dialog', async () => {
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce({ outcome: 'completed', message: null })
    renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm connection' })
    await userEvent.type(within(dialog).getByLabelText(/Your name/i), 'Alice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))

    await waitFor(() =>
      expect(completeHandshake).toHaveBeenCalledWith(
        expect.objectContaining({
          inviteId: 'inv-1',
          accept: true,
          selfAssertedName: 'Alice',
          shownFingerprint: 'feedface00112233',
        }),
      ),
    )
  })

  // ⚠⚠ Threat model T-CI-10: a second inviter device can replace the claim while the dialog is open.
  // The dialog must render the polled row, not the snapshot taken on click, so the new code shows
  // and the submission carries the fingerprint now on screen.
  it('the open dialog follows a claim replaced by the poll', async () => {
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce({ outcome: 'completed', message: null })
    const queryClient = renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm connection' })

    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'baadf00d44556677' }),
    ])
    const calls = listContactInvites.mock.calls.length
    await queryClient.invalidateQueries()
    await waitFor(() => expect(listContactInvites.mock.calls.length).toBeGreaterThan(calls))

    await userEvent.type(within(dialog).getByLabelText(/Your name/i), 'Alice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))
    await waitFor(() =>
      expect(completeHandshake).toHaveBeenCalledWith(
        expect.objectContaining({ shownFingerprint: 'baadf00d44556677' }),
      ),
    )
  })

  // Threat model T-CI-10: the reply on the relay is not the one the dialog showed. Retrying
  // refuses the same way, so the copy must send the user to decline and re-invite.
  async function acceptAndFailWith(result: { outcome: string; message: string | null }) {
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce(result)
    renderShared()

    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm connection' })
    await userEvent.type(within(dialog).getByLabelText(/Your name/i), 'Alice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))
    return dialog
  }

  it('tells the user to decline and re-invite when the reply changed, never to retry', async () => {
    const dialog = await acceptAndFailWith({
      outcome: 'responseChanged',
      message: 'error_invite_response_changed',
    })

    expect(await within(dialog).findByText(/Decline this invite and send a new one/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/Try again/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/error_invite/)).not.toBeInTheDocument()
  })

  it('says there is no code yet when a confirmation had no code to confirm', async () => {
    const dialog = await acceptAndFailWith({
      outcome: 'codeNotShown',
      message: 'error_invite_code_not_shown',
    })

    expect(await within(dialog).findByText(/There is no code to confirm for this invite yet/)).toBeInTheDocument()
  })

  it('keeps the retry copy for an ordinary failure', async () => {
    const dialog = await acceptAndFailWith({ outcome: 'failed', message: 'error_generic' })

    expect(await within(dialog).findByText("Couldn't complete the connection. Try again.")).toBeInTheDocument()
  })
})
