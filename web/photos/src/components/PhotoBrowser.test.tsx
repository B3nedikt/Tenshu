// Drive's desktop interaction model on Photos' grids (web-apps-layout.md §3.2/§3.3, L4): pointer
// rules, keys on the focused tile, and the item menu.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Photo } from '@tenshu/sdk/photos'
import { album, photo, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { FeedView } = await import('./FeedView')
const { FavouritesView } = await import('./FavouritesView')

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

function feedOf(photos: Photo[]) {
  proxy.captureTimes.mockResolvedValue({ ok: true, times: photos.map((p) => p.capturedAtMs) })
  proxy.feedPage.mockResolvedValue({ ok: true, photos, nextCursor: null })
  return renderWithProviders(<FeedView />)
}

const tile = (name: string) => screen.getByRole('button', { name: new RegExp(`^(Photo|Video) ${name.replace('.', '\\.')}`) })
const findTile = (name: string) => screen.findByRole('button', { name: new RegExp(`^(Photo|Video) ${name.replace('.', '\\.')}`) })
// `hidden`: an open Radix menu marks the rest of the page aria-hidden.
const pressed = (name: string) =>
  screen
    .getByRole('button', { name: new RegExp(`^(Photo|Video) ${name.replace('.', '\\.')}`), hidden: true })
    .getAttribute('aria-pressed') === 'true'

describe('pointer rules', () => {
  const names = ['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg']
  let photos: Photo[]
  beforeEach(() => {
    photos = names.map((name) => photo({ name }))
  })

  it('a click selects one and does not open the viewer', async () => {
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    expect(pressed('a.jpg')).toBe(true)
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    // Another click moves the selection; it never adds.
    await userEvent.click(tile('b.jpg'))
    expect(pressed('a.jpg')).toBe(false)
    expect(pressed('b.jpg')).toBe(true)
  })

  it('a double-click opens the viewer', async () => {
    feedOf(photos)
    await userEvent.dblClick(await findTile('c.jpg'))
    expect(screen.getByRole('dialog', { name: 'c.jpg' })).toBeInTheDocument()
  })

  it('⌘/Ctrl-click toggles; Shift-click extends a range in grid order', async () => {
    feedOf(photos)
    await userEvent.click(await findTile('b.jpg'))
    fireEvent.click(tile('d.jpg'), { shiftKey: true })
    expect(names.map(pressed)).toEqual([false, true, true, true])
    fireEvent.click(tile('c.jpg'), { ctrlKey: true })
    expect(names.map(pressed)).toEqual([false, true, false, true])
    fireEvent.click(tile('a.jpg'), { metaKey: true })
    expect(screen.getByText('3 selected')).toBeInTheDocument()
    // The anchor moved to a.jpg: a range from it upward of nothing, down to c.
    fireEvent.click(tile('c.jpg'), { shiftKey: true })
    expect(names.map(pressed)).toEqual([true, true, true, false])
  })

  it('a Shift-click from an anchor that is no longer in the list is a plain click', async () => {
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(<FavouritesView />)
    await userEvent.click(await findTile('a.jpg'))
    // a.jpg was unfavourited elsewhere: index 0 is now b.jpg.
    act(() => streams.favourites.push({ ok: true, photos: photos.slice(1) }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Photo a\.jpg/ })).toBeNull())
    expect(screen.queryByText(/selected/)).toBeNull()
    fireEvent.click(tile('c.jpg'), { shiftKey: true })
    expect(['b.jpg', 'c.jpg', 'd.jpg'].map(pressed)).toEqual([false, true, false])
  })

  it('a click rings ONE tile and nothing else: no check circles, no selecting state on the rest', async () => {
    feedOf(photos)
    const circles = () => screen.getAllByTestId('check-circle').map((el) => el.className)
    await findTile('a.jpg')
    const before = circles()
    await userEvent.click(tile('a.jpg'))
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    // The ring is the selected tile's alone.
    expect(screen.getAllByTestId('selected-ring')).toHaveLength(1)
    expect(tile('a.jpg').parentElement).toContainElement(screen.getByTestId('selected-ring'))
    expect(tile('b.jpg').closest('[data-tile]')).not.toHaveAttribute('data-selected')
    // The circles are touch-only (display:none unless `pointer: coarse`), and a selection changes
    // nothing about them on any tile — there is no grid-wide mode.
    expect(circles()).toEqual(before)
    for (const className of circles()) {
      expect(className.split(' ')).toContain('hidden')
      expect(className.split(' ')).toContain('pointer-coarse:flex')
    }
  })

  it('the (touch) check circle toggles without clearing the rest', async () => {
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select c.jpg' }))
    expect(names.map(pressed)).toEqual([true, false, true, false])
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select a.jpg' }))
    expect(names.map(pressed)).toEqual([false, false, true, false])
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('keys on the grid', () => {
  it('Escape clears the selection; ⌘A selects every loaded photograph', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' }), photo({ name: 'c.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    await userEvent.keyboard('{Control>}a{/Control}')
    expect(screen.getByText('3 selected')).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByText(/selected/)).toBeNull()
  })

  it('Enter and Space open the FOCUSED tile, not the selection', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    tile('b.jpg').focus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('dialog', { name: 'b.jpg' })).toBeInTheDocument()
    // Space again is Quick Look's close.
    await userEvent.keyboard(' ')
    expect(screen.queryByRole('dialog')).toBeNull()
    tile('a.jpg').focus()
    await userEvent.keyboard(' ')
    expect(screen.getByRole('dialog', { name: 'a.jpg' })).toBeInTheDocument()
  })

  it('keys on the toolbar are the toolbar’s: Escape and Enter there leave the selection alone', async () => {
    const photos = [photo({ name: 'a.jpg' })]
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [] })
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    screen.getByRole('button', { name: 'Refresh' }).focus()
    await userEvent.keyboard('{Escape}')
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    screen.getByRole('button', { name: 'Add to album' }).focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByRole('dialog', { name: 'Add to album' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'a.jpg' })).toBeNull()
  })
})

describe('the item menu', () => {
  it('right-click shows the native sheet’s entries and selects the photo it points at', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    fireEvent.contextMenu(tile('b.jpg'))
    expect(pressed('b.jpg')).toBe(true)
    expect(pressed('a.jpg')).toBe(false)
    const menu = await screen.findByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((el) => el.textContent?.replace('↵', ''))).toEqual([
      'Open',
      'Download',
      'Favourite',
      'Add to album…',
      'Move to trash',
    ])
    // No Hide on web (photos-app.md §42.6).
    expect(within(menu).queryByRole('menuitem', { name: /hide/i })).toBeNull()
  })

  it('right-click on a photo in a selected set keeps the set', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    fireEvent.click(tile('b.jpg'), { metaKey: true })
    fireEvent.contextMenu(tile('a.jpg'))
    expect(await screen.findByRole('menu')).toBeInTheDocument()
    expect(screen.getByText('2 selected')).toBeInTheDocument()
  })

  it('keys in the menu are the menu’s: Escape keeps the selection, Enter on "Move to trash" trashes and opens nothing', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    fireEvent.click(tile('b.jpg'), { metaKey: true })
    fireEvent.contextMenu(tile('b.jpg'))
    await screen.findByRole('menu')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(screen.getByText('2 selected')).toBeInTheDocument()

    fireEvent.contextMenu(tile('b.jpg'))
    const trash = await screen.findByRole('menuitem', { name: 'Move to trash' })
    trash.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(proxy.trashPhoto).toHaveBeenCalledWith(photos[1]!.path))
    expect(proxy.trashPhoto).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('the ⋯ on a hovered tile opens the same entries', async () => {
    feedOf([photo({ name: 'a.jpg' })])
    await userEvent.hover(await findTile('a.jpg'))
    await userEvent.click(screen.getByRole('button', { name: 'Actions for a.jpg' }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((el) => el.textContent)).toEqual(['Open', 'Download', 'Favourite', 'Add to album…', 'Move to trash'])
    await userEvent.click(screen.getByRole('menuitem', { name: 'Open' }))
    expect(screen.getByRole('dialog', { name: 'a.jpg' })).toBeInTheDocument()
  })

  it('Favourite follows the favourites stream, and writes by content id', async () => {
    const fav = photo({ name: 'fav.jpg' })
    const other = photo({ name: 'other.jpg' })
    proxy.listFavourites.mockResolvedValue({ ok: true, photos: [fav] })
    feedOf([fav, other])
    await findTile('fav.jpg')
    fireEvent.contextMenu(tile('fav.jpg'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Unfavourite' }))
    expect(proxy.setFavourite).toHaveBeenCalledWith(fav.contentId, false)
    fireEvent.contextMenu(tile('other.jpg'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Favourite' }))
    expect(proxy.setFavourite).toHaveBeenLastCalledWith(other.contentId, true)
  })

  it('Add to album… opens the picker and files the photo', async () => {
    const p = photo({ name: 'a.jpg' })
    const trip = album({ name: 'Trip' })
    proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
    feedOf([p])
    fireEvent.contextMenu(await findTile('a.jpg'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add to album…' }))
    await userEvent.click(await screen.findByRole('button', { name: /Trip/ }))
    expect(proxy.addToAlbum).toHaveBeenCalledWith(trip.albumId, p.path)
  })

  it('a failed trash renders its kind; a file with no content id offers no filing entries', async () => {
    proxy.trashPhoto.mockResolvedValue({ ok: false, error: { kind: 'error_photos_not_a_photo' } })
    feedOf([photo({ name: 'old.jpg', contentId: '' })])
    fireEvent.contextMenu(await findTile('old.jpg'))
    expect(screen.queryByRole('menuitem', { name: 'Favourite' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Add to album…' })).toBeNull()
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move to trash' }))
    expect(await screen.findByText('That file isn’t a photo or video, or it is already in the trash.')).toBeInTheDocument()
  })

  it('Download saves the rendition of an edited photo, never its original', async () => {
    proxy.openContentStream.mockResolvedValue({ ok: true, handle: 'h', totalSize: 1 })
    proxy.readContentRange.mockResolvedValue({ ok: true, bytes: new Int8Array([1]) })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      feedOf([photo({ name: 'e.jpg', path: '/Photos/e.jpg', renditionPath: '/Trash/.edits/e-1.jpg' })])
      fireEvent.contextMenu(await findTile('e.jpg'))
      await userEvent.click(await screen.findByRole('menuitem', { name: 'Download' }))
      await waitFor(() => expect(click).toHaveBeenCalled())
      expect(proxy.openContentStream.mock.calls.map((c) => c[0])).toEqual(['/Trash/.edits/e-1.jpg'])
    } finally {
      click.mockRestore()
    }
  })
})

// The marquee (rubber band), Finder / iCloud Photos. jsdom has no layout: the grid falls back to a
// 960 × 800 box, so at the list's 160 px target there are 6 columns of 156 px cells on a 160 px
// pitch, rows at y 0 / 160 (no headers in a list). The scroller's rect is pinned at the origin.
describe('marquee selection', () => {
  const names = ['m0.jpg', 'm1.jpg', 'm2.jpg', 'm3.jpg', 'm4.jpg', 'm5.jpg', 'm6.jpg', 'm7.jpg']
  async function list() {
    const photos = names.map((name) => photo({ name }))
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(<FavouritesView />)
    await findTile('m0.jpg')
    const scroller = screen.getByTestId('grid-scroller')
    vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 960, bottom: 800, width: 960, height: 800, x: 0, y: 0, toJSON: () => ({}),
    } as DOMRect)
    return scroller
  }
  const at = (x: number, y: number, extra: Record<string, unknown> = {}) => ({ clientX: x, clientY: y, pointerId: 1, button: 0, isPrimary: true, ...extra })
  function drag(scroller: HTMLElement, from: [number, number], to: [number, number], extra: Record<string, unknown> = {}) {
    fireEvent.pointerDown(scroller, at(...from, extra))
    fireEvent.pointerMove(scroller, at((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, extra))
    fireEvent.pointerMove(scroller, at(...to, extra))
    fireEvent.pointerUp(scroller, at(...to, extra))
  }

  it('a drag across empty space selects every tile the band intersects, live', async () => {
    const scroller = await list()
    // From the empty space right of m7 (row 1) up-left into row 0: x 170–900, y 100–300.
    fireEvent.pointerDown(scroller, at(900, 300))
    fireEvent.pointerMove(scroller, at(170, 100))
    expect(screen.getByTestId('marquee')).toBeInTheDocument()
    expect(names.map(pressed)).toEqual([false, true, true, true, true, true, false, true])
    expect(screen.getByText('6 selected')).toBeInTheDocument()
    fireEvent.pointerUp(scroller, at(170, 100))
    expect(screen.queryByTestId('marquee')).toBeNull()
    expect(screen.getByText('6 selected')).toBeInTheDocument()
  })

  it('a plain drag replaces the selection; ⌘ or Shift at the press adds to it', async () => {
    const scroller = await list()
    await userEvent.click(tile('m0.jpg'))
    drag(scroller, [900, 300], [170, 250], { metaKey: true })
    expect(names.map(pressed)).toEqual([true, false, false, false, false, false, false, true])
    drag(scroller, [900, 300], [170, 250], { shiftKey: true })
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    drag(scroller, [900, 300], [170, 250])
    expect(names.map(pressed)).toEqual([false, false, false, false, false, false, false, true])
  })

  it('a click on empty space (no drag) clears the selection; a press on a tile never bands', async () => {
    const scroller = await list()
    await userEvent.click(tile('m0.jpg'))
    fireEvent.click(tile('m1.jpg'), { metaKey: true })
    // A press that starts on a tile is the tile's (click / drag of the photo), not a marquee.
    fireEvent.pointerDown(tile('m2.jpg'), at(330, 50))
    fireEvent.pointerMove(scroller, at(900, 300))
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(scroller, at(900, 300))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    // Under the 4 px threshold: a click.
    fireEvent.pointerDown(scroller, at(900, 300))
    fireEvent.pointerMove(scroller, at(902, 301))
    fireEvent.pointerUp(scroller, at(902, 301))
    expect(screen.queryByText(/selected/)).toBeNull()
  })

  // Review findings (selection model).
  it('the band stays inside the content, however far the pointer goes', async () => {
    const scroller = await list()
    fireEvent.pointerDown(scroller, at(900, 300))
    fireEvent.pointerMove(scroller, at(5000, -400)) // onto the Info pane, above the grid
    const band = screen.getByTestId('marquee')
    expect(parseFloat(band.style.left) + parseFloat(band.style.width)).toBeLessThanOrEqual(960)
    expect(parseFloat(band.style.top)).toBeGreaterThanOrEqual(0)
    fireEvent.pointerUp(scroller, at(5000, -400))
  })

  it('a press on empty space gives the grid focus, so its keys work after a band', async () => {
    const scroller = await list()
    drag(scroller, [900, 300], [170, 100])
    expect(document.activeElement).toBe(scroller)
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByText(/selected/)).toBeNull()
  })

  it('a Shift-click after a band extends from the band (its first photo is the anchor)', async () => {
    const scroller = await list()
    drag(scroller, [900, 300], [170, 250]) // m7 only (row 1, x ≥ 170)
    fireEvent.click(tile('m4.jpg'), { shiftKey: true })
    expect(names.map(pressed)).toEqual([false, false, false, false, true, true, true, true])
  })

  it('a band can start in the content pane around the grid (its padding), as in iCloud Photos', async () => {
    const photos = names.map((name) => photo({ name }))
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(
      <main>
        <div data-testid="pane-padding">Header text</div>
        <FavouritesView />
      </main>,
    )
    await findTile('m0.jpg')
    const scroller = screen.getByTestId('grid-scroller')
    vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 960, bottom: 800, width: 960, height: 800, x: 0, y: 0, toJSON: () => ({}),
    } as DOMRect)
    // Left of the grid, beside row 1: pulled onto the grid's left edge (x 0), then up into row 0.
    const press = fireEvent.pointerDown(screen.getByTestId('pane-padding'), at(-20, 300))
    expect(press).toBe(false) // default prevented: no text selection from the padding
    fireEvent.pointerMove(scroller, at(170, 100))
    fireEvent.pointerUp(scroller, at(170, 100))
    expect(names.map(pressed)).toEqual([true, true, false, false, false, false, true, true])
    expect(document.activeElement).toBe(scroller)
  })

  it('a press above the scrolled grid, in the pane: a jitter is no drag and nothing scrolls away', async () => {
    const photos = names.map((name) => photo({ name }))
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(
      <main>
        <div data-testid="pane-gap">gap</div>
        <FavouritesView />
      </main>,
    )
    await findTile('m0.jpg')
    const scroller = screen.getByTestId('grid-scroller')
    vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 100, right: 960, bottom: 900, width: 960, height: 800, x: 0, y: 100, toJSON: () => ({}),
    } as DOMRect)
    await userEvent.click(tile('m0.jpg'))
    // Scrolled down: at the very top the content clamp would hide a pull above the grid.
    scroller.scrollTop = 150
    fireEvent.pointerDown(screen.getByTestId('pane-gap'), at(500, 90))
    fireEvent.pointerMove(scroller, at(501, 80)) // further above: still pinned to the grid's top
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(scroller, at(501, 80))
    // A click on empty space: the selection clears, as a click on the grid's own empty space does.
    expect(screen.queryByText(/selected/)).toBeNull()
    // Down into the grid it is a band from the grid's top edge (content y 150, row 0's bottom edge)
    // to y 250, x 170–500: row 0's m1–m3 and row 1's m7.
    fireEvent.pointerDown(screen.getByTestId('pane-gap'), at(500, 90))
    fireEvent.pointerMove(scroller, at(170, 200))
    expect(screen.getByTestId('marquee')).toBeInTheDocument()
    fireEvent.pointerUp(scroller, at(170, 200))
    expect(names.map(pressed)).toEqual([false, true, true, true, false, false, false, true])
  })

  it('a press on another scrollbar in the pane (the toolbar’s) scrolls it; it is no band', async () => {
    const photos = names.map((name) => photo({ name }))
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(
      <main>
        <div data-testid="scrolling-row">wide</div>
        <FavouritesView />
      </main>,
    )
    await findTile('m0.jpg')
    const row = screen.getByTestId('scrolling-row')
    // 300 × 40 with a 10 px horizontal scrollbar under its 30 px content box.
    vi.spyOn(row, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, right: 300, bottom: 40, width: 300, height: 40, x: 0, y: 0, toJSON: () => ({}) } as DOMRect)
    Object.defineProperties(row, { scrollWidth: { value: 900 }, clientWidth: { value: 300 }, clientHeight: { value: 30 }, scrollHeight: { value: 30 } })
    expect(fireEvent.pointerDown(row, at(150, 35))).toBe(true)
    fireEvent.pointerMove(screen.getByTestId('grid-scroller'), at(170, 300))
    expect(screen.queryByTestId('marquee')).toBeNull()
  })

  it('a press in the pane on a control is the control’s, not a band', async () => {
    const photos = names.map((name) => photo({ name }))
    proxy.listFavourites.mockResolvedValue({ ok: true, photos })
    renderWithProviders(
      <main>
        <button type="button">Elsewhere</button>
        <FavouritesView />
      </main>,
    )
    await findTile('m0.jpg')
    const scroller = screen.getByTestId('grid-scroller')
    expect(fireEvent.pointerDown(screen.getByRole('button', { name: 'Elsewhere' }), at(-20, 300))).toBe(true)
    fireEvent.pointerMove(scroller, at(170, 100))
    expect(screen.queryByTestId('marquee')).toBeNull()
  })

  it('ignores a secondary button', async () => {
    const scroller = await list()
    await userEvent.click(tile('m0.jpg'))
    drag(scroller, [900, 300], [170, 100], { button: 2 })
    expect(screen.getByText('1 selected')).toBeInTheDocument()
  })
})

describe('the selection’s toolbar actions', () => {
  it('Move to trash trashes every selected photograph and empties the selection', async () => {
    const photos = [photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' }), photo({ name: 'c.jpg' })]
    feedOf(photos)
    await userEvent.click(await findTile('a.jpg'))
    fireEvent.click(tile('c.jpg'), { metaKey: true })
    await userEvent.click(within(screen.getByRole('group', { name: 'Selection' })).getByRole('button', { name: 'Move to trash' }))
    await waitFor(() => expect(proxy.trashPhoto).toHaveBeenCalledTimes(2))
    expect(proxy.trashPhoto.mock.calls.map((c) => c[0])).toEqual([photos[0]!.path, photos[2]!.path])
    await waitFor(() => expect(screen.queryByText(/selected/)).toBeNull())
  })

  it('the count and actions sit in the toolbar row that was already there (nothing shifts)', async () => {
    feedOf([photo({ name: 'a.jpg' })])
    await findTile('a.jpg')
    const row = screen.getByRole('button', { name: 'Refresh' }).parentElement!.parentElement!
    const gridTop = screen.getByTestId('grid-scroller').parentElement!.previousElementSibling
    await userEvent.click(tile('a.jpg'))
    const group = screen.getByRole('group', { name: 'Selection' })
    expect(row).toContainElement(group)
    expect(group).toHaveTextContent('1 selected')
    // The grid's previous sibling is still that one row: no bar was inserted above the grid.
    expect(screen.getByTestId('grid-scroller').parentElement!.previousElementSibling).toBe(gridTop)
  })
})

// Review findings (L4).
describe('review fixes', () => {
  it('Open from the menu leaves focus INSIDE the viewer (the menu hands focus back late)', async () => {
    feedOf([photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })])
    fireEvent.contextMenu(await findTile('a.jpg'))
    await userEvent.click(await screen.findByRole('menuitem', { name: /^Open/ }))
    const dialog = await screen.findByRole('dialog')
    // After Radix's own setTimeout(0) focus return has run.
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  })

  it('a photo trashed from the viewer leaves the selection', async () => {
    feedOf([photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })])
    await userEvent.dblClick(await findTile('a.jpg'))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to trash' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.queryByText('1 selected')).toBeNull()
  })

  it('a list that empties under the viewer closes it (open= does not outlive the list)', async () => {
    const close = vi.fn()
    const viewer = { index: 0, open: vi.fn(), move: vi.fn(), close }
    const only = photo({ name: 'a.jpg' })
    proxy.listFavourites.mockResolvedValue({ ok: true, photos: [only] })
    renderWithProviders(<FavouritesView viewer={viewer} />)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(close).not.toHaveBeenCalled()
    act(() => streams.favourites.push({ ok: true, photos: [] }))
    await waitFor(() => expect(close).toHaveBeenCalled())
  })
})

// With the circles touch-only, Shift+Space is how a keyboard picks several.
describe('keyboard multi-select', () => {
  it('Shift+Space toggles the focused photo without opening the viewer', async () => {
    feedOf([photo({ name: 'a.jpg' }), photo({ name: 'b.jpg' })])
    await userEvent.click(await findTile('a.jpg'))
    tile('b.jpg').focus()
    await userEvent.keyboard('{Shift>} {/Shift}')
    expect(pressed('a.jpg')).toBe(true)
    expect(pressed('b.jpg')).toBe(true)
    expect(screen.queryByRole('dialog')).toBeNull()
    await userEvent.keyboard('{Shift>} {/Shift}')
    expect(pressed('b.jpg')).toBe(false)
  })
})

