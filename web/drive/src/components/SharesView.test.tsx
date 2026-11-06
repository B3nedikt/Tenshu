import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const listShares = vi.fn()
const revokeShare = vi.fn()
vi.mock('../lib/proxy', () => ({
  listShares: () => listShares(),
  revokeShare: (id: string) => revokeShare(id),
}))

const { SharesView } = await import('./SharesView')

function shareItem(over: Record<string, unknown> = {}) {
  return {
    shareId: 's1',
    fileName: 'a.txt',
    filePath: '/a.txt',
    createdAtMillis: Date.UTC(2024, 0, 1),
    expiresAtMillis: null,
    maxOpens: null,
    revoked: false,
    ...over,
  }
}

function renderView() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SharesView />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  listShares.mockReset()
  revokeShare.mockReset()
})

describe('SharesView', () => {
  it('lists shares and badges the revoked ones', async () => {
    listShares.mockResolvedValueOnce([
      shareItem(),
      shareItem({ shareId: 's2', fileName: 'b.txt', revoked: true }),
    ])
    renderView()

    await screen.findByText('a.txt')
    expect(screen.getByText('b.txt')).toBeInTheDocument()
    expect(screen.getByText('Revoked')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    listShares.mockResolvedValueOnce([])
    renderView()
    expect(await screen.findByText(/haven.t shared anything/i)).toBeInTheDocument()
  })

  it('revokes a share after confirmation', async () => {
    listShares.mockResolvedValue([shareItem()])
    revokeShare.mockResolvedValueOnce(true)
    renderView()

    await userEvent.click(await screen.findByRole('button', { name: 'Revoke' }))
    const dialog = screen.getByRole('dialog', { name: 'Revoke link' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))

    await waitFor(() => expect(revokeShare).toHaveBeenCalledWith('s1'))
  })
})
