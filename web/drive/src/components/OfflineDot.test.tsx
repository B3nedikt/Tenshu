import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const hasLocalData = vi.fn()
vi.mock('../lib/proxy', () => ({ hasLocalData: (p: string) => hasLocalData(p) }))
// The real hook needs IntersectionObserver; in jsdom it degrades to eager, which is what we want
// here — the visibility gate itself is FileThumbnail's contract and is tested there.
vi.mock('../lib/useInView', () => ({ useInView: () => [() => {}, true] }))

const { OfflineDot } = await import('./OfflineDot')

function renderDot(path = '/a.jpg', isFolder = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <OfflineDot path={path} isFolder={isFolder} />
    </QueryClientProvider>,
  )
}

beforeEach(() => hasLocalData.mockReset())

describe('OfflineDot', () => {
  it('marks a file whose bytes are not on this device', async () => {
    hasLocalData.mockResolvedValue(false)
    renderDot()
    expect(await screen.findByTitle(/not on this device/i)).toBeInTheDocument()
  })

  // ⚠ The common case earns no ink — only the state with a consequence is marked.
  it('says nothing when the bytes are local', async () => {
    hasLocalData.mockResolvedValue(true)
    const { container } = renderDot()
    await waitFor(() => expect(hasLocalData).toHaveBeenCalled())
    expect(container.querySelector('[title]')).toBeNull()
  })

  // ⚠ A folder has no bytes of its own; asking would mark every folder "in the cloud", which is
  // meaningless rather than merely wrong.
  it('never probes a folder', async () => {
    renderDot('/Photos', true)
    await waitFor(() => expect(hasLocalData).not.toHaveBeenCalled())
  })
})
