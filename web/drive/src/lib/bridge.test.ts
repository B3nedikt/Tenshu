import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  mountApexProxy,
  serveProxyGateway,
  type ProxyClient,
  type ProxyGateway,
} from '@tenshu/sdk/bridge'

// Wire names from the bridge envelope contract (kept in sync with bridge.ts).
const HELLO = 'tenshu/bridge/hello'
const READY = 'tenshu/bridge/ready'
const REQUEST = 'tenshu/bridge/req'
const RESPONSE = 'tenshu/bridge/res'

const APEX = 'https://tenshu.app'
const FOREIGN = 'https://evil.example'
// F5: every gateway now maps an origin to its allowlist. These envelope tests are not about
// permissions (`web/apex/src/lib/gatewayPermissions.test.ts` is), so the one origin may use every
// name they exercise.
const PERMITS = {
  [APEX]: ['fs.*', 'nope', 'boom', 'devices.*', 'stream', 'slow', 'failing'],
}

// Dispatch a `message` on the real window with a controllable origin/source —
// MessageEvent's `source` is read-only, so we define it after construction.
function fireMessage(data: unknown, origin: string, source?: { postMessage: ReturnType<typeof vi.fn> }) {
  const ev = new MessageEvent('message', { data, origin })
  if (source) Object.defineProperty(ev, 'source', { value: source, configurable: true })
  window.dispatchEvent(ev)
}

describe('serveProxyGateway', () => {
  let gateway: ProxyGateway | null = null
  afterEach(() => {
    gateway?.dispose()
    gateway = null
  })

  it('answers the hello handshake with ready', () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({ permissions: PERMITS, handlers: {} })

    fireMessage({ kind: HELLO }, APEX, source)

    expect(source.postMessage).toHaveBeenCalledWith({ kind: READY }, APEX)
  })

  it('dispatches a request to its handler and posts the result back', async () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: { 'fs.list': (params) => ({ echoed: params }) },
    })

    fireMessage({ kind: REQUEST, id: 'r1', method: 'fs.list', params: { path: '/' } }, APEX, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())

    expect(source.postMessage).toHaveBeenCalledWith(
      { kind: RESPONSE, id: 'r1', ok: true, result: { echoed: { path: '/' } } },
      APEX,
    )
  })

  it('rejects an unknown method', async () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({ permissions: PERMITS, handlers: {} })

    fireMessage({ kind: REQUEST, id: 'r2', method: 'nope', params: null }, APEX, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())

    expect(source.postMessage).toHaveBeenCalledWith(
      { kind: RESPONSE, id: 'r2', ok: false, error: "unknown method 'nope'" },
      APEX,
    )
  })

  it('surfaces a handler error as a redacted message (no stack)', async () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {
        boom: () => {
          throw new Error('kaboom')
        },
      },
    })

    fireMessage({ kind: REQUEST, id: 'r3', method: 'boom', params: null }, APEX, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())

    expect(source.postMessage).toHaveBeenCalledWith(
      { kind: RESPONSE, id: 'r3', ok: false, error: 'kaboom' },
      APEX,
    )
  })

  it('ignores messages from a non-allowed origin', () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({ permissions: PERMITS, handlers: { 'fs.list': () => [] } })

    fireMessage({ kind: HELLO }, FOREIGN, source)
    fireMessage({ kind: REQUEST, id: 'x', method: 'fs.list', params: null }, FOREIGN, source)

    expect(source.postMessage).not.toHaveBeenCalled()
  })
})

// Minimal fake iframe + document so the client can be exercised without a
// real cross-origin frame (jsdom can't route postMessage across iframes).
function fakeFrame() {
  const handlers: Record<string, Array<(e: unknown) => void>> = {}
  const contentWindow = { postMessage: vi.fn() }
  const el = {
    setAttribute: () => {},
    style: {} as Record<string, string>,
    src: '',
    contentWindow,
    addEventListener: (type: string, cb: (e: unknown) => void) => {
      ;(handlers[type] ??= []).push(cb)
    },
    remove: () => {},
  }
  const doc = {
    createElement: () => el,
    body: { appendChild: () => {} },
  } as unknown as Document
  return {
    doc,
    contentWindow,
    fireLoad: () => (handlers['load'] ?? []).forEach((cb) => cb(undefined)),
  }
}

describe('mountApexProxy (client)', () => {
  let client: ProxyClient | null = null
  afterEach(() => {
    client?.dispose()
    client = null
  })

  it('resolves on ready, correlates a call to its response, and posts the request', async () => {
    const frame = fakeFrame()
    const mounting = mountApexProxy({ apexOrigin: APEX, document: frame.doc })

    // Frame finishes loading → client sends hello; apex answers ready.
    frame.fireLoad()
    expect(frame.contentWindow.postMessage).toHaveBeenCalledWith({ kind: HELLO }, APEX)
    fireMessage({ kind: READY }, APEX, frame.contentWindow)

    client = await mounting

    const call = client.call('fs.list', { path: '/' })

    // The request envelope reached the apex frame; capture its generated id.
    const sent = frame.contentWindow.postMessage.mock.calls
      .map(([msg]) => msg as { kind: string; id?: string; method?: string })
      .find((m) => m.kind === REQUEST)
    expect(sent).toMatchObject({ method: 'fs.list', params: { path: '/' } })

    // Apex replies for that id → the call resolves with the result.
    fireMessage({ kind: RESPONSE, id: sent!.id, ok: true, result: [{ path: '/a.txt' }] }, APEX, frame.contentWindow)
    await expect(call).resolves.toEqual([{ path: '/a.txt' }])
  })

  it('rejects a call when the apex returns an error envelope', async () => {
    const frame = fakeFrame()
    const mounting = mountApexProxy({ apexOrigin: APEX, document: frame.doc })
    frame.fireLoad()
    fireMessage({ kind: READY }, APEX, frame.contentWindow)
    client = await mounting

    const call = client.call('fs.list')
    const sent = frame.contentWindow.postMessage.mock.calls
      .map(([msg]) => msg as { kind: string; id?: string })
      .find((m) => m.kind === REQUEST)
    fireMessage({ kind: RESPONSE, id: sent!.id, ok: false, error: 'denied' }, APEX, frame.contentWindow)

    await expect(call).rejects.toThrow('denied')
  })

  it('ignores response envelopes from a foreign origin', async () => {
    const frame = fakeFrame()
    const mounting = mountApexProxy({ apexOrigin: APEX, document: frame.doc })
    frame.fireLoad()
    fireMessage({ kind: READY }, APEX, frame.contentWindow)
    client = await mounting

    const call = client.call('fs.list', undefined)
    const sent = frame.contentWindow.postMessage.mock.calls
      .map(([msg]) => msg as { kind: string; id?: string })
      .find((m) => m.kind === REQUEST)

    // A spoofed response from another origin must not resolve the call.
    fireMessage({ kind: RESPONSE, id: sent!.id, ok: true, result: 'spoofed' }, FOREIGN, frame.contentWindow)
    const settled = await Promise.race([
      call.then(() => 'resolved').catch(() => 'rejected'),
      Promise.resolve('pending'),
    ])
    expect(settled).toBe('pending')
  })

  it('ignores a ready from another apex-origin window than the one it dialled', async () => {
    const frame = fakeFrame()
    const mounting = mountApexProxy({ apexOrigin: APEX, document: frame.doc })
    frame.fireLoad()
    // Right origin, wrong window — e.g. the embedding apex answering for our own frame.
    fireMessage({ kind: READY }, APEX, { postMessage: vi.fn() })
    const settled = await Promise.race([
      mounting.then(() => 'resolved'),
      new Promise((resolve) => setTimeout(() => resolve('pending'), 10)),
    ])
    expect(settled).toBe('pending')
  })
})

// Apex hosts Drive at `/drive/files` inside its runtime-holding shell. A nested hidden
// apex iframe would be a second runtime contending for the one `tenshu-runtime` lock the host
// page already holds — every call refused. Embedded in the apex, Drive must use its PARENT.
describe('mountApexProxy embedded in the apex', () => {
  let client: ProxyClient | null = null
  afterEach(() => {
    client?.dispose()
    client = null
  })

  function embedded() {
    const parent = { postMessage: vi.fn() }
    const host = { parent } as unknown as Window
    const createElement = vi.fn()
    const doc = { createElement, body: { appendChild: vi.fn() } } as unknown as Document
    return { parent, host, doc, createElement }
  }

  it('dials the embedding apex and mounts no iframe of its own', async () => {
    const { parent, host, doc, createElement } = embedded()
    const mounting = mountApexProxy({ apexOrigin: APEX, host, document: doc })

    expect(parent.postMessage).toHaveBeenCalledWith({ kind: HELLO }, APEX)
    fireMessage({ kind: READY }, APEX, parent)
    client = await mounting

    // Never answered; the afterEach dispose rejects it, which is expected here.
    client.call('fs.list', { path: '/' }).catch(() => undefined)
    const sent = parent.postMessage.mock.calls
      .map(([msg]) => msg as { kind: string; method?: string })
      .find((m) => m.kind === REQUEST)
    expect(sent).toMatchObject({ method: 'fs.list' })
    expect(createElement).not.toHaveBeenCalled()
  })

  // A parent KNOWN to be the apex is never given up on a timer — its main thread may be
  // blocked for seconds by hydration, and falling back would mount a second runtime.
  it('keeps waiting for a slow parent known to be the apex, and never mounts an iframe', async () => {
    const parent = { postMessage: vi.fn() }
    const host = { parent, location: { ancestorOrigins: [APEX] } } as unknown as Window
    const createElement = vi.fn()
    const doc = { createElement, body: { appendChild: vi.fn() } } as unknown as Document
    const mounting = mountApexProxy({
      apexOrigin: APEX, host, document: doc, parentHandshakeMs: 5, helloRetryMs: 5,
    })

    await new Promise((resolve) => setTimeout(resolve, 40))
    expect(createElement).not.toHaveBeenCalled()
    const hellos = parent.postMessage.mock.calls.filter(([m]) => (m as { kind: string }).kind === HELLO)
    expect(hellos.length).toBeGreaterThan(1)

    fireMessage({ kind: READY }, APEX, parent)
    client = await mounting
    expect(createElement).not.toHaveBeenCalled()
  })

  // In Firefox, after the framed app navigates itself the referrer is its OWN origin. That
  // says nothing about the parent — it must not read as "known not apex".
  it('treats a self-referrer as unknown and still dials a real apex parent (Firefox, after navigation)', async () => {
    const DRIVE = 'https://drive.tenshu.app'
    const parent = { postMessage: vi.fn() }
    const host = {
      parent,
      location: { origin: DRIVE },
      document: { referrer: `${DRIVE}/files` },
    } as unknown as Window
    const createElement = vi.fn()
    const doc = { createElement, body: { appendChild: vi.fn() } } as unknown as Document
    const mounting = mountApexProxy({ apexOrigin: APEX, host, document: doc, parentHandshakeMs: 1_000 })

    expect(parent.postMessage).toHaveBeenCalledWith({ kind: HELLO }, APEX)
    fireMessage({ kind: READY }, APEX, parent)
    client = await mounting
    expect(createElement).not.toHaveBeenCalled()
  })

  it('goes straight to its own iframe under a parent known NOT to be the apex', () => {
    const parent = { postMessage: vi.fn() }
    const host = { parent, location: { ancestorOrigins: [FOREIGN] } } as unknown as Window
    const frame = fakeFrame()
    void mountApexProxy({ apexOrigin: APEX, host, document: frame.doc }).catch(() => undefined)
    expect(parent.postMessage).not.toHaveBeenCalled()
  })

  it('reads the parent origin from the referrer where ancestorOrigins is missing (Firefox)', async () => {
    const parent = { postMessage: vi.fn() }
    const host = { parent, location: {}, document: { referrer: `${APEX}/drive/files` } } as unknown as Window
    const createElement = vi.fn()
    const doc = { createElement, body: { appendChild: vi.fn() } } as unknown as Document
    const mounting = mountApexProxy({ apexOrigin: APEX, host, document: doc, parentHandshakeMs: 5, helloRetryMs: 5 })
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(createElement).not.toHaveBeenCalled()
    fireMessage({ kind: READY }, APEX, parent)
    client = await mounting
  })

  it('falls back to its own iframe when the parent is not an apex gateway', async () => {
    const { parent, host } = embedded()
    const frame = fakeFrame()
    const mounting = mountApexProxy({ apexOrigin: APEX, host, document: frame.doc, parentHandshakeMs: 5 })

    // A foreign parent's "ready" (wrong origin) must not be taken for the apex.
    fireMessage({ kind: READY }, FOREIGN, parent)
    await new Promise((resolve) => setTimeout(resolve, 20))
    frame.fireLoad()
    expect(frame.contentWindow.postMessage).toHaveBeenCalledWith({ kind: HELLO }, APEX)
    fireMessage({ kind: READY }, APEX, frame.contentWindow)
    client = await mounting
    expect(client).not.toBeNull()
  })
})

// W12 (`drive-web-parity.md` §1.2g). Until these envelopes existed the bridge was
// request/response only, so nothing could be subscribed to across the origin split and every
// Drive read was "snapshot + refetch". The capability was already there in-apex — `Devices` and
// `Notifications` expose `observe(cb): () => void` — it just could not cross.
const SUBSCRIBE = 'tenshu/bridge/sub'
const EVENT = 'tenshu/bridge/evt'
const UNSUBSCRIBE = 'tenshu/bridge/unsub'

describe('serveProxyGateway subscriptions', () => {
  let gateway: ProxyGateway | null = null
  afterEach(() => {
    gateway?.dispose()
    gateway = null
  })

  it('pushes each emission to the subscriber', async () => {
    const source = { postMessage: vi.fn() }
    let emit: ((v: unknown) => void) | null = null
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: {
        'devices.observe': (_params, e) => {
          emit = e
          return () => undefined
        },
      },
    })

    fireMessage({ kind: SUBSCRIBE, id: 's1', method: 'devices.observe', params: null }, APEX, source)
    await Promise.resolve()

    emit!(['a'])
    emit!(['a', 'b'])

    expect(source.postMessage).toHaveBeenCalledWith({ kind: EVENT, id: 's1', value: ['a'] }, APEX)
    expect(source.postMessage).toHaveBeenCalledWith({ kind: EVENT, id: 's1', value: ['a', 'b'] }, APEX)
  })

  it('tears down on unsubscribe and drops later emissions', async () => {
    const source = { postMessage: vi.fn() }
    const teardown = vi.fn()
    let emit: ((v: unknown) => void) | null = null
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { stream: (_p, e) => { emit = e; return teardown } },
    })

    fireMessage({ kind: SUBSCRIBE, id: 's2', method: 'stream', params: null }, APEX, source)
    await Promise.resolve()
    fireMessage({ kind: UNSUBSCRIBE, id: 's2' }, APEX, source)

    expect(teardown).toHaveBeenCalledTimes(1)
    source.postMessage.mockClear()
    emit!('after')
    // ⚠ An emission after teardown is the handler's business, not a crash — but it must not
    // reach a client that has forgotten the id.
    expect(source.postMessage).not.toHaveBeenCalled()
  })

  // ⚠⚠ The race the implementation exists to handle: a handler may be async, so `unsub` can
  // arrive while it is still starting. Without the cancelled-set the unsubscribe finds nothing
  // to cancel, the handler then registers, and the stream leaks for the life of the page.
  it('tears down a subscription cancelled while its handler was still starting', async () => {
    const source = { postMessage: vi.fn() }
    const teardown = vi.fn()
    let release: (() => void) | null = null
    const started = new Promise<void>((r) => { release = r })
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { slow: async () => { await started; return teardown } },
    })

    fireMessage({ kind: SUBSCRIBE, id: 's3', method: 'slow', params: null }, APEX, source)
    // Cancel BEFORE the handler resolves.
    fireMessage({ kind: UNSUBSCRIBE, id: 's3' }, APEX, source)
    release!()
    await Promise.resolve()
    await Promise.resolve()

    expect(teardown).toHaveBeenCalledTimes(1)
  })

  it('reports an unknown subscription as a terminal event, not silence', async () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({ permissions: PERMITS, handlers: {}, subscriptions: {} })

    fireMessage({ kind: SUBSCRIBE, id: 's4', method: 'nope', params: null }, APEX, source)
    await Promise.resolve()

    expect(source.postMessage).toHaveBeenCalledWith(
      { kind: EVENT, id: 's4', error: "unknown subscription 'nope'" },
      APEX,
    )
  })

  it('reports a handler that throws as a terminal event', async () => {
    const source = { postMessage: vi.fn() }
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { boom: () => { throw new Error('no stream for you') } },
    })

    fireMessage({ kind: SUBSCRIBE, id: 's5', method: 'boom', params: null }, APEX, source)
    await Promise.resolve()

    expect(source.postMessage).toHaveBeenCalledWith(
      { kind: EVENT, id: 's5', error: 'no stream for you' },
      APEX,
    )
  })

  it('tears down every live subscription when the gateway is disposed', async () => {
    const source = { postMessage: vi.fn() }
    const teardown = vi.fn()
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { stream: () => teardown },
    })
    fireMessage({ kind: SUBSCRIBE, id: 's6', method: 'stream', params: null }, APEX, source)
    await Promise.resolve()

    gateway.dispose()
    gateway = null

    expect(teardown).toHaveBeenCalledTimes(1)
  })

  // Run-A review: a producer whose collector died had no way to say so, and the subscriber froze.
  it('a producer `fail` reaches the subscriber as a terminal event, tears down, and drops later emits', async () => {
    const source = { postMessage: vi.fn() }
    const teardown = vi.fn()
    let emit: ((v: unknown) => void) | null = null
    let fail: ((m: string) => void) | null = null
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { failing: (_p, e, f) => { emit = e; fail = f; return teardown } },
    })
    fireMessage({ kind: SUBSCRIBE, id: 's8', method: 'failing', params: null }, APEX, source)
    await Promise.resolve()

    emit!('first')
    fail!('collector died')
    fail!('again')
    emit!('late')

    expect(source.postMessage.mock.calls.map((c) => c[0])).toEqual([
      { kind: EVENT, id: 's8', value: 'first' },
      { kind: EVENT, id: 's8', error: 'collector died' },
    ])
    expect(teardown).toHaveBeenCalledTimes(1)
    // A later unsubscribe for the dead id is harmless.
    fireMessage({ kind: UNSUBSCRIBE, id: 's8' }, APEX, source)
    expect(teardown).toHaveBeenCalledTimes(1)
  })

  it('a `fail` before the handler has returned still ends the stream and tears it down', async () => {
    const source = { postMessage: vi.fn() }
    const teardown = vi.fn()
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { failing: async (_p, _e, f) => { f('no stream'); return teardown } },
    })
    fireMessage({ kind: SUBSCRIBE, id: 's9', method: 'failing', params: null }, APEX, source)
    await vi.waitFor(() => expect(teardown).toHaveBeenCalledTimes(1))
    expect(source.postMessage).toHaveBeenCalledWith({ kind: EVENT, id: 's9', error: 'no stream' }, APEX)
  })

  it('ignores a subscribe from a foreign origin', async () => {
    const source = { postMessage: vi.fn() }
    const handler = vi.fn(() => () => undefined)
    gateway = serveProxyGateway({
      permissions: PERMITS,
      handlers: {},
      subscriptions: { stream: handler },
    })

    fireMessage({ kind: SUBSCRIBE, id: 's7', method: 'stream', params: null }, FOREIGN, source)
    await Promise.resolve()

    expect(handler).not.toHaveBeenCalled()
    expect(source.postMessage).not.toHaveBeenCalled()
  })
})
