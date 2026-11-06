import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const realmMembers = vi.fn()
const connectedContacts = vi.fn()
const grantMembership = vi.fn()
const removeMember = vi.fn()
vi.mock('../lib/proxy', () => ({
  realmMembers: (id: string) => realmMembers(id),
  connectedContacts: () => connectedContacts(),
  grantMembership: (r: string, p: string, l: string) => grantMembership(r, p, l),
  removeMember: (r: string, m: string) => removeMember(r, m),
}))

const { ManageAccessView } = await import('./ManageAccessView')

function renderView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ManageAccessView realmId="realm-1" folderName="Holiday Photos" onBack={() => {}} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  realmMembers.mockReset()
  connectedContacts.mockReset()
  grantMembership.mockReset()
  removeMember.mockReset()
})

describe('ManageAccessView', () => {
  it('lists members with the owner badge', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm1', displayName: 'Me', contactFingerprint: '', role: 'owner' },
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    connectedContacts.mockResolvedValue([])
    renderView()

    await screen.findByText('Bob')
    expect(screen.getByText(/· owner/)).toBeInTheDocument()
  })

  it('grants a grantable contact not already a member', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    connectedContacts.mockResolvedValue([
      { personId: 'p-carol', displayName: 'Carol', fingerprint: 'fp-carol', grantable: true },
      // Bob is already a member (fingerprint match) — must be filtered out.
      { personId: 'p-bob', displayName: 'Bob', fingerprint: 'fp-bob', grantable: true },
    ])
    grantMembership.mockResolvedValueOnce(true)
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Add people' }))
    // Carol is addable; Bob is not shown (already a member).
    expect(screen.queryByText('Bob', { selector: 'span' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() =>
      expect(grantMembership).toHaveBeenCalledWith('realm-1', 'p-carol', 'Holiday Photos'),
    )
  })

  it('removes a non-owner member after confirmation', async () => {
    realmMembers.mockResolvedValue([
      { memberId: 'm2', displayName: 'Bob', contactFingerprint: 'fp-bob', role: 'member' },
    ])
    connectedContacts.mockResolvedValue([])
    removeMember.mockResolvedValueOnce(true)
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    const dialog = screen.getByRole('dialog', { name: 'Remove member' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('realm-1', 'm2'))
  })
})
