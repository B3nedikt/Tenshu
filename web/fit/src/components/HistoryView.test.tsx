import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, workoutRow, streams } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { HISTORY_PAGE_SIZE, forgetSeenVersion } = await import('../lib/history')
const { ROW_HEIGHT } = await import('../lib/virtual')
const { HistoryView } = await import('./HistoryView')

const T0 = Date.UTC(2026, 9, 28, 7, 0)

function rows(prefix: string, n: number, from = 0) {
  return Array.from({ length: n }, (_, i) => workoutRow(`${prefix}${from + i}`, T0 - (from + i) * 3_600_000))
}

function loaded(): number {
  return Number(screen.getByRole('list', { name: 'Workouts' }).getAttribute('data-loaded'))
}

beforeEach(() => {
  resetProxy()
  forgetSeenVersion()
})

describe('HistoryView paging', () => {
  it('asks for the head, then for exactly the cursor the last page returned', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.100.a2' })
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 2, 3), nextCursor: null })
    renderWithProviders(<HistoryView onOpen={() => {}} />)

    // Three rows leave the window near its end, so the next page is asked for at once.
    await waitFor(() => expect(proxy.workoutsPage).toHaveBeenCalledTimes(2))
    expect(proxy.workoutsPage.mock.calls[0]).toEqual([null, HISTORY_PAGE_SIZE])
    expect(proxy.workoutsPage.mock.calls[1]).toEqual(['w1.100.a2', HISTORY_PAGE_SIZE])
    await waitFor(() => expect(loaded()).toBe(5))
    // `nextCursor: null` is the end: nothing more is asked for.
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.workoutsPage).toHaveBeenCalledTimes(2)
  })

  it('never renders a row twice, even when a page serves it again', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.1.a2' })
      .mockResolvedValueOnce({ ok: true, workouts: [workoutRow('a2', T0 - 2 * 3_600_000), ...rows('a', 2, 3)], nextCursor: null })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(5))
    const ids = screen.getAllByTestId('workout-row').map((r) => r.getAttribute('data-id'))
    expect(ids).toEqual(['a0', 'a1', 'a2', 'a3', 'a4'])
  })

  it('renders only the rows around the viewport, and pages on as the list scrolls — never the whole history', async () => {
    let served = 0
    proxy.workoutsPage.mockImplementation(async (cursor) => {
      const page = rows('w', HISTORY_PAGE_SIZE, served)
      served += HISTORY_PAGE_SIZE
      return { ok: true, workouts: page, nextCursor: `w1.c${served}` }
      void cursor
    })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(HISTORY_PAGE_SIZE))
    // One page and no more: the window (~10 rows + overscan) is nowhere near its end.
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.workoutsPage).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('workout-row').length).toBeLessThan(30)

    // Scroll to the bottom of what is loaded: the next page, with the previous page's cursor.
    const scroller = screen.getByTestId('history-scroller')
    scroller.scrollTop = (HISTORY_PAGE_SIZE - 5) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await waitFor(() => expect(proxy.workoutsPage).toHaveBeenCalledTimes(2))
    expect(proxy.workoutsPage.mock.calls[1]![0]).toBe(`w1.c${HISTORY_PAGE_SIZE}`)
    await waitFor(() => expect(loaded()).toBe(2 * HISTORY_PAGE_SIZE))
    // The rendered rows moved with the scroll; the first ones are gone from the DOM.
    expect(screen.queryByText(/a0/)).toBeNull()
    const rendered = screen.getAllByTestId('workout-row')
    expect(rendered.length).toBeLessThan(30)
    expect(rendered[0]!.getAttribute('data-id')).not.toBe('w0')
  })

  it('a workout change re-reads the head only, and shows the new row first', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.c3' })
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 2, 3), nextCursor: null })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(5))
    await waitFor(() => expect(proxy.subscribeWorkoutChanges).toHaveBeenCalled())

    // The stream's first value is the current version: not a change.
    act(() => streams.changes.push(7))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.workoutsPage).toHaveBeenCalledTimes(2)

    // A new workout arrived above the head.
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: [workoutRow('new', T0 + 3_600_000), ...rows('a', 2)], nextCursor: 'w1.c1' })
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3, 2), nextCursor: null })
    act(() => streams.changes.push(8))
    await waitFor(() => expect(proxy.workoutsPage).toHaveBeenCalledTimes(3))
    // ⚠ The head, not every cursor again.
    expect(proxy.workoutsPage.mock.calls[2]).toEqual([null, HISTORY_PAGE_SIZE])
    await waitFor(() => expect(screen.getAllByTestId('workout-row')[0]).toHaveAttribute('data-id', 'new'))
    // The old second page's cursor is never asked for again; paging goes on from the new head's.
    await waitFor(() => expect(proxy.workoutsPage).toHaveBeenCalledTimes(4))
    expect(proxy.workoutsPage.mock.calls[3]).toEqual(['w1.c1', HISTORY_PAGE_SIZE])
    expect(proxy.workoutsPage.mock.calls.slice(2).some(([c]) => c === 'w1.c3')).toBe(false)
    expect(screen.getAllByTestId('workout-row').map((r) => r.getAttribute('data-id'))).toEqual(['new', 'a0', 'a1', 'a2', 'a3', 'a4'])
  })

  // Pins that a follow-up `fetchNextPage` never cancels a head re-read. A real QueryClient
  // (`renderWithQueryClient`), and a head re-read that takes a moment, as it does over the bridge.
  it('a change while scrolled deep shows the new head, then pages on from ITS cursor', async () => {
    let changed = false
    let releaseHead: () => void = () => {}
    const headHeld = new Promise<void>((r) => (releaseHead = r))
    proxy.workoutsPage.mockImplementation(async (cursor) => {
      if (!changed) {
        const from = cursor === null ? 0 : Number(cursor.slice('w1.old'.length))
        const next = from + HISTORY_PAGE_SIZE
        return { ok: true, workouts: rows('w', HISTORY_PAGE_SIZE, from), nextCursor: `w1.old${next}` }
      }
      if (cursor === null) {
        await headHeld
        return { ok: true, workouts: [workoutRow('new', T0 + 3_600_000), ...rows('w', HISTORY_PAGE_SIZE - 1)], nextCursor: 'w1.new49' }
      }
      if (cursor === 'w1.new49') return { ok: true, workouts: rows('w', HISTORY_PAGE_SIZE, HISTORY_PAGE_SIZE - 1), nextCursor: null }
      // A stale cursor read after the change: served, so a regression shows as old rows only.
      const from = Number(cursor.slice('w1.old'.length))
      return { ok: true, workouts: rows('w', HISTORY_PAGE_SIZE, from), nextCursor: `w1.old${from + HISTORY_PAGE_SIZE}` }
    })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(HISTORY_PAGE_SIZE))
    await waitFor(() => expect(proxy.subscribeWorkoutChanges).toHaveBeenCalled())
    act(() => streams.changes.push(1))

    // Scroll deep: three pages read, the window at the end of the third.
    const scroller = screen.getByTestId('history-scroller')
    for (const pages of [2, 3]) {
      scroller.scrollTop = ((pages - 1) * HISTORY_PAGE_SIZE - 5) * ROW_HEIGHT
      fireEvent.scroll(scroller)
      await waitFor(() => expect(loaded()).toBe(pages * HISTORY_PAGE_SIZE))
    }
    scroller.scrollTop = (3 * HISTORY_PAGE_SIZE - 10) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await new Promise((r) => setTimeout(r, 20))
    const before = proxy.workoutsPage.mock.calls.length

    // A workout syncs in from a phone. The head re-read is in flight.
    changed = true
    act(() => streams.changes.push(2))
    await waitFor(() => expect(proxy.workoutsPage.mock.calls.length).toBe(before + 1))
    expect(proxy.workoutsPage.mock.calls[before]).toEqual([null, HISTORY_PAGE_SIZE])
    await new Promise((r) => setTimeout(r, 20))
    // ⚠ Nothing else while it runs: no next page from the old head's cursor (which cancelled it).
    expect(proxy.workoutsPage.mock.calls.length).toBe(before + 1)

    await act(async () => releaseHead())
    // The re-read head is SHOWN: the list is back at the top with the new row first.
    await waitFor(() => expect(screen.getAllByTestId('workout-row')[0]).toHaveAttribute('data-id', 'new'))
    expect(scroller.scrollTop).toBe(0)
    expect(loaded()).toBe(HISTORY_PAGE_SIZE)

    // Paging on from there asks with the NEW head's cursor, never an old one.
    scroller.scrollTop = (HISTORY_PAGE_SIZE - 5) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await waitFor(() => expect(loaded()).toBe(2 * HISTORY_PAGE_SIZE))
    const after = proxy.workoutsPage.mock.calls.slice(before).map(([c]) => c)
    expect(after).toEqual([null, 'w1.new49'])
  })

  it('a change while History was closed re-reads the head when it comes back', async () => {
    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: rows('a', 2), nextCursor: null })
    const view = renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(2))
    act(() => streams.changes.push(20))
    view.unmount()

    // Another tab of the app is open; a workout syncs in. Back to History, on the SAME cache.
    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('synced', T0 + 1), ...rows('a', 2)], nextCursor: null })
    const calls = proxy.workoutsPage.mock.calls.length
    const { QueryClientProvider } = await import('@tanstack/react-query')
    const { LiveProvider } = await import('../lib/live')
    render(
      <QueryClientProvider client={view.client}>
        <LiveProvider>
          <HistoryView onOpen={() => {}} />
        </LiveProvider>
      </QueryClientProvider>,
    )
    act(() => streams.changes.push(21))
    await waitFor(() => expect(proxy.workoutsPage.mock.calls.length).toBeGreaterThan(calls))
    expect(proxy.workoutsPage.mock.calls.at(-1)).toEqual([null, HISTORY_PAGE_SIZE])
    await waitFor(() => expect(screen.getAllByTestId('workout-row')[0]).toHaveAttribute('data-id', 'synced'))
  })

  it('opens a workout from its row', async () => {
    proxy.workoutsPage.mockResolvedValueOnce({ ok: true, workouts: [workoutRow('w1', T0, { title: 'Evening run' })], nextCursor: null })
    const onOpen = vi.fn()
    renderWithProviders(<HistoryView onOpen={onOpen} />)
    await userEvent.click(await screen.findByRole('button', { name: /Evening run/ }))
    expect(onOpen).toHaveBeenCalledWith('w1')
  })

  it('an empty history says so — only after a successful read', async () => {
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    expect(await screen.findByText(/No workouts yet/)).toBeInTheDocument()
  })

  it('a failed head read is a failure by kind, not an empty history', async () => {
    proxy.workoutsPage.mockResolvedValueOnce(failure('error_fit_bad_cursor'))
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_fit_bad_cursor')
    expect(screen.queryByText(/No workouts yet/)).toBeNull()
  })

  it('a later page failing keeps the rows already read and says why no more come', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.c3' })
      .mockResolvedValueOnce(failure('error_internal'))
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_internal')
    expect(loaded()).toBe(3)

    // Its retry asks for that page again, with the same cursor.
    proxy.workoutsPage.mockResolvedValueOnce({ ok: true, workouts: rows('a', 2, 3), nextCursor: null })
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(loaded()).toBe(5))
    expect(proxy.workoutsPage.mock.calls.at(-1)).toEqual(['w1.c3', HISTORY_PAGE_SIZE])
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // A failed head RE-READ with rows on screen is retried as the head, never as a next page: that would
  // read on from the stale head's cursor and clear the error with the new row still unread.
  it.each([
    ['the head has no next page', null],
    ['the head has a next page', 'w1.c2'],
  ])('a failed head re-read is retried as the head (%s)', async (_label, headCursor) => {
    proxy.workoutsPage.mockResolvedValueOnce({ ok: true, workouts: rows('a', 2), nextCursor: headCursor })
    if (headCursor) proxy.workoutsPage.mockResolvedValueOnce({ ok: true, workouts: [], nextCursor: null })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(2))
    await waitFor(() => expect(proxy.subscribeWorkoutChanges).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    act(() => streams.changes.push(1))
    const before = proxy.workoutsPage.mock.calls.length

    // A workout lands; the head re-read fails. The rows already read stay, with the failure under them.
    proxy.workoutsPage.mockResolvedValueOnce(failure('error_internal'))
    act(() => streams.changes.push(2))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_internal')
    expect(loaded()).toBe(2)

    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('new', T0 + 3_600_000), ...rows('a', 2)], nextCursor: null })
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.getAllByTestId('workout-row')[0]).toHaveAttribute('data-id', 'new'))
    expect(screen.queryByRole('alert')).toBeNull()
    // Every read after the change was the head, never an older page.
    expect(proxy.workoutsPage.mock.calls.slice(before).map(([c]) => c)).toEqual([null, null])
  })

  // The same bad cursor would fail forever. Its one way on is the head, and the button says so.
  it('a bad cursor starts over from the top — the head is re-read, never that cursor again', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.bad' })
      .mockResolvedValueOnce(failure('error_fit_bad_cursor'))
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_fit_bad_cursor')
    expect(loaded()).toBe(3)
    expect(within(alert).queryByRole('button', { name: 'Try again' })).toBeNull()

    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('new', T0 + 3_600_000), ...rows('a', 3)], nextCursor: null })
    await userEvent.click(within(alert).getByRole('button', { name: 'Start from the top' }))
    await waitFor(() => expect(screen.getAllByTestId('workout-row')[0]).toHaveAttribute('data-id', 'new'))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(proxy.workoutsPage.mock.calls.slice(2).map(([c]) => c)).toEqual([null])
  })

  // While any read runs, Retry is disabled, and the retry itself holds off too: a second
  // `fetchNextPage` (`cancelRefetch: true`) would cancel the read in flight.
  it('Retry is disabled and a no-op while a read runs', async () => {
    proxy.workoutsPage
      .mockResolvedValueOnce({ ok: true, workouts: rows('a', 3), nextCursor: 'w1.c3' })
      .mockResolvedValueOnce(failure('error_internal'))
    const { useHistory } = await import('../lib/history')
    let history: ReturnType<typeof useHistory> | undefined
    function Probe() {
      history = useHistory()
      return null
    }
    renderWithProviders(
      <>
        <HistoryView onOpen={() => {}} />
        <Probe />
      </>,
    )
    const alert = await screen.findByRole('alert')
    const button = within(alert).getByRole('button', { name: 'Try again' })
    expect(button).toBeEnabled()

    let resolve!: (r: Awaited<ReturnType<typeof proxy.workoutsPage>>) => void
    proxy.workoutsPage.mockImplementationOnce(() => new Promise((r) => (resolve = r)))
    act(() => button.click())
    await waitFor(() => expect(button).toBeDisabled())
    const calls = proxy.workoutsPage.mock.calls.length
    // Straight at the hook too, past the disabled button.
    act(() => history!.retryMore())
    act(() => history!.retryMore())
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.workoutsPage.mock.calls.length).toBe(calls)

    await act(async () => resolve({ ok: true, workouts: rows('a', 2, 3), nextCursor: null }))
    await waitFor(() => expect(loaded()).toBe(5))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a dead change stream says the list may be stale', async () => {
    proxy.workoutsPage.mockResolvedValueOnce({ ok: true, workouts: rows('a', 1), nextCursor: null })
    renderWithProviders(<HistoryView onOpen={() => {}} />)
    await waitFor(() => expect(loaded()).toBe(1))
    act(() => streams.changes.fail(new Error('stream ended')))
    expect(await screen.findByText(/Updates stopped/)).toBeInTheDocument()
  })
})
