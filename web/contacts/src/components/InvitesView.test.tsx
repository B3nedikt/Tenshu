import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { InvitesView } from './InvitesView'
import { contact, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// The QR encoder is not what these tests are about.
vi.mock('qrcode', () => ({ default: { toDataURL: async () => 'data:image/png;base64,AA==' } }))

beforeEach(() => resetProxy())

const confirmation = {
  inviteId: 'inv-1',
  claimedName: 'Mira',
  claimedFingerprint: 'ABCDEF0123456789RAW',
  claimedSas: '654321',
}

describe('InvitesView', () => {
  it('creates a bare invite and shows its one-time link', async () => {
    renderWithProviders(<InvitesView />)
    await userEvent.click(screen.getByRole('button', { name: 'Create invitation' }))
    expect(await screen.findByDisplayValue('https://tenshu.app/i#v1.abc')).toBeInTheDocument()
    expect(proxy.createInvite).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/works once/)).toBeInTheDocument()
  })

  it('renders a refused mint by its kind', async () => {
    proxy.createInvite.mockResolvedValueOnce({ ok: false, error: { kind: 'error_unannounced_session' } } as never)
    renderWithProviders(<InvitesView />)
    await userEvent.click(screen.getByRole('button', { name: 'Create invitation' }))
    expect(await screen.findByText('Tenshu needs to reconnect. Please try again.')).toBeInTheDocument()
  })

  it('lists outstanding invites and revokes one', async () => {
    proxy.outstandingInvites.mockResolvedValue({
      ok: true,
      invites: [{ inviteId: 'inv-9', createdAtMillis: Date.UTC(2026, 9, 1), expiresAtMillis: 0 }],
    })
    renderWithProviders(<InvitesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(proxy.revokeInvite).toHaveBeenCalledWith('inv-9'))
  })

  it('renders a failed invite read as an error, not as "no invitations"', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: false, error: { kind: 'error_generic' } } as never)
    renderWithProviders(<InvitesView />)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('Nobody is waiting to be saved.')).not.toBeInTheDocument()
  })

  it('completes a handshake with the RAW shown fingerprint, and sasConfirmed only from the tick', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: true, confirmations: [confirmation] })
    renderWithProviders(<InvitesView />)
    await waitFor(() => expect(streams.profile.open()).toBe(1))
    act(() => streams.profile.push({ displayName: 'Ben', fingerprint: '' }))

    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('654 321')).toBeInTheDocument()
    // The claim is pre-filled as the petname, but it is the user's to change.
    const petname = within(dialog).getByLabelText('Save them as')
    expect(petname).toHaveValue('Mira')
    await userEvent.clear(petname)
    await userEvent.type(petname, 'Mira from work')
    expect(within(dialog).getByLabelText('Your name')).toHaveValue('Ben')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Save contact' }))
    await waitFor(() =>
      expect(proxy.completeHandshake).toHaveBeenCalledWith({
        inviteId: 'inv-1',
        accept: true,
        shownFingerprint: 'ABCDEF0123456789RAW',
        personId: '',
        newPersonName: 'Mira from work',
        selfAssertedName: 'Ben',
        sasConfirmed: false,
      }),
    )
  })

  it('carries sasConfirmed: true only after the explicit "codes match" tick', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: true, confirmations: [confirmation] })
    renderWithProviders(<InvitesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Your name'), 'Ben')
    await userEvent.click(within(dialog).getByLabelText('The codes match'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save contact' }))
    await waitFor(() =>
      expect(proxy.completeHandshake).toHaveBeenCalledWith(expect.objectContaining({ accept: true, sasConfirmed: true })),
    )
  })

  it('links the key to an existing contact instead of creating one', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: true, confirmations: [confirmation] })
    proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'mira', displayName: 'Mira K.' })] })
    renderWithProviders(<InvitesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Your name'), 'Ben')
    await userEvent.selectOptions(within(dialog).getByLabelText(/existing contact/), 'mira')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save contact' }))
    await waitFor(() =>
      expect(proxy.completeHandshake).toHaveBeenCalledWith(
        expect.objectContaining({ personId: 'mira', newPersonName: '' }),
      ),
    )
  })

  it('says to decline and re-invite when the reply changed under the prompt (T-CI-10)', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: true, confirmations: [confirmation] })
    proxy.completeHandshake.mockResolvedValueOnce({ outcome: 'responseChanged', message: 'error_invite_response_changed' })
    renderWithProviders(<InvitesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Your name'), 'Ben')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save contact' }))
    expect(await within(dialog).findByText(/Decline this invite and send a new one/)).toBeInTheDocument()
  })

  it('declines', async () => {
    proxy.pendingConfirmations.mockResolvedValue({ ok: true, confirmations: [confirmation] })
    renderWithProviders(<InvitesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Decline' }))
    await waitFor(() =>
      expect(proxy.completeHandshake).toHaveBeenCalledWith(expect.objectContaining({ accept: false, sasConfirmed: false })),
    )
  })
})
