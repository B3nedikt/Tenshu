// Photos copy of Drive's `public/tenshu-stream-sw.js` (a Service Worker serves its own origin only).
// Change one, change both.
// Tenshu Drive video streaming Service Worker (drive-streaming-preview.md §3.5, S6b).
//
// A deliberately logic-free relay: it intercepts requests for the virtual stream URL
// (`/__tenshu_stream__/<token>`) that a <video> element loads, and forwards each one — with its
// Range header — to the window client that owns the apex proxy. The window (videoStream.ts) reads
// the covering byte range through the streaming ops (decrypting only those chunks in the
// in-process runtime) and posts back a ready-to-serve response plan, which this SW turns into a
// Response. No keys, no crypto, no proxy state live here — House Rule #1 intact.
//
// This file is plain JS served verbatim from public/ (no bundler), so it stays self-contained; the
// parsing/planning it would otherwise do lives in the unit-tested window module instead.

const STREAM_PATH_PREFIX = '/__tenshu_stream__/'
const STREAM_REQUEST_MSG = 'tenshu-stream-request'

self.addEventListener('install', () => {
  // Take over without waiting for existing tabs to close, so the first video open works.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  // Control already-open clients immediately (no reload needed before streaming works).
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (url.origin !== self.location.origin || !url.pathname.startsWith(STREAM_PATH_PREFIX)) return
  event.respondWith(handleStreamRequest(event))
})

async function handleStreamRequest(event) {
  // The window that issued the <video> request owns the session + the apex proxy. Fall back to any
  // window client if the initiating id isn't resolvable (e.g. a controlled-but-detached client).
  const client =
    (event.clientId && (await self.clients.get(event.clientId))) ||
    (await self.clients.matchAll({ type: 'window' }))[0]
  if (!client) return new Response('No Tenshu window to serve the stream.', { status: 503 })

  const range = event.request.headers.get('range')
  const plan = await requestFromClient(client, url(event), range)
  if (!plan || plan.status >= 400) {
    return new Response(null, { status: plan ? plan.status : 500 })
  }
  return new Response(plan.body ?? null, { status: plan.status, headers: plan.headers })
}

function url(event) {
  return new URL(event.request.url).pathname
}

// One MessageChannel per read: post the request (transferring port2), resolve on the reply.
function requestFromClient(client, pathname, range) {
  return new Promise((resolve) => {
    const channel = new MessageChannel()
    channel.port1.onmessage = (event) => resolve(event.data)
    client.postMessage(
      { type: STREAM_REQUEST_MSG, url: pathname, range },
      [channel.port2],
    )
  })
}
