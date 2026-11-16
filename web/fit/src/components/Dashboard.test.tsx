import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, dayRow, PROFILE, streams, workoutRow } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { addDays, localToday } = await import('../lib/dates')
const { Dashboard } = await import('./Dashboard')

const today = localToday()

beforeEach(() => {
  resetProxy()
})

describe('Dashboard', () => {
  it('reads the week plus the week before, in the browser zone, and shows today against the goals', async () => {
    proxy.dailyAggregates.mockResolvedValue({
      ok: true,
      days: [
        dayRow(addDays(today, -1), { steps: 12_000, activeMinutes: 50 }),
        dayRow(today, { steps: 4_000, activeMinutes: 15, distanceM: 3_200, activeEnergyKj: 1_000 }),
      ],
    })
    renderWithProviders(<Dashboard />)

    // 7 days and the 7 before them, for the change figure; the zone is the proxy's '' (browser).
    await waitFor(() => expect(proxy.dailyAggregates).toHaveBeenCalledWith(addDays(today, -13), today))
    expect(proxy.subscribeDailyAggregates.mock.calls[0]!.slice(0, 2)).toEqual([addDays(today, -13), today])

    const meters = await screen.findAllByRole('meter')
    expect(meters[0]).toHaveAttribute('aria-valuetext', '15 of 30')
    expect(meters[1]).toHaveAttribute('aria-valuetext', '4,000 of 8,000')
    const todayCard = screen.getByRole('region', { name: 'Today' })
    expect(within(todayCard).getByText('3.2 km')).toBeInTheDocument()
    expect(within(todayCard).getByText('239 kcal')).toBeInTheDocument()
  })

  // A tab left open past midnight moves its window with the date.
  describe('today rolls over', () => {
    function lastWindow() {
      return proxy.dailyAggregates.mock.calls.at(-1)!.slice(0, 2)
    }

    it('at the next local midnight', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      try {
        vi.setSystemTime(new Date(2026, 9, 28, 23, 59, 0))
        renderWithProviders(<Dashboard />)
        await waitFor(() => expect(lastWindow()).toEqual(['2026-10-15', '2026-10-28']))
        await act(async () => {
          vi.advanceTimersByTime(61_000)
        })
        await waitFor(() => expect(lastWindow()).toEqual(['2026-10-16', '2026-10-29']))
        expect(proxy.subscribeDailyAggregates.mock.calls.at(-1)!.slice(0, 2)).toEqual(['2026-10-16', '2026-10-29'])
        // And again a day later: the timer re-arms itself.
        await act(async () => {
          vi.advanceTimersByTime(24 * 3_600_000)
        })
        await waitFor(() => expect(lastWindow()).toEqual(['2026-10-17', '2026-10-30']))
      } finally {
        vi.useRealTimers()
      }
    })

    it('when the tab becomes visible again, if the timer was held back', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      try {
        vi.setSystemTime(new Date(2026, 9, 28, 22, 0, 0))
        renderWithProviders(<Dashboard />)
        await waitFor(() => expect(lastWindow()).toEqual(['2026-10-15', '2026-10-28']))
        // The clock moved on and no timer ran (a sleeping laptop, a throttled background tab).
        vi.setSystemTime(new Date(2026, 9, 29, 9, 0, 0))
        await new Promise((r) => setTimeout(r, 20))
        expect(lastWindow()).toEqual(['2026-10-15', '2026-10-28'])
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
        act(() => {
          document.dispatchEvent(new Event('visibilitychange'))
        })
        await waitFor(() => expect(lastWindow()).toEqual(['2026-10-16', '2026-10-29']))
      } finally {
        vi.useRealTimers()
      }
    })
  })

  it('a week card is always seven bars: a day the read does not name is a zero bar', async () => {
    proxy.dailyAggregates.mockResolvedValue({ ok: true, days: [dayRow(addDays(today, -3), { activeMinutes: 40 })] })
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    const card = within(screen.getByTestId('week-activeMinutes'))
    const week = card.getAllByTestId('bar').map((b) => ({ date: b.getAttribute('data-key'), value: Number(b.getAttribute('data-value')) }))
    expect(week).toHaveLength(7)
    expect(week[0]!.date).toBe(addDays(today, -6))
    expect(week.at(-1)!.date).toBe(today)
    expect(week.map((b) => b.value)).toEqual([0, 0, 0, 40, 0, 0, 0])
    // The goal on the active-minutes and steps cards; none on energy and distance.
    expect(card.getByTestId('goal-line')).toBeInTheDocument()
    expect(within(screen.getByTestId('week-distanceM')).queryByTestId('goal-line')).toBeNull()
  })

  it('native Home: the hero ring says what is left, four week cards with the change vs last week', async () => {
    proxy.dailyAggregates.mockResolvedValue({
      ok: true,
      days: [dayRow(addDays(today, -10), { activeMinutes: 20 }), dayRow(addDays(today, -1), { activeMinutes: 45 }), dayRow(today, { activeMinutes: 12 })],
    })
    renderWithProviders(<Dashboard />)
    expect(await screen.findByText('18 min to go')).toBeInTheDocument()
    expect(screen.getAllByTestId(/^week-/).map((c) => c.getAttribute('data-testid'))).toEqual([
      'week-activeEnergyKj',
      'week-steps',
      'week-activeMinutes',
      'week-distanceM',
    ])
    // 57 this week against 20 the week before.
    expect(within(screen.getByTestId('week-activeMinutes')).getByTestId('delta')).toHaveTextContent('+185% vs last week')
    expect(within(screen.getByTestId('week-activeMinutes')).getByText('57 min this week')).toBeInTheDocument()
  })

  it('a week card and a strip cell open their metric; the latest workout opens it', async () => {
    const onOpenMetric = vi.fn()
    const onOpenWorkout = vi.fn()
    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('w9', Date.UTC(2026, 10, 14, 7), { title: 'Long run', distanceM: 12_000 })], nextCursor: null })
    renderWithProviders(<Dashboard onOpenMetric={onOpenMetric} onOpenWorkout={onOpenWorkout} />)
    await screen.findAllByRole('meter')
    await userEvent.click(within(screen.getByTestId('week-steps')).getByRole('button', { name: 'Steps' }))
    expect(onOpenMetric).toHaveBeenLastCalledWith('steps')
    await userEvent.click(within(screen.getByTestId('strip')).getByRole('button', { name: /Distance/ }))
    expect(onOpenMetric).toHaveBeenLastCalledWith('distanceM')
    await userEvent.click(await screen.findByTestId('latest-workout'))
    expect(onOpenWorkout).toHaveBeenCalledWith('w9')
    expect(screen.getByTestId('latest-workout')).toHaveTextContent('Long run')
    // One read for the whole page.
    expect(proxy.dailyAggregates).toHaveBeenCalledTimes(1)
  })

  it('a pushed change replaces what is shown', async () => {
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    act(() => streams.days.push({ ok: true, days: [dayRow(today, { activeMinutes: 25 })] }))
    await waitFor(() => expect(screen.getAllByRole('meter')[0]).toHaveAttribute('aria-valuetext', '25 of 30'))
  })

  it('formats distance in the profile units', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, units: 'imperial' } })
    proxy.dailyAggregates.mockResolvedValue({ ok: true, days: [dayRow(today, { distanceM: 3_219 })] })
    renderWithProviders(<Dashboard />)
    const todayCard = await screen.findByRole('region', { name: 'Today' })
    await waitFor(() => expect(within(todayCard).getByText('2.0 mi')).toBeInTheDocument())
  })

  it('a failed aggregate read is a failure by kind, never a ring at zero', async () => {
    proxy.dailyAggregates.mockResolvedValue(failure('error_fit_invalid_zone'))
    renderWithProviders(<Dashboard />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_fit_invalid_zone')
    expect(alert).toHaveTextContent('time zone isn’t recognised')
    expect(screen.queryByRole('meter')).toBeNull()
    expect(screen.queryByText('0')).toBeNull()
  })

  it('a failed goals read shows the failure, never native’s 30 / 8 000 defaults', async () => {
    proxy.goals.mockResolvedValue(failure('error_internal'))
    proxy.dailyAggregates.mockResolvedValue({ ok: true, days: [dayRow(today, { activeMinutes: 12 })] })
    renderWithProviders(<Dashboard />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_internal')
    expect(screen.queryByRole('meter')).toBeNull()
    expect(screen.queryByText(/of 30/)).toBeNull()
    expect(screen.queryByTestId('goal-line')).toBeNull()
  })

  it('a rejected bridge call says so in its own words', async () => {
    proxy.dailyAggregates.mockRejectedValue(new Error('not permitted: fit.dailyAggregates'))
    renderWithProviders(<Dashboard />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Fit is not allowed to do that.')
  })
})
