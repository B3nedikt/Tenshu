// `NotificationFeed` subscribes to `Notifications.observe(cb) → unsub`.
// Each emission delivers the full snapshot — the component sorts by
// timestamp DESC and lets the user hide individual entries (local
// state) or all of them at once. Pagination caps the list at 20 per
// page.
//
// Mock the SDK module at the boundary so the feed can be exercised
// without a live Kotlin/JS runtime. Test fixtures are minimal `{uuid,
// timestamp}` objects matching the contract in `@tenshu/sdk/notifications`.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Notification } from '@tenshu/sdk/notifications'

const observeMock = vi.fn<(cb: (items: readonly Notification[]) => void) => () => void>()

vi.mock('@tenshu/sdk/notifications', () => ({
  Notifications: {
    observe: (cb: (items: readonly Notification[]) => void) => observeMock(cb),
  },
}))

import { NotificationFeed } from './NotificationFeed'

function fixture(uuid: string, ageMinutes: number): Notification {
  return { uuid, timestamp: Date.now() - ageMinutes * 60_000 }
}

function emit(items: readonly Notification[]) {
  observeMock.mockImplementation(cb => {
    cb(items)
    return () => {}
  })
}

describe('NotificationFeed', () => {
  it('shows the empty-state copy when the SDK reports zero items', () => {
    emit([])
    render(<NotificationFeed />)
    expect(
      screen.getByText('Activity will show up here as you use Tenshu.'),
    ).toBeInTheDocument()
  })

  it('renders one row per notification, sorted newest-first, with a singular count', () => {
    emit([
      fixture('alpha', 60), // 1h ago — older
      fixture('beta', 5), // 5m ago — newer
    ])
    render(<NotificationFeed />)
    expect(screen.getByText('2 notifications')).toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    // Newer entry first.
    expect(within(rows[0]!).getByText('beta')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('alpha')).toBeInTheDocument()
  })

  it('uses the singular form when exactly one notification is visible', () => {
    emit([fixture('only', 5)])
    render(<NotificationFeed />)
    expect(screen.getByText('1 notification')).toBeInTheDocument()
  })

  it('"Mark all read" hides every entry and shows the all-caught-up state', async () => {
    const user = userEvent.setup()
    emit([fixture('a', 5), fixture('b', 6)])
    render(<NotificationFeed />)
    await user.click(screen.getByRole('button', { name: 'Mark all read' }))
    expect(screen.getByText("You're all caught up.")).toBeInTheDocument()
    expect(screen.queryByText('a')).toBeNull()
    expect(screen.queryByText('b')).toBeNull()
  })

  it('per-row "Clear notification" hides only that row', async () => {
    const user = userEvent.setup()
    emit([fixture('keep-me', 5), fixture('drop-me', 6)])
    render(<NotificationFeed />)
    const dropRow = screen.getByText('drop-me').closest('li')!
    await user.click(within(dropRow).getByRole('button', { name: 'Clear notification' }))
    expect(screen.queryByText('drop-me')).toBeNull()
    expect(screen.getByText('keep-me')).toBeInTheDocument()
    expect(screen.getByText('1 notification')).toBeInTheDocument()
  })

  it('"Show older" reveals the next page once more than 20 items are present', async () => {
    const user = userEvent.setup()
    const items: Notification[] = []
    for (let i = 0; i < 25; i++) items.push(fixture(`n-${i}`, i))
    emit(items)
    render(<NotificationFeed />)
    expect(screen.getAllByRole('listitem')).toHaveLength(20)
    await user.click(screen.getByRole('button', { name: 'Show older' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(25)
    expect(screen.queryByRole('button', { name: 'Show older' })).toBeNull()
  })
})
