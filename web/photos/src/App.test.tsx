import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { album, photo, proxy, resetProxy } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  // The section is the URL path (useAppRoute): start every test at `/`, the default section.
  window.history.replaceState(null, '', '/')
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
    expect(window.location.hash).toBe('#at=1')
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

  it('speaks German', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('navigation', { name: 'Fotos-Bereiche' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Papierkorb' })).toBeInTheDocument()
  })
})
