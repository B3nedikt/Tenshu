import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const connectedContacts = vi.fn()
const listContactInvites = vi.fn()
const revokeContactInvite = vi.fn()
const completeHandshake = vi.fn()
const createContactInvite = vi.fn()
vi.mock('../lib/proxy', () => ({
  connectedContacts: () => connectedContacts(),
  listContactInvites: () => listContactInvites(),
  revokeContactInvite: (id: string) => revokeContactInvite(id),
  completeHandshake: (args: unknown) => completeHandshake(args),
  createContactInvite: (k: string, l: string) => createContactInvite(k, l),
}))

const { PeopleView } = await import('./PeopleView')

function contact(over: Record<string, unknown> = {}) {
  return {
    personId: 'p1',
    displayName: 'Alice',
    fingerprint: '0123456789abcdef0123456789',
    grantable: true,
    verified: false,
    ...over,
  }
}

function invite(over: Record<string, unknown> = {}) {
  return {
    inviteId: 'inv-1',
    status: 'PENDING',
    intentKind: '',
    intentLabel: '',
    createdAtMillis: Date.UTC(2024, 0, 1),
    expiresAtMillis: Date.UTC(2024, 0, 4),
    claimedName: '',
    claimedFingerprint: '',
    claimedSas: '',
    ...over,
  }
}

function renderView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <PeopleView />
    </QueryClientProvider>,
  )
  return queryClient
}

afterEach(() => {
  connectedContacts.mockReset()
  listContactInvites.mockReset()
  revokeContactInvite.mockReset()
  completeHandshake.mockReset()
  createContactInvite.mockReset()
})

describe('PeopleView', () => {
  it('lists connected contacts with the formatted fingerprint', async () => {
    connectedContacts.mockResolvedValue([contact()])
    listContactInvites.mockResolvedValue([])
    renderView()

    await screen.findByText('Alice')
    expect(screen.getByText('01234-56789-ABCDE')).toBeInTheDocument()
  })

  it('badges a non-grantable contact as "Contact only"', async () => {
    connectedContacts.mockResolvedValue([contact({ grantable: false })])
    listContactInvites.mockResolvedValue([])
    renderView()

    await screen.findByText('Alice')
    expect(screen.getByText('Contact only')).toBeInTheDocument()
  })

  it('badges a verified contact (MB-5)', async () => {
    connectedContacts.mockResolvedValue([contact({ verified: true })])
    listContactInvites.mockResolvedValue([])
    renderView()

    await screen.findByText('Alice')
    expect(screen.getByText('Verified')).toBeInTheDocument()
  })

  it('empty state when there are no contacts', async () => {
    connectedContacts.mockResolvedValue([])
    listContactInvites.mockResolvedValue([])
    renderView()
    expect(await screen.findByText(/No contacts yet/i)).toBeInTheDocument()
  })

  it('shows a REDEEMED invite as awaiting confirmation', async () => {
    connectedContacts.mockResolvedValue([])
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    renderView()

    expect(await screen.findByText(/“Bob” responded/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
  })

  it('completes the handshake from the confirmation dialog', async () => {
    connectedContacts.mockResolvedValue([])
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce({ outcome: 'completed', message: null })
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm connection' })
    // Fill in the inviter's own name (the "Save them as" field defaults to Bob).
    const ownNameInput = within(dialog).getByLabelText(/Your name/i)
    await userEvent.type(ownNameInput, 'Alice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))

    await waitFor(() =>
      expect(completeHandshake).toHaveBeenCalledWith(
        expect.objectContaining({
          inviteId: 'inv-1',
          accept: true,
          selfAssertedName: 'Alice',
          // The raw fingerprint the dialog displayed (contact-service v2).
          shownFingerprint: 'feedface00112233',
        }),
      ),
    )
  })

  // ⚠⚠ Threat model T-CI-10: a second inviter device can replace the claim while the dialog is open.
  // The dialog must render the polled row, not the snapshot taken on click, so the new code shows
  // and the submission carries the fingerprint now on screen.
  it('the open dialog follows a claim replaced by the poll', async () => {
    connectedContacts.mockResolvedValue([])
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce({ outcome: 'completed', message: null })
    const queryClient = renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm connection' })

    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'baadf00d44556677' }),
    ])
    await queryClient.invalidateQueries()
    await waitFor(() => expect(listContactInvites).toHaveBeenCalledTimes(2))

    await userEvent.type(within(dialog).getByLabelText(/Your name/i), 'Alice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))
    await waitFor(() =>
      expect(completeHandshake).toHaveBeenCalledWith(
        expect.objectContaining({ shownFingerprint: 'baadf00d44556677' }),
      ),
    )
  })

  // Threat model T-CI-10: the reply on the relay is not the one the dialog showed. Retrying
  // refuses the same way, so the copy must send the user to decline and re-invite — this used
  // to be a boolean, and every `false` read "Try again".
  async function acceptAndFailWith(result: { outcome: string; message: string | null }) {
    connectedContacts.mockResolvedValue([])
    listContactInvites.mockResolvedValue([
      invite({ status: 'REDEEMED', claimedName: 'Bob', claimedFingerprint: 'feedface00112233' }),
    ])
    completeHandshake.mockResolvedValueOnce(result)
    renderView()

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
