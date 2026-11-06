// "Download original" (photos-app.md §42.2): save the file, render nothing.
//
// The bytes come over Drive's streaming ops (`fs.openContentStream` / `readContentRange` /
// `closeContentStream`), the same ones its playback and its W7 save use, so this app's allowlist
// needs no `fs.getData`.
// - Where the browser has the File System Access API, the bytes go to disk a window at a time
//   (Drive's `streamToDisk`): the tab never holds the file.
// - Elsewhere (Firefox, Safari) the windows are gathered into one Blob and handed to an anchor
//   download. ⚠ That holds the whole file once — the only way to save without the API — and it is
//   reported (`streamed: false`), never taken silently.
//
// ⚠⚠ The picker is called FIRST, before any other `await`: `showSaveFilePicker()` needs the click's
// transient activation, which a slow stream open would let expire.
import { closeContentStream, openContentStream, readContentRange } from './proxy'
import { STREAM_RESPONSE_WINDOW } from './streamProtocol'

export type DownloadOutcome =
  | { readonly kind: 'saved'; readonly streamed: boolean }
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'failed'; readonly errorKind: string }

interface WritableFileStream {
  write(data: BufferSource): Promise<void>
  close(): Promise<void>
  abort(): Promise<void>
}
type SaveFilePicker = (options?: { suggestedName?: string }) => Promise<{ createWritable(): Promise<WritableFileStream> }>

function picker(): SaveFilePicker | null {
  const candidate = (window as unknown as { showSaveFilePicker?: SaveFilePicker }).showSaveFilePicker
  return typeof candidate === 'function' ? candidate : null
}

/** Read [path] window by window into [sink]. Throws with the failing `kind`. */
async function pump(path: string, sink: (chunk: ArrayBuffer) => Promise<void> | void): Promise<void> {
  const stream = await openContentStream(path)
  if (!stream.ok) throw new DownloadError(stream.error.kind)
  try {
    let written = 0
    // Bounded by the stream's own size; an empty read ends it early, so a wrong size cannot spin.
    while (written < stream.totalSize) {
      const want = Math.min(STREAM_RESPONSE_WINDOW, stream.totalSize - written)
      const read = await readContentRange(stream.handle, written, want)
      if (!read.ok) throw new DownloadError(read.error.kind)
      const chunk = read.bytes
      if (chunk === null) throw new DownloadError('error_generic')
      if (chunk.length === 0) break
      await sink(detach(chunk))
      written += chunk.length
    }
  } finally {
    await closeContentStream(stream.handle).catch(() => undefined)
  }
}

class DownloadError extends Error {
  readonly errorKind: string
  constructor(errorKind: string) {
    super(errorKind)
    this.errorKind = errorKind
  }
}

export async function downloadOriginal(path: string, name: string): Promise<DownloadOutcome> {
  const show = picker()
  let handle: Awaited<ReturnType<SaveFilePicker>> | null = null
  if (show) {
    try {
      handle = await show({ suggestedName: name })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return { kind: 'cancelled' }
      // ⚠⚠ Not a failure: the API exists but refused this context. Inside the apex the app is a
      // CROSS-ORIGIN iframe, where the spec forbids file pickers (SecurityError) — which is the
      // normal hosted flow, so it falls through to the Blob download.
      console.warn(`downloadOriginal: save picker refused (${String(error)}) — using a Blob download`)
    }
  }
  if (handle) {
    const writable = await handle.createWritable()
    try {
      await pump(path, (chunk) => writable.write(chunk))
      await writable.close()
      return { kind: 'saved', streamed: true }
    } catch (error) {
      // ⚠ Abort, not close: closing would publish a truncated file that looks complete.
      await writable.abort().catch(() => undefined)
      return { kind: 'failed', errorKind: error instanceof DownloadError ? error.errorKind : 'error_generic' }
    }
  }

  const chunks: ArrayBuffer[] = []
  try {
    await pump(path, (chunk) => {
      chunks.push(chunk)
    })
  } catch (error) {
    return { kind: 'failed', errorKind: error instanceof DownloadError ? error.errorKind : 'error_generic' }
  }
  console.warn('downloadOriginal: no File System Access API — the file was held in memory once')
  const url = URL.createObjectURL(new Blob(chunks, { type: 'application/octet-stream' }))
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    // After the click has handed the URL to the download manager.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return { kind: 'saved', streamed: false }
}

function detach(bytes: Int8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  return copy.buffer
}
