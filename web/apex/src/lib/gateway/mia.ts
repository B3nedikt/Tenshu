// `mia.*`: the Mia web surface (web-app-suite Wave 4, Mia run A). Thin, validated pass-throughs to
// `Mia` (`@tenshu/sdk/mia`). The rules (native's planner and verdicts, the in-memory conversation,
// keyset turns, the grants as the proxy reads them) live in the Kotlin SDK and are pinned by
// `MiaWebContractTest`.
//
// ⚠⚠ No confirm, no delete. Two things here write: `mia.ask` appends to the in-memory conversation,
// and the two grant switches write native Settings' realm rows (run B, web-app-suite §8,
// 2026-10-28). ⚠⚠ Only the Mia origin's allowlist names `mia.*` (F5; threat model A39);
// `proxyGateway.mia.test.ts` pins that.
import { MAX_TURN_PAGE_SIZE, MAX_UTTERANCE_CHARS, MIA_SOURCES, Mia, MiaMethod, type MiaSource } from '@tenshu/sdk/mia'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const miaGateway: GatewayModule = {
  handlers: {
    [MiaMethod.Ask]: async (params) => Mia.ask(readUtterance(params), readString(params, 'zone', MiaMethod.Ask)),
    [MiaMethod.History]: async () => Mia.history(),
    // A keyset page in, a page and the next cursor out. Never the whole conversation.
    [MiaMethod.TurnsPage]: async (params) => {
      const { cursor, pageSize } = readPage(params)
      return Mia.turnsPage(readId(params, 'conversationId', MiaMethod.TurnsPage), cursor, pageSize)
    },
    [MiaMethod.Grants]: async () => Mia.grants(),
    [MiaMethod.SetGranted]: async (params) => {
      const { source, granted } = readSwitch(params, MiaMethod.SetGranted)
      return Mia.setGranted(source, granted)
    },
    [MiaMethod.SetWritable]: async (params) => {
      const { source, granted } = readSwitch(params, MiaMethod.SetWritable)
      return Mia.setWritable(source, granted)
    },
  },
  subscriptions: {
    // Genuine push. ⚠ `fail` is wired through: a collector that dies ends the subscriber's stream
    // with a terminal error instead of freezing its screen.
    [MiaMethod.HistoryObserve]: (_params, emit, fail) => Mia.observeHistory((result) => emit(result), fail),
    [MiaMethod.GrantsObserve]: (_params, emit, fail) => Mia.observeGrants((result) => emit(result), fail),
  },
}

// ⚠ Bounded here as well as in the SDK, so an oversized string is refused before it is cloned into
// the runtime. Measured on the trimmed text, as the SDK measures it; a blank one is the SDK's typed
// `error_mia_empty_utterance`, not a gateway throw, so the app can render it like any refusal.
function readUtterance(params: unknown): string {
  const v = asRecord(params).utterance
  if (typeof v !== 'string') throw new Error(`${MiaMethod.Ask} requires { utterance: string }`)
  if (v.trim().length > MAX_UTTERANCE_CHARS) {
    throw new Error(`${MiaMethod.Ask} requires { utterance: at most ${MAX_UTTERANCE_CHARS} characters }`)
  }
  return v
}

// `cursor` is optional (absent or null = the head) and opaque. `pageSize` is a required integer in
// 1..MAX_TURN_PAGE_SIZE: a missing one must not quietly become "the whole conversation".
function readPage(params: unknown): { cursor: string | null; pageSize: number } {
  const r = asRecord(params)
  const cursor = r.cursor ?? null
  if (cursor !== null && typeof cursor !== 'string') {
    throw new Error(`${MiaMethod.TurnsPage} requires { cursor: string | null }`)
  }
  const pageSize = r.pageSize
  if (typeof pageSize !== 'number' || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > MAX_TURN_PAGE_SIZE) {
    throw new Error(`${MiaMethod.TurnsPage} requires { pageSize: integer 1..${MAX_TURN_PAGE_SIZE} }`)
  }
  return { cursor, pageSize }
}

// `{ source, granted }`: a `MiaSource` name and a boolean. ⚠ A string 'false' must never become a
// truthy switch, so the type is checked exactly. A source outside `MIA_SOURCES` is refused here; a
// write switch on a source with no write tier is the SDK's typed `error_mia_no_write_grant`.
function readSwitch(params: unknown, method: string): { source: MiaSource; granted: boolean } {
  const r = asRecord(params)
  const source = r.source
  if (typeof source !== 'string' || !(MIA_SOURCES as readonly string[]).includes(source)) {
    throw new Error(`${method} requires { source: one of ${MIA_SOURCES.join(', ')} }`)
  }
  if (typeof r.granted !== 'boolean') throw new Error(`${method} requires { granted: boolean }`)
  return { source: source as MiaSource, granted: r.granted }
}
