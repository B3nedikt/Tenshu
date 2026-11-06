// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams.history` / `streams.grants`.
import { vi } from 'vitest'
import type {
  AskResult,
  GrantsResult,
  HistoryResult,
  MiaAnswer,
  MiaConversation,
  MiaFailure,
  MiaGrant,
  MiaHit,
  MiaSource,
  MiaTurn,
  SetGrantResult,
  TurnsPageResult,
} from '@tenshu/sdk/mia'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean }

function stream<T>() {
  const subs: Sub<T>[] = []
  return {
    subs,
    subscribe: (onValue: (v: T) => void, onError: (e: Error) => void) => {
      const sub: Sub<T> = { onValue, onError, closed: false }
      subs.push(sub)
      return () => {
        sub.closed = true
      }
    },
    push(value: T) {
      for (const s of subs) if (!s.closed) s.onValue(value)
    },
    fail(error: Error) {
      for (const s of subs) if (!s.closed) s.onError(error)
    },
    reset: () => subs.splice(0),
  }
}

export const streams = {
  history: stream<HistoryResult>(),
  grants: stream<GrantsResult>(),
}

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  ask: vi.fn(async (_u: string): Promise<AskResult> => failure('error_generic')),
  history: vi.fn(async (): Promise<HistoryResult> => ({ ok: true, conversations: [], replying: false })),
  subscribeHistory: vi.fn(streams.history.subscribe),
  turnsPage: vi.fn(async (_id: string, _c: string | null): Promise<TurnsPageResult> => ({ ok: true, turns: [], nextCursor: null })),
  grants: vi.fn(async (): Promise<GrantsResult> => ({ ok: true, grants: defaultGrants() })),
  subscribeGrants: vi.fn(streams.grants.subscribe),
  setGranted: vi.fn(async (_s: MiaSource, _g: boolean): Promise<SetGrantResult> => ({ ok: true, grants: defaultGrants() })),
  setWritable: vi.fn(async (_s: MiaSource, _g: boolean): Promise<SetGrantResult> => ({ ok: true, grants: defaultGrants() })),
}

export const proxyModule = {
  ...proxy,
  ProxyMethod: {},
  MAX_UTTERANCE_CHARS: 500,
  TURN_PAGE_SIZE: 20,
  APEX: 'http://localhost:5173',
}

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: since Vitest 3 `restoreMocks` leaves `vi.fn(impl)` alone, so
  // a `mockResolvedValue` would leak into the next test. `mockReset` puts each back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  streams.history.reset()
  streams.grants.reset()
}

export function failure(kind: string): MiaFailure {
  return { ok: false, error: { kind, message: `diagnostic for ${kind}` } }
}

// ── fixtures ─────────────────────────────────────────────────────────────────

export const SOURCES: MiaSource[] = ['FILES', 'TIMELINE', 'FITNESS', 'PLACES', 'DEVICES', 'ROUTINES', 'LABELS', 'VISUAL_SEARCH', 'CALENDAR']

// The proxy's defaults: every read on, Calendar's write tier off, no other write tier.
export function defaultGrants(overrides: Partial<Record<MiaSource, Partial<MiaGrant>>> = {}): MiaGrant[] {
  return SOURCES.map((source) => ({
    source,
    read: true,
    write: source === 'CALENDAR' ? false : null,
    ...overrides[source],
  }))
}

export function hit(type: string, title: string, subtitle = '', keys: string[] = [title]): MiaHit {
  return { target: { type, keys }, title, subtitle, atMs: null }
}

export function answer(verdict: MiaAnswer['verdict'], explanation: string, hits: MiaHit[] = []): MiaAnswer {
  return { verdict, explanation, hits }
}

export function turn(seq: number, utterance: string, a: MiaAnswer): MiaTurn {
  return { turnId: `t${seq}`, askedAtMs: 1_800_000_000_000 + seq, utterance, answer: a }
}

export function conversation(turnCount: number, title = 'what is on my calendar tomorrow'): MiaConversation {
  return { conversationId: 'c1', startedAtMs: 1_800_000_000_000, title, turnCount, updatedAtMs: 1_800_000_000_000 + turnCount }
}

export function historyOf(conversations: MiaConversation[], replying = false): HistoryResult {
  return { ok: true, conversations, replying }
}
