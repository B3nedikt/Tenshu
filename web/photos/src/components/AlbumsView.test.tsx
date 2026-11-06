import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { album, photo, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { AlbumsView } = await import('./AlbumsView')

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

describe('Albums', () => {
  it('lists the albums and follows the push stream', async () => {
    const trip = album({ name: 'Trip', photoCount: 2 })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    renderWithProviders(<AlbumsView />)
    expect(await screen.findByText('Trip')).toBeInTheDocument()
    expect(screen.getByText('2 items')).toBeInTheDocument()

    act(() => streams.albums.push({ ok: true, albums: [{ ...trip, name: 'Trip 2026' }] }))
    expect(await screen.findByText('Trip 2026')).toBeInTheDocument()
  })

  it('a dead stream says so rather than freezing', async () => {
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [] })
    renderWithProviders(<AlbumsView />)
    await screen.findByText('No albums yet.')
    act(() => streams.albums.fail(new Error('collector died')))
    expect(await screen.findByText(/stopped updating/)).toBeInTheDocument()
  })

  it('a failed list renders its kind, never "No albums yet"', async () => {
    proxy.listAlbums.mockResolvedValue({ ok: false, error: { kind: 'error_internal' } })
    renderWithProviders(<AlbumsView />)
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_internal')
    expect(screen.queryByText('No albums yet.')).toBeNull()
  })

  it('creates an album', async () => {
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [] })
    renderWithProviders(<AlbumsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'New album' }))
    await userEvent.type(screen.getByLabelText('Album name'), 'Summer')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(proxy.createAlbum).toHaveBeenCalledWith('Summer')
  })

  it('opens an album, renames it, and deletes it only after the confirm', async () => {
    const trip = album({ name: 'Trip', photoCount: 1 })
    const p = photo({ name: 'in-trip.jpg' })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    proxy.albumPhotos.mockResolvedValue({ ok: true, photos: [p] })
    renderWithProviders(<AlbumsView />)
    await userEvent.click(await screen.findByText('Trip'))
    expect(await screen.findByRole('button', { name: /in-trip\.jpg/ })).toBeInTheDocument()
    expect(proxy.albumPhotos).toHaveBeenCalledWith(trip.albumId)

    await userEvent.click(screen.getByRole('button', { name: 'Rename' }))
    const field = screen.getByLabelText('Album name')
    await userEvent.clear(field)
    await userEvent.type(field, 'Road trip')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(proxy.renameAlbum).toHaveBeenCalledWith(trip.albumId, 'Road trip')

    await userEvent.click(screen.getByRole('button', { name: 'Delete album' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete this album?' })
    expect(proxy.deleteAlbum).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete album' }))
    expect(proxy.deleteAlbum).toHaveBeenCalledWith(trip.albumId)
    expect(await screen.findByRole('button', { name: 'New album' })).toBeInTheDocument()
  })

  it('a failed rename renders its kind and keeps the dialog open', async () => {
    const trip = album({ name: 'Trip' })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    proxy.renameAlbum.mockResolvedValue({ ok: false, error: { kind: 'error_photos_unknown_album' } })
    renderWithProviders(<AlbumsView />)
    await userEvent.click(await screen.findByText('Trip'))
    await userEvent.click(screen.getByRole('button', { name: 'Rename' }))
    await userEvent.type(screen.getByLabelText('Album name'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('That album no longer exists.')).toBeInTheDocument()
    expect(screen.getByLabelText('Album name')).toBeInTheDocument()
  })

  it('multi-select removes photographs from the album', async () => {
    const trip = album({ name: 'Trip', photoCount: 2 })
    const a = photo({ name: 'a.jpg' })
    const b = photo({ name: 'b.jpg' })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    proxy.albumPhotos.mockResolvedValue({ ok: true, photos: [a, b] })
    renderWithProviders(<AlbumsView />)
    await userEvent.click(await screen.findByText('Trip'))
    await screen.findByRole('button', { name: /a\.jpg/ })
    await userEvent.click(screen.getByRole('button', { name: 'Select' }))
    await userEvent.click(screen.getByRole('button', { name: /a\.jpg/ }))
    await userEvent.click(screen.getByRole('button', { name: /b\.jpg/ }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove from album' }))
    await waitFor(() => expect(proxy.removeFromAlbum).toHaveBeenCalledTimes(2))
    expect(proxy.removeFromAlbum.mock.calls).toEqual([
      [trip.albumId, a.contentId],
      [trip.albumId, b.contentId],
    ])
  })
})
