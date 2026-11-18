// Album and year covers plan their rung from the card's MEASURED size, like the grid's tiles. They
// used to stop at LARGE (256 px), so a ~300 px card on a Retina screen drew its cover blurry.
import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { album, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { AlbumsView } = await import('./AlbumsView')
const { planCover } = await import('../lib/thumbnails')

// jsdom has no layout and no ResizeObserver: every element measures [side] CSS px square.
let side = 0
class FakeResizeObserver {
  private readonly callback: () => void
  constructor(callback: () => void) {
    this.callback = callback
  }
  observe() {
    this.callback()
  }
  disconnect() {}
}

let minted = 0
beforeEach(() => {
  resetProxy()
  minted = 0
  URL.createObjectURL = vi.fn(() => `blob:${++minted}`)
  URL.revokeObjectURL = vi.fn()
  proxy.getPreview.mockResolvedValue({ ok: true, bytes: new Int8Array([1]) })
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    () => ({ width: side, height: side, top: 0, left: 0, right: side, bottom: side, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
  )
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  Object.defineProperty(window, 'devicePixelRatio', { value: 1, configurable: true })
})

async function cover(px: number, ratio: number) {
  side = px
  Object.defineProperty(window, 'devicePixelRatio', { value: ratio, configurable: true })
  const trip = album({ name: 'Trip', photoCount: 1, coverPath: '/a.jpg' })
  proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
  renderWithProviders(<AlbumsView />)
  const card = (await screen.findByText('Trip')).closest('button')!
  return () => card.querySelector('img')
}

describe('album covers', () => {
  it('a 300 px card on a 2× screen swaps in the sharp rung', async () => {
    proxy.getTilePreview.mockResolvedValue({ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'xlarge', coveringRung: 'present' })
    const img = await cover(300, 2)
    await waitFor(() => expect(proxy.getTilePreview).toHaveBeenCalledWith('/a.jpg'))
    await waitFor(() => expect(img()?.getAttribute('src')).toBe('blob:2'))
  })

  it('a card LARGE already covers stays on LARGE', async () => {
    const img = await cover(150, 1)
    await waitFor(() => expect(img()?.getAttribute('src')).toBe('blob:1'))
    expect(proxy.getTilePreview).not.toHaveBeenCalled()
  })
})

// The year banner: a wide card filled by `object-cover`, so a portrait cover's WIDTH must span it.
describe('banner covers (fit)', () => {
  it('asks for the aspect more, so a portrait photo still covers the width', () => {
    // 400 CSS px on 2×: 800 device px across. A 3:4 photo fit in N px is only 3N/4 wide.
    const plan = planCover(400, 2, { fit: true, pixelWidth: 3000, pixelHeight: 4000 })
    expect(plan).toEqual({ rung: 'xlarge', devicePx: 1024, fit: true })
  })

  it('a narrow banner a portrait LARGE cannot span leaves LARGE', () => {
    // 220 device px across: a 256 px LARGE is only 192 px wide when portrait.
    expect(planCover(220, 1, { fit: true, pixelWidth: 3000, pixelHeight: 4000 }).rung).toBe('xlarge')
  })

  it('unmeasured is LARGE', () => {
    expect(planCover(0, 2, { fit: true })).toEqual({ rung: 'large' })
  })
})
