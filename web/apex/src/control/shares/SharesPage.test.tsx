import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { ShareListItem } from '@tenshu/sdk/share'

const listMock = vi.fn<() => Promise<ShareListItem[]>>()
const revokeMock = vi.fn<(id: string) => Promise<boolean>>()

vi.mock('@tenshu/sdk/share', () => ({
  Shares: {
    list: () => listMock(),
    revoke: (id: string) => revokeMock(id),
  },
}))

import { SharesPage } from './SharesPage'

function share(overrides: Partial<ShareListItem> = {}): ShareListItem {
  return {
    shareId: 'share-1',
    fileName: 'return.pdf',
    filePath: '/Tax/return.pdf',
    createdAtMillis: 1_717_000_000_000,
    expiresAtMillis: null,
    maxOpens: null,
    revoked: false,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SharesPage />
    </MemoryRouter>,
  )
}

describe('SharesPage', () => {
  beforeEach(() => {
    listMock.mockReset()
    revokeMock.mockReset()
  })

  it('lists each share newest-first with its file name + count', async () => {
    listMock.mockResolvedValue([
      share({ shareId: 'a', fileName: 'return.pdf', maxOpens: 5 }),
      share({ shareId: 'b', fileName: 'beach.jpg' }),
    ])
    renderPage()
    expect(await screen.findByText('return.pdf')).toBeInTheDocument()
    expect(screen.getByText('beach.jpg')).toBeInTheDocument()
    expect(screen.getByText('2 shares')).toBeInTheDocument()
  })

  it('shows the empty state when nothing is shared', async () => {
    listMock.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/Nothing shared yet/i)).toBeInTheDocument()
  })

  it('renders a Revoked badge for a revoked share and hides its Revoke button', async () => {
    listMock.mockResolvedValue([share({ revoked: true })])
    renderPage()
    expect(await screen.findByText('Revoked')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })

  it('revokes a share after the confirm dialog and reloads the list', async () => {
    listMock.mockResolvedValue([share({ shareId: 'share-42', fileName: 'doc.pdf' })])
    revokeMock.mockResolvedValue(true)
    const user = (await import('@testing-library/user-event')).default.setup()

    renderPage()
    await screen.findByText('doc.pdf')
    expect(listMock).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: 'Revoke' }))
    expect(await screen.findByText('Revoke link?')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Yes, revoke' }))

    await waitFor(() => expect(revokeMock).toHaveBeenCalledWith('share-42'))
    // List is re-fetched after a successful revoke (initial + reload).
    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2))
  })

  it('can cancel the confirm dialog without revoking', async () => {
    listMock.mockResolvedValue([share({ shareId: 'share-42', fileName: 'doc.pdf' })])
    const user = (await import('@testing-library/user-event')).default.setup()

    renderPage()
    await screen.findByText('doc.pdf')
    await user.click(screen.getByRole('button', { name: 'Revoke' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByText('Revoke link?')).not.toBeInTheDocument()
    expect(revokeMock).not.toHaveBeenCalled()
  })
})
