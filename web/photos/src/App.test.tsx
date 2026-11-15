import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { album, photo, proxy, resetProxy } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

// This jsdom has no usable Storage; the Info pane's open state needs one to be observable.
const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
  clear: () => store.clear(),
})

beforeEach(async () => {
  // The section is the URL path (useAppRoute): start every test at `/`, the default section.
  window.history.replaceState(null, '', '/')
  store.clear()
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell and the four sections, with no lease banner while this tab holds the runtime', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    renderWithQueryClient(<App />)
    // No headline: the apex top bar names the app (web-apps-layout.md §3.1).
    expect(screen.queryByRole('heading', { name: 'Photos' })).toBeNull()
    expect(screen.getByRole('navigation', { name: 'Photos sections' })).toBeInTheDocument()
    expect(await screen.findByText(/No photos yet/)).toBeInTheDocument()
    for (const name of ['Library', 'Years', 'Albums', 'Favourites', 'Trash']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.queryByRole('status')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Trash' }))
    expect(await screen.findByText('The trash is empty.')).toBeInTheDocument()
  })

  it('builds none of the surfaces decided out of web v1 (photos-app.md §42.6)', () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    renderWithQueryClient(<App />)
    for (const name of [/people/i, /faces/i, /search/i, /upload/i, /places/i, /duplicates/i, /hidden/i]) {
      expect(screen.queryByRole('button', { name })).toBeNull()
    }
  })

  // web-apps-layout.md §3.4 / §5 L2: the section is the URL path; `/` is the library.
  describe('routes', () => {
    const current = () => screen.getByRole('navigation', { name: 'Photos sections' }).querySelector('[aria-current="page"]')

    it('opens the section its path names', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      window.history.replaceState(null, '', '/trash')
      renderWithQueryClient(<App />)
      expect(await screen.findByText('The trash is empty.')).toBeInTheDocument()
      expect(current()).toHaveTextContent('Trash')
    })

    it('puts the clicked section in the path, and the library at /', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      renderWithQueryClient(<App />)
      await userEvent.click(screen.getByRole('button', { name: 'Albums' }))
      expect(window.location.pathname).toBe('/albums')
      await userEvent.click(screen.getByRole('button', { name: 'Favourites' }))
      expect(window.location.pathname).toBe('/favourites')
      await userEvent.click(screen.getByRole('button', { name: 'Library' }))
      expect(window.location.pathname).toBe('/')
      expect(await screen.findByText(/No photos yet/)).toBeInTheDocument()
    })

    it('opens the library on a path that is not a section', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      window.history.replaceState(null, '', '/nonsense')
      renderWithQueryClient(<App />)
      expect(await screen.findByText(/No photos yet/)).toBeInTheDocument()
      expect(current()).toHaveTextContent('Library')
    })
  })

  // §3.5: the same feed by day, month or year — a view setting in the fragment, never the path.
  it('switches the Library density in the fragment, and opens a Years card at its year', async () => {
    const jan2025 = Date.UTC(2025, 0, 15)
    const may2026 = Date.UTC(2026, 4, 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [may2026, jan2025] })
    renderWithQueryClient(<App />)
    await userEvent.click(await screen.findByRole('radio', { name: 'Years' }))
    expect(window.location.pathname).toBe('/')
    // The new density opens where the old one was (`at=` the top index): no jump to the newest.
    expect(window.location.hash).toBe('#view=years&at=0')
    expect(screen.getByRole('radio', { name: 'Years' })).toHaveAttribute('aria-checked', 'true')
    expect(await screen.findByRole('heading', { name: '2025' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Years', pressed: undefined, current: false }))
    expect(window.location.pathname).toBe('/years')
    await userEvent.click(await screen.findByRole('button', { name: 'Show 2025' }))
    expect(window.location.pathname).toBe('/')
    expect(window.location.hash).toBe('#view=months&at=1')
  })

  // D6 (web-apps-layout.md §4.2): the viewer takes over the content — `open=<index>` in the
  // fragment; opening pushes, stepping within it replaces, closing is `back`.
  describe('the viewer in the URL', () => {
    const photos = ['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg'].map((name) => photo({ name }))
    beforeEach(() => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: photos.map((p) => p.capturedAtMs) })
      proxy.feedPage.mockResolvedValue({ ok: true, photos, nextCursor: null })
    })
    const tile = (name: string) => screen.findByRole('button', { name: new RegExp(`^Photo ${name.replace('.', '\\.')}`) })

    it('opening pushes `open=`, ←/→ and the filmstrip replace, Escape closes and the hash loses `open=`', async () => {
      // Framed: the apex is told push or replace.
      const parent = { postMessage: vi.fn() }
      const getParent = vi.spyOn(window, 'parent', 'get').mockReturnValue(parent as unknown as Window)
      const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
      try {
        window.history.replaceState(null, '', '/#view=days')
        renderWithQueryClient(<App />)
        await userEvent.dblClick(await tile('b.jpg'))
        expect(screen.getByRole('dialog', { name: 'b.jpg' })).toBeInTheDocument()
        expect(window.location.hash).toBe('#view=days&open=1')
        expect(parent.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({ hash: '#view=days&open=1', push: true }), expect.any(String))

        fireEvent.keyDown(window, { key: 'ArrowRight' })
        expect(window.location.hash).toBe('#view=days&open=2')
        expect(parent.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({ push: false }), expect.any(String))
        expect(screen.getByRole('dialog', { name: 'c.jpg' })).toBeInTheDocument()
        await userEvent.click(within(screen.getByRole('navigation', { name: 'Photos nearby' })).getByRole('button', { name: 'd.jpg' }))
        expect(window.location.hash).toBe('#view=days&open=3')
        expect(parent.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({ push: false }), expect.any(String))

        // This app pushed the entry, so its close is browser Back (the apex then routes us back).
        fireEvent.keyDown(window, { key: 'Escape' })
        expect(back).toHaveBeenCalledTimes(1)
      } finally {
        getParent.mockRestore()
        back.mockRestore()
      }
    })

    it('standalone, Escape replaces: the hash loses `open=` and keeps the rest, and focus returns to the tile', async () => {
      window.history.replaceState(null, '', '/#view=days')
      renderWithQueryClient(<App />)
      ;(await tile('c.jpg')).focus()
      await userEvent.keyboard('{Enter}')
      expect(window.location.hash).toBe('#view=days&open=2')
      fireEvent.keyDown(window, { key: 'ArrowLeft' })
      expect(screen.getByRole('dialog', { name: 'b.jpg' })).toBeInTheDocument()
      fireEvent.keyDown(window, { key: 'Escape' })
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(window.location.hash).toBe('#view=days')
      await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: /^Photo b\.jpg/ })))
    })

    it('a deep link `#open=3` opens the viewer at that photograph', async () => {
      window.history.replaceState(null, '', '/#open=3')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('dialog', { name: 'd.jpg' })).toBeInTheDocument()
      // Its Back, from a deep link, replaces — never history.back() out of the app.
      await userEvent.click(screen.getByRole('button', { name: '← Back' }))
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(window.location.hash).toBe('')
    })

    it('favourites carry `open=<index into the list>`', async () => {
      proxy.listFavourites.mockResolvedValue({ ok: true, photos: [photos[2]!, photos[0]!] })
      window.history.replaceState(null, '', '/favourites#open=1')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('dialog', { name: 'a.jpg' })).toBeInTheDocument()
    })

    it('an album is `a=<albumId>` — an opaque id, never its title — and its viewer adds `open=`', async () => {
      const trip = album({ name: 'Trip to Rome', photoCount: 2 })
      proxy.listAlbums.mockResolvedValue({ ok: true, albums: [trip] })
      proxy.albumPhotos.mockResolvedValue({ ok: true, photos: [photos[1]!, photos[3]!] })
      window.history.replaceState(null, '', '/albums')
      renderWithQueryClient(<App />)
      await userEvent.click(await screen.findByText('Trip to Rome'))
      expect(window.location.hash).toBe(`#a=${trip.albumId}`)
      await userEvent.dblClick(await tile('d.jpg'))
      expect(window.location.hash).toBe(`#a=${trip.albumId}&open=1`)
      expect(window.location.href).not.toMatch(/Rome/)
      fireEvent.keyDown(window, { key: 'Escape' })
      expect(window.location.hash).toBe(`#a=${trip.albumId}`)
      await userEvent.click(screen.getByRole('button', { name: '← Albums' }))
      expect(window.location.hash).toBe('')
      expect(await screen.findByRole('button', { name: 'New album' })).toBeInTheDocument()
    })
  })

  // L5: the Videos collection is the library's walk with kind 'videos' — pages AND times, so the
  // grid's skeleton maps its rows.
  it('Videos reads the videos feed (pages and times) and has its own empty state', async () => {
    const clip = photo({ name: 'trip.mp4', isVideo: true, durationMs: 42000 })
    proxy.captureTimes.mockImplementation(async (kind?: string) =>
      kind === 'videos' ? { ok: true, times: [clip.capturedAtMs] } : { ok: true, times: [] },
    )
    proxy.feedPage.mockImplementation(async (_c: string | null, _s: number, kind?: string) =>
      kind === 'videos' ? { ok: true, photos: [clip], nextCursor: null } : { ok: true, photos: [], nextCursor: null },
    )
    window.history.replaceState(null, '', '/videos')
    renderWithQueryClient(<App />)
    expect(await screen.findByRole('button', { name: /^Video trip\.mp4/ })).toBeInTheDocument()
    expect(proxy.captureTimes).toHaveBeenCalledWith('videos')
    expect(proxy.feedPage).toHaveBeenCalledWith(null, expect.any(Number), 'videos')
  })

  // Review finding (L5): a library refresh (a trash, a restore, Refresh) must also make the cached
  // Videos times stale — else they no longer map the Videos rows when that section reopens.
  it('a library refresh refetches the Videos times when Videos opens again', async () => {
    const clip = photo({ name: 'trip.mp4', isVideo: true })
    proxy.captureTimes.mockImplementation(async (kind?: string) =>
      kind === 'videos' ? { ok: true, times: [clip.capturedAtMs] } : { ok: true, times: [clip.capturedAtMs] },
    )
    proxy.feedPage.mockResolvedValue({ ok: true, photos: [clip], nextCursor: null })
    window.history.replaceState(null, '', '/videos')
    renderWithQueryClient(<App />)
    await screen.findByRole('button', { name: /^Video trip\.mp4/ })
    const videoReads = () => (proxy.captureTimes.mock.calls as unknown as [string?][]).filter(([kind]) => kind === 'videos').length
    expect(videoReads()).toBe(1)
    await userEvent.click(screen.getByRole('button', { name: 'Library' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Refresh' }))
    await userEvent.click(screen.getByRole('button', { name: 'Videos' }))
    await screen.findByRole('button', { name: /^Video trip\.mp4/ })
    await waitFor(() => expect(videoReads()).toBe(2))
  })

  it('an empty Videos collection says so', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    window.history.replaceState(null, '', '/videos')
    renderWithQueryClient(<App />)
    expect(await screen.findByText('No videos yet.')).toBeInTheDocument()
  })

  // L6: the Library opens at this browser's remembered density when the URL names none, and a
  // change becomes the new default — but a URL that names one wins.
  it('remembers the density per browser, and the URL wins over it', async () => {
    proxy.photoSettings.mockResolvedValue({ ok: true, density: 'years' })
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [Date.UTC(2026, 4, 3)] })
    renderWithQueryClient(<App />)
    expect(await screen.findByRole('radio', { name: 'Years', checked: true })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: 'Months' }))
    expect(proxy.setPhotoDensity).toHaveBeenCalledWith('months')
    // Months is named in the URL, so the remembered Years no longer applies.
    expect(screen.getByRole('radio', { name: 'Months' })).toHaveAttribute('aria-checked', 'true')
    // Review finding (L6): the Library nav (no `view=`) opens at the NEW default, not the cached Years.
    await userEvent.click(screen.getByRole('button', { name: 'Albums' }))
    await userEvent.click(screen.getByRole('button', { name: 'Library' }))
    expect(await screen.findByRole('radio', { name: 'Months', checked: true })).toBeInTheDocument()
  })

  it('remembers the mosaic and whole-photo switches per browser (§4.9)', async () => {
    proxy.photoSettings.mockResolvedValue({ ok: true, density: 'days', mosaic: true, fit: false })
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [Date.UTC(2026, 4, 3)] })
    renderWithQueryClient(<App />)
    expect(await screen.findByRole('button', { name: 'Mosaic', pressed: true })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Whole photos', pressed: false }))
    expect(proxy.setPhotoLayout).toHaveBeenCalledWith(true, true)
    expect(screen.getByRole('button', { name: 'Whole photos' })).toHaveAttribute('aria-pressed', 'true')
    // The remembered density is untouched by a layout switch.
    expect(screen.getByRole('radio', { name: 'Days' })).toHaveAttribute('aria-checked', 'true')
    expect(proxy.setPhotoDensity).not.toHaveBeenCalled()
  })

  it('a switch made before the remembered settings arrive is not undone when they land', async () => {
    let answer: (v: unknown) => void = () => undefined
    proxy.photoSettings.mockImplementation(() => new Promise((resolve) => (answer = resolve)))
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [Date.UTC(2026, 4, 3)] })
    renderWithQueryClient(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Mosaic', pressed: false }))
    await act(async () => answer({ ok: true, density: 'months', mosaic: false, fit: false }))
    expect(screen.getByRole('button', { name: 'Mosaic' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('remembers the size slider per browser (§4.10)', async () => {
    proxy.photoSettings.mockResolvedValue({ ok: true, density: 'days', mosaic: false, fit: false, columns: 3 })
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [Date.UTC(2026, 4, 3)] })
    renderWithQueryClient(<App />)
    const slider = await screen.findByRole('slider', { name: 'Photo size' })
    await waitFor(() => expect(slider).toHaveAttribute('aria-valuetext', '3 photos per row'))
    await userEvent.click(screen.getByRole('button', { name: 'Smaller photos' }))
    expect(proxy.setPhotoColumns).toHaveBeenCalledWith(4)
    expect(slider).toHaveAttribute('aria-valuetext', '4 photos per row')
    expect(proxy.setPhotoLayout).not.toHaveBeenCalled()
    // Mosaic draws whole three-column blocks: the label names the 3 drawn, not the 4 asked …
    await userEvent.click(screen.getByRole('button', { name: 'Mosaic', pressed: false }))
    await waitFor(() => expect(slider).toHaveAttribute('aria-valuetext', '3 photos per row'))
    // … and the switch kept the chosen size (the cached settings merge, never reset it).
    await userEvent.click(screen.getByRole('button', { name: 'Mosaic', pressed: true }))
    await waitFor(() => expect(slider).toHaveAttribute('aria-valuetext', '4 photos per row'))
  })

  it('an apex older than this app answers no switches: both are off', async () => {
    proxy.photoSettings.mockResolvedValue({ ok: true, density: 'months' })
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [Date.UTC(2026, 4, 3)] })
    renderWithQueryClient(<App />)
    expect(await screen.findByRole('button', { name: 'Mosaic', pressed: false })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Whole photos', pressed: false })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: 'Photo size' })).toHaveAttribute('aria-valuetext', '5 photos per row')
  })

  // The Info pane (web-apps-layout.md §3.2, D2 — Drive's, sticky): AppLayout's `pane`, showing the
  // ONE selected photograph, and staying as you left it per browser.
  describe('the Info pane', () => {
    const a = photo({ name: 'a.jpg' })
    const b = photo({ name: 'b.jpg' })
    beforeEach(() => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [a.capturedAtMs, b.capturedAtMs] })
      proxy.feedPage.mockResolvedValue({ ok: true, photos: [a, b], nextCursor: null })
      proxy.photoDetails.mockResolvedValue({
        ok: true,
        details: {
          capturedAtMs: a.capturedAtMs, pixelWidth: 4032, pixelHeight: 3024, sizeBytes: 2_500_000,
          cameraMake: 'Fujifilm', cameraModel: 'X100V', durationMs: null, latE7: null, lngE7: null,
        },
      })
    })
    const tileOf = (name: string) => screen.findByRole('button', { name: new RegExp(`^Photo ${name.replace('.', '\\.')}`) })
    const pane = () => screen.getByRole('complementary', { name: 'Info' })

    it('ⓘ opens it on the selected photograph; it stays open across a remount', async () => {
      const first = renderWithQueryClient(<App />)
      await userEvent.click(await tileOf('a.jpg'))
      expect(screen.queryByRole('complementary', { name: 'Info' })).toBeNull()
      await userEvent.click(screen.getByRole('button', { name: 'Info' }))
      expect(within(pane()).getByRole('heading', { name: 'a.jpg' })).toBeInTheDocument()
      // The viewer's stored details, through the same component.
      expect(await within(pane()).findByText('Fujifilm X100V')).toBeInTheDocument()
      for (const action of ['Favourite', 'Add to album', 'Download', 'Move to trash'])
        expect(within(pane()).getByRole('button', { name: action })).toBeInTheDocument()
      expect(store.get('tenshu-photos-pane')).toBe('open')

      // Several, then none: a hint.
      fireEvent.click(await tileOf('b.jpg'), { metaKey: true })
      expect(within(pane()).getByText('2 photos selected')).toBeInTheDocument()

      first.unmount()
      renderWithQueryClient(<App />)
      await tileOf('a.jpg')
      expect(within(pane()).getByText('Select a photo to see its details.')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Info' })).toHaveAttribute('aria-pressed', 'true')
    })

    it('⌘I on the grid toggles it; a Move to trash in it trashes that photograph', async () => {
      renderWithQueryClient(<App />)
      await userEvent.click(await tileOf('b.jpg'))
      await userEvent.keyboard('{Meta>}i{/Meta}')
      expect(within(pane()).getByRole('heading', { name: 'b.jpg' })).toBeInTheDocument()
      await userEvent.click(within(pane()).getByRole('button', { name: 'Move to trash' }))
      await waitFor(() => expect(proxy.trashPhoto).toHaveBeenCalledWith(b.path))
      await waitFor(() => expect(within(pane()).getByText('Select a photo to see its details.')).toBeInTheDocument())
      ;(await tileOf('a.jpg')).focus()
      await userEvent.keyboard('{Meta>}i{/Meta}')
      expect(screen.queryByRole('complementary', { name: 'Info' })).toBeNull()
    })

    it('keys inside the pane are the pane’s: ⌘A, Enter and Escape there never act on the grid', async () => {
      store.set('tenshu-photos-pane', 'open')
      proxy.listAlbums.mockResolvedValue({ ok: true, albums: [] })
      renderWithQueryClient(<App />)
      await userEvent.click(await tileOf('a.jpg'))
      within(pane()).getByRole('button', { name: 'Download' }).focus()
      await userEvent.keyboard('{Control>}a{/Control}')
      expect(screen.getByText('1 selected')).toBeInTheDocument()
      within(pane()).getByRole('button', { name: 'Add to album' }).focus()
      await userEvent.keyboard('{Enter}')
      expect(await screen.findByRole('dialog', { name: 'Add to album' })).toBeInTheDocument()
      expect(screen.queryByRole('dialog', { name: 'a.jpg' })).toBeNull()
      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      // Escape in the pane closes the pane (AppLayout) — the selection stays.
      within(pane()).getByRole('button', { name: 'Download' }).focus()
      await userEvent.keyboard('{Escape}')
      expect(screen.queryByRole('complementary', { name: 'Info' })).toBeNull()
      expect(screen.getByText('1 selected')).toBeInTheDocument()
    })
  })

  // L7: search by name or by a date the field understood; the query is `q=` in the FRAGMENT.
  describe('search', () => {
    it('typing searches by name, and the query stays out of the path', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      const hit = photo({ name: 'beach.jpg' })
      proxy.searchPhotos.mockResolvedValue({ ok: true, photos: [hit], truncated: false })
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      await user.type(screen.getByRole('searchbox', { name: 'Search photos' }), 'beach{Enter}')
      expect(window.location.pathname).toBe('/search')
      expect(window.location.hash).toBe('#q=beach')
      expect(await screen.findByText('1 photo matches “beach”')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /^Photo beach\.jpg/ })).toBeInTheDocument()
      expect(proxy.searchPhotos).toHaveBeenLastCalledWith('beach', null, null)
      // The field kept focus through the commit (it never remounts).
      expect(screen.getByRole('searchbox', { name: 'Search photos' })).toHaveFocus()
    })

    it('deleting the text again closes the search (back to the Library), and the field keeps focus', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      proxy.searchPhotos.mockResolvedValue({ ok: true, photos: [], truncated: false })
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      const box = screen.getByRole('searchbox', { name: 'Search photos' })
      await user.type(box, 'be')
      await waitFor(() => expect(window.location.pathname).toBe('/search'))
      await user.type(box, '{Backspace}{Backspace}')
      await waitFor(() => expect(window.location.pathname).toBe('/'))
      expect(window.location.hash).toBe('')
      expect(box).toHaveValue('')
      expect(box).toHaveFocus()
    })

    it('Escape in a deep-linked search closes it too', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      window.history.replaceState(null, '', '/search#q=beach')
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      const box = screen.getByRole('searchbox', { name: 'Search photos' })
      await waitFor(() => expect(box).toHaveValue('beach'))
      await user.click(box)
      await user.keyboard('{Escape}')
      expect(window.location.pathname).toBe('/')
    })

    it('a date the field understands searches a local range', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      window.history.replaceState(null, '', '/search#q=May%202025')
      renderWithQueryClient(<App />)
      expect(await screen.findByText('No photos from May 2025')).toBeInTheDocument()
      expect(proxy.searchPhotos).toHaveBeenCalledWith(null, new Date(2025, 4, 1).getTime(), new Date(2025, 5, 1).getTime() - 1)
      expect(screen.getByRole('searchbox', { name: 'Search photos' })).toHaveValue('May 2025')
    })

    // Review finding (L7): a search still waiting to fire is cancelled by a move elsewhere.
    it('a pending search does not fire after the user moves on', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      await user.type(screen.getByRole('searchbox', { name: 'Search photos' }), 'b')
      await user.click(screen.getByRole('button', { name: 'Years' }))
      await new Promise((resolve) => setTimeout(resolve, 400))
      expect(window.location.pathname).toBe('/years')
      expect(window.location.hash).toBe('')
      expect(proxy.searchPhotos).not.toHaveBeenCalled()
    })

    it('search results have the Info pane like any grid', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      proxy.searchPhotos.mockResolvedValue({ ok: true, photos: [photo({ name: 'beach.jpg' })], truncated: false })
      window.history.replaceState(null, '', '/search#q=beach')
      renderWithQueryClient(<App />)
      await userEvent.click(await screen.findByRole('button', { name: /^Photo beach\.jpg/ }))
      await userEvent.click(screen.getByRole('button', { name: 'Info' }))
      expect(screen.getByRole('complementary', { name: 'Info' })).toHaveTextContent('beach.jpg')
    })

    it('a truncated result says so', async () => {
      proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
      proxy.searchPhotos.mockResolvedValue({ ok: true, photos: [photo({ name: 'a.jpg' })], truncated: true })
      window.history.replaceState(null, '', '/search#q=a')
      renderWithQueryClient(<App />)
      expect(await screen.findByText(/newest 500/)).toBeInTheDocument()
    })
  })

  it('speaks German', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('navigation', { name: 'Fotos-Bereiche' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Papierkorb' })).toBeInTheDocument()
  })
})
