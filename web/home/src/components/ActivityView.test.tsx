import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, run, streams } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { ACTIVITY_PAGE_SIZE } = await import('../lib/activity')
const { ROW_HEIGHT } = await import('../lib/virtual')
const { ActivityView } = await import('./ActivityView')

// Rows a minute apart, all on one day: one day header, then the rows.
const T0 = new Date(2026, 9, 20, 18, 0).getTime()

function rows(prefix: string, n: number, from = 0) {
  return Array.from({ length: n }, (_, i) => run(`${prefix}${from + i}`, T0 - (from + i) * 60_000))
}

function loaded(): number {
  return Number(screen.getByRole('list', { name: 'Activity' }).getAttribute('data-loaded'))
}

function ids(): (string | null)[] {
  return screen.getAllByTestId('activity-row').map((r) => r.getAttribute('data-id'))
}

beforeEach(() => {
  resetProxy()
})

describe('ActivityView — paging (a real QueryClient)', () => {
  it('asks for the head, then for exactly the cursor the last page returned', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.opaque~2' })
      .mockResolvedValueOnce({ ok: true, events: rows('a', 2, 3), nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(proxy.activityPage).toHaveBeenCalledTimes(2))
    expect(proxy.activityPage.mock.calls[0]).toEqual([null, ACTIVITY_PAGE_SIZE])
    expect(proxy.activityPage.mock.calls[1]).toEqual(['a1.opaque~2', ACTIVITY_PAGE_SIZE])
    await waitFor(() => expect(loaded()).toBe(5))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage).toHaveBeenCalledTimes(2)
  })

  it('an exactly-full last page, then [] + null, is the normal end — no error, no more reads', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.c3' })
      .mockResolvedValueOnce({ ok: true, events: [], nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(proxy.activityPage).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage).toHaveBeenCalledTimes(2)
    expect(loaded()).toBe(3)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('never renders a row twice, even when a page serves it again', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.c2' })
      .mockResolvedValueOnce({ ok: true, events: [run('a2', T0 - 2 * 60_000), ...rows('a', 2, 3)], nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(5))
    expect(ids()).toEqual(['a0', 'a1', 'a2', 'a3', 'a4'])
  })

  it('renders only the rows around the viewport, and pages on as the list scrolls', async () => {
    let served = 0
    proxy.activityPage.mockImplementation(async () => {
      const page = rows('w', ACTIVITY_PAGE_SIZE, served)
      served += ACTIVITY_PAGE_SIZE
      return { ok: true, events: page, nextCursor: `a1.c${served}` }
    })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(ACTIVITY_PAGE_SIZE))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('activity-row').length).toBeLessThan(30)

    const scroller = screen.getByTestId('activity-scroller')
    scroller.scrollTop = (ACTIVITY_PAGE_SIZE - 5) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await waitFor(() => expect(proxy.activityPage).toHaveBeenCalledTimes(2))
    expect(proxy.activityPage.mock.calls[1]![0]).toBe(`a1.c${ACTIVITY_PAGE_SIZE}`)
    await waitFor(() => expect(loaded()).toBe(2 * ACTIVITY_PAGE_SIZE))
    expect(ids()).not.toContain('w0')
    expect(screen.getAllByTestId('activity-row').length).toBeLessThan(30)
  })

  it('a change re-reads the head only, and shows the new run first', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.c3' })
      .mockResolvedValueOnce({ ok: true, events: rows('a', 2, 3), nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(5))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    // The first version is the current one, not a change.
    act(() => streams.changes.push(1))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage).toHaveBeenCalledTimes(2)

    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: [run('new', T0 + 60_000), ...rows('a', 3)], nextCursor: 'a1.n3' })
      .mockResolvedValueOnce({ ok: true, events: rows('a', 2, 3), nextCursor: null })
    act(() => streams.changes.push(2))
    await waitFor(() => expect(ids()[0]).toBe('new'))
    // ⚠ The head — never the old second page's cursor again; paging goes on from the new head's.
    const after = proxy.activityPage.mock.calls.slice(2).map(([c]) => c)
    await waitFor(() => expect(proxy.activityPage.mock.calls.slice(2).map(([c]) => c)).toEqual([null, 'a1.n3']))
    expect(after[0]).toBeNull()
    expect(ids()).toEqual(['new', 'a0', 'a1', 'a2', 'a3', 'a4'])
  })

  // Pins that a follow-up `fetchNextPage` cannot cancel the head re-read. Held here so the
  // re-read is in flight while the window sits at the end of what is loaded.
  it('a change while scrolled deep shows the new head, then pages on from ITS cursor — fetchMore cannot cancel it', async () => {
    let changed = false
    let releaseHead: () => void = () => {}
    const headHeld = new Promise<void>((r) => (releaseHead = r))
    proxy.activityPage.mockImplementation(async (cursor) => {
      if (!changed) {
        const from = cursor === null ? 0 : Number(cursor.slice('a1.old'.length))
        return { ok: true, events: rows('w', ACTIVITY_PAGE_SIZE, from), nextCursor: `a1.old${from + ACTIVITY_PAGE_SIZE}` }
      }
      if (cursor === null) {
        await headHeld
        return { ok: true, events: [run('new', T0 + 60_000), ...rows('w', ACTIVITY_PAGE_SIZE - 1)], nextCursor: 'a1.new49' }
      }
      if (cursor === 'a1.new49') return { ok: true, events: rows('w', ACTIVITY_PAGE_SIZE, ACTIVITY_PAGE_SIZE - 1), nextCursor: null }
      // A stale cursor read after the change: served, so a regression shows as what it does.
      const from = Number(cursor.slice('a1.old'.length))
      return { ok: true, events: rows('w', ACTIVITY_PAGE_SIZE, from), nextCursor: `a1.old${from + ACTIVITY_PAGE_SIZE}` }
    })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(ACTIVITY_PAGE_SIZE))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    act(() => streams.changes.push(1))

    const scroller = screen.getByTestId('activity-scroller')
    for (const pages of [2, 3]) {
      scroller.scrollTop = ((pages - 1) * ACTIVITY_PAGE_SIZE - 5) * ROW_HEIGHT
      fireEvent.scroll(scroller)
      await waitFor(() => expect(loaded()).toBe(pages * ACTIVITY_PAGE_SIZE))
    }
    scroller.scrollTop = (3 * ACTIVITY_PAGE_SIZE - 10) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await new Promise((r) => setTimeout(r, 20))
    const before = proxy.activityPage.mock.calls.length

    changed = true
    act(() => streams.changes.push(2))
    await waitFor(() => expect(proxy.activityPage.mock.calls.length).toBe(before + 1))
    expect(proxy.activityPage.mock.calls[before]).toEqual([null, ACTIVITY_PAGE_SIZE])
    await new Promise((r) => setTimeout(r, 20))
    // ⚠ Nothing else while it runs: no next page from the old head's cursor.
    expect(proxy.activityPage.mock.calls.length).toBe(before + 1)

    await act(async () => releaseHead())
    await waitFor(() => expect(ids()[0]).toBe('new'))
    expect(scroller.scrollTop).toBe(0)
    expect(loaded()).toBe(ACTIVITY_PAGE_SIZE)

    scroller.scrollTop = (ACTIVITY_PAGE_SIZE - 5) * ROW_HEIGHT
    fireEvent.scroll(scroller)
    await waitFor(() => expect(loaded()).toBe(2 * ACTIVITY_PAGE_SIZE))
    expect(proxy.activityPage.mock.calls.slice(before).map(([c]) => c)).toEqual([null, 'a1.new49'])
  })

  // `Home.observeActivityChanges` counts per SUBSCRIPTION — every new one starts at 1.
  // So the old subscription's last version and the new one's first can be equal across a change.
  it('a change while the feed was closed re-reads the head when it comes back, though the new stream starts at 1 again', async () => {
    proxy.activityPage.mockResolvedValue({ ok: true, events: rows('a', 2), nextCursor: null })
    const view = renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(2))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    act(() => streams.changes.push(1))
    view.unmount()
    expect(proxy.activityPage).toHaveBeenCalledTimes(1)

    // A run lands while nothing listens. Same client, as the app keeps one (`staleTime: Infinity`).
    const { client } = view
    proxy.activityPage.mockResolvedValue({ ok: true, events: [run('new', T0 + 60_000), ...rows('a', 2)], nextCursor: null })
    const { QueryClientProvider } = await import('@tanstack/react-query')
    const { LiveProvider } = await import('../lib/live')
    const { render } = await import('@testing-library/react')
    render(
      <QueryClientProvider client={client}>
        <LiveProvider>
          <ActivityView />
        </LiveProvider>
      </QueryClientProvider>,
    )
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    // The new subscription's first value — equal to the old one's last.
    act(() => streams.changes.push(1))
    await waitFor(() => expect(ids()[0]).toBe('new'))
    expect(proxy.activityPage.mock.calls.slice(1).map(([c]) => c)).toEqual([null])
  })

  it('a first mount reads the head once — the stream’s first value is not a change', async () => {
    proxy.activityPage.mockResolvedValue({ ok: true, events: rows('a', 2), nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(2))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    act(() => streams.changes.push(7))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage).toHaveBeenCalledTimes(1)
  })

  // A failed head RE-READ with rows on screen is retried as the head, never as a next page.
  it.each([
    ['the head has no next page', null],
    ['the head has a next page', 'a1.c2'],
  ])('a failed head re-read is retried as the head (%s)', async (_label, headCursor) => {
    proxy.activityPage.mockResolvedValueOnce({ ok: true, events: rows('a', 2), nextCursor: headCursor })
    if (headCursor) proxy.activityPage.mockResolvedValueOnce({ ok: true, events: [], nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(2))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    await new Promise((r) => setTimeout(r, 20))
    act(() => streams.changes.push(1))
    const before = proxy.activityPage.mock.calls.length

    // A run lands; the head re-read fails. The rows already read stay, with the failure under them.
    proxy.activityPage.mockResolvedValueOnce(failure('error_home_service_unavailable'))
    act(() => streams.changes.push(2))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(loaded()).toBe(2)

    proxy.activityPage.mockResolvedValue({ ok: true, events: [run('new', T0 + 60_000), ...rows('a', 2)], nextCursor: null })
    act(() => within(alert).getByRole('button', { name: 'Try again' }).click())
    await waitFor(() => expect(ids()[0]).toBe('new'))
    expect(screen.queryByRole('alert')).toBeNull()
    // Every read after the change was the head, never an older page.
    expect(proxy.activityPage.mock.calls.slice(before).map(([c]) => c)).toEqual([null, null])
  })
})

describe('ActivityView — states', () => {
  it('groups by day and names each run with native’s outcome words', async () => {
    const now = Date.now()
    const yesterday = new Date(now)
    yesterday.setDate(yesterday.getDate() - 1)
    const older = new Date(2026, 0, 5, 9, 0).getTime()
    proxy.activityPage.mockResolvedValueOnce({
      ok: true,
      events: [
        run('t1', now, { state: 'ran' }),
        run('y1', yesterday.getTime(), { state: 'skipped' }),
        run('y2', yesterday.getTime() - 1, { state: 'missed', routineName: '' }),
        run('o1', older, { state: 'throttled' }),
        run('o2', older - 1, { state: 'failed' }),
      ],
      nextCursor: null,
    })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(5))
    const headers = screen.getAllByTestId('day-header').map((h) => h.textContent)
    expect(headers[0]).toBe('Today')
    expect(headers[1]).toBe('Yesterday')
    expect(headers[2]).toMatch(/Jan 5, 2026/)
    const text = (id: string) => screen.getAllByTestId('activity-row').find((r) => r.getAttribute('data-id') === id)!.textContent
    expect(text('t1')).toContain('Ran')
    expect(text('y1')).toContain('Conditions not met')
    expect(text('y2')).toContain('A deleted routine')
    expect(text('y2')).toContain('Missed — the device was not awake')
    expect(text('o1')).toContain('Throttled — it fired too often')
    expect(text('o2')).toContain('Failed')
  })

  it('an empty feed says so — only after a successful read', async () => {
    renderWithProviders(<ActivityView />)
    expect(await screen.findByText('Nothing here yet — automation runs will show up in this feed.')).toBeInTheDocument()
  })

  it('the Devices and Voice filters say what is not recorded, and read nothing for it', async () => {
    const user = userEvent.setup()
    proxy.activityPage.mockResolvedValue({ ok: true, events: rows('a', 2), nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(2))
    await user.click(screen.getByRole('button', { name: 'Devices' }))
    expect(screen.getByText(/Device events aren’t recorded yet/)).toBeInTheDocument()
    expect(screen.queryAllByTestId('activity-row')).toEqual([])
    await user.click(screen.getByRole('button', { name: 'Voice' }))
    expect(screen.getByText(/Voice isn’t available yet/)).toBeInTheDocument()
    // Device and Voice read nothing; back on the feed, its cached head is re-read once.
    expect(proxy.activityPage).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: 'Automations' }))
    await waitFor(() => expect(loaded()).toBe(2))
    expect(screen.getByRole('button', { name: 'Automations' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(proxy.activityPage).toHaveBeenCalledTimes(2))
    expect(proxy.activityPage.mock.calls[1]![0]).toBeNull()
  })

  it('a failed head read is a failure by kind, not an empty feed', async () => {
    proxy.activityPage.mockResolvedValue(failure('error_home_service_unavailable'))
    renderWithProviders(<ActivityView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(screen.queryByText(/Nothing here yet/)).toBeNull()
  })

  it('a later page failing keeps the rows already read and says why no more come — and retries THAT page', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.c3' })
      .mockResolvedValueOnce(failure('error_home_service_unavailable'))
    renderWithProviders(<ActivityView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(loaded()).toBe(3)
    proxy.activityPage.mockResolvedValueOnce({ ok: true, events: rows('a', 2, 3), nextCursor: null })
    act(() => within(alert).getByRole('button', { name: 'Try again' }).click())
    await waitFor(() => expect(loaded()).toBe(5))
    expect(proxy.activityPage.mock.calls.at(-1)![0]).toBe('a1.c3')
  })

  // The same bad cursor would fail forever. Its one way on is the head, and it says so.
  it('a bad cursor starts over from the top — the head is re-read, never that cursor again', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.bad' })
      .mockResolvedValueOnce(failure('error_home_bad_cursor'))
    renderWithProviders(<ActivityView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_bad_cursor')
    expect(loaded()).toBe(3)
    expect(within(alert).queryByRole('button', { name: 'Try again' })).toBeNull()

    proxy.activityPage.mockResolvedValue({ ok: true, events: [run('new', T0 + 60_000), ...rows('a', 3)], nextCursor: null })
    act(() => within(alert).getByRole('button', { name: 'Start from the top' }).click())
    await waitFor(() => expect(ids()[0]).toBe('new'))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(proxy.activityPage.mock.calls.slice(2).map(([c]) => c)).toEqual([null])
  })

  // While any read runs, Retry is disabled, and the retry itself holds off too — a second
  // `fetchNextPage` (`cancelRefetch: true`) would cancel the read in flight.
  it('Retry is disabled and a no-op while a read runs', async () => {
    proxy.activityPage
      .mockResolvedValueOnce({ ok: true, events: rows('a', 3), nextCursor: 'a1.c3' })
      .mockResolvedValueOnce(failure('error_home_service_unavailable'))
    const { useActivity } = await import('../lib/activity')
    let activity: ReturnType<typeof useActivity> | undefined
    function Probe() {
      activity = useActivity()
      return null
    }
    renderWithProviders(
      <>
        <ActivityView />
        <Probe />
      </>,
    )
    const alert = await screen.findByRole('alert')
    const button = within(alert).getByRole('button', { name: 'Try again' })
    expect(button).toBeEnabled()

    let resolve!: (r: Awaited<ReturnType<typeof proxy.activityPage>>) => void
    proxy.activityPage.mockImplementationOnce(() => new Promise((r) => (resolve = r)))
    act(() => button.click())
    await waitFor(() => expect(button).toBeDisabled())
    const calls = proxy.activityPage.mock.calls.length
    // Straight at the hook too, past the disabled button.
    act(() => activity!.retryMore())
    act(() => activity!.retryMore())
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.activityPage.mock.calls.length).toBe(calls)

    await act(async () => resolve({ ok: true, events: rows('a', 2, 3), nextCursor: null }))
    await waitFor(() => expect(loaded()).toBe(5))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a dead change stream says the feed may be stale, by kind, and reconnects', async () => {
    proxy.activityPage.mockResolvedValue({ ok: true, events: rows('a', 2), nextCursor: null })
    renderWithProviders(<ActivityView />)
    await waitFor(() => expect(loaded()).toBe(2))
    await waitFor(() => expect(streams.changes.open()).toBe(1))
    act(() => streams.changes.fail(new Error('error_home_stream_ended')))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_stream_ended')
    expect(ids()).toEqual(['a0', 'a1'])
    act(() => within(alert).getByRole('button', { name: 'Reconnect' }).click())
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    expect(streams.changes.open()).toBe(1)
  })
})
