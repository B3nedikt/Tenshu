import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { album, photo, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { FavouritesView } = await import('./FavouritesView')
const { FeedView } = await import('./FeedView')

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

describe('Favourites', () => {
  it('lists the favourites, videos included, and follows the stream', async () => {
    const still = photo({ name: 'fav.jpg' })
    const clip = photo({ name: 'fav.mp4', isVideo: true, durationMs: 3000 })
    proxy.listFavourites.mockResolvedValue({ ok: true, photos: [still] })
    renderWithProviders(<FavouritesView />)
    expect(await screen.findByRole('button', { name: /fav\.jpg/ })).toBeInTheDocument()
    act(() => streams.favourites.push({ ok: true, photos: [still, clip] }))
    expect(await screen.findByRole('button', { name: /Video fav\.mp4/ })).toBeInTheDocument()
  })

  it('empty only after a successful read; a failure renders its kind', async () => {
    proxy.listFavourites.mockResolvedValue({ ok: false, error: { kind: 'error_unannounced_session' } })
    renderWithProviders(<FavouritesView />)
    expect(await screen.findByText('Tenshu needs to reconnect. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText(/No favourites yet/)).toBeNull()
  })
})

describe('Multi-select in the feed', () => {
  it('adds the picked photographs to an album and favourites, skipping none silently', async () => {
    const photos = [photo({ name: 'x.jpg' }), photo({ name: 'y.jpg' })]
    const trip = album({ name: 'Trip' })
    proxy.captureTimes.mockResolvedValue({ ok: true, times: photos.map((p) => p.capturedAtMs) })
    proxy.feedPage.mockResolvedValue({ ok: true, photos, nextCursor: null })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    proxy.addToAlbum.mockResolvedValueOnce({ ok: true }).mockResolvedValueOnce({ ok: false, error: { kind: 'error_photos_not_a_photo' } })
    renderWithProviders(<FeedView />)

    await userEvent.click(await screen.findByRole('button', { name: 'Select' }))
    await userEvent.click(await screen.findByRole('button', { name: /x\.jpg/ }))
    await userEvent.click(screen.getByRole('button', { name: /y\.jpg/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Add to album' }))
    await userEvent.click(await screen.findByRole('button', { name: /Trip/ }))

    await waitFor(() => expect(proxy.addToAlbum).toHaveBeenCalledTimes(2))
    expect(proxy.addToAlbum.mock.calls).toEqual([
      [trip.albumId, photos[0]!.path],
      [trip.albumId, photos[1]!.path],
    ])
    // A partial batch reports its count and the failure by kind.
    expect(await screen.findByText(/1 of 2 done\. That file isn’t a photo or video/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add to favourites' }))
    await waitFor(() => expect(proxy.setFavourite).toHaveBeenCalledTimes(2))
    expect(proxy.setFavourite).toHaveBeenCalledWith(photos[0]!.contentId, true)
  })

  it('a tile click opens the viewer outside select mode', async () => {
    const photos = [photo({ name: 'open-me.jpg' })]
    proxy.captureTimes.mockResolvedValue({ ok: true, times: photos.map((p) => p.capturedAtMs) })
    proxy.feedPage.mockResolvedValue({ ok: true, photos, nextCursor: null })
    renderWithProviders(<FeedView />)
    await userEvent.click(await screen.findByRole('button', { name: /open-me\.jpg/ }))
    expect(screen.getByRole('dialog', { name: 'open-me.jpg' })).toBeInTheDocument()
  })
})
