import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { failure, placeDetail, placeRow, proxy, resetProxy, visitsOf } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { PlaceDetail } = await import('./PlaceDetail')

const HOME = placeRow({
  placeId: 'p-home',
  name: 'Apartment',
  nameIsUsers: true,
  city: 'München',
  countryCode: 'DE',
  latitude: 49.2000123,
  longitude: 11.4654321,
  role: 'home',
})

const DAY = 86_400_000

function show(place = HOME, overrides = {}) {
  proxy.place.mockResolvedValue({ ok: true, place: placeDetail(place, overrides) })
  return renderWithProviders(<PlaceDetail placeId="p-home" onBack={vi.fn()} />)
}

beforeEach(() => {
  resetProxy()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PlaceDetail', () => {
  it('shows the name, who named it, the city/country, the coordinate and the confirmed role', async () => {
    show()
    expect(await screen.findByRole('heading', { name: 'Apartment' })).toBeInTheDocument()
    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Named by you')).toBeInTheDocument()
    expect(screen.getByText('München, Germany')).toBeInTheDocument()
    expect(screen.getByText('49.20001, 11.46543')).toBeInTheDocument()
    expect(screen.getByText('Offline maps are managed in Tenshu Control on your phone or computer.')).toBeInTheDocument()
  })

  it('a stored (device-found) name says so, and no role draws no chip', async () => {
    show(placeRow({ placeId: 'p-home', name: 'Hauptbahnhof', nameIsUsers: false }))
    expect(await screen.findByText('Name found by your device')).toBeInTheDocument()
    expect(screen.queryByText('Home')).toBeNull()
    expect(screen.queryByText('Work')).toBeNull()
  })

  it('an unnamed place reads "Unnamed place" and says how to name it', async () => {
    show(placeRow({ placeId: 'p-home' }))
    expect(await screen.findByRole('heading', { name: 'Unnamed place' })).toBeInTheDocument()
    expect(screen.getByText('Rename it to give it a name.')).toBeInTheDocument()
  })

  it('visits: count and last visit, read from timeline.placeVisitSummary with the id the read returned', async () => {
    proxy.placeVisits.mockResolvedValue(visitsOf('p-survivor', 12, Date.now() - 3 * DAY))
    show(placeRow({ ...HOME, placeId: 'p-survivor' }), { redirectedFrom: 'p-home', mergedPlaceCount: 1 })
    expect(await screen.findByText('12 visits')).toBeInTheDocument()
    expect(screen.getByText('last 3 days ago')).toBeInTheDocument()
    expect(proxy.placeVisits).toHaveBeenCalledWith('p-survivor')
    expect(screen.getByText('The place you opened was merged into this one.')).toBeInTheDocument()
    expect(screen.getByText('Includes 1 merged place')).toBeInTheDocument()
  })

  it('no visits is said, whether Timeline counts 0 or has no row', async () => {
    proxy.placeVisits.mockResolvedValue(visitsOf('p-home', 0, null))
    const { unmount } = show()
    expect(await screen.findByText('No visits recorded yet.')).toBeInTheDocument()
    unmount()
    proxy.placeVisits.mockResolvedValue({ ok: true, summary: null })
    show()
    expect(await screen.findByText('No visits recorded yet.')).toBeInTheDocument()
  })

  it('a silent visits read is a retryable failure, not "no visits"', async () => {
    proxy.placeVisits.mockResolvedValueOnce(failure('error_timeline_source_silent'))
    show()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_timeline_source_silent')
    expect(screen.queryByText('No visits recorded yet.')).toBeNull()
    proxy.placeVisits.mockResolvedValue(visitsOf('p-home', 1, Date.now()))
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('1 visit')).toBeInTheDocument()
    expect(screen.getByText('last today')).toBeInTheDocument()
  })

  it('a missing place is an answer, not a failure', async () => {
    proxy.place.mockResolvedValue({ ok: true, place: null })
    renderWithProviders(<PlaceDetail placeId="gone" onBack={vi.fn()} />)
    expect(await screen.findByText('Place not found')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a failed place read renders by kind, with a retry', async () => {
    proxy.place.mockResolvedValueOnce(failure('error_timeline_source_silent'))
    renderWithProviders(<PlaceDetail placeId="p-home" onBack={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Still loading — or couldn’t load')
  })

  it('the map shows the place, unavailable without WebGL, and the detail stays usable', async () => {
    show()
    expect(await screen.findByTestId('map-unavailable')).toHaveTextContent('Map unavailable')
    expect(screen.getByRole('button', { name: 'Rename' })).toBeEnabled()
  })
})

describe('rename', () => {
  async function openRename(user: ReturnType<typeof userEvent.setup>, place = HOME, overrides = {}) {
    show(place, overrides)
    await user.click(await screen.findByRole('button', { name: 'Rename' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename place' })
    const field = within(dialog).getByLabelText('Place name')
    await user.clear(field)
    return { dialog, field }
  }

  it('asks to confirm, then renames the id the read returned and refetches the detail', async () => {
    const user = userEvent.setup()
    const { field } = await openRename(user, placeRow({ ...HOME, placeId: 'p-survivor' }), { redirectedFrom: 'p-home' })
    await user.type(field, '  Our flat ')
    await user.click(screen.getByRole('button', { name: 'Next' }))

    const confirm = await screen.findByRole('dialog', { name: 'Rename this place?' })
    expect(confirm).toHaveTextContent('“Apartment” will be called “Our flat” on all your devices.')
    expect(proxy.renamePlace).not.toHaveBeenCalled()
    const reads = proxy.place.mock.calls.length

    await user.click(within(confirm).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(proxy.renamePlace).toHaveBeenCalledWith('p-survivor', 'Our flat')
    await waitFor(() => expect(proxy.place.mock.calls.length).toBeGreaterThan(reads))
  })

  it('Back from the confirm keeps the typed name; Cancel writes nothing', async () => {
    const user = userEvent.setup()
    const { field } = await openRename(user)
    await user.type(field, 'Flat')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Back' }))
    expect(within(await screen.findByRole('dialog', { name: 'Rename place' })).getByLabelText('Place name')).toHaveValue('Flat')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(proxy.renamePlace).not.toHaveBeenCalled()
  })

  it('a blank or unchanged name cannot go on', async () => {
    const user = userEvent.setup()
    const { field } = await openRename(user)
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await user.type(field, '   ')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await user.type(field, 'Apartment')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it.each([
    ['error_timeline_blank_name', 'Enter a name.'],
    ['error_timeline_place_not_found', 'That place is no longer available.'],
    ['error_brand_new', 'Something went wrong. Please try again.'],
  ])('a typed failure (%s) renders by kind and keeps the dialog open', async (kind, copy) => {
    proxy.renamePlace.mockResolvedValueOnce(failure(kind))
    const user = userEvent.setup()
    const { field } = await openRename(user)
    await user.type(field, 'Flat')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    const confirm = await screen.findByRole('dialog', { name: 'Rename this place?' })
    await user.click(within(confirm).getByRole('button', { name: 'Rename' }))
    const alert = await within(confirm).findByRole('alert')
    expect(alert).toHaveTextContent(copy)
    expect(alert).toHaveAttribute('data-kind', kind)
    expect(alert).not.toHaveTextContent('diagnostic')
  })

  it('a rejected bridge call is its own sentence', async () => {
    proxy.renamePlace.mockRejectedValueOnce(new Error('request timed out'))
    const user = userEvent.setup()
    const { field } = await openRename(user)
    await user.type(field, 'Flat')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Rename' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Tenshu took too long to answer. Try again.')
  })
})

describe('copy coordinates', () => {
  it('writes the dot-separated coordinate to the clipboard and says so', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn(async () => {})
    show()
    await screen.findByRole('heading', { name: 'Apartment' })
    // After userEvent.setup(), which installs its own clipboard stub.
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await user.click(screen.getByRole('button', { name: 'Copy coordinates' }))
    expect(writeText).toHaveBeenCalledWith('49.20001, 11.46543')
    expect(await screen.findByText('Copied')).toBeInTheDocument()
  })

  it('a refused clipboard says so, and the coordinate stays on screen', async () => {
    const user = userEvent.setup()
    show()
    await screen.findByRole('heading', { name: 'Apartment' })
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn(async () => Promise.reject(new Error('NotAllowedError'))) },
      configurable: true,
    })
    await user.click(screen.getByRole('button', { name: 'Copy coordinates' }))
    expect(await screen.findByText(/Couldn’t copy/)).toBeInTheDocument()
    expect(screen.getByText('49.20001, 11.46543')).toBeInTheDocument()
  })

  it('no Clipboard API at all is the same failure, not a crash', async () => {
    const user = userEvent.setup()
    show()
    await screen.findByRole('heading', { name: 'Apartment' })
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    await user.click(screen.getByRole('button', { name: 'Copy coordinates' }))
    expect(await screen.findByText(/Couldn’t copy/)).toBeInTheDocument()
  })
})
