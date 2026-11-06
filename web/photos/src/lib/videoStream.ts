// Drive's playback path, copied from `web/drive/src/lib/videoStream.ts` (see `streamProtocol.ts`).
// Window-side bridge for the D2b video streaming Service Worker (drive-streaming-preview.md
// §3.5, S6b). The SW is a separate context from the window that owns the apex proxy, so the SW
// forwards each intercepted `<video>` Range request here (via a MessagePort); this module reads
// the covering byte range through the streaming ops and hands back a ready-to-serve response plan.
//
// Channel: SW → window (client `postMessage`) → apex bridge → in-process runtime. The window owns
// the session lifecycle (open on play, close on teardown) and the token → handle map; the SW only
// deals in tokens + byte ranges.

import { openContentStream, readContentRange, closeContentStream } from './proxy'
import {
  STREAM_REQUEST_MSG,
  clampToWindow,
  parseRangeHeader,
  streamPath,
  tokenFromPath,
} from './streamProtocol'

// What the window hands the SW to build a `Response`. Plain data so it survives `postMessage`;
// `body` is transferred (no copy across the boundary).
export interface StreamResponsePlan {
  status: number
  headers: Record<string, string>
  body?: ArrayBuffer
}

interface StreamSession {
  handle: string
  totalSize: number
  contentType: string
}

const sessions = new Map<string, StreamSession>()

// Register a session under a fresh token (exported for tests + used by openVideoStream).
export function registerStream(session: StreamSession): string {
  const token = crypto.randomUUID()
  sessions.set(token, session)
  return token
}

export function unregisterStream(token: string): void {
  sessions.delete(token)
}

/**
 * Build the response for a SW-intercepted stream request: resolve the token, read the covering
 * byte range (bounded to one window; only those chunks decrypt), and return a `206 Partial
 * Content` plan. A missing/unknown token → 404; a read failure → 500. Missing/unsatisfiable
 * `Range` is treated as `bytes=0-` (media elements accept a 206 and then issue proper ranges).
 */
export async function buildStreamResponse(
  pathname: string,
  rangeHeader: string | null | undefined,
): Promise<StreamResponsePlan> {
  const token = tokenFromPath(pathname)
  const session = token ? sessions.get(token) : undefined
  if (!session) return { status: 404, headers: {} }

  const total = session.totalSize
  const requested = parseRangeHeader(rangeHeader, total) ?? { start: 0, end: total - 1 }
  const { start, end } = clampToWindow(requested)
  const length = end - start + 1

  const read = await readContentRange(session.handle, start, length)
  if (!read.ok) {
    // ⚠ Logged, not swallowed into a bare 500: a failed range mid-playback is what a stalled
    // video looks like, and the reason used to be unobtainable.
    console.warn(`stream range failed: ${read.error.kind}: ${read.error.message ?? ''}`)
    return { status: 500, headers: {} }
  }
  const bytes = read.bytes
  if (!bytes) return { status: 500, headers: {} }

  // Copy into a fresh, exact-size ArrayBuffer we own outright, so it transfers cleanly to the SW.
  // Near EOF `bytes.length` may be < the requested length.
  const buffer = new ArrayBuffer(bytes.length)
  new Uint8Array(buffer).set(bytes)
  const actualEnd = start + bytes.length - 1

  return {
    status: 206,
    headers: {
      'Content-Type': session.contentType,
      'Content-Length': String(bytes.length),
      'Content-Range': `bytes ${start}-${actualEnd}/${total}`,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'no-store',
    },
    body: buffer,
  }
}

// A live video stream: the virtual URL to feed `<video src>` + a teardown that closes the
// proxy-side session and drops the token.
export interface VideoStream {
  url: string
  close: () => Promise<void>
}

let messageListenerInstalled = false
let serviceWorkerReady: Promise<void> | null = null

// Answer SW read requests once, for the lifetime of the page. Each request carries a MessagePort;
// we post the response plan back through it (transferring the body buffer).
function installMessageListener(): void {
  if (messageListenerInstalled) return
  messageListenerInstalled = true
  navigator.serviceWorker.addEventListener('message', (event: MessageEvent) => {
    const msg = event.data
    if (!msg || msg.type !== STREAM_REQUEST_MSG) return
    const port = event.ports[0]
    if (!port) return
    void buildStreamResponse(msg.url, msg.range).then((plan) => {
      port.postMessage(plan, plan.body ? [plan.body] : [])
    })
  })
}

// Register the SW (idempotent) and wait until it controls the page, so it intercepts the first
// `<video>` request without a reload (the SW skipWaiting + clients.claim on activate).
function ensureServiceWorker(): Promise<void> {
  if (serviceWorkerReady) return serviceWorkerReady
  serviceWorkerReady = (async () => {
    await navigator.serviceWorker.register('/tenshu-stream-sw.js')
    await navigator.serviceWorker.ready
  })()
  return serviceWorkerReady
}

/**
 * Open a streaming preview for the personal video at [path] with the given [contentType]. Opens a
 * bounded-memory session (only the played chunks decrypt — no whole-file blob URL), registers it
 * with the SW under a token, and returns the virtual `<video src>` URL + a close callback. Resolves
 * null if the file isn't streamable yet (unsynced / locked). Call [VideoStream.close] on teardown.
 */
export async function openVideoStream(path: string, contentType: string): Promise<VideoStream | null> {
  await ensureServiceWorker()
  installMessageListener()

  const opened = await openContentStream(path)
  if (!opened.ok) {
    // ⚠ The reason is available now — before 2026-09-01 this was a bare null and a refused
    // stream was indistinguishable from an unstreamable file. Playback still degrades the same
    // way (the caller falls back), but the cause is no longer invisible.
    console.warn(`openVideoStream: ${opened.error.kind}: ${opened.error.message ?? ''}`)
    return null
  }

  const token = registerStream({
    handle: opened.handle,
    totalSize: opened.totalSize,
    contentType,
  })

  return {
    url: streamPath(token),
    close: async () => {
      unregisterStream(token)
      await closeContentStream(opened.handle)
    },
  }
}
