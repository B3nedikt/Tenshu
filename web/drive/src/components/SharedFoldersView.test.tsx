import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const sharedFolders = vi.fn()
// SharesView + ManageAccessView pull these off the same module; stub so imports resolve.
const listShares = vi.fn(async () => [])
const realmMembers = vi.fn(async (_id: string) => [])
const connectedContacts = vi.fn(async () => [])
vi.mock('../lib/proxy', () => ({
  sharedFolders: () => sharedFolders(),
  listShares: () => listShares(),
  revokeShare: vi.fn(),
  realmMembers: (id: string) => realmMembers(id),
  connectedContacts: () => connectedContacts(),
  grantMembership: vi.fn(),
  removeMember: vi.fn(),
}))

const { SharedFoldersView } = await import('./SharedFoldersView')

function renderView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SharedFoldersView />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  sharedFolders.mockReset()
  realmMembers.mockReset()
  connectedContacts.mockReset()
})

describe('SharedFoldersView', () => {
  it('empty state when no folders are shared', async () => {
    sharedFolders.mockResolvedValue([])
    renderView()
    expect(await screen.findByText(/haven.t shared any folders/i)).toBeInTheDocument()
  })

  it('lists shared folders and opens Manage access', async () => {
    sharedFolders.mockResolvedValue([
      { sharedRealmId: 'realm-1', name: 'Holiday Photos', parentPath: '/Photos' },
    ])
    renderView()

    await screen.findByText('Holiday Photos')
    await userEvent.click(screen.getByRole('button', { name: 'Manage access' }))
    // ManageAccessView takes over — its People header + Add people button appear.
    expect(await screen.findByRole('button', { name: 'Add people' })).toBeInTheDocument()
    expect(realmMembers).toHaveBeenCalledWith('realm-1')
  })
})
