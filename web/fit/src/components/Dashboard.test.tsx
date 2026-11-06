import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, dayRow, PROFILE, streams } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { addDays, localToday } = await import('../lib/dates')
const { Dashboard } = await import('./Dashboard')

const today = localToday()

beforeEach(() => {
  resetProxy()
})

function bars() {
  return screen.getAllByTestId('bar').map((b) => ({ date: b.getAttribute('data-date'), value: Number(b.getAttribute('data-value')) }))
}

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

  it('a day the read does not name is a zero bar, so a week is always seven bars', async () => {
    proxy.dailyAggregates.mockResolvedValue({ ok: true, days: [dayRow(addDays(today, -3), { activeMinutes: 40 })] })
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    const week = bars()
    expect(week).toHaveLength(7)
    expect(week[0]!.date).toBe(addDays(today, -6))
    expect(week.at(-1)!.date).toBe(today)
    expect(week.map((b) => b.value)).toEqual([0, 0, 0, 40, 0, 0, 0])
    // The goal line is drawn on the bars' axis.
    expect(screen.getByTestId('goal-line')).toBeInTheDocument()
  })

  it('totals, averages, the change against the week before and the days the goal was met', async () => {
    proxy.dailyAggregates.mockResolvedValue({
      ok: true,
      days: [
        // the week before: 20 minutes in all
        dayRow(addDays(today, -10), { activeMinutes: 20 }),
        // this week: 30 + 45 + 15 = 90 minutes, two days at or above the 30-minute goal
        dayRow(addDays(today, -2), { activeMinutes: 30 }),
        dayRow(addDays(today, -1), { activeMinutes: 45 }),
        dayRow(today, { activeMinutes: 15 }),
      ],
    })
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    expect(screen.getByText('Total').nextSibling).toHaveTextContent('1 h 30 min')
    expect(screen.getByText('Daily average').nextSibling).toHaveTextContent('13 min')
    expect(screen.getByText('Change vs. the period before').nextSibling).toHaveTextContent('+350%')
    expect(screen.getByText('Goal reached').nextSibling).toHaveTextContent('2 of 7 days')
  })

  it('switching the metric redraws the bars from the same read', async () => {
    proxy.dailyAggregates.mockResolvedValue({ ok: true, days: [dayRow(today, { steps: 9_000, activeMinutes: 10 })] })
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    await userEvent.click(screen.getByRole('button', { name: 'Steps' }))
    expect(bars().at(-1)!.value).toBe(9_000)
    expect(proxy.dailyAggregates).toHaveBeenCalledTimes(1)
  })

  it('a year reads 365 days and shows no change figure (two years exceed the SDK window)', async () => {
    renderWithProviders(<Dashboard />)
    await screen.findAllByRole('meter')
    await userEvent.click(screen.getByRole('button', { name: '12 months' }))
    await waitFor(() => expect(proxy.dailyAggregates).toHaveBeenLastCalledWith(addDays(today, -364), today))
    await waitFor(() => expect(bars()).toHaveLength(365))
    expect(screen.queryByText('Change vs. the period before')).toBeNull()
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
