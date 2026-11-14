import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { failure, manyPlaces, placeRow, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { PlacesView, ROW_PX } = await import('./PlacesView')

const CAFE = placeRow({ placeId: 'p-cafe', name: 'Café Luitpold', nameIsUsers: true, city: 'München', countryCode: 'DE' })
const OFFICE = placeRow({ placeId: 'p-office', name: 'Office', city: 'Berlin', countryCode: 'DE', role: 'work' })
const UNNAMED = placeRow({ placeId: 'p-unnamed', latitude: 52.52, longitude: 13.405 })

function seed(places = [UNNAMED, OFFICE, CAFE]) {
  proxy.places.mockResolvedValue({ ok: true, places })
}

function rows() {
  return within(screen.getByRole('list', { name: 'Your places' }))
    .getAllByRole('button')
    .map((b) => b.textContent)
}

beforeEach(() => {
  resetProxy()
})

describe('PlacesView', () => {
  it('lists every place, named first, with city/country, role and the unnamed coordinate', async () => {
    seed()
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    expect(await screen.findByText('Café Luitpold')).toBeInTheDocument()
    expect(rows()).toEqual(['Café LuitpoldMünchen, Germany', 'OfficeBerlin, GermanyWork', 'Unnamed place52.52000, 13.40500'])
    expect(screen.getByText('Offline maps are managed in Tenshu Control on your phone or computer.')).toBeInTheDocument()
  })

  it('search folds accents and case: "cafe" and "CAFÉ" both find "Café"', async () => {
    seed()
    const user = userEvent.setup()
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    const box = await screen.findByRole('searchbox', { name: 'Search your places' })

    await user.type(box, 'cafe')
    expect(rows()).toEqual(['Café LuitpoldMünchen, Germany'])

    await user.clear(box)
    await user.type(box, 'CAFÉ')
    expect(rows()).toEqual(['Café LuitpoldMünchen, Germany'])

    await user.clear(box)
    await user.type(box, 'munchen')
    expect(rows()).toEqual(['Café LuitpoldMünchen, Germany'])
  })

  it('a query says world search is coming, and no match is said, not left blank', async () => {
    seed()
    const user = userEvent.setup()
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    await user.type(await screen.findByRole('searchbox'), 'Eiffel')
    expect(screen.getByText('None of your places match “Eiffel”.')).toBeInTheDocument()
    expect(screen.getByText(/Searching for places you haven’t been is coming/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Your places' })).toBeNull()
  })

  it('a row opens its place', async () => {
    seed()
    const onOpen = vi.fn()
    renderWithProviders(<PlacesView onOpen={onOpen} />)
    await userEvent.click(await screen.findByRole('button', { name: /Office/ }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'place', id: 'p-office' })
  })

  it('the pushed stream updates the list in place', async () => {
    seed([CAFE])
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    await screen.findByText('Café Luitpold')
    act(() => streams.places.push({ ok: true, places: [CAFE, { ...OFFICE, name: 'HQ' }] }))
    expect(await screen.findByText('HQ')).toBeInTheDocument()
  })

  it('no places is an explained empty state', async () => {
    seed([])
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    expect(await screen.findByText('No places yet')).toBeInTheDocument()
  })

  it('the map is unavailable in a browser without WebGL — the list stays usable', async () => {
    seed()
    const onOpen = vi.fn()
    renderWithProviders(<PlacesView onOpen={onOpen} />)
    // jsdom has no WebGL: map-kit's explicit state, with this app's copy.
    expect(await screen.findByTestId('map-unavailable')).toHaveTextContent('Map unavailable')
    expect(screen.getByTestId('map-unavailable')).toHaveTextContent(/WebGL/)
    await userEvent.click(screen.getByRole('button', { name: /Café Luitpold/ }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'place', id: 'p-cafe' })
  })
})

// Thousands of places.
describe('PlacesView with 5 000 places', () => {
  it('renders only a window of rows; the rest is padding, and search still covers every place', async () => {
    seed(manyPlaces(5_000))
    const user = userEvent.setup()
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    const list = await screen.findByRole('list', { name: 'Your places' })
    const items = within(list).getAllByRole('listitem')
    expect(items.length).toBeGreaterThan(0)
    expect(items.length).toBeLessThan(60)
    expect(items[0]).toHaveAttribute('aria-setsize', '5000')
    expect(items[0]).toHaveAttribute('aria-posinset', '1')
    expect(parseInt(list.style.paddingBottom, 10)).toBe((5_000 - items.length) * ROW_PX)

    // "Place 4999 Café" sorts last of the named ones — far outside the window, but search finds it.
    await user.type(screen.getByRole('searchbox'), 'place 4999')
    expect(rows()).toEqual(['Place 4999 CaféZürich, Switzerland'])
  })
  // ⚠ The app shell scrolls its <main>, never the window, and `scroll` does not bubble: a window
  // listener that does not capture never hears it, and the list stays on its first window of rows.
  it('moves its window when an ANCESTOR scrolls, not only the window', async () => {
    seed(manyPlaces(5_000))
    const { container } = renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    const list = await screen.findByRole('list', { name: 'Your places' })
    expect(within(list).getAllByRole('listitem')[0]).toHaveAttribute('aria-posinset', '1')

    // As if the shell's scroller had moved the list 1 000 rows up past the viewport's top.
    list.getBoundingClientRect = () => ({ top: -1_000 * ROW_PX }) as DOMRect
    act(() => {
      container.dispatchEvent(new Event('scroll', { bubbles: false }))
    })
    const first = Number(within(list).getAllByRole('listitem')[0]!.getAttribute('aria-posinset'))
    expect(first).toBeGreaterThan(900)
    expect(first).toBeLessThanOrEqual(1_000)
  })
})

describe('the list map anchor', () => {
  const BERLIN = placeRow({ placeId: 'berlin', name: 'Berlin', latitude: 52.52, longitude: 13.405 })
  const NEW_YORK = placeRow({ placeId: 'ny', name: 'New York', latitude: 40.713, longitude: -74.006 })

  it('two equally dense clusters: the last visit is read once, to break the tie', async () => {
    seed([BERLIN, NEW_YORK])
    proxy.placeRecency.mockResolvedValue(new Map([['berlin', 1], ['ny', 2]]))
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    await screen.findByText('Berlin')
    await waitFor(() => expect(proxy.placeRecency).toHaveBeenCalledTimes(1))
  })

  it('one cluster denser than the rest: no recency read at all', async () => {
    seed([BERLIN, { ...BERLIN, placeId: 'b2', name: 'Mitte', latitude: 52.53 }, NEW_YORK])
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    await screen.findByText('Mitte')
    expect(proxy.placeRecency).not.toHaveBeenCalled()
  })
})

describe('PlacesView failures render by kind, never as an empty list', () => {
  it('error_timeline_source_silent reads as late, with a retry that re-reads', async () => {
    proxy.places.mockResolvedValueOnce(failure('error_timeline_source_silent'))
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_timeline_source_silent')
    expect(alert).toHaveTextContent('Still loading — or couldn’t load')
    expect(alert).toHaveTextContent('Your places haven’t answered yet.')
    expect(screen.queryByText('No places yet')).toBeNull()

    seed([CAFE])
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Café Luitpold')).toBeInTheDocument()
  })

  it('an unknown kind is the generic sentence, never the raw key or message', async () => {
    proxy.places.mockResolvedValueOnce(failure('error_something_new'))
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong. Please try again.')
    expect(alert).not.toHaveTextContent('error_something_new')
    expect(alert).not.toHaveTextContent('diagnostic')
  })

  it('a refused bridge call is its own sentence', async () => {
    proxy.places.mockRejectedValueOnce(new Error('not permitted: place.list'))
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Maps is not allowed to do that.')
  })

  it('a dead stream says the list may be stale', async () => {
    seed([CAFE])
    renderWithProviders(<PlacesView onOpen={vi.fn()} />)
    await screen.findByText('Café Luitpold')
    act(() => streams.places.fail(new Error('collector died')))
    expect(await screen.findByText('Updates stopped — what you see may be out of date.')).toBeInTheDocument()
    expect(screen.getByText('Café Luitpold')).toBeInTheDocument()
  })
})
