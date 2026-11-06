import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { TimelineFeedResult } from '@tenshu/sdk/timeline'
import { HomeView } from './HomeView'
import { activity, day, failure, movement, proxy, resetProxy, streams, suggestionOf, visit } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const H = 3_600_000
const JUN_8 = Date.UTC(2026, 5, 8, 9)
const JUN_7 = Date.UTC(2026, 5, 7, 9)

function feedOf(result: Partial<Extract<TimelineFeedResult, { ok: true }>> = {}) {
  proxy.feed.mockResolvedValue({ ok: true, days: [], trips: [], suggestion: null, ...result })
}

function rowTitles(section: HTMLElement): string[] {
  return within(section)
    .getAllByRole('button')
    .map((b) => b.querySelector('span.font-medium')?.textContent ?? '')
}

describe('HomeView — the day list', () => {
  it('renders days and their rows exactly as delivered, with place names and activity kinds', async () => {
    feedOf({
      days: [
        day('2026-06-08', visit('a', JUN_8, { id: 'home', name: 'Ring 12', role: 'home' }), movement('b', JUN_8 + H, 'walk', 2449), activity('c', JUN_8 + 2 * H, 'Running')),
        day('2026-06-07', visit('d', JUN_7, { id: 'cafe', name: 'Café Luitpold', city: 'Munich' })),
      ],
    })
    renderWithProviders(<HomeView onOpen={() => {}} />)

    const days = await screen.findAllByRole('region')
    expect(days.map((d) => d.getAttribute('aria-label'))).toEqual(['Monday, June 8, 2026', 'Sunday, June 7, 2026'])
    expect(rowTitles(days[0]!)).toEqual(['Home', 'Walk', 'Running'])
    expect(within(days[0]!).getByText('2.4 km · 20 min')).toBeInTheDocument()
    expect(rowTitles(days[1]!)).toEqual(['Café Luitpold'])
    expect(within(days[1]!).getByText('Munich')).toBeInTheDocument()
  })

  // ⚠ The SDK decides how rows fold (run A: unfolded; the projection module: folded). Either
  // arrives as rows, and the list renders the rows it got — it never merges or re-sorts them.
  it('renders an unfolded split stay as two rows and a folded one as one, in delivered order', async () => {
    const cafe = { id: 'cafe', name: 'Café' }
    feedOf({ days: [day('2026-06-08', visit('late', JUN_8 + 2 * H, cafe, 1800), visit('early', JUN_8, cafe, 1800))] })
    const { unmount } = renderWithProviders(<HomeView onOpen={() => {}} />)
    const [unfolded] = await screen.findAllByRole('region')
    expect(within(unfolded!).getAllByRole('button')).toHaveLength(2)
    expect(within(unfolded!).getAllByRole('button')[0]).toHaveTextContent('30 min')
    unmount()

    feedOf({ days: [day('2026-06-08', visit('merged', JUN_8, cafe, 3 * 3600))] })
    renderWithProviders(<HomeView onOpen={() => {}} />)
    const [folded] = await screen.findAllByRole('region')
    expect(within(folded!).getAllByRole('button')).toHaveLength(1)
    expect(within(folded!).getByText('3 h')).toBeInTheDocument()
  })

  it('shows an unnamed place as "Unnamed place" with its coordinate', async () => {
    feedOf({ days: [day('2026-06-08', visit('a', JUN_8, { id: 'p', name: '', latE7: 481_374_300, lngE7: 115_754_900 }))] })
    renderWithProviders(<HomeView onOpen={() => {}} />)
    expect(await screen.findByText('Unnamed place')).toBeInTheDocument()
    expect(screen.getByText('48.13743, 11.57549')).toBeInTheDocument()
  })

  it('formats distances in the units it is given', async () => {
    feedOf({ days: [day('2026-06-08', movement('b', JUN_8, 'cycle', 2414, 600))] })
    renderWithProviders(<HomeView onOpen={() => {}} />, { units: 'imperial' })
    expect(await screen.findByText('1.5 mi · 10 min')).toBeInTheDocument()
  })

  it('opens a moment, and a bound trip', async () => {
    const onOpen = vi.fn()
    feedOf({
      days: [day('2026-06-08', visit('a', JUN_8, { id: 'p', name: 'Café' }))],
      trips: [{ tripId: 't1', title: 'Lisbon', dateRange: 'Jun 3 – Jun 7', year: '2026', momentIds: [] }],
    })
    renderWithProviders(<HomeView onOpen={onOpen} />)
    await userEvent.click(await screen.findByRole('button', { name: /Café/ }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'moment', id: 'a' })
    await userEvent.click(screen.getByRole('button', { name: /Lisbon/ }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'trip', id: 't1' })
  })

  it('says the timeline is empty only when the read succeeded with nothing', async () => {
    feedOf()
    renderWithProviders(<HomeView onOpen={() => {}} />)
    expect(await screen.findByText('Nothing on your timeline yet')).toBeInTheDocument()
  })

  it('takes the pushed snapshot', async () => {
    feedOf()
    renderWithProviders(<HomeView onOpen={() => {}} />)
    await screen.findByText('Nothing on your timeline yet')
    streams.feed.push({ ok: true, days: [day('2026-06-08', visit('a', JUN_8, { id: 'p', name: 'Pushed' }))], trips: [], suggestion: null })
    expect(await screen.findByText('Pushed')).toBeInTheDocument()
  })
})

describe('HomeView — failures are never an empty feed', () => {
  // ⚠⚠ Run A's bounded wait: a relation that never answered. It is late, not gone.
  it('renders error_timeline_source_silent as still loading / couldn’t load, with a retry that reads again', async () => {
    proxy.feed.mockResolvedValueOnce(failure('error_timeline_source_silent'))
    renderWithProviders(<HomeView onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_timeline_source_silent')
    expect(within(alert).getByText('Still loading — or couldn’t load')).toBeInTheDocument()
    expect(within(alert).getByText(/hasn’t answered yet/)).toBeInTheDocument()
    expect(screen.queryByText('Nothing on your timeline yet')).toBeNull()

    proxy.feed.mockResolvedValueOnce({ ok: true, days: [day('2026-06-08', visit('a', JUN_8, { id: 'p', name: 'Arrived' }))], trips: [], suggestion: null })
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Arrived')).toBeInTheDocument()
    expect(proxy.feed).toHaveBeenCalledTimes(2)
  })

  it('renders any other typed failure by kind, and an unknown kind as the generic copy', async () => {
    proxy.feed.mockResolvedValueOnce(failure('error_timeline_invalid_zone'))
    const { unmount } = renderWithProviders(<HomeView onOpen={() => {}} />)
    expect(await screen.findByText(/time zone isn’t recognised/)).toBeInTheDocument()
    expect(screen.getByText('Couldn’t load this')).toBeInTheDocument()
    unmount()

    proxy.feed.mockResolvedValueOnce(failure('error_from_a_newer_peer'))
    renderWithProviders(<HomeView onOpen={() => {}} />)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText(/error_from_a_newer_peer/)).toBeNull()
  })

  it('renders a bridge rejection as its own sentence', async () => {
    proxy.feed.mockRejectedValueOnce(new Error('not permitted: timeline.feed'))
    renderWithProviders(<HomeView onOpen={() => {}} />)
    expect(await screen.findByText('Timeline is not allowed to do that.')).toBeInTheDocument()
  })

  it('says so when the stream dies, and keeps the last rows', async () => {
    feedOf({ days: [day('2026-06-08', visit('a', JUN_8, { id: 'p', name: 'Café' }))] })
    renderWithProviders(<HomeView onOpen={() => {}} />)
    await screen.findByText('Café')
    streams.feed.fail(new Error('collector died'))
    expect(await screen.findByText(/Updates stopped/)).toBeInTheDocument()
    expect(screen.getByText('Café')).toBeInTheDocument()
  })
})

describe('HomeView — the trip suggestion', () => {
  it('accepts exactly the suggestion shown and opens the new trip', async () => {
    const onOpen = vi.fn()
    feedOf({ suggestion: suggestionOf({ suggestionId: 'sg-9' }) })
    proxy.acceptTripSuggestion.mockResolvedValueOnce({ ok: true, tripId: 'trip-9' })
    renderWithProviders(<HomeView onOpen={onOpen} />)
    expect(await screen.findByText('Read as a trip?')).toBeInTheDocument()
    expect(screen.getByText('Jun 3 – Jun 7 · 5 days · 4 places')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Make it a trip' }))
    expect(proxy.acceptTripSuggestion).toHaveBeenCalledWith('sg-9')
    expect(onOpen).toHaveBeenCalledWith({ kind: 'trip', id: 'trip-9' })
    expect(screen.queryByText('Read as a trip?')).toBeNull()
  })

  it('dismisses it for this tab', async () => {
    feedOf({ suggestion: suggestionOf({ suggestionId: 'sg-2' }) })
    renderWithProviders(<HomeView onOpen={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Not a trip' }))
    expect(proxy.dismissTripSuggestion).toHaveBeenCalledWith('sg-2')
    await waitFor(() => expect(screen.queryByText('Read as a trip?')).toBeNull())
    expect(proxy.acceptTripSuggestion).not.toHaveBeenCalled()
  })

  // ⚠ The suggestion changed under the user: refused, never retried against the stale id.
  it('on suggestion_gone says so and reads the feed again instead of accepting another one', async () => {
    feedOf({ suggestion: suggestionOf({ suggestionId: 'stale' }) })
    proxy.acceptTripSuggestion.mockResolvedValueOnce(failure('error_timeline_suggestion_gone'))
    const onOpen = vi.fn()
    renderWithProviders(<HomeView onOpen={onOpen} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Make it a trip' }))
    expect(await screen.findByText(/This suggestion changed/)).toBeInTheDocument()
    await waitFor(() => expect(proxy.feed).toHaveBeenCalledTimes(2))
    expect(proxy.acceptTripSuggestion).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()
  })
})
