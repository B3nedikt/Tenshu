// Overview preview cards (docs/proposals/web-apex-home.md §3.6, H4).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { findHostedApp, HOSTED_APPS, type HostedApp } from '../../apps/registry'
import { GlanceCard } from './GlanceCard'
import { GlanceGrid } from './GlanceGrid'
import { GlanceGatewayContext, scopedCall, type GatewayCall } from './glanceGateway'
import { ShownAppsProvider } from '../ShownAppsProvider'
import { fakeShownApps } from '../../test/fakeShownApps'

// Plausible answers for the methods today's loaders call; anything else answers null.
function fakeGateway(overrides: Record<string, (params: unknown) => unknown> = {}) {
  const calls: string[] = []
  const answers: Record<string, (params: unknown) => unknown> = {
    'fs.recentFiles': () => [],
    'storage.status': () => null,
    'photo.feedPage': () => ({ ok: true, photos: [], nextCursor: null }),
    'fs.getPreview': () => ({ ok: true, bytes: new Int8Array([1, 2, 3]) }),
    'calendar.occurrences': () => ({ ok: true, occurrences: [], readerZone: '', readerZoneFallback: false }),
    'fit.dailyAggregates': () => ({ ok: true, days: [] }),
    'fit.goals': () => ({ ok: true, goals: { steps: 10_000, activeMinutes: 30 } }),
    'fit.workoutsPage': () => ({ ok: true, workouts: [], nextCursor: null }),
    'timeline.feed': () => ({ ok: true, days: [], trips: [], suggestion: null }),
    ...overrides,
  }
  const call: GatewayCall = async (method, params) => {
    calls.push(method)
    const answer = answers[method]
    return answer ? answer(params) : null
  }
  return { call, calls }
}

const drive = findHostedApp('drive')!
const photos = findHostedApp('photos')!

beforeEach(() => {
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:tile', revokeObjectURL: () => {} }))
})
afterEach(() => vi.unstubAllGlobals())

describe('scopedCall', () => {
  // ⚠⚠ The card's trust boundary: the same check the gateway applies to the app's origin.
  it('refuses a method outside the app\'s allowlist, in the gateway\'s words', async () => {
    const { call, calls } = fakeGateway()
    await expect(scopedCall(photos, call)('fs.delete', { path: '/x' })).rejects.toThrow(/^not permitted/)
    await expect(scopedCall(drive, call)('contact.setVerified')).rejects.toThrow(/^not permitted/)
    expect(calls).toEqual([])
  })

  it('passes an allowed method through', async () => {
    const { call, calls } = fakeGateway()
    await scopedCall(drive, call)('fs.recentFiles', { limit: 1 })
    expect(calls).toEqual(['fs.recentFiles'])
  })
})

describe('every registered glance', () => {
  // A loader that reaches outside its app's allowlist would throw `not permitted` here — and in
  // production, where it would show the error state forever.
  const withGlance = HOSTED_APPS.filter((app): app is HostedApp & { glance: NonNullable<HostedApp['glance']> } =>
    app.glance !== undefined,
  )

  it('is declared by Drive, Photos, Calendar, Fit and Timeline', () => {
    expect(withGlance.map(a => a.id)).toEqual(expect.arrayContaining(['drive', 'photos', 'calendar', 'fit', 'timeline']))
  })

  for (const app of withGlance) {
    it(`${app.id} loads through its own scoped call`, async () => {
      const { call } = fakeGateway()
      await expect(app.glance.load(scopedCall(app, call))).resolves.toBeDefined()
    })
  }

  it('Photos can preview its tiles through its own scope', async () => {
    const { call } = fakeGateway()
    await expect(scopedCall(photos, call)('fs.getPreview', { path: '/p.jpg', width: 1, height: 1 })).resolves.toBeDefined()
  })
})

describe('Drive glance', () => {
  it('lists recent files (not folders) with a type badge, and the storage bar when funded', async () => {
    const { call } = fakeGateway({
      'fs.recentFiles': () => [
        { path: '/Docs', size: 0, lastModified: 1, isFolder: true },
        { path: '/Docs/lease.pdf', size: 840_000, lastModified: 2, isFolder: false },
        { path: '/README', size: 10, lastModified: 3, isFolder: false },
      ],
      'storage.status': () => ({ hasStorage: true, usedBytes: 5, limitBytes: 10 }),
    })
    const data = await drive.glance!.load(scopedCall(drive, call))
    expect(data).toEqual({
      layout: 'list',
      rows: [
        { title: 'lease.pdf', badge: 'PDF', timeMs: 2, sizeBytes: 840_000 },
        { title: 'README', badge: '', timeMs: 3, sizeBytes: 10 },
      ],
      usage: { usedBytes: 5, limitBytes: 10 },
    })
  })

  // The bar is garnish: its failure must not take the card down.
  it('drops the storage bar when storage cannot be read', async () => {
    const { call } = fakeGateway({ 'storage.status': () => Promise.reject(new Error('offline')) })
    const data = await drive.glance!.load(scopedCall(drive, call))
    expect(data).toMatchObject({ layout: 'list', usage: null })
  })
})

describe('Photos glance', () => {
  // ⚠⚠ `{ ok: false }` is a failure, never an empty grid.
  it('throws on a failed feed read', async () => {
    const { call } = fakeGateway({ 'photo.feedPage': () => ({ ok: false, error: { kind: 'boom' } }) })
    await expect(photos.glance!.load(scopedCall(photos, call))).rejects.toThrow('boom')
  })
})

describe('GlanceCard', () => {
  function renderCard(app: HostedApp, gateway: GatewayCall) {
    return render(
      <MemoryRouter>
        <GlanceCard app={app} glance={app.glance!} call={scopedCall(app, gateway)} />
      </MemoryRouter>,
    )
  }

  it('a failed load shows the failure and a retry — never the empty state', async () => {
    let fail = true
    const { call } = fakeGateway({
      'fs.recentFiles': () =>
        fail ? Promise.reject(new Error('offline')) : [{ path: '/a.txt', size: 1, lastModified: 1, isFolder: false }],
    })
    renderCard(drive, call)
    expect(await screen.findByText("Couldn't load this right now.")).toBeInTheDocument()
    expect(screen.queryByText(/No files yet/)).toBeNull()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('a.txt')).toBeInTheDocument()
  })

  it('an empty answer shows the app\'s empty state', async () => {
    renderCard(drive, fakeGateway().call)
    expect(await screen.findByText('No files yet. Uploads show up here.')).toBeInTheDocument()
  })

  it('the header opens the app', async () => {
    renderCard(drive, fakeGateway().call)
    expect(await screen.findByRole('link', { name: /Drive/ })).toHaveAttribute('href', '/drive')
  })

  it('a grid draws each tile through the card\'s own fs.getPreview', async () => {
    const { call, calls } = fakeGateway({
      'photo.feedPage': () => ({
        ok: true,
        nextCursor: null,
        photos: [
          { path: '/p1.jpg', isVideo: false, durationMs: null },
          { path: '/v1.mp4', isVideo: true, durationMs: 14_000 },
        ],
      }),
    })
    const { container } = renderCard(photos, call)
    expect(await screen.findByText('0:14')).toBeInTheDocument()
    await waitFor(() => expect(container.querySelectorAll('img')).toHaveLength(2))
    expect(calls.filter(m => m === 'fs.getPreview')).toHaveLength(2)
  })
})

describe('GlanceGrid', () => {
  function renderGrid(stored: string[], gateway: GatewayCall | null) {
    return render(
      <GlanceGatewayContext.Provider value={gateway}>
        <ShownAppsProvider source={fakeShownApps(stored)}>
          <MemoryRouter>
            <GlanceGrid />
          </MemoryRouter>
        </ShownAppsProvider>
      </GlanceGatewayContext.Provider>,
    )
  }

  it('draws a card per shown app with a glance, in the user\'s order', async () => {
    renderGrid(['photos', 'drive', 'fit'], fakeGateway().call)
    await screen.findByRole('article', { name: 'Drive' })
    const cards = screen.getAllByRole('article').map(a => a.getAttribute('aria-labelledby'))
    expect(cards).toEqual(['glance-photos', 'glance-drive', 'glance-fit'])
  })

  it('a hidden app has no card', async () => {
    renderGrid(['drive'], fakeGateway().call)
    await screen.findByRole('article', { name: 'Drive' })
    expect(screen.queryByRole('article', { name: 'Photos' })).toBeNull()
  })

  it('draws nothing without a gateway', async () => {
    renderGrid(['drive', 'photos'], null)
    await new Promise(r => setTimeout(r, 0))
    expect(screen.queryByRole('article')).toBeNull()
  })
})

const calendar = findHostedApp('calendar')!
const fit = findHostedApp('fit')!
const timeline = findHostedApp('timeline')!

const HOUR = 3_600_000
const localNow = () => new Date()
const pad = (n: number) => String(n).padStart(2, '0')
const civil = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

describe('Calendar glance', () => {
  it('lists the next three events still to end, soonest first, on the reader\'s clock', async () => {
    const now = Date.now()
    const occ = (title: string, start: number, end: number) => ({
      title, location: '', startLocal: 'ignored', allDay: false, startEpochMs: start, endEpochMs: end,
    })
    const { call } = fakeGateway({
      'calendar.occurrences': () => ({
        ok: true,
        occurrences: [
          occ('later', now + 5 * HOUR, now + 6 * HOUR),
          occ('over', now - 3 * HOUR, now - 2 * HOUR),
          occ('now', now - HOUR, now + HOUR),
          occ('soon', now + HOUR, now + 2 * HOUR),
          occ('last', now + 9 * HOUR, now + 10 * HOUR),
        ],
      }),
    })
    const data = await calendar.glance!.load(scopedCall(calendar, call))
    expect(data.layout === 'agenda' && data.rows.map(r => r.title)).toEqual(['now', 'soon', 'later'])
  })

  // A conference since yesterday is labelled by today, not by the day it began.
  it('labels an all-day event already under way by today', async () => {
    const now = Date.now()
    const { call } = fakeGateway({
      'calendar.occurrences': () => ({
        ok: true,
        occurrences: [{ title: 'Conf', location: '', startLocal: '2000-01-01', allDay: true, startEpochMs: now - 30 * HOUR, endEpochMs: now + 20 * HOUR }],
      }),
    })
    const data = await calendar.glance!.load(scopedCall(calendar, call))
    expect(data.layout === 'agenda' && data.rows[0]!.startLocal).toBe(civil(localNow()))
  })

  it('throws on a failed read — never "nothing coming up"', async () => {
    const { call } = fakeGateway({ 'calendar.occurrences': () => ({ ok: false, error: { kind: 'boom' } }) })
    await expect(calendar.glance!.load(scopedCall(calendar, call))).rejects.toThrow('boom')
  })
})

describe('Fit glance', () => {
  it('today\'s steps against the goal, with the last workout', async () => {
    const { call } = fakeGateway({
      'fit.dailyAggregates': () => ({ ok: true, days: [{ steps: 7_500 }] }),
      'fit.workoutsPage': () => ({
        ok: true,
        nextCursor: null,
        workouts: [{ title: '', activityType: 'Running', startLocal: '2026-11-11T07:14:00' }],
      }),
    })
    expect(await fit.glance!.load(scopedCall(fit, call))).toEqual({
      layout: 'stat',
      value: 7_500,
      unit: 'steps',
      progress: 0.75,
      // No stored title: the apex words the activity type (never the raw 'Running' token).
      footnote: { title: '', titleKey: 'activity.running', startLocal: '2026-11-11T07:14', allDay: false, detail: '' },
    })
  })

  // 0 steps is a real figure, not "no data"; the goal and workout are garnish.
  it('shows 0 steps, and drops the bar and the line when goal and workouts fail', async () => {
    const { call } = fakeGateway({
      'fit.goals': () => Promise.reject(new Error('x')),
      'fit.workoutsPage': () => ({ ok: false, error: { kind: 'y' } }),
    })
    expect(await fit.glance!.load(scopedCall(fit, call))).toMatchObject({ value: 0, progress: null, footnote: null })
  })

  it('throws when today\'s figure cannot be read', async () => {
    const { call } = fakeGateway({ 'fit.dailyAggregates': () => ({ ok: false, error: { kind: 'boom' } }) })
    await expect(fit.glance!.load(scopedCall(fit, call))).rejects.toThrow('boom')
  })
})

describe('Timeline glance', () => {
  // ⚠ Never the stored, ingest-composed English `title`: names are data, the rest is catalogue keys.
  it('today\'s latest three moments, newest first, worded like the Timeline app', async () => {
    const today = civil(localNow())
    const t0 = new Date(`${today}T08:00`).getTime()
    const m = (ts: number, extra: Record<string, unknown>) => ({
      title: 'English ingest title', timestampMs: ts, kind: 'Visit', placeName: null, placeCity: null,
      placeRole: null, movementMode: null, activityType: null, ...extra,
    })
    const { call } = fakeGateway({
      'timeline.feed': () => ({
        ok: true,
        trips: [],
        suggestion: null,
        days: [
          {
            date: today,
            moments: [
              m(t0, { kind: 'Visit', placeName: 'Flat', placeRole: 'home' }),
              m(t0 + HOUR, { kind: 'Visit', placeName: 'Café Luna', placeCity: 'Berlin' }),
              m(t0 + 2 * HOUR, { kind: 'Movement', movementMode: 'transit' }),
              m(t0 + 3 * HOUR, { kind: 'Movement', movementMode: 'hoverboard' }),
            ],
          },
          { date: '2000-01-01', moments: [m(1, {})] },
        ],
      }),
    })
    const data = await timeline.glance!.load(scopedCall(timeline, call))
    expect(data.layout === 'agenda' && data.rows.map(r => [r.title, r.titleKey, r.detail])).toEqual([
      ['', 'movement.unknown', ''],
      ['', 'movement.transit', ''],
      ['Café Luna', '', 'Berlin'],
    ])
  })

  it('a home/work visit is worded by its role', async () => {
    const today = civil(localNow())
    const { call } = fakeGateway({
      'timeline.feed': () => ({
        ok: true, trips: [], suggestion: null,
        days: [{ date: today, moments: [{ title: 'x', timestampMs: Date.now(), kind: 'Visit', placeName: 'Flat', placeCity: null, placeRole: 'home', movementMode: null, activityType: null }] }],
      }),
    })
    const data = await timeline.glance!.load(scopedCall(timeline, call))
    expect(data.layout === 'agenda' && data.rows[0]).toMatchObject({ title: '', titleKey: 'placeRole.home' })
  })

  it('throws on a failed feed', async () => {
    const { call } = fakeGateway({ 'timeline.feed': () => ({ ok: false, error: { kind: 'boom' } }) })
    await expect(timeline.glance!.load(scopedCall(timeline, call))).rejects.toThrow('boom')
  })
})

describe('agenda and stat cards', () => {
  function renderCard(app: HostedApp, gateway: GatewayCall) {
    return render(
      <MemoryRouter>
        <GlanceCard app={app} glance={app.glance!} call={scopedCall(app, gateway)} />
      </MemoryRouter>,
    )
  }

  // The clock is pinned: an event "later today" must not have ended when the suite runs at 23:55.
  afterEach(() => vi.useRealTimers())

  it('an agenda row says "Today · HH:mm"', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    const morning = new Date()
    morning.setHours(9, 0, 0, 0)
    vi.setSystemTime(morning)
    const start = new Date(morning)
    start.setHours(23, 30, 0, 0)
    const { call } = fakeGateway({
      'calendar.occurrences': () => ({
        ok: true,
        occurrences: [
          { title: 'Late call', location: 'Room 4', startLocal: '', allDay: false, startEpochMs: start.getTime(), endEpochMs: start.getTime() + 20 * 60_000 },
        ],
      }),
    })
    renderCard(calendar, call)
    expect(await screen.findByText('Late call')).toBeInTheDocument()
    expect(screen.getByText('Today · 23:30 · Room 4')).toBeInTheDocument()
  })

  it('a footnote without a stored title is worded, in the reader\'s language catalogue', async () => {
    const { call } = fakeGateway({
      'fit.workoutsPage': () => ({ ok: true, nextCursor: null, workouts: [{ title: '', activityType: 'Cycling', startLocal: '2000-01-03T07:00' }] }),
    })
    renderCard(fit, call)
    expect(await screen.findByText(/^Last workout · Cycling · /)).toBeInTheDocument()
  })

  it('a stat card draws the number and the goal meter', async () => {
    const { call } = fakeGateway({ 'fit.dailyAggregates': () => ({ ok: true, days: [{ steps: 2_500 }] }) })
    renderCard(fit, call)
    expect(await screen.findByText('2,500')).toBeInTheDocument()
    expect(screen.getByRole('meter', { name: 'Progress towards your goal' })).toHaveAttribute('aria-valuenow', '25')
  })

  it('an empty agenda says so, per app', async () => {
    renderCard(calendar, fakeGateway().call)
    expect(await screen.findByText('Nothing coming up this week.')).toBeInTheDocument()
  })
})
