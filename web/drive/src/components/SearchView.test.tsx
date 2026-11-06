import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const searchFiles = vi.fn()
vi.mock('../lib/proxy', () => ({ searchFiles: (q: string) => searchFiles(q) }))
vi.mock('./FileThumbnail', () => ({ FileThumbnail: () => null }))

const { SearchView } = await import('./SearchView')

function file(path: string) {
  return { path, size: 10, lastModified: 0, createdAt: 0, isFolder: false, itemCount: 0 }
}

function renderView(onOpenFolder = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <SearchView onOpenFolder={onOpenFolder} />
    </QueryClientProvider>,
  )
  return onOpenFolder
}

beforeEach(() => {
  // ⚠ mockClear as well as mockResolvedValue: the fn is module-level and shared across tests,
  // so a call-count assertion otherwise counts its neighbours' calls.
  searchFiles.mockClear()
  searchFiles.mockResolvedValue([])
})

describe('SearchView', () => {
  // ⚠ An empty query must not query at all. Each search is a full drive traverse proxy-side, so
  // "show everything until you type" would be the most expensive possible idle state.
  it('does not search until something is typed', async () => {
    renderView()
    expect(screen.getByText(/type to search/i)).toBeInTheDocument()
    await waitFor(() => expect(searchFiles).not.toHaveBeenCalled())
  })

  it('searches once typing settles and lists the hits', async () => {
    searchFiles.mockResolvedValue([file('/Photos/cat.jpg')])
    const user = userEvent.setup()
    renderView()
    await user.type(screen.getByRole('searchbox'), 'cat')
    expect(await screen.findByText('cat.jpg')).toBeInTheDocument()
    // The containing folder is what distinguishes two files of the same name.
    expect(screen.getByText(/\/Photos/)).toBeInTheDocument()
  })

  // ⚠ One traverse per burst of typing, not per keystroke.
  it('debounces — typing three characters searches once', async () => {
    const user = userEvent.setup()
    renderView()
    await user.type(screen.getByRole('searchbox'), 'abc')
    await waitFor(() => expect(searchFiles).toHaveBeenCalled())
    expect(searchFiles).toHaveBeenCalledTimes(1)
    expect(searchFiles).toHaveBeenCalledWith('abc')
  })

  // ⚠ Names the query. A bare "No results" reads as "your drive is empty" — the same correction
  // the native zero-state sweep made.
  it('says what found nothing', async () => {
    const user = userEvent.setup()
    renderView()
    await user.type(screen.getByRole('searchbox'), 'zzz')
    expect(await screen.findByText(/nothing matches/i)).toBeInTheDocument()
    expect(screen.getByText(/zzz/)).toBeInTheDocument()
  })

  it('opens the containing folder for a file hit', async () => {
    searchFiles.mockResolvedValue([file('/Photos/cat.jpg')])
    const user = userEvent.setup()
    const onOpenFolder = renderView()
    await user.type(screen.getByRole('searchbox'), 'cat')
    await user.click(await screen.findByRole('button', { name: /cat\.jpg/i }))
    expect(onOpenFolder).toHaveBeenCalledWith('/Photos')
  })
})
