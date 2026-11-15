import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { photo, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { FavouritesView } = await import('./FavouritesView')

// jsdom: the grid falls back to 960 px, so a list tile is 156 CSS px (layout.ts); no worker, so the
// rung is drawn as it came (`squareCover`'s fallback).
let minted = 0
beforeEach(() => {
  resetProxy()
  minted = 0
  URL.createObjectURL = vi.fn(() => `blob:${++minted}`)
  URL.revokeObjectURL = vi.fn()
  proxy.getPreview.mockResolvedValue({ ok: true, bytes: new Int8Array([1]) })
})
afterEach(() => {
  Object.defineProperty(window, 'devicePixelRatio', { value: 1, configurable: true })
})

function retina(ratio: number) {
  Object.defineProperty(window, 'devicePixelRatio', { value: ratio, configurable: true })
}

async function showOne(name = 'a.jpg') {
  const p = photo({ name, pixelWidth: 4032, pixelHeight: 3024 })
  proxy.listFavourites.mockResolvedValue({ ok: true, photos: [p] })
  renderWithProviders(<FavouritesView />)
  const tile = await screen.findByRole('button', { name: new RegExp(name.replace('.', '\\.')) })
  return { p, img: () => tile.querySelector('img') }
}

describe('Retina tiles', () => {
  it('a 1× screen asks for LARGE only', async () => {
    retina(1)
    const { img } = await showOne()
    await waitFor(() => expect(img()).not.toBeNull())
    expect(proxy.getTilePreview).not.toHaveBeenCalled()
  })

  it('on a 2× screen LARGE shows first, then XLARGE replaces it', async () => {
    retina(2)
    let answer: (value: unknown) => void = () => undefined
    proxy.getTilePreview.mockImplementation(() => new Promise((resolve) => (answer = resolve)) as never)
    const { p, img } = await showOne()
    await waitFor(() => expect(img()?.getAttribute('src')).toBe('blob:1'))
    expect(proxy.getTilePreview).toHaveBeenCalledWith(p.path)
    answer({ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'xlarge', coveringRung: 'present' })
    await waitFor(() => expect(img()?.getAttribute('src')).toBe('blob:2'))
  })

  it('a stand-in answer (no XLARGE) keeps LARGE', async () => {
    retina(2)
    proxy.getTilePreview.mockResolvedValue({ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'large', coveringRung: 'unknown' })
    const { img } = await showOne()
    await waitFor(() => expect(proxy.getTilePreview).toHaveBeenCalled())
    await waitFor(() => expect(img()?.getAttribute('src')).toBe('blob:1'))
    expect(minted).toBe(1)
  })

  it('a rung the relay confirmed absent is not asked for again this session', async () => {
    retina(2)
    proxy.getTilePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
    const first = await showOne('gone.jpg')
    await waitFor(() => expect(proxy.getTilePreview).toHaveBeenCalledTimes(1))
    // The same path in a fresh grid (a scroll back, another view): no second pull.
    const { loadSharpTile, sharpKey } = await import('../lib/thumbnails')
    expect(await loadSharpTile(sharpKey(first.p.path, 320))).toBeNull()
    expect(proxy.getTilePreview).toHaveBeenCalledTimes(1)
    // …but only for a while: absent is often a wait (a backfill elsewhere).
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 11 * 60 * 1000)
    await loadSharpTile(sharpKey(first.p.path, 320))
    expect(proxy.getTilePreview).toHaveBeenCalledTimes(2)
    vi.restoreAllMocks()
  })
})
