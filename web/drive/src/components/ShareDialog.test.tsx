import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const createShareLink = vi.fn()
const storageStatus = vi.fn()
vi.mock('../lib/proxy', () => ({
  APEX: 'https://tenshu.app',
  createShareLink: (...args: unknown[]) => createShareLink(...args),
  storageStatus: () => storageStatus(),
}))

const { ShareDialog } = await import('./ShareDialog')

const writeText = vi.fn().mockResolvedValue(undefined)

const STORAGE_OK = { hasStorage: true, usedBytes: 0, limitBytes: 1_000_000_000 }

function renderDialog(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ShareDialog path={path} onClose={vi.fn()} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  // Default: storage available, so the gate allows minting.
  storageStatus.mockResolvedValue(STORAGE_OK)
})

afterEach(() => {
  createShareLink.mockReset()
  storageStatus.mockReset()
  writeText.mockClear()
})

describe('ShareDialog', () => {
  it('mints a link and shows the assembled URL', async () => {
    createShareLink.mockResolvedValueOnce({ shareId: 'abc', urlFragment: 'XYZ' })
    renderDialog('/Photos/a.jpg')

    const input = await screen.findByLabelText<HTMLInputElement>('Share link')
    expect(input.value).toBe('https://tenshu.app/s/abc#k=XYZ')
    expect(createShareLink).toHaveBeenCalledWith('/Photos/a.jpg')
  })

  it('copies the URL to the clipboard', async () => {
    createShareLink.mockResolvedValueOnce({ shareId: 'abc', urlFragment: 'XYZ' })
    renderDialog('/a.txt')

    await screen.findByLabelText('Share link')
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('https://tenshu.app/s/abc#k=XYZ'))
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('shows an error when minting fails', async () => {
    createShareLink.mockResolvedValueOnce(null)
    renderDialog('/a.txt')

    expect(await screen.findByText("Couldn't create a share link.")).toBeInTheDocument()
  })

  it('blocks sharing with a hand-off when the realm has no storage plan', async () => {
    storageStatus.mockResolvedValue({ hasStorage: false, usedBytes: 0, limitBytes: 0 })
    renderDialog('/a.txt')

    expect(await screen.findByText(/no storage plan/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Tenshu' })).toHaveAttribute(
      'href',
      'https://tenshu.app',
    )
    expect(createShareLink).not.toHaveBeenCalled()
  })

  it('blocks sharing with an out-of-space message when full', async () => {
    storageStatus.mockResolvedValue({ hasStorage: true, usedBytes: 100, limitBytes: 100 })
    renderDialog('/a.txt')

    expect(await screen.findByText(/out of storage/)).toBeInTheDocument()
    expect(createShareLink).not.toHaveBeenCalled()
  })
})
