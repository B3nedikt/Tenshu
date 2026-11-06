// Cross-origin "IPC" bridge — the web equivalent of Android Binder
// (`app.sendNotification(...)` → Control) and the desktop UDS transport.
// See docs/web/architecture.md §3.2 + §5.2.
//
// A Tenshu app on a subdomain (`drive.tenshu.app`) has NO direct backend
// access. It posts request envelopes to the apex (`tenshu.app`), loaded in
// a hidden iframe, which holds the master keys and runs the in-process
// proxy. The apex validates the requesting origin, dispatches to a handler
// (which drives the SDK — e.g. `FileSystem.list`), and posts the result
// back. The transport is `postMessage` with structured-clone serialization;
// this module is the typed envelope + correlation layer over it.
//
// This module is intentionally framework-agnostic (no React) and has no
// dependency on the SDK's service surface — the apex injects the handlers
// (`serveProxyGateway`), so the same bridge serves any app/handler set and
// there is no circular import on `FileSystem`.

const HELLO = 'tenshu/bridge/hello'
const READY = 'tenshu/bridge/ready'
const REQUEST = 'tenshu/bridge/req'
const RESPONSE = 'tenshu/bridge/res'
// W12 (`drive-web-parity.md` §1.2g). The four kinds above are request/response only, so nothing
// could be *subscribed* to across the origin split and the subdomain could only poll — which is
// why "snapshot + refetch" is the pattern everywhere in Drive. These three add the missing
// direction. In-apex the capability already existed (`Devices.observe`, `Notifications.observe`
// return an unsubscribe function); it simply could not cross the boundary.
const SUBSCRIBE = 'tenshu/bridge/sub'
const EVENT = 'tenshu/bridge/evt'
const UNSUBSCRIBE = 'tenshu/bridge/unsub'

interface HelloEnvelope {
  readonly kind: typeof HELLO
}

interface ReadyEnvelope {
  readonly kind: typeof READY
}

interface RequestEnvelope {
  readonly kind: typeof REQUEST
  readonly id: string
  readonly method: string
  readonly params: unknown
}

interface ResponseEnvelope {
  readonly kind: typeof RESPONSE
  readonly id: string
  readonly ok: boolean
  readonly result?: unknown
  readonly error?: string
}

interface SubscribeEnvelope {
  readonly kind: typeof SUBSCRIBE
  readonly id: string
  readonly method: string
  readonly params: unknown
}

// Apex → subdomain. `error` set means the subscription is over: the gateway has already
// dropped it, so the client must not send an `unsub` for it.
interface EventEnvelope {
  readonly kind: typeof EVENT
  readonly id: string
  readonly value?: unknown
  readonly error?: string
}

interface UnsubscribeEnvelope {
  readonly kind: typeof UNSUBSCRIBE
  readonly id: string
}

type Envelope =
  | HelloEnvelope
  | ReadyEnvelope
  | RequestEnvelope
  | ResponseEnvelope
  | SubscribeEnvelope
  | EventEnvelope
  | UnsubscribeEnvelope

function isEnvelope(data: unknown): data is Envelope {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as { kind?: unknown }).kind === 'string' &&
    (data as { kind: string }).kind.startsWith('tenshu/bridge/')
  )
}

// ---------------------------------------------------------------------------
// Subdomain side — the proxy client.
// ---------------------------------------------------------------------------

export interface ProxyClient {
  // Dispatch a request to the apex and await its result. Rejects if the
  // handler errors, the method is unknown, or the call times out.
  call<T = unknown>(method: string, params?: unknown): Promise<T>
  // Subscribe to a pushed stream of values. Returns the unsubscribe function — the same
  // contract the in-apex `observe` surfaces already use, and the one web-standard primitive
  // for change notification (Observables are a TC39 *proposal*, not a standard, and RxJS is a
  // library; neither belongs in the transport contract).
  //
  // ⚠ `onError` is terminal: the gateway has already dropped the subscription by the time it
  // fires, and calling the returned unsubscribe afterwards is a no-op rather than an error.
  subscribe<T = unknown>(
    method: string,
    params: unknown,
    onValue: (value: T) => void,
    onError?: (error: Error) => void,
  ): () => void
  // Tear down the iframe + listener.
  dispose(): void
}

export interface MountApexProxyOptions {
  // The apex origin to postMessage to — `https://tenshu.app` in production,
  // the apex dev-server origin locally. MUST be an exact origin; it is used
  // both as the iframe `src` and as the `targetOrigin` on every post, so a
  // wrong value means messages silently go nowhere (never to a foreign
  // origin).
  readonly apexOrigin: string
  // Per-call timeout in ms before `call` rejects. Default 15s.
  readonly callTimeoutMs?: number
  // Override the iframe-mount target (tests inject a stub). Production uses
  // a real hidden iframe pointing at `apexOrigin`.
  readonly document?: Document
  // The window whose `parent` is probed for an embedding apex (tests inject a stub).
  readonly host?: Window
  // How long an embedding parent of UNKNOWN origin gets to answer the hello before we fall back
  // to the hidden iframe. Default 1.5s. ⚠ Never applied to a parent known to be the apex — see
  // `mountApexProxy`.
  readonly parentHandshakeMs?: number
  // How often the hello is re-sent to a parent known to be the apex until it answers. Default 1s.
  readonly helloRetryMs?: number
}

// The embedding parent's origin, when the browser tells us: `location.ancestorOrigins`
// (Chromium, Safari), else the referrer's origin (Firefox — the iframe's initial navigation
// carries the embedder as referrer under the default policy). null when neither says.
//
// ⚠⚠ The referrer names the embedder only on the iframe's FIRST navigation. Once the
// framed app navigates itself (a link, `location.assign`, a redirect) the referrer is its own
// origin — which says nothing about the parent. Reading that as "known not apex" would mount the
// nested iframe under a real apex parent in Firefox — a second runtime, refused the lock the
// parent holds. So a referrer from our own origin is UNKNOWN (the 1.5 s parent attempt), never a
// verdict.
function parentOrigin(host: Window): string | null {
  const ancestors = (host.location as Location | undefined)?.ancestorOrigins
  if (ancestors && ancestors.length > 0) return ancestors[0] ?? null
  try {
    const referrer = host.document?.referrer
    if (!referrer) return null
    const origin = new URL(referrer).origin
    const own = (host.location as Location | undefined)?.origin
    return origin === own ? null : origin
  } catch {
    return null
  }
}

// Where the client's envelopes go: the embedding apex window, or our own hidden apex iframe.
interface ProxyTarget {
  readonly window: () => Window | null
  // Send the hello once the target can hear it; `fail` if it never will.
  readonly start: (sendHello: () => void, fail: (error: Error) => void) => void
  readonly remove: () => void
  // Re-send the hello at this interval until `ready` (a parent may be too busy to hear the first).
  readonly resendHelloMs?: number
}

// Dials the apex gateway and resolves once it has answered the `hello` handshake. The returned
// client routes every `call` to that window. Mirrors how the desktop app dials Control's UDS
// before it can issue operations.
//
// ⚠⚠ EMBEDDED IN THE APEX ⇒ USE THE PARENT, NEVER A NESTED IFRAME (docs/web/architecture.md
// "Cross-tab"). Apex hosts Drive at `/drive/files` inside its runtime-holding shell. A nested
// hidden apex iframe would be a second runtime under the same origin, contending for the one
// `tenshu-runtime` lock the host page already holds: every call would be refused, and "Use here"
// would displace the host. So when we are framed, the parent is tried first; only a parent that
// is not an apex gateway (no `ready` from exactly `apexOrigin`, from exactly `window.parent`)
// falls back to the hidden iframe.
export function mountApexProxy(options: MountApexProxyOptions): Promise<ProxyClient> {
  const host = options.host ?? window
  const parent = host.parent && host.parent !== host ? host.parent : null
  if (!parent) return dial(options, frameTarget(options), null)
  const knownOrigin = parentOrigin(host)
  // A parent known NOT to be the apex can never answer — go straight to our own frame.
  if (knownOrigin !== null && knownOrigin !== options.apexOrigin) {
    return dial(options, frameTarget(options), null)
  }
  const retryMs = options.helloRetryMs ?? 1_000
  const parentTarget: ProxyTarget = {
    window: () => parent,
    start: (sendHello) => sendHello(),
    remove: () => undefined,
    resendHelloMs: retryMs,
  }
  // ⚠⚠ A parent KNOWN to be the apex is never given up on a timer. Its main thread
  // can be blocked for seconds (hydration decodes every row in one synchronous callback); falling
  // back then would mount the nested iframe — a second runtime refused the lock the parent holds,
  // intermittently. Keep re-sending hello until it answers.
  if (knownOrigin === options.apexOrigin) return dial(options, parentTarget, null)
  // Unknown parent: try it briefly, then our own frame.
  return dial(options, parentTarget, options.parentHandshakeMs ?? 1_500).catch(() =>
    dial(options, frameTarget(options), null),
  )
}

const FRAME_HELLO_RESEND_MS = 250

function frameTarget(options: MountApexProxyOptions): ProxyTarget {
  const doc = options.document ?? document
  const iframe = doc.createElement('iframe')
  iframe.setAttribute('aria-hidden', 'true')
  iframe.setAttribute('title', 'Tenshu proxy')
  iframe.style.display = 'none'
  iframe.src = options.apexOrigin
  return {
    window: () => iframe.contentWindow,
    start: (sendHello, fail) => {
      iframe.addEventListener('load', sendHello)
      iframe.addEventListener('error', () => fail(new Error('failed to load apex proxy frame')))
      doc.body.appendChild(iframe)
    },
    remove: () => iframe.remove(),
    // ⚠ The apex loads its gateway with a dynamic import (the Kotlin runtime is a lazy chunk,
    // web-app-suite.md F6), so it may start listening AFTER `load` fires. Keep re-sending until
    // `ready`. A hello sent before the frame navigated goes to `about:blank` and is dropped by
    // the browser, because `targetOrigin` is the apex origin.
    resendHelloMs: FRAME_HELLO_RESEND_MS,
  }
}

function dial(
  options: MountApexProxyOptions,
  proxy: ProxyTarget,
  handshakeTimeoutMs: number | null,
): Promise<ProxyClient> {
  const { apexOrigin, callTimeoutMs = 15_000 } = options

  const pending = new Map<
    string,
    { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }
  >()
  const streams = new Map<string, { onValue: (v: unknown) => void; onError?: (e: Error) => void }>()
  // ⚠⚠ Globally unique, not just per-client. Response envelopes are posted back to the
  // requesting window so a colliding `c0` was harmless; a subscription is state the GATEWAY
  // holds, so two frames sharing an id would let one frame's unsubscribe cancel the other's.
  const clientTag = Math.random().toString(36).slice(2, 10)

  return new Promise<ProxyClient>((resolveClient, rejectClient) => {
    const target = proxy.window
    let handshake: ReturnType<typeof setTimeout> | null = null
    let resend: ReturnType<typeof setInterval> | null = null

    function onMessage(event: MessageEvent) {
      // ⚠⚠ Origin AND source. The origin alone cannot tell the embedding apex from our own
      // hidden apex iframe — both are `apexOrigin` — and a page holding both must not take one's
      // answer for the other's. `source` is set by the browser and cannot be forged.
      if (event.origin !== apexOrigin || !isEnvelope(event.data)) return
      if (event.source !== target()) return
      const env = event.data

      if (env.kind === READY) {
        if (handshake !== null) clearTimeout(handshake)
        if (resend !== null) clearInterval(resend)
        resolveClient(client)
        return
      }
      if (env.kind === RESPONSE) {
        const entry = pending.get(env.id)
        if (!entry) return
        clearTimeout(entry.timer)
        pending.delete(env.id)
        if (env.ok) entry.resolve(env.result)
        else entry.reject(new Error(env.error ?? 'proxy call failed'))
        return
      }
      if (env.kind === EVENT) {
        const stream = streams.get(env.id)
        if (!stream) return
        if (env.error !== undefined) {
          // Terminal: the gateway has dropped it already, so forget it here too rather than
          // leaving a dead id that a later unsubscribe would post about.
          streams.delete(env.id)
          stream.onError?.(new Error(env.error))
          return
        }
        stream.onValue(env.value)
      }
    }

    window.addEventListener('message', onMessage)

    let nextId = 0
    const client: ProxyClient = {
      call<T>(method: string, params?: unknown): Promise<T> {
        const win = target()
        if (!win) return Promise.reject(new Error('apex proxy frame is not available'))
        const id = `${clientTag}-c${nextId++}`
        const req: RequestEnvelope = { kind: REQUEST, id, method, params }
        return new Promise<T>((resolve, reject) => {
          const timer = setTimeout(() => {
            pending.delete(id)
            reject(new Error(`proxy call '${method}' timed out`))
          }, callTimeoutMs)
          pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer })
          win.postMessage(req, apexOrigin)
        })
      },
      subscribe<T>(
        method: string,
        params: unknown,
        onValue: (value: T) => void,
        onError?: (error: Error) => void,
      ): () => void {
        const win = target()
        const id = `${clientTag}-s${nextId++}`
        if (!win) {
          onError?.(new Error('apex proxy frame is not available'))
          return () => undefined
        }
        streams.set(id, { onValue: onValue as (v: unknown) => void, onError })
        const sub: SubscribeEnvelope = { kind: SUBSCRIBE, id, method, params }
        win.postMessage(sub, apexOrigin)
        return () => {
          // Idempotent, and silent when the stream already ended — see `onError` above.
          if (!streams.delete(id)) return
          const unsub: UnsubscribeEnvelope = { kind: UNSUBSCRIBE, id }
          target()?.postMessage(unsub, apexOrigin)
        }
      },
      dispose() {
        if (resend !== null) clearInterval(resend)
        window.removeEventListener('message', onMessage)
        for (const entry of pending.values()) {
          clearTimeout(entry.timer)
          entry.reject(new Error('proxy client disposed'))
        }
        pending.clear()
        // ⚠ Tell the gateway. Losing the iframe would eventually collect these anyway (it dies
        // with this document), but a client disposed while the page lives on would otherwise
        // leave the apex pushing into nothing.
        const win = target()
        for (const id of streams.keys()) {
          const unsub: UnsubscribeEnvelope = { kind: UNSUBSCRIBE, id }
          win?.postMessage(unsub, apexOrigin)
        }
        streams.clear()
        proxy.remove()
      },
    }

    if (handshakeTimeoutMs !== null) {
      handshake = setTimeout(() => {
        if (resend !== null) clearInterval(resend)
        window.removeEventListener('message', onMessage)
        rejectClient(new Error('apex gateway did not answer'))
      }, handshakeTimeoutMs)
    }

    const sendHello = () => {
      const hello: HelloEnvelope = { kind: HELLO }
      // ⚠ `targetOrigin` = apexOrigin: a parent that is NOT the apex never even receives it.
      target()?.postMessage(hello, apexOrigin)
    }
    proxy.start(sendHello, rejectClient)
    if (proxy.resendHelloMs !== undefined) resend = setInterval(sendHello, proxy.resendHelloMs)
  })
}

// ---------------------------------------------------------------------------
// Apex side — the gateway.
// ---------------------------------------------------------------------------

// A handler resolves one method. `params` is the structured-cloned payload
// the subdomain sent; the return value is cloned back. Throwing rejects the
// caller's `call` with the error message (redacted to the message string —
// no stack crosses the origin boundary).
export type ProxyHandler = (params: unknown) => unknown | Promise<unknown>

// A subscription handler starts a stream and returns its teardown — the same shape the in-apex
// `observe(callback): () => void` surfaces already have, so wiring one through is a pass-through
// rather than an adapter. `emit` may be called any number of times, including never.
//
// `fail` ends the stream from the producer's side: the subscriber gets the terminal error event
// (`ProxyClient.subscribe`'s `onError`), the gateway drops the subscription and runs its teardown.
// ⚠ Without it a producer whose collector died had no way to say so — the subdomain kept the
// last value on screen forever. Later `emit`/`fail` calls are dropped.
export type ProxySubscriptionHandler = (
  params: unknown,
  emit: (value: unknown) => void,
  fail: (message: string) => void,
) => (() => void) | Promise<() => void>

// F5 (`web-app-suite.md` §3): what each app origin may call. An entry is an exact method name, or
// a namespace prefix ending in `.*` (`'contact.*'` admits `contact.list`, not `contactx.list`).
// There is deliberately no bare `'*'`: an origin that can call everything is the pre-F5 state.
export type ProxyMethodAllowlist = readonly string[]

// The prefix of the error a refused method gets — distinct from `unknown method`, so a caller (and
// a test) can tell "this app may not" from "nobody can".
export const NOT_PERMITTED = 'not permitted'

export function isMethodPermitted(allowlist: ProxyMethodAllowlist, method: string): boolean {
  return allowlist.some((entry) =>
    entry.endsWith('.*') ? method.startsWith(entry.slice(0, -1)) : entry === method,
  )
}

// W-MAP: a handler result whose binary parts MOVE to the caller instead of being copied. Wrap the
// result with `transferring(result, [buffer])`; the gateway posts `result` with `transfer` as the
// postMessage transfer list, so a map tile's bytes cross the origin boundary without a structured-clone
// copy. ⚠ The listed buffers are DETACHED in the apex afterwards: pass only buffers made for this
// response (`MapRegion.streamRange` returns a fresh copy for exactly this reason).
const TRANSFER = Symbol.for('tenshu/bridge/transfer')

export interface TransferringResult {
  readonly [TRANSFER]: true
  readonly value: unknown
  readonly transfer: readonly Transferable[]
}

export function transferring(value: unknown, transfer: readonly Transferable[]): TransferringResult {
  return { [TRANSFER]: true, value, transfer }
}

function isTransferring(value: unknown): value is TransferringResult {
  return typeof value === 'object' && value !== null && (value as Record<symbol, unknown>)[TRANSFER] === true
}

export interface ServeProxyGatewayOptions {
  // Exact origin → the methods (and subscriptions) that origin may use. A message from an origin
  // not in this map is ignored: the §5.2 admission boundary, only declared Tenshu app subdomains
  // may reach the proxy. ⚠⚠ F5: admission is no longer the whole check. An admitted origin is
  // refused (`not permitted …`) any method outside its own list, so an XSS on one app reaches only
  // that app's surface — never, say, `contact.setVerified` from Drive (threat model A24).
  readonly permissions: Readonly<Record<string, ProxyMethodAllowlist>>
  // Method name → handler. Unknown methods reject with `unknown method`.
  readonly handlers: Readonly<Record<string, ProxyHandler>>
  // Method name → subscription handler (W12). Separate from `handlers` on purpose: the two
  // have different lifetimes, and a caller that subscribes to a one-shot method (or calls a
  // stream) should get a clear error rather than a silently wrong shape.
  readonly subscriptions?: Readonly<Record<string, ProxySubscriptionHandler>>
}

export interface ProxyGateway {
  dispose(): void
}

// Mounts the message listener that turns this window (the apex, embedded as
// a subdomain's hidden iframe) into the proxy. Answers the `hello`
// handshake and dispatches `req` envelopes to handlers. Idempotent per
// window only in the sense that disposing removes the listener; callers
// mount it once during apex boot.
export function serveProxyGateway(options: ServeProxyGatewayOptions): ProxyGateway {
  // A Map, not the record: `permissions['constructor']` must not admit anything.
  const permissions = new Map(Object.entries(options.permissions))

  function onMessage(event: MessageEvent) {
    if (!permissions.has(event.origin) || !isEnvelope(event.data)) return
    const env = event.data
    const source = event.source as Window | null
    if (!source) return

    if (env.kind === HELLO) {
      const ready: ReadyEnvelope = { kind: READY }
      source.postMessage(ready, event.origin)
      return
    }
    if (env.kind === REQUEST) {
      void dispatch(env, source, event.origin)
      return
    }
    if (env.kind === SUBSCRIBE) {
      void startStream(env, source, event.origin)
      return
    }
    if (env.kind === UNSUBSCRIBE) {
      stopStream(env.id)
    }
  }

  // id → teardown. Ids are globally unique (the client tags them), so one gateway can serve
  // several subdomain frames without their unsubscribes crossing.
  const active = new Map<string, () => void>()
  // Ids cancelled while their handler was still starting — see the race note below.
  const cancelled = new Set<string>()

  function permitted(origin: string, method: string): boolean {
    return isMethodPermitted(permissions.get(origin) ?? [], method)
  }

  function refusal(origin: string, method: string): string {
    return `${NOT_PERMITTED}: '${method}' is not in the allowlist of ${origin}`
  }

  async function startStream(env: SubscribeEnvelope, source: Window, origin: string) {
    if (!permitted(origin, env.method)) {
      const refused: EventEnvelope = { kind: EVENT, id: env.id, error: refusal(origin, env.method) }
      source.postMessage(refused, origin)
      return
    }
    const handler = Object.hasOwn(options.subscriptions ?? {}, env.method)
      ? options.subscriptions?.[env.method]
      : undefined
    if (!handler) {
      const failed: EventEnvelope = {
        kind: EVENT,
        id: env.id,
        error: `unknown subscription '${env.method}'`,
      }
      source.postMessage(failed, origin)
      return
    }
    // Set by the producer's `fail` — per stream, so it outlives the stream's map entries.
    let ended = false
    const emit = (value: unknown) => {
      // Drop late emissions rather than posting to a stream the client has forgotten.
      if (!active.has(env.id) && !cancelled.has(env.id)) return
      if (cancelled.has(env.id) || ended) return
      const evt: EventEnvelope = { kind: EVENT, id: env.id, value }
      source.postMessage(evt, origin)
    }
    const fail = (message: string) => {
      // Once: and never for a stream the client already cancelled — it is not listening.
      if (ended || cancelled.has(env.id)) return
      ended = true
      const evt: EventEnvelope = { kind: EVENT, id: env.id, error: message }
      source.postMessage(evt, origin)
      const teardown = active.get(env.id)
      if (teardown) {
        active.delete(env.id)
        try { teardown() } catch { /* teardown must not surface */ }
      }
      // Still starting: `startStream` sees `ended` on arrival and tears down there.
    }
    let teardown: () => void
    try {
      // ⚠⚠ Registered BEFORE the await resolves is impossible, so the window between `sub` and
      // the handler returning is covered by `cancelled` instead: an `unsub` arriving in that
      // window would otherwise find nothing to cancel and leak the stream forever.
      cancelled.delete(env.id)
      teardown = await handler(env.params, emit, fail)
    } catch (e) {
      if (ended) return // the producer already ended it; one terminal event, not two
      const failed: EventEnvelope = {
        kind: EVENT,
        id: env.id,
        error: e instanceof Error ? e.message : String(e),
      }
      source.postMessage(failed, origin)
      return
    }
    if (cancelled.delete(env.id) || ended) {
      // Unsubscribed (or failed) while starting. Tear down immediately and never register it.
      try { teardown() } catch { /* teardown must not surface */ }
      return
    }
    active.set(env.id, teardown)
  }

  function stopStream(id: string) {
    const teardown = active.get(id)
    if (!teardown) {
      // Either already gone, or still starting — mark it so `startStream` tears down on arrival.
      cancelled.add(id)
      return
    }
    active.delete(id)
    try { teardown() } catch { /* teardown must not surface */ }
  }

  async function dispatch(env: RequestEnvelope, source: Window, origin: string) {
    if (!permitted(origin, env.method)) {
      const refused: ResponseEnvelope = {
        kind: RESPONSE,
        id: env.id,
        ok: false,
        error: refusal(origin, env.method),
      }
      source.postMessage(refused, origin)
      return
    }
    const handler = Object.hasOwn(options.handlers, env.method) ? options.handlers[env.method] : undefined
    let response: ResponseEnvelope
    let transfer: Transferable[] = []
    if (!handler) {
      response = { kind: RESPONSE, id: env.id, ok: false, error: `unknown method '${env.method}'` }
    } else {
      try {
        const raw = await handler(env.params)
        const result = isTransferring(raw) ? raw.value : raw
        if (isTransferring(raw)) transfer = [...raw.transfer]
        response = { kind: RESPONSE, id: env.id, ok: true, result }
      } catch (e) {
        response = {
          kind: RESPONSE,
          id: env.id,
          ok: false,
          error: e instanceof Error ? e.message : String(e),
        }
      }
    }
    try {
      if (transfer.length > 0) source.postMessage(response, { targetOrigin: origin, transfer })
      else source.postMessage(response, origin)
    } catch (e) {
      // A result that cannot be posted (e.g. a detached buffer) must not leave the caller waiting out
      // its timeout: answer with the failure instead.
      source.postMessage(
        { kind: RESPONSE, id: env.id, ok: false, error: e instanceof Error ? e.message : String(e) },
        origin,
      )
    }
  }

  window.addEventListener('message', onMessage)
  return {
    dispose() {
      window.removeEventListener('message', onMessage)
      for (const teardown of active.values()) {
        try { teardown() } catch { /* teardown must not surface */ }
      }
      active.clear()
      cancelled.clear()
    },
  }
}

// Exposed for unit tests that exercise the envelope contract without a real
// cross-origin frame.
export const __bridgeInternals = {
  HELLO,
  READY,
  REQUEST,
  RESPONSE,
  SUBSCRIBE,
  EVENT,
  UNSUBSCRIBE,
  isEnvelope,
}
