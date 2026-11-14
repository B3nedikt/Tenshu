// The top bar's bell and sync pill (docs/proposals/web-apex-home.md §3.4, H5b), over a fake source.
import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { Bell } from './Bell'
import { SyncPill } from './SyncPill'
import { syncView } from './syncView'
import { StatusContext, type RecentEvent, type StatusSource, type SyncRow } from './statusContext'

const recovery = vi.hoisted(() => ({ kind: 'verified' as string }))
vi.mock('../control/recoveryStatus', () => ({
  getRecoveryStatus: () => Promise.resolve({ kind: recovery.kind, verifiedAtMs: 0 }),
}))

function fakeStatus(opts: {
  events?: readonly RecentEvent[] | null
  row?: SyncRow | null
  syncNow?: () => Promise<boolean>
  /** The sync stream ends right after its first row. */
  syncEnds?: boolean
  paused?: boolean
}) {
  const limits: number[] = []
  // A stand-in for the browser's persisted switch: one value, every observer told of each change.
  let paused = opts.paused ?? false
  const pauseObservers = new Set<(p: boolean) => void>()
  const setPaused = vi.fn((next: boolean) => {
    paused = next
    pauseObservers.forEach(cb => cb(next))
  })
  const source: StatusSource = {
    observeRecent(limit, onEvents) {
      limits.push(limit)
      queueMicrotask(() => onEvents(opts.events === undefined ? [] : opts.events))
      return () => {}
    },
    observeSync(onRow, onLost) {
      queueMicrotask(() => {
        onRow(opts.row ?? null)
        if (opts.syncEnds) onLost()
      })
      return () => {}
    },
    syncNow: opts.syncNow ?? (async () => true),
    observePaused(onPaused) {
      pauseObservers.add(onPaused)
      queueMicrotask(() => onPaused(paused))
      return () => pauseObservers.delete(onPaused)
    },
    setPaused,
  }
  return { source, limits, setPaused, otherTabSets: (next: boolean) => {
    paused = next
    pauseObservers.forEach(cb => cb(next))
  } }
}

function renderWith(source: StatusSource, ui: React.ReactNode) {
  return render(
    <StatusContext.Provider value={source}>
      <MemoryRouter>{ui}</MemoryRouter>
    </StatusContext.Provider>,
  )
}

const event = (kind: string, name: string, otherCount = 0, id = kind): RecentEvent => ({
  id, timestamp: Date.UTC(2026, 10, 13, 9, 30), kind, name, otherCount,
})

describe('syncView', () => {
  // ⚠ Before the runtime answers, the pill must not claim "Synced".
  it('says Checking until a row arrives', () => {
    expect(syncView(null).key).toBe('checking')
  })

  it('words each connectivity', () => {
    const row = (connectivity: string, extra: Partial<SyncRow> = {}): SyncRow => ({
      connectivity, isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: 1, ...extra,
    })
    expect(syncView(row('online')).key).toBe('synced')
    expect(syncView(row('online', { pendingBlockCount: 3 })).key).toBe('syncing')
    expect(syncView(row('online', { isSyncing: true })).key).toBe('syncing')
    expect(syncView(row('offline')).key).toBe('offline')
    expect(syncView(row('device_unrecognized'))).toEqual({ key: 'attention', tone: 'warn' })
    expect(syncView(row('something-new')).key).toBe('unknown')
  })

  // ⚠⚠ A paused browser sends and fetches nothing: its last "online" row is no promise of "Synced".
  it('says Paused over every row while paused', () => {
    const online: SyncRow = { connectivity: 'online', isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: 1 }
    expect(syncView(online, false, true)).toEqual({ key: 'paused', tone: 'muted' })
    expect(syncView(null, false, true).key).toBe('paused')
    expect(syncView(online, true, true).key).toBe('paused')
  })
})

describe('SyncPill', () => {
  it('shows its state, and Sync now reports that it was requested', async () => {
    const syncNow = vi.fn(async () => true)
    const { source } = fakeStatus({
      row: { connectivity: 'online', isSyncing: false, pendingBlockCount: 2, lastSyncedAtMillis: Date.UTC(2026, 0, 1) },
      syncNow,
    })
    renderWith(source, <SyncPill compact={false} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Sync: Syncing' }))
    const panel = screen.getByRole('dialog', { name: 'Sync' })
    expect(within(panel).getByText('2')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Sync now' }))
    expect(syncNow).toHaveBeenCalledOnce()
    expect(within(panel).getByRole('status')).toHaveTextContent('Sync requested.')
  })

  it('says so when a sync cannot be started', async () => {
    const { source } = fakeStatus({ syncNow: async () => false })
    renderWith(source, <SyncPill compact />)
    await userEvent.click(screen.getByRole('button', { name: /^Sync: / }))
    await userEvent.click(screen.getByRole('button', { name: 'Sync now' }))
    expect(screen.getByRole('status')).toHaveTextContent("Couldn't start a sync.")
  })

  it('inside an app it is a dot: no visible label', async () => {
    const { source } = fakeStatus({ row: { connectivity: 'offline', isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: 0 } })
    renderWith(source, <SyncPill compact />)
    const pill = await screen.findByRole('button', { name: 'Sync: Offline' })
    expect(pill).not.toHaveTextContent('Offline')
  })
})

describe('SyncPill after review', () => {
  const synced: SyncRow = { connectivity: 'online', isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: 1 }

  // ⚠⚠ A dead stream must not leave "Synced" standing.
  it('stops claiming its last state once the stream ends', async () => {
    const { source } = fakeStatus({ row: synced, syncEnds: true })
    renderWith(source, <SyncPill compact={false} />)
    expect(await screen.findByRole('button', { name: 'Sync: Unknown' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sync: Synced' })).toBeNull()
  })

  it('sends one kick for a double click, and forgets "requested" when reopened', async () => {
    let release: (ok: boolean) => void = () => {}
    const syncNow = vi.fn(() => new Promise<boolean>(r => (release = r)))
    const { source } = fakeStatus({ row: synced, syncNow })
    renderWith(source, <SyncPill compact={false} />)
    const pill = await screen.findByRole('button', { name: 'Sync: Synced' })
    await userEvent.click(pill)
    const now = screen.getByRole('button', { name: 'Sync now' })
    await userEvent.dblClick(now)
    expect(syncNow).toHaveBeenCalledOnce()
    await act(async () => release(true))
    expect(screen.getByRole('status')).toHaveTextContent('Sync requested.')
    await userEvent.click(pill)
    await userEvent.click(pill)
    expect(screen.queryByText('Sync requested.')).toBeNull()
  })
})

describe('SyncPill pause', () => {
  const synced: SyncRow = { connectivity: 'online', isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: 1 }

  it('pauses: the pill reads Paused and Resume replaces Sync now', async () => {
    const syncNow = vi.fn(async () => true)
    const { source, setPaused } = fakeStatus({ row: synced, syncNow })
    renderWith(source, <SyncPill compact={false} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Sync: Synced' }))
    await userEvent.click(screen.getByRole('button', { name: 'Pause' }))
    expect(setPaused).toHaveBeenCalledWith(true)
    expect(screen.getByRole('button', { name: 'Sync: Paused' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sync now' })).toBeNull()
    expect(screen.getByText(/Paused in this browser/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Resume' }))
    expect(setPaused).toHaveBeenLastCalledWith(false)
    expect(screen.getByRole('button', { name: 'Sync: Synced' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sync now' })).toBeInTheDocument()
    expect(syncNow).not.toHaveBeenCalled()
  })

  // The switch is persisted per browser: a reload (or another tab) starts out paused.
  it('starts Paused when the browser already is, and follows another tab', async () => {
    const { source, otherTabSets } = fakeStatus({ row: synced, paused: true })
    renderWith(source, <SyncPill compact />)
    expect(await screen.findByRole('button', { name: 'Sync: Paused' })).toBeInTheDocument()
    await act(async () => otherTabSets(false))
    expect(screen.getByRole('button', { name: 'Sync: Synced' })).toBeInTheDocument()
  })
})

describe('Bell', () => {
  it('shows a date, not a bare time, for an older event', async () => {
    recovery.kind = 'verified'
    const old = { ...event('file_added', '/a/old.txt/'), timestamp: Date.UTC(2020, 0, 2, 12) }
    const { source } = fakeStatus({ events: [old] })
    renderWith(source, <Bell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))
    const row = (await screen.findByText('Added old.txt')).closest('li')!
    expect(row).not.toHaveTextContent(/\d{1,2}:\d{2}/)
  })

  it('lists the last four events in the tray\'s words, and links the full history', async () => {
    recovery.kind = 'verified'
    const { source, limits } = fakeStatus({
      events: [
        event('file_added', '/Docs/notes.txt'),
        event('files_added', '/Pics/a.png', 3),
        event('app_registered', 'Fit'),
        event('from-a-newer-build', 'x'),
      ],
    })
    renderWith(source, <Bell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))
    const panel = screen.getByRole('dialog', { name: 'Notifications' })
    expect(limits).toEqual([4])
    expect(await within(panel).findByText('Added notes.txt')).toBeInTheDocument()
    expect(within(panel).getByText('Added a.png and 3 more')).toBeInTheDocument()
    expect(within(panel).getByText('Fit connected')).toBeInTheDocument()
    expect(within(panel).getByText('Something changed')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'All notifications' })).toHaveAttribute('href', '/control/home')
  })

  // ⚠⚠ A failed read is a failure, never "Nothing new".
  it('says it could not load, never "nothing new", when the read fails', async () => {
    recovery.kind = 'verified'
    const { source } = fakeStatus({ events: null })
    renderWith(source, <Bell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))
    expect(await screen.findByText("Couldn't load notifications.")).toBeInTheDocument()
    expect(screen.queryByText('Nothing new.')).toBeNull()
  })

  it('badges and lists what needs action', async () => {
    recovery.kind = 'never'
    const { source } = fakeStatus({ events: [] })
    renderWith(source, <Bell />)
    const bell = await screen.findByRole('button', { name: 'Notifications, 1 needs action' })
    await userEvent.click(bell)
    const panel = screen.getByRole('dialog', { name: 'Notifications' })
    expect(within(panel).getByRole('link', { name: /recovery phrase/ })).toHaveAttribute('href', '/control/settings/recovery')
    expect(within(panel).getByText('Nothing new.')).toBeInTheDocument()
  })
})
