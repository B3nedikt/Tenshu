// Data → Files: loading, data, empty, and an error BY KIND — never zeros for a failed read
// (`FileSystem.inventory()`'s contract, pinned on the Kotlin side by `FileInventoryContractTest`).
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { FileInventory, FileInventoryResult } from '@tenshu/sdk/files'
import i18n from '../../../i18n'

const inventoryMock = vi.fn<() => Promise<FileInventoryResult>>()

vi.mock('@tenshu/sdk/files', () => ({
  FileSystem: { inventory: () => inventoryMock() },
}))

import { FilesInventoryPage } from './FilesInventoryPage'
import { resetInventoryReadForTests } from './fileInventoryReader'

const INVENTORY: FileInventory = {
  fileCount: 1234,
  folderCount: 56,
  totalBytes: 5 * 1024 * 1024 * 1024,
  newestModifiedMillis: Date.UTC(2026, 9, 27, 12),
  byExtension: [
    { extension: 'jpg', count: 1000, bytes: 4 * 1024 * 1024 * 1024 },
    { extension: 'pdf', count: 200, bytes: 1024 * 1024 * 1024 - 100 },
    { extension: '', count: 34, bytes: 100 },
  ],
}

function renderPage() {
  render(
    <MemoryRouter>
      <FilesInventoryPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  inventoryMock.mockReset()
  resetInventoryReadForTests()
})

afterEach(async () => {
  await i18n.changeLanguage('en')
})

describe('FilesInventoryPage', () => {
  it('shows a loading state until the read answers', async () => {
    let resolve: (r: FileInventoryResult) => void = () => {}
    inventoryMock.mockReturnValue(new Promise(r => (resolve = r)))
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading the file inventory…' })).toBeInTheDocument()
    await act(async () => resolve({ ok: true, inventory: INVENTORY }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('renders the totals and the breakdown by kind, largest first', async () => {
    inventoryMock.mockResolvedValue({ ok: true, inventory: INVENTORY })
    renderPage()

    const files = await screen.findByText('Files', { selector: 'dt' })
    expect(files.nextElementSibling).toHaveTextContent('1,234')
    expect(screen.getByText('Folders', { selector: 'dt' }).nextElementSibling).toHaveTextContent('56')
    expect(screen.getByText('Size of your files', { selector: 'dt' }).nextElementSibling).toHaveTextContent('5 GB')
    expect(screen.getByText('Last change', { selector: 'dt' }).nextElementSibling).toHaveTextContent('Oct 27, 2026')

    const rows = within(screen.getByRole('list', { name: 'By kind' })).getAllByRole('listitem')
    expect(rows.map(r => r.textContent)).toEqual([
      'Images1,000 files · 4 GB',
      'Documents200 files · 1 GB',
      'Other34 files · 100 B',
    ])
  })

  it('opens Drive for browsing, and offers no destructive action', async () => {
    inventoryMock.mockResolvedValue({ ok: true, inventory: INVENTORY })
    renderPage()
    expect(await screen.findByRole('link', { name: 'Open Drive' })).toHaveAttribute('href', '/drive/files')
    expect(screen.getByRole('link', { name: 'Services' })).toHaveAttribute('href', '/control/services')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the empty state for an empty drive', async () => {
    inventoryMock.mockResolvedValue({
      ok: true,
      inventory: { fileCount: 0, folderCount: 0, totalBytes: 0, newestModifiedMillis: 0, byExtension: [] },
    })
    renderPage()
    expect(await screen.findByText('No files yet')).toBeInTheDocument()
    expect(screen.queryByText('Size of your files')).not.toBeInTheDocument()
  })

  it('renders folders-only as data, not as empty', async () => {
    inventoryMock.mockResolvedValue({
      ok: true,
      inventory: { fileCount: 0, folderCount: 3, totalBytes: 0, newestModifiedMillis: 0, byExtension: [] },
    })
    renderPage()
    expect((await screen.findByText('Folders', { selector: 'dt' })).nextElementSibling).toHaveTextContent('3')
    expect(screen.getByText('Last change', { selector: 'dt' }).nextElementSibling).toHaveTextContent('—')
    expect(screen.queryByText('No files yet')).not.toBeInTheDocument()
  })

  // ⚠⚠ The point of the result shape: a failed read is an error, never "0 files".
  it('renders a failed read as the read error, with no figures', async () => {
    inventoryMock.mockResolvedValue({ ok: false, error: { kind: 'read', message: 'tree read broke' } })
    renderPage()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Couldn't read your files")
    expect(alert).toHaveTextContent('tree read broke')
    expect(screen.queryByText('Files', { selector: 'dt' })).not.toBeInTheDocument()
    expect(screen.queryByText('No files yet')).not.toBeInTheDocument()
  })

  it('renders a rejected read as the runtime error', async () => {
    inventoryMock.mockRejectedValue(new Error('runtime not ready'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent("Tenshu isn't running in this tab")
  })

  it('renders an unknown failure kind as the generic error, naming the kind', async () => {
    inventoryMock.mockResolvedValue({ ok: false, error: { kind: 'quota' } })
    renderPage()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong')
    expect(alert).toHaveTextContent('quota')
  })

  it('reads again on Try again', async () => {
    inventoryMock.mockResolvedValueOnce({ ok: false, error: { kind: 'read' } })
    inventoryMock.mockResolvedValueOnce({ ok: true, inventory: INVENTORY })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Files', { selector: 'dt' })).toBeInTheDocument()
    expect(inventoryMock).toHaveBeenCalledTimes(2)
  })

  // ⚠⚠ Review finding #1: the read is a full-tree walk on the tab's one JS thread and cannot be
  // cancelled, so a second open while it runs must JOIN it, never start another.
  it('joins the read already running when the page is opened again before it answers', async () => {
    let resolve: (r: FileInventoryResult) => void = () => {}
    inventoryMock.mockReturnValue(new Promise(r => (resolve = r)))
    renderPage()
    cleanup() // navigate away while the read is still running…
    renderPage() // …and back
    expect(inventoryMock).toHaveBeenCalledTimes(1)
    await act(async () => resolve({ ok: true, inventory: INVENTORY }))
    expect((await screen.findByText('Files', { selector: 'dt' })).nextElementSibling).toHaveTextContent('1,234')
    expect(inventoryMock).toHaveBeenCalledTimes(1)
  })

  it("reads once under StrictMode's double effect", async () => {
    inventoryMock.mockResolvedValue({ ok: true, inventory: INVENTORY })
    render(
      <StrictMode>
        <MemoryRouter>
          <FilesInventoryPage />
        </MemoryRouter>
      </StrictMode>,
    )
    expect(await screen.findByText('Files', { selector: 'dt' })).toBeInTheDocument()
    expect(inventoryMock).toHaveBeenCalledTimes(1)
  })

  it('reads fresh on the next open once the previous read has answered', async () => {
    inventoryMock.mockResolvedValue({ ok: true, inventory: INVENTORY })
    renderPage()
    expect(await screen.findByText('Files', { selector: 'dt' })).toBeInTheDocument()
    cleanup()
    renderPage()
    expect(await screen.findByText('Files', { selector: 'dt' })).toBeInTheDocument()
    expect(inventoryMock).toHaveBeenCalledTimes(2)
  })

  it('renders in German and French', async () => {
    inventoryMock.mockResolvedValue({ ok: true, inventory: INVENTORY })
    await act(() => i18n.changeLanguage('de'))
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Dateien' })).toBeInTheDocument()
    // ⚠ The heading renders before the inventory resolves: wait for the data, not just the page.
    expect(await screen.findByText('Bilder')).toBeInTheDocument()
    expect(screen.getByText(/1\.000 Dateien/)).toBeInTheDocument()

    await act(() => i18n.changeLanguage('fr'))
    expect(await screen.findByRole('heading', { name: 'Fichiers' })).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Par type' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ouvrir Drive' })).toBeInTheDocument()
  })
})
