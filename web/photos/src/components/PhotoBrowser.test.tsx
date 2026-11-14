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

  it('the check circle toggles without clearing the rest', async () => {
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

