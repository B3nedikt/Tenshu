import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const listFiles = vi.fn()
vi.mock('../lib/proxy', () => ({ listFiles: (path: string) => listFiles(path) }))

const { MovePicker } = await import('./MovePicker')

function folder(path: string) {
  return { path, size: 0, lastModified: 0, isFolder: true, itemCount: 0 }
}
function file(path: string) {
  return { path, size: 1, lastModified: 0 }
}

function renderPicker(sourcePaths: string[], onMove = vi.fn(async () => null)) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MovePicker sourcePaths={sourcePaths} onMove={onMove} onClose={vi.fn()} />
    </QueryClientProvider>,
  )
  return onMove
}

afterEach(() => listFiles.mockReset())

describe('MovePicker', () => {
  it('lists folders only, excluding the sources, and moves into the chosen folder', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos'), file('/a.txt'), folder('/Movee')]) // root
    listFiles.mockResolvedValueOnce([folder('/Photos/Sub')]) // inside Photos
    const onMove = renderPicker(['/Movee'])

    await screen.findByText('Photos')
    expect(screen.queryByText('a.txt')).toBeNull() // files aren't destinations
    expect(screen.queryByText('Movee')).toBeNull() // a source can't be its own destination

    await userEvent.click(screen.getByText('Photos'))
    await screen.findByText('Sub')
    await userEvent.click(screen.getByRole('button', { name: 'Move here' }))

    await waitFor(() => expect(onMove).toHaveBeenCalledWith('/Photos'))
  })

  it('disables "Move here" when the sources already live in the shown folder', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // root; source /a.txt already lives here
    renderPicker(['/a.txt'])

    await screen.findByText('Photos')
    expect(screen.getByRole('button', { name: 'Move here' })).toBeDisabled()
  })
})
