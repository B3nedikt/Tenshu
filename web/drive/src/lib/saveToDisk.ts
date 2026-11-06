// W7 (`drive-web-parity.md`). Saving a file used to mean `getData(path)` — the whole file into
// the tab's heap — and then a second full copy into a `Blob` before the object URL. So the peak
// cost of downloading an N-byte file was ~2N in the window, on top of whatever the Kotlin runtime
// was already holding. That is fine for a photo and impossible for a video.
//
// The read side is already chunked and already bridged: `openContentStream` /
// `readContentRange` / `closeContentStream` are what the Service Worker drives to answer Range
// requests for `<video src>` without loading the file. This points the same ops at a
// `FileSystemWritableFileStream`, so bytes go relay → decrypt → disk a window at a time and the
// heap never holds more than one window.
//
// ⚠⚠ The picker is called FIRST, before any other `await`. `showSaveFilePicker()` requires
// transient activation, which expires a few seconds after the click — opening a content stream
// first would usually work and would intermittently throw `NotAllowedError` on a slow read.
//
// ⚠ One dialog on every path. When the stream is unavailable, the fallback writes `getData`
// bytes into the handle the user already chose, rather than dropping to an anchor download that
// would ask them where to save it a second time.
//
// ⚠⚠ The fallback is correct but it is not free — it holds the whole file in memory, which is
// the exact cost this module exists to avoid. It is therefore reported (`streamed: false`) and
// logged, not taken silently: a refusal that happens on *every* file is a defect, and the
// silence is what hid one for weeks (`tech-debt.md`, 2026-09-01).

import { closeContentStream, getData, openContentStream, readContentRange } from './proxy'
import { STREAM_RESPONSE_WINDOW } from './streamProtocol'

export type SaveOutcome =
  // `streamed` says which path ran. ⚠ It is not cosmetic: for weeks this always fell back and
  // nothing could tell, because `openContentStream` answered a bare null for both "no stream"
  // and "refused". A caller that cares (or a test) can now see which happened.
  | { kind: 'saved'; streamed: boolean }
  | { kind: 'cancelled' }
  // The browser has no File System Access API (Firefox, Safari). The caller keeps the old
  // in-memory path for these — it is the same bytes, just bounded by what the tab can hold.
  | { kind: 'unsupported' }
  | { kind: 'failed'; reason: string }

interface WritableFileStream {
  write(data: BufferSource): Promise<void>
  close(): Promise<void>
  abort(): Promise<void>
}
interface SaveFileHandle {
  createWritable(): Promise<WritableFileStream>
}
type SaveFilePicker = (options?: {
  suggestedName?: string
}) => Promise<SaveFileHandle>

function picker(): SaveFilePicker | null {
  const candidate = (window as unknown as { showSaveFilePicker?: SaveFilePicker })
    .showSaveFilePicker
  return typeof candidate === 'function' ? candidate : null
}

/** Whether this browser can stream a download to disk at all. */
export function canStreamToDisk(): boolean {
  return picker() !== null
}

/**
 * Streams the file at [path] to a location the user picks. The loop is bounded by the stream's
 * own reported size, and an empty read ends it early, so a disagreeing size cannot spin.
 */
export async function streamToDisk(
  path: string,
  suggestedName: string,
  onProgress?: (bytesWritten: number) => void,
): Promise<SaveOutcome> {
  const show = picker()
  if (!show) return { kind: 'unsupported' }

  let handle: SaveFileHandle
  try {
    handle = await show({ suggestedName })
  } catch (error) {
    // The user dismissing the picker is an AbortError, and is not a failure to report.
    if (error instanceof DOMException && error.name === 'AbortError') return { kind: 'cancelled' }
    return { kind: 'failed', reason: 'Could not open the save dialog.' }
  }

  const writable = await handle.createWritable()
  let written = 0
  try {
    const stream = await openContentStream(path)
    if (stream.ok) {
      // ⚠ The stream's own size, not the caller's metadata: the two come from different reads
      // and only this one describes the bytes actually being served.
      const total = stream.totalSize
      try {
        while (written < total) {
          const want = Math.min(STREAM_RESPONSE_WINDOW, total - written)
          const read = await readContentRange(stream.handle, written, want)
          if (!read.ok) throw new Error(`read failed: ${read.error.kind}`)
          const chunk = read.bytes
          if (chunk === null) throw new Error('read returned nothing')
          if (chunk.length === 0) break // EOF
          await writable.write(detach(chunk))
          written += chunk.length
          onProgress?.(written)
        }
      } finally {
        await closeContentStream(stream.handle)
      }
      await writable.close()
      return { kind: 'saved', streamed: true }
    }

    // No seekable session. Same bytes, one buffer — the large-file win is lost, but the user
    // still gets their file, into the handle they already picked.
    console.warn(
      `saveToDisk: streaming unavailable (${stream.error.kind}: ${stream.error.message ?? ''}) — ` +
        'falling back to a whole-file read',
    )
    const read = await getData(path)
    if (!read.ok) throw new Error(`read failed: ${read.error.kind}`)
    const bytes = read.bytes
    if (!bytes) throw new Error('no bytes on this device')
    await writable.write(detach(bytes))
    written = bytes.length
    onProgress?.(written)
    await writable.close()
    return { kind: 'saved', streamed: false }
  } catch {
    // ⚠ Abort rather than close: closing would publish a truncated file that looks complete.
    await writable.abort().catch(() => undefined)
    return { kind: 'failed', reason: "Couldn't download this file." }
  }
}

// Copy out of the Kotlin/JS heap's shared buffer before handing bytes to the platform — the same
// reason the in-memory download path copies before constructing its Blob.
function detach(bytes: Int8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  return copy.buffer
}
