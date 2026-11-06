// The hub tile's staleness on the app's OWN clock (web-app-suite.md §4.H): `stale` was judged at read
// time, and the tile must flip to "Hub offline" when `now - freshAsOfMs > staleAfterMs` with no new row.
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { hub } from '../test/proxyMock'
import { HubTile } from './HubTile'

const FRESH = new Date(2026, 9, 28, 14, 5).getTime()
const STALE_AFTER = 2 * 3_600_000

beforeEach(async () => {
  vi.useFakeTimers()
  await i18n.changeLanguage('en')
})
afterEach(() => vi.useRealTimers())

function tile() {
  return screen.getByTestId('hub-tile')
}

describe('HubTile', () => {
  it('shows free of total, usage and "As of" while fresh', () => {
    vi.setSystemTime(FRESH + 60_000)
    render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER })} />)
    expect(tile()).toHaveTextContent('1.5 TB free of 2 TB')
    expect(screen.getByRole('progressbar', { name: 'Storage used' })).toHaveAttribute('aria-valuenow', '25')
    expect(tile()).toHaveTextContent('As of 14:05')
    expect(tile()).toHaveAttribute('data-stale', 'false')
  })

  it('flips to "Hub offline" exactly past staleAfterMs, with no new row, on its own clock', () => {
    vi.setSystemTime(FRESH)
    render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER, stale: false })} />)
    expect(tile()).toHaveAttribute('data-stale', 'false')

    // At the boundary itself (now - fresh == staleAfterMs): still fresh. (The minute ticks ran.)
    act(() => vi.advanceTimersByTime(STALE_AFTER))
    expect(Date.now()).toBe(FRESH + STALE_AFTER)
    expect(tile()).toHaveAttribute('data-stale', 'false')

    // One millisecond past it: stale, from the tile's own timer, not a minute later.
    act(() => vi.advanceTimersByTime(1))
    expect(tile()).toHaveAttribute('data-stale', 'true')
    expect(tile()).toHaveTextContent('Hub offline · as of 14:05')
  })

  it('reads stale at once when mounted past the boundary', () => {
    vi.setSystemTime(FRESH + STALE_AFTER + 1)
    render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER, stale: false })} />)
    expect(tile()).toHaveAttribute('data-stale', 'true')
  })

  it('a row the runtime already judged stale never reads fresh', () => {
    vi.setSystemTime(FRESH + 1_000)
    render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER, stale: true })} />)
    expect(tile()).toHaveAttribute('data-stale', 'true')
  })

  it('a new row moves the boundary', () => {
    vi.setSystemTime(FRESH + STALE_AFTER - 10)
    const view = render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER })} />)
    view.rerender(<HubTile hub={hub({ freshAsOfMs: FRESH + 3_600_000, staleAfterMs: STALE_AFTER })} />)
    act(() => vi.advanceTimersByTime(1_000))
    expect(tile()).toHaveAttribute('data-stale', 'false')
    act(() => vi.advanceTimersByTime(3_600_000))
    expect(tile()).toHaveAttribute('data-stale', 'true')
  })

  it('the as-of line names the date once the snapshot is from another day', () => {
    vi.setSystemTime(new Date(2026, 9, 29, 9, 0).getTime())
    render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER })} />)
    expect(tile()).toHaveTextContent('Hub offline · as of 2026-10-28 14:05')
  })

  it('starts no timer that outlives the tile', () => {
    vi.setSystemTime(FRESH)
    const view = render(<HubTile hub={hub({ freshAsOfMs: FRESH, staleAfterMs: STALE_AFTER })} />)
    expect(vi.getTimerCount()).toBeGreaterThan(0)
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
