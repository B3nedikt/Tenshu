import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TrashedPhoto } from '@tenshu/sdk/photos'
import { photo, proxy, resetProxy, T0 } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { TrashView } = await import('./TrashView')

function trashed(name: string): TrashedPhoto {
  const p = photo({ name, path: `/Trash/${name}` })
  return { contentId: p.contentId, photo: p, originalPath: `/Photos/${name}`, trashedAtMs: T0 }
}

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

describe('Trash', () => {
  it('restores, and the feed refetches', async () => {
    const item = trashed('old.jpg')
    proxy.listTrash.mockResolvedValue({ ok: true, items: [item] })
    renderWithProviders(<TrashView />)
    await screen.findByText('old.jpg')
    const timesBefore = proxy.captureTimes.mock.calls.length
    await userEvent.click(screen.getByRole('button', { name: 'Restore' }))
    expect(proxy.restorePhoto).toHaveBeenCalledWith(item.contentId)
    await waitFor(() => expect(proxy.captureTimes.mock.calls.length).toBeGreaterThan(timesBefore))
  })

  it('deletes forever only from the confirm — cancel sends nothing', async () => {
    const item = trashed('gone.jpg')
    proxy.listTrash.mockResolvedValue({ ok: true, items: [item] })
    renderWithProviders(<TrashView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete forever' }))
    let dialog = await screen.findByRole('dialog', { name: 'Delete forever?' })
    expect(dialog).toHaveTextContent('“gone.jpg” will be deleted from all your devices.')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(proxy.deleteForever).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Delete forever' }))
    dialog = await screen.findByRole('dialog', { name: 'Delete forever?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete forever' }))
    expect(proxy.deleteForever).toHaveBeenCalledWith(item.contentId)
  })

  it('a failed delete renders its kind inside the dialog', async () => {
    const item = trashed('stuck.jpg')
    proxy.listTrash.mockResolvedValue({ ok: true, items: [item] })
    proxy.deleteForever.mockResolvedValue({ ok: false, error: { kind: 'error_photos_not_in_trash' } })
    renderWithProviders(<TrashView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete forever' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete forever' }))
    expect(await within(dialog).findByText('That item is no longer in the trash.')).toBeInTheDocument()
  })

  it('a failed restore renders its kind on the row', async () => {
    proxy.listTrash.mockResolvedValue({ ok: true, items: [trashed('x.jpg')] })
    proxy.restorePhoto.mockResolvedValue({ ok: false, error: { kind: 'error_photos_file_op_failed' } })
    renderWithProviders(<TrashView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect(await screen.findByText('The file could not be moved. Please try again.')).toBeInTheDocument()
  })

  it('a failed read renders, never "The trash is empty"', async () => {
    proxy.listTrash.mockRejectedValue(new Error('timed out'))
    renderWithProviders(<TrashView />)
    expect(await screen.findByText('Tenshu took too long to answer. Try again.')).toBeInTheDocument()
    expect(screen.queryByText('The trash is empty.')).toBeNull()
  })
})
