// One metric over a range (fit-web-parity.md §2.3): the switcher, the daily average, the chart, the
// period's figures and the entries.
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, dayRow } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { addDays, localToday } = await import('../lib/dates')
const { MetricDetail } = await import('./MetricDetail')
type Range = 'week' | 'month' | 'year'

const today = localToday()
beforeEach(() => resetProxy())

function Page({ metric = 'activeMinutes' as const, start = 'week' as Range }) {
  const [range, setRange] = useState<Range>(start)
  return <MetricDetail metric={metric} range={range} onRange={setRange} onBack={() => {}} />
}
const bars = () => screen.getAllByTestId('bar').map((b) => ({ key: b.getAttribute('data-key'), value: Number(b.getAttribute('data-value')) }))

describe('MetricDetail', () => {
  it('the daily average up top, then total, change against the period before and goal days, and the entries newest first', async () => {
    proxy.dailyAggregates.mockResolvedValue({
      ok: true,
      days: [
        dayRow(addDays(today, -10), { activeMinutes: 20 }),
        dayRow(addDays(today, -2), { activeMinutes: 30 }),
        dayRow(addDays(today, -1), { activeMinutes: 45 }),
        dayRow(today, { activeMinutes: 15 }),
      ],
    })
    renderWithProviders(<Page />)
    expect(await screen.findByTestId('daily-average')).toHaveTextContent('13 min')
    expect(screen.getByText('Total').nextSibling).toHaveTextContent('1 h 30 min')
    expect(screen.getByText('Change vs. the period before').nextSibling).toHaveTextContent('+350%')
    expect(screen.getByText('Goal reached').nextSibling).toHaveTextContent('2 of 7 days')
    const entries = within(screen.getByTestId('entries')).getAllByRole('listitem')
    expect(entries).toHaveLength(7)
    expect(entries[0]).toHaveTextContent('15 min')
    expect(bars()).toHaveLength(7)
  })

  it('a year reads 365 days, draws monthly bars, and shows no change figure (two years exceed the SDK window)', async () => {
    renderWithProviders(<Page />)
    await screen.findByTestId('daily-average')
    await userEvent.click(screen.getByRole('button', { name: '12 months' }))
    await waitFor(() => expect(proxy.dailyAggregates).toHaveBeenLastCalledWith(addDays(today, -364), today))
    // 365 days touch 12 or 13 calendar months: one bar each, never 365 slivers.
    await waitFor(() => expect(bars().length).toBeGreaterThanOrEqual(12))
    expect(bars().length).toBeLessThanOrEqual(13)
    expect(bars().every((b) => /^\d{4}-\d{2}$/.test(b.key ?? ''))).toBe(true)
    expect(bars().at(-1)!.key).toBe(today.slice(0, 7))
    expect(screen.queryByText('Change vs. the period before')).toBeNull()
    expect(screen.queryByTestId('entries')).toBeNull()
  })

  it('a month: thirty bars and thirty entries', async () => {
    renderWithProviders(<Page start="month" />)
    await screen.findByTestId('daily-average')
    expect(bars()).toHaveLength(30)
    expect(within(screen.getByTestId('entries')).getAllByRole('listitem')).toHaveLength(30)
  })
})
