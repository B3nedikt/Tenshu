// Pure helpers for the D2b video streaming Service Worker (drive-streaming-preview.md §3.5, S6b).
// The SW itself (public/tenshu-stream-sw.js) is a logic-free relay; all parsing/planning lives
// here so it's unit-testable and shared with the window-side bridge (videoStream.ts).

// Virtual URL prefix the SW intercepts. A `<video src>` points at `${PREFIX}${token}`; the SW
// forwards the request to the window, which reads the covering byte range via the streaming ops.
export const STREAM_PATH_PREFIX = '/__tenshu_stream__/'

// Message type the SW posts to its window client (with a MessagePort for the reply).
export const STREAM_REQUEST_MSG = 'tenshu-stream-request'

// Max bytes served per response — bounds how much we assemble/decrypt at once. The media element
// re-requests the rest via further Range requests, so playback stays bounded-memory regardless of
// file size. 2 MiB == the content chunk size (ADR 0017), so a window spans ~1–2 chunks.
export const STREAM_RESPONSE_WINDOW = 2 * 1024 * 1024

export interface ByteRange {
  /** Inclusive start offset. */
  start: number
  /** Inclusive end offset. */
  end: number
}

/** The virtual URL a `<video>` element loads for the stream [token]. */
export function streamPath(token: string): string {
  return STREAM_PATH_PREFIX + encodeURIComponent(token)
}

/** Extract the stream token from a virtual URL pathname, or null if it isn't one. */
export function tokenFromPath(pathname: string): string | null {
  if (!pathname.startsWith(STREAM_PATH_PREFIX)) return null
  const raw = pathname.slice(STREAM_PATH_PREFIX.length)
  if (!raw) return null
  return decodeURIComponent(raw)
}

/**
 * Parse a single HTTP `Range` header against a known [totalSize] into an inclusive [ByteRange].
 * Supports `bytes=start-`, `bytes=start-end`, and the `bytes=-suffix` (last N bytes) form. Returns
 * null when the header is absent, malformed, multi-range, or unsatisfiable — the caller then serves
 * from the start.
 */
export function parseRangeHeader(header: string | null | undefined, totalSize: number): ByteRange | null {
  if (!header) return null
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!match) return null
  const [, rawStart, rawEnd] = match
  if (rawStart === '' && rawEnd === '') return null

  let start: number
  let end: number
  if (rawStart === '') {
    // Suffix range: the last `rawEnd` bytes.
    const suffix = Number(rawEnd)
    if (suffix <= 0) return null
    start = Math.max(0, totalSize - suffix)
    end = totalSize - 1
  } else {
    start = Number(rawStart)
    end = rawEnd === '' ? totalSize - 1 : Math.min(Number(rawEnd), totalSize - 1)
  }
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null
  if (start > end || start >= totalSize || start < 0) return null
  return { start, end }
}

/**
 * Clamp a requested [range] so we never serve more than [windowBytes] in one response (bounded
 * memory). The end is pulled in to `start + windowBytes - 1`; the media element re-requests the
 * remainder.
 */
export function clampToWindow(range: ByteRange, windowBytes: number = STREAM_RESPONSE_WINDOW): ByteRange {
  return { start: range.start, end: Math.min(range.end, range.start + windowBytes - 1) }
}
