import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const connectedContacts = vi.fn()
const pollPendingGrants = vi.fn()
const acceptGrant = vi.fn()
const declineGrant = vi.fn()
const realmMembers = vi.fn()
const sharedWithMe = vi.fn()
vi.mock('../lib/proxy', () => ({
  connectedContacts: () => connectedContacts(),
  pollPendingGrants: () => pollPendingGrants(),
  acceptGrant: (fp: string) => acceptGrant(fp),
  declineGrant: (fp: string) => declineGrant(fp),
  realmMembers: (id: string) => realmMembers(id),
  sharedWithMe: () => sharedWithMe(),
  // SharedFolderBrowser pulls these via the same module; stub so its import resolves.
  browseSharedFolder: vi.fn(async () => []),
  readSharedFile: vi.fn(async () => ({ ok: true, bytes: null })),
}))

const { SharedWithMeView } = await import('./SharedWithMeView')

function renderView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SharedWithMeView />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  connectedContacts.mockReset()
  pollPendingGrants.mockReset()
  acceptGrant.mockReset()
  declineGrant.mockReset()
  realmMembers.mockReset()
  sharedWithMe.mockReset()
})

beforeEach(() => {
  // Default: no joined realms unless a test overrides it.
  sharedWithMe.mockResolvedValue([])
})

describe('SharedWithMeView', () => {
  it('empty state when nothing is shared', async () => {
    connectedContacts.mockResolvedValue([])
    pollPendingGrants.mockResolvedValue([])
    renderView()
    expect(await screen.findByText(/Nothing shared with you yet/i)).toBeInTheDocument()
  })

  it('lists joined shared folders and opens the browser', async () => {
    connectedContacts.mockResolvedValue([])
    pollPendingGrants.mockResolvedValue([])
    sharedWithMe.mockResolvedValue([{ sharedRealmId: 'realm-1', name: 'Holiday Photos' }])
    renderView()

    await userEvent.click(await screen.findByText('Holiday Photos'))
    // The browser takes over — its breadcrumb shows the realm name + a back link.
    expect(await screen.findByRole('button', { name: 'Shared with me' })).toBeInTheDocument()
  })

  it('falls back to a label when the wrapper name has not synced', async () => {
    connectedContacts.mockResolvedValue([])
    pollPendingGrants.mockResolvedValue([])
    sharedWithMe.mockResolvedValue([{ sharedRealmId: 'realm-2', name: null }])
    renderView()
    expect(await screen.findByText('Shared folder')).toBeInTheDocument()
  })

  it('renders a pending grant with the grantor petname resolved from contacts', async () => {
    connectedContacts.mockResolvedValue([
      { personId: 'p1', displayName: 'Alice', fingerprint: 'fp-1', grantable: true },
    ])
    pollPendingGrants.mockResolvedValue([
      { grantorFingerprint: 'fp-1', discoveredAtMillis: Date.UTC(2024, 0, 1) },
    ])
    renderView()
    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText(/shared a folder with you/i)).toBeInTheDocument()
  })

  it('accepts a grant and shows the joined realm members', async () => {
    connectedContacts.mockResolvedValue([
      { personId: 'p1', displayName: 'Alice', fingerprint: 'fp-1', grantable: true },
    ])
    pollPendingGrants.mockResolvedValue([
      { grantorFingerprint: 'fp-1', discoveredAtMillis: Date.UTC(2024, 0, 1) },
    ])
    acceptGrant.mockResolvedValueOnce('realm-1')
    realmMembers.mockResolvedValue([
      { memberId: 'm1', displayName: 'Alice', contactFingerprint: 'fp-1', role: 'owner' },
    ])
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Accept' }))
    await waitFor(() => expect(acceptGrant).toHaveBeenCalledWith('fp-1'))
    expect(await screen.findByText(/You joined the folder/i)).toBeInTheDocument()
    await waitFor(() => expect(realmMembers).toHaveBeenCalledWith('realm-1'))
  })

  it('declines a grant', async () => {
    connectedContacts.mockResolvedValue([])
    pollPendingGrants.mockResolvedValue([
      { grantorFingerprint: 'fp-9', discoveredAtMillis: Date.UTC(2024, 0, 1) },
    ])
    declineGrant.mockResolvedValueOnce(true)
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Decline' }))
    await waitFor(() => expect(declineGrant).toHaveBeenCalledWith('fp-9'))
  })
})
