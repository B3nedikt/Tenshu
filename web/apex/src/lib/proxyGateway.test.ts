// Gateway coverage for the `:timeline-service` raw read bridge.
//
// ⚠ Every `@tenshu/sdk/*` module here is mocked — the real ones value-import the
// Kotlin/JS bundle (`web/sdk/src/_bundle.ts`), which drags ~1.7 MB of runtime
// into the test process and needs a Gradle build to even exist. Mocking
// `serveProxyGateway` also lets us capture the handler map directly instead of
// driving real postMessage traffic; the transport itself is `@tenshu/sdk`'s
// concern, what this file guards is *our* wiring and param validation.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { TimelineMoment, TimelineTrace, TimelineTrip } from '@tenshu/sdk/timeline'

type Handler = (params: unknown) => unknown
let captured: Record<string, Handler> = {}

vi.mock('@tenshu/sdk/bridge', () => ({
  serveProxyGateway: (options: { handlers: Record<string, Handler> }) => {
    captured = options.handlers
    return { dispose: () => {} }
  },
}))

const momentsMock = vi.fn<() => Promise<TimelineMoment[]>>()
const momentMock = vi.fn<(id: string) => Promise<TimelineMoment | null>>()
const tripsMock = vi.fn<() => Promise<TimelineTrip[]>>()
const tracesMock = vi.fn<() => Promise<TimelineTrace[]>>()

vi.mock('@tenshu/sdk/timeline', () => ({
  Timeline: {
    moments: () => momentsMock(),
    moment: (momentId: string) => momentMock(momentId),
    trips: () => tripsMock(),
    traces: () => tracesMock(),
  },
}))

// The other SDK surfaces the gateway imports. Not exercised here; stubbed only
// so importing `proxyGateway` doesn't reach the real bundle.
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/calendar', () => ({ Calendar: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/photos', () => ({ Photos: {}, MAX_FEED_PAGE_SIZE: 500 }))
vi.mock('@tenshu/sdk/fit', () => ({ Fit: {}, MAX_WORKOUT_PAGE_SIZE: 200, MAX_ACTIVITY_FILE_BYTES: 33554432 }))
vi.mock('@tenshu/sdk/home', () => ({ Home: {}, MAX_ACTIVITY_PAGE_SIZE: 200 }))
vi.mock('@tenshu/sdk/mia', () => ({
  Mia: {},
  MAX_UTTERANCE_CHARS: 500,
  MAX_TURN_PAGE_SIZE: 100,
  MiaMethod: {
    Ask: 'mia.ask',
    History: 'mia.history',
    HistoryObserve: 'mia.history.observe',
    TurnsPage: 'mia.turnsPage',
    Grants: 'mia.grants',
    GrantsObserve: 'mia.grants.observe',
    SetGranted: 'mia.grants.setGranted',
    SetWritable: 'mia.grants.setWritable',
  },
}))

import { mountProxyGateway } from './proxyGateway'

// `noUncheckedIndexedAccess` types the map lookup as possibly-undefined; a
// missing handler is a wiring failure, so fail loudly here rather than at the
// call site.
function handler(method: string): Handler {
  const h = captured[method]
  if (!h) throw new Error(`gateway registered no handler for '${method}'`)
  return h
}

function moment(overrides: Partial<TimelineMoment> = {}): TimelineMoment {
  return {
    momentId: 'cap-visit:1',
    timestampMs: 1_700_000_000_000,
    kind: 'Visit',
    title: 'Home',
    durationSec: 3_600,
    subjectKind: 'place',
    subjectId: 'place-1',
    supersededBy: '',
    sources: ['capture'],
    ...overrides,
  }
}

describe('proxyGateway — timeline read bridge', () => {
  beforeEach(() => {
    // `restoreMocks` restores spies, not standalone `vi.fn()`s — without this the
    // "not dispatched" assertions below would see the previous test's call.
    vi.clearAllMocks()
    captured = {}
    mountProxyGateway()
  })

  // The full `timeline.*` set (run A's projected surface and writes included) is pinned in
  // `proxyGateway.timeline.test.ts`; this file covers the four raw reads.
  it('registers the four raw read methods', () => {
    const timelineMethods = Object.keys(captured).filter((m) => m.startsWith('timeline.'))
    expect(timelineMethods).toEqual(
      expect.arrayContaining(['timeline.moment', 'timeline.moments', 'timeline.traces', 'timeline.trips']),
    )
  })

  it('timeline.moments passes the feed through untouched', async () => {
    const feed = [moment(), moment({ momentId: 'cap-move:2', kind: 'Movement' })]
    momentsMock.mockResolvedValue(feed)

    await expect(handler('timeline.moments')(undefined)).resolves.toEqual(feed)
    expect(momentsMock).toHaveBeenCalledTimes(1)
  })

  it('timeline.moment forwards the id and passes a miss through as null', async () => {
    momentMock.mockResolvedValue(null)

    await expect(handler('timeline.moment')({ momentId: 'cap-visit:9' })).resolves.toBeNull()
    expect(momentMock).toHaveBeenCalledWith('cap-visit:9')
  })

  // A blank/absent id would otherwise resolve to "no such moment" and render as
  // an empty detail screen instead of surfacing the malformed call. The handler
  // is `async`, so the validator surfaces as a *rejection*, not a sync throw —
  // which is what the bridge turns into an `ok: false` response envelope.
  it.each([
    ['absent', {}],
    ['non-string', { momentId: 7 }],
    ['empty', { momentId: '' }],
    ['not an object', 'cap-visit:1'],
  ])('timeline.moment rejects a %s id without dispatching', async (_label, params) => {
    await expect(handler('timeline.moment')(params)).rejects.toThrow(/timeline\.moment requires/)
    expect(momentMock).not.toHaveBeenCalled()
  })

  it('timeline.trips and timeline.traces pass through', async () => {
    const trips: TimelineTrip[] = [
      { tripId: 't1', title: 'Lisbon', dateRange: '3–7 May', year: '2026', momentIds: ['m1'] },
    ]
    const traces: TimelineTrace[] = [{ traceId: 'cap-trace:1', mode: 'walk', distanceM: 1_240 }]
    tripsMock.mockResolvedValue(trips)
    tracesMock.mockResolvedValue(traces)

    await expect(handler('timeline.trips')(undefined)).resolves.toEqual(trips)
    await expect(handler('timeline.traces')(undefined)).resolves.toEqual(traces)
  })
})
