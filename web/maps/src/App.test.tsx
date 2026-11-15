// The app shell and its navigation, with `<TenshuMap>` replaced by a fake that renders a button per
// pin: jsdom has no WebGL, and what is under test is the app's wiring — which pins it passes, what
// camera, and that a pin click opens its place.
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import type { TenshuMapProps } from '@tenshu/map-kit'
import i18n from './i18n'
import { placeDetail, placeRow, proxy, resetProxy, visitsOf } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const maps = vi.hoisted(() => [] as TenshuMapProps[])
vi.mock('@tenshu/map-kit', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tenshu/map-kit')>()),
  TenshuMap: (props: TenshuMapProps) => {
    maps.push(props)
    return (
      <div data-testid="fake-map">
        {props.pins?.map((pin) => (
          <button key={pin.id} type="button" onClick={() => props.onPinClick?.(pin.id)}>
            pin:{pin.label}
          </button>
        ))}
      </div>
    )
  },
}))

const { default: App } = await import('./App')

const CAFE = placeRow({ placeId: 'p-cafe', name: 'Café', latitude: 48.1, longitude: 11.5 })
const PARK = placeRow({ placeId: 'p-park', name: 'Park', latitude: 48.2, longitude: 11.7 })

beforeEach(async () => {
  // The route reads the URL on mount (web-apps-layout.md §3.4).
  window.history.replaceState(null, '', '/')
  resetProxy()
  maps.length = 0
  await i18n.changeLanguage('en')
  proxy.places.mockResolvedValue({ ok: true, places: [PARK, CAFE] })
  proxy.place.mockImplementation(async (id: string) => ({
    ok: true,
    place: placeDetail(id === 'p-cafe' ? CAFE : PARK),
  }))
  proxy.placeVisits.mockResolvedValue(visitsOf('p-cafe', 2, null))
})

describe('App', () => {
  it('renders its shell, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    // No headline, and no one-item sidebar: the apex top bar already names the app.
    expect(screen.queryByRole('heading', { name: 'Maps' })).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(await screen.findByText('Park')).toBeInTheDocument()
    expect(screen.queryByText('Tenshu is open in another tab')).toBeNull()
  })

  it('the list map has a pin per place, fits to them, and is anchored on one of them', async () => {
    renderWithQueryClient(<App />)
    await screen.findByText('Park')
    const last = maps.at(-1)!
    expect(last.pins?.map((p) => p.id)).toEqual(['p-cafe', 'p-park'])
    expect(last.camera).toEqual({
      kind: 'bounds',
      points: [
        { lat: 48.1, lng: 11.5 },
        { lat: 48.2, lng: 11.7 },
      ],
      // One cluster: its first place in list order picks the region (`lib/anchor.ts`).
      anchor: { lat: 48.1, lng: 11.5 },
    })
    expect(last.labels?.notConfigured).toBe('Map tiles aren’t set up for this Tenshu server, so no map is shown.')
  })

  // A place picks the region, never the bounding box's middle (here, the Atlantic).
  it('Berlin + New York: pins for both, and the map anchored on the more recently visited one', async () => {
    const berlin = placeRow({ placeId: 'berlin', name: 'Berlin', latitude: 52.52, longitude: 13.405 })
    const newYork = placeRow({ placeId: 'ny', name: 'New York', latitude: 40.713, longitude: -74.006 })
    proxy.places.mockResolvedValue({ ok: true, places: [berlin, newYork] })
    proxy.placeRecency.mockResolvedValue(new Map([['berlin', 1_000], ['ny', 2_000]]))
    renderWithQueryClient(<App />)
    await screen.findByText('New York')
    await waitFor(() => expect(maps.at(-1)!.camera).toMatchObject({ anchor: { lat: 40.713, lng: -74.006 } }))
    expect(maps.at(-1)!.pins?.map((p) => p.id)).toEqual(['berlin', 'ny'])
  })

  it('a search narrows the pins and the fit to its matches', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<App />)
    await user.type(await screen.findByRole('searchbox'), 'park')
    const last = maps.at(-1)!
    expect(last.pins?.map((p) => p.id)).toEqual(['p-park'])
    expect(last.camera).toEqual({ kind: 'bounds', points: [{ lat: 48.2, lng: 11.7 }], anchor: { lat: 48.2, lng: 11.7 } })
  })

  it('a pin click opens its place, whose map is centred on its one pin; Back returns to the list', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<App />)
    await user.click(await screen.findByRole('button', { name: 'pin:Café' }))

    expect(await screen.findByRole('heading', { name: 'Café' })).toBeInTheDocument()
    expect(proxy.place).toHaveBeenCalledWith('p-cafe')
    const detailMap = maps.at(-1)!
    expect(detailMap.pins?.map((p) => p.id)).toEqual(['p-cafe'])
    expect(detailMap.camera).toEqual({ kind: 'center', center: { lat: 48.1, lng: 11.5 }, zoom: 16 })
    // The list's own map stays mounted (hidden) behind the detail; the detail's is the last one.
    expect(within(screen.getAllByTestId('fake-map').at(-1)!).getAllByRole('button')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: /Back/ }))
    expect(await screen.findByRole('searchbox')).toBeInTheDocument()
  })

  // Pins that opening a place keeps the list mounted, so Back keeps the query and the scroll. ⚠ The
  // scroller is the shell's <main>, not the window: the shell is full-height, so the window never
  // scrolls and a restore through `window.scrollY` would silently put the list back at the top.
  it('Back keeps the search and puts the list back where its scroller was scrolled', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<App />)
    await user.type(await screen.findByRole('searchbox'), 'park')
    expect(within(screen.getByRole('list', { name: 'Your places' })).getAllByRole('button')).toHaveLength(1)

    const scroller = screen.getByRole('main')
    scroller.scrollTop = 1234
    await user.click(screen.getByRole('button', { name: /^Park/ }))
    expect(await screen.findByRole('heading', { name: 'Park' })).toBeInTheDocument()
    expect(screen.queryByRole('searchbox')).toBeNull() // hidden, not just covered
    expect(scroller.scrollTop).toBe(0)

    await user.click(screen.getByRole('button', { name: /Back/ }))
    expect(await screen.findByRole('searchbox')).toHaveValue('park')
    expect(within(screen.getByRole('list', { name: 'Your places' })).getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Park48.20000, 11.70000',
    ])
    expect(scroller.scrollTop).toBe(1234)
  })

  describe('routes', () => {
    it('a start fragment opens its place', async () => {
      window.history.replaceState(null, '', '/#place=p-cafe')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('heading', { name: 'Café' })).toBeInTheDocument()
      expect(proxy.place).toHaveBeenCalledWith('p-cafe')
    })

    it('opening a place puts its id in the fragment; Back clears it', async () => {
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      await user.click(await screen.findByRole('button', { name: /^Park/ }))
      expect(await screen.findByRole('heading', { name: 'Park' })).toBeInTheDocument()
      expect(window.location.pathname).toBe('/')
      expect(window.location.hash).toBe('#place=p-park')
      // ⚠⚠ Never the name, anywhere in the URL.
      expect(window.location.href).not.toMatch(/Park/)

      await user.click(screen.getByRole('button', { name: /Back/ }))
      expect(await screen.findByRole('searchbox')).toBeInTheDocument()
      expect(window.location.hash).toBe('')
    })

    it('an unknown path or fragment shows the list', async () => {
      window.history.replaceState(null, '', '/elsewhere#nonsense')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('searchbox')).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Café' })).toBeNull()
    })
  })
})
