import { useState } from 'react'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Photo } from '@tenshu/sdk/photos'
import { album, photo, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { Viewer, FILMSTRIP_RADIUS } = await import('./Viewer')

const JPEG = new Int8Array([0xff, 0xd8, 0xff, 0x00])

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

function open(photos: Photo[], props: { albumId?: string; onClose?: (i: number) => void; onTrashed?: () => void; start?: number } = {}) {
  return renderWithProviders(
    <Viewer
      total={photos.length}
      photoAt={(i) => photos[i]}
      startIndex={props.start ?? 0}
      albumId={props.albumId}
      onClose={props.onClose ?? (() => {})}
      onTrashed={props.onTrashed}
    />,
  )
}

describe('Viewer — rung choice', () => {
  it('asks getLargePreview for the viewport-measured size and draws the rung that answered', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: JPEG, thumbnailClass: 'xxlarge', coveringRung: 'present' })
    const p = photo({ name: 'beach.jpg' })
    open([p])
    expect(await screen.findByRole('img', { name: 'beach.jpg' })).toHaveAttribute('src', 'blob:x')
    const [path, px] = proxy.getLargePreview.mock.calls[0]!
    expect(path).toBe(p.path)
    expect(px % 512).toBe(0)
    expect(px).toBeGreaterThanOrEqual(1024)
    expect(screen.queryByRole('button', { name: 'Download original' })).toBeNull()
  })

  it('ED-3: an edited photo is drawn by its rendition, and its download is the rendition — never the original', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: JPEG, thumbnailClass: 'large', coveringRung: 'absent' })
    const p = photo({ name: 'IMG_0002.jpg', path: '/Photos/IMG_0002.jpg', renditionPath: '/Trash/.edits/c2-1.jpg' })
    open([p])
    expect(await screen.findByRole('img', { name: 'IMG_0002.jpg' })).toBeInTheDocument()
    expect(proxy.getLargePreview.mock.calls.map((c) => c[0])).toEqual(['/Trash/.edits/c2-1.jpg'])
    await userEvent.click(screen.getByRole('button', { name: 'Download original' }))
    await waitFor(() => expect(proxy.openContentStream).toHaveBeenCalled())
    expect(proxy.openContentStream.mock.calls.map((c) => c[0])).toEqual(['/Trash/.edits/c2-1.jpg'])
  })

  it('a HEIC with the covering rung absent: the largest rung, and "Download original" offered — never decoded', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: JPEG, thumbnailClass: 'large', coveringRung: 'absent' })
    open([photo({ name: 'IMG_0001.HEIC', path: '/Photos/IMG_0001.HEIC' })])
    expect(await screen.findByRole('img', { name: 'IMG_0001.HEIC' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download original' })).toBeInTheDocument()
    // Nothing read the original to draw it.
    expect(proxy.openContentStream).not.toHaveBeenCalled()
    expect(proxy.readContentRange).not.toHaveBeenCalled()
  })

  it('"unknown" is not "absent": no download offered on a blip', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'unknown' })
    open([photo()])
    expect(await screen.findByText(/No preview of this photo/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download original' })).toBeNull()
  })

  it('a failed preview read renders its kind, not a blank', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: false, error: { kind: 'error_photos_not_found' } })
    open([photo()])
    expect(await screen.findByText('That photo is no longer there.')).toBeInTheDocument()
  })
})

describe('Viewer — Download original', () => {
  it('is a user action: it streams the original over the content-stream ops and saves it', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
    proxy.openContentStream.mockResolvedValue({ ok: true, handle: 'h1', totalSize: 6 })
    proxy.readContentRange
      .mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
      .mockResolvedValueOnce({ ok: true, bytes: new Int8Array([4, 5, 6]) })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    open([photo({ name: 'IMG_0002.HEIC', path: '/Photos/IMG_0002.HEIC' })])

    await userEvent.click(await screen.findByRole('button', { name: 'Download original' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Saved.')
    expect(proxy.openContentStream).toHaveBeenCalledWith('/Photos/IMG_0002.HEIC')
    expect(proxy.readContentRange.mock.calls.map(([, offset]) => offset)).toEqual([0, 3])
    expect(proxy.closeContentStream).toHaveBeenCalledWith('h1')
    expect(click).toHaveBeenCalledTimes(1)
    click.mockRestore()
  })

  it('in the cross-origin apex frame the picker throws SecurityError: it falls back to a Blob download', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
    proxy.openContentStream.mockResolvedValue({ ok: true, handle: 'h2', totalSize: 2 })
    proxy.readContentRange.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([7, 8]) })
    const w = window as unknown as { showSaveFilePicker?: unknown }
    w.showSaveFilePicker = vi.fn(async () => {
      throw new DOMException('Cross origin sub frames aren\'t allowed to show a file picker.', 'SecurityError')
    })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      open([photo({ name: 'IMG_0003.HEIC' })])
      await userEvent.click(await screen.findByRole('button', { name: 'Download original' }))
      expect(await screen.findByRole('status')).toHaveTextContent('Saved.')
      expect(click).toHaveBeenCalledTimes(1)
      expect(proxy.closeContentStream).toHaveBeenCalledWith('h2')
    } finally {
      delete w.showSaveFilePicker
      click.mockRestore()
    }
  })

  it('a dismissed picker is a cancel, not a failure and not a second download', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
    const w = window as unknown as { showSaveFilePicker?: unknown }
    w.showSaveFilePicker = vi.fn(async () => {
      throw new DOMException('The user aborted a request.', 'AbortError')
    })
    try {
      open([photo()])
      await userEvent.click(await screen.findByRole('button', { name: 'Download original' }))
      await waitFor(() => expect(screen.getByRole('button', { name: 'Download original' })).toBeEnabled())
      expect(proxy.openContentStream).not.toHaveBeenCalled()
      expect(screen.queryByRole('alert')).toBeNull()
    } finally {
      delete w.showSaveFilePicker
    }
  })

  it('a refused stream renders its kind', async () => {
    proxy.getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
    proxy.openContentStream.mockResolvedValue({ ok: false, error: { kind: 'error_photos_not_found' } })
    open([photo()])
    await userEvent.click(await screen.findByRole('button', { name: 'Download original' }))
    expect(await screen.findByText('That photo is no longer there.')).toBeInTheDocument()
  })
})

describe('Viewer — navigation', () => {
  const photos = [photo({ name: 'one.jpg' }), photo({ name: 'two.jpg' }), photo({ name: 'three.jpg' })]

  it('arrow keys move between photographs, Escape closes at the current one', async () => {
    const onClose = vi.fn()
    open(photos, { onClose })
    expect(screen.getByRole('dialog', { name: 'one.jpg' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByRole('dialog', { name: 'two.jpg' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByRole('dialog', { name: 'three.jpg' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledWith(1)
  })

  it('follows its list in when the list shrinks under it, and closes when it empties', () => {
    const onClose = vi.fn()
    let setItems: (items: Photo[]) => void = () => {}
    function Harness() {
      const [items, set] = useState<Photo[]>(photos)
      setItems = set
      return <Viewer total={items.length} photoAt={(i) => items[i]} startIndex={2} onClose={onClose} />
    }
    renderWithProviders(<Harness />)
    expect(screen.getByRole('dialog', { name: 'three.jpg' })).toBeInTheDocument()
    // The last one was unfavourited: the viewer shows the new last, not "Loading…" forever.
    act(() => setItems(photos.slice(0, 2)))
    expect(screen.getByRole('dialog', { name: 'two.jpg' })).toBeInTheDocument()
    act(() => setItems([]))
    expect(onClose).toHaveBeenCalledWith(0)
  })

  it('a swipe left goes forward, right goes back', () => {
    open(photos, { start: 1 })
    const dialog = screen.getByRole('dialog')
    fireEvent.pointerDown(dialog, { clientX: 300 })
    fireEvent.pointerUp(dialog, { clientX: 100 })
    expect(screen.getByRole('dialog', { name: 'three.jpg' })).toBeInTheDocument()
    fireEvent.pointerDown(dialog, { clientX: 100 })
    fireEvent.pointerUp(dialog, { clientX: 400 })
    expect(screen.getByRole('dialog', { name: 'two.jpg' })).toBeInTheDocument()
  })
})

describe('Viewer — filmstrip and keys (Drive’s FileViewer rules)', () => {
  it('shows the neighbours, the current one marked, and a click moves to it', async () => {
    const photos = [photo({ name: 'one.jpg' }), photo({ name: 'two.jpg' }), photo({ name: 'three.jpg' })]
    open(photos)
    const strip = screen.getByRole('navigation', { name: 'Photos nearby' })
    expect(within(strip).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['one.jpg', 'two.jpg', 'three.jpg'])
    expect(within(strip).getByRole('button', { name: 'one.jpg' })).toHaveAttribute('aria-current', 'true')
    await userEvent.click(within(strip).getByRole('button', { name: 'three.jpg' }))
    expect(screen.getByRole('dialog', { name: 'three.jpg' })).toBeInTheDocument()
    expect(within(strip).getByRole('button', { name: 'three.jpg' })).toHaveAttribute('aria-current', 'true')
    expect(within(strip).getByRole('button', { name: 'one.jpg' })).not.toHaveAttribute('aria-current')
  })

  it('draws a window of neighbours, never the whole list, and pages the far end in', () => {
    const photos = Array.from({ length: 500 }, (_, i) => photo({ name: `n${i}.jpg` }))
    const need = vi.fn()
    renderWithProviders(
      <Viewer total={photos.length} photoAt={(i) => (i < 140 ? photos[i] : undefined)} need={need} startIndex={120} onClose={() => {}} />,
    )
    const strip = screen.getByRole('navigation', { name: 'Photos nearby' })
    const names = within(strip).getAllByRole('button').map((b) => b.getAttribute('aria-label'))
    // 90…139 are loaded; 140…150 are placeholders until the pager brings them.
    expect(names[0]).toBe(`n${120 - FILMSTRIP_RADIUS}.jpg`)
    expect(names).toHaveLength(140 - (120 - FILMSTRIP_RADIUS))
    expect(need).toHaveBeenCalledWith(120 + FILMSTRIP_RADIUS)
  })

  it('controlled: a move is reported, not taken, until the owner moves the index', () => {
    const photos = [photo({ name: 'one.jpg' }), photo({ name: 'two.jpg' })]
    const onMove = vi.fn()
    renderWithProviders(<Viewer total={2} photoAt={(i) => photos[i]} index={0} onMove={onMove} onClose={() => {}} />)
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(onMove).toHaveBeenCalledWith(1)
    expect(screen.getByRole('dialog', { name: 'one.jpg' })).toBeInTheDocument()
  })

  it('skips a key already handled, a key from outside the viewer, and ←/→/Space on a focused video', async () => {
    proxy.motionClip.mockResolvedValue({ ok: true, bytes: new Int8Array([0, 0, 0, 0x20]) })
    const photos = [photo({ name: 'clip.mp4', isVideo: true, durationMs: 4000 }), photo({ name: 'two.jpg' })]
    const onClose = vi.fn()
    open(photos, { onClose })
    const handled = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true })
    handled.preventDefault()
    act(() => {
      window.dispatchEvent(handled)
    })
    expect(screen.getByRole('dialog', { name: 'clip.mp4' })).toBeInTheDocument()

    const outside = document.createElement('button')
    document.body.appendChild(outside)
    fireEvent.keyDown(outside, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    outside.remove()

    const loop = await screen.findByTestId('motion-loop')
    fireEvent.keyDown(loop, { key: 'ArrowRight' })
    fireEvent.keyDown(loop, { key: ' ' })
    expect(screen.getByRole('dialog', { name: 'clip.mp4' })).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    // Space anywhere else in the viewer is Quick Look's close.
    fireEvent.keyDown(screen.getByRole('dialog'), { key: ' ' })
    expect(onClose).toHaveBeenCalledWith(0)
  })
})

describe('Viewer — details, favourites, albums, trash', () => {
  it('shows the stored fields only', async () => {
    const p = photo({ name: 'dolomites.jpg' })
    proxy.photoDetails.mockResolvedValue({
      ok: true,
      details: {
        path: p.path,
        capturedAtMs: p.capturedAtMs,
        sizeBytes: 4_200_000,
        pixelWidth: 4032,
        pixelHeight: 3024,
        orientation: 1,
        cameraMake: 'Fujifilm',
        cameraModel: 'X100V',
        durationMs: null,
        latE7: 465_000_000,
        lngE7: 118_000_000,
      },
    })
    proxy.albumsFor.mockResolvedValue({ ok: true, albums: [album({ name: 'Dolomites 2026' })] })
    open([p])
    await userEvent.click(screen.getByRole('button', { name: 'Details' }))
    const panel = await screen.findByRole('complementary', { name: 'Details' })
    await waitFor(() => expect(panel).toHaveTextContent('Fujifilm X100V'))
    await waitFor(() => expect(panel).toHaveTextContent('Dolomites 2026'))
    expect(proxy.albumsFor).toHaveBeenCalledWith(p.contentId)
    expect(panel).toHaveTextContent('4032 × 3024')
    expect(panel).toHaveTextContent('46.50000, 11.80000')
    expect(proxy.photoDetails).toHaveBeenCalledWith(p.path)
  })

  it('favourites and unfavourites by content id', async () => {
    const p = photo()
    proxy.isFavourite.mockResolvedValueOnce({ ok: true, favourite: false }).mockResolvedValue({ ok: true, favourite: true })
    open([p])
    await userEvent.click(await screen.findByRole('button', { name: 'Favourite', pressed: false }))
    expect(proxy.setFavourite).toHaveBeenCalledWith(p.contentId, true)
    expect(await screen.findByRole('button', { name: 'Unfavourite', pressed: true })).toBeInTheDocument()
  })

  it('a file with no content id is shown but cannot be filed', async () => {
    open([photo({ contentId: '' })])
    expect(screen.getByRole('button', { name: 'Favourite' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Add to album' })).toBeDisabled()
    expect(proxy.isFavourite).not.toHaveBeenCalled()
  })

  it('adds to an album from the picker', async () => {
    const p = photo()
    const trip = album({ name: 'Trip', photoCount: 3 })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    open([p])
    await userEvent.click(screen.getByRole('button', { name: 'Add to album' }))
    await userEvent.click(await screen.findByRole('button', { name: /Trip/ }))
    expect(proxy.addToAlbum).toHaveBeenCalledWith(trip.albumId, p.path)
    await waitFor(() => expect(screen.queryByRole('button', { name: /Trip/ })).toBeNull())
    expect(streams.albums.subs.length).toBeGreaterThan(0)
  })

  it('removes from the album it was opened in', async () => {
    const p = photo()
    open([p], { albumId: 'a-trip' })
    await userEvent.click(screen.getByRole('button', { name: 'Remove from album' }))
    expect(proxy.removeFromAlbum).toHaveBeenCalledWith('a-trip', p.contentId)
  })

  it('moves to the trash and tells the list, or renders notAPhoto by kind', async () => {
    const onTrashed = vi.fn()
    const onClose = vi.fn()
    proxy.trashPhoto.mockResolvedValueOnce({ ok: false, error: { kind: 'error_photos_not_a_photo' } })
    const p = photo()
    open([p], { onTrashed, onClose })
    await userEvent.click(screen.getByRole('button', { name: 'Move to trash' }))
    expect(await screen.findByText('That file isn’t a photo or video, or it is already in the trash.')).toBeInTheDocument()
    expect(onTrashed).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Move to trash' }))
    await waitFor(() => expect(onTrashed).toHaveBeenCalled())
    expect(proxy.trashPhoto).toHaveBeenLastCalledWith(p.path)
    expect(onClose).toHaveBeenCalledWith(0)
  })
})

describe('Viewer — video', () => {
  it('loops the MOTION clip until Play', async () => {
    proxy.motionClip.mockResolvedValue({ ok: true, bytes: new Int8Array([0, 0, 0, 0x20]) })
    const v = photo({ name: 'clip.mp4', isVideo: true, durationMs: 4000 })
    open([v])
    expect(await screen.findByTestId('motion-loop')).toHaveAttribute('loop')
    expect(proxy.motionClip).toHaveBeenCalledWith(v.path)
    expect(screen.getByRole('button', { name: 'Play' })).toBeInTheDocument()
  })
})
