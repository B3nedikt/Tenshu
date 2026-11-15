// Save one file's bytes to the user's disk — the row menu's Download, the details pane's and the
// selection bar's. Resolves null when saved (or cancelled), else a sentence to show.
//
// W7: stream straight to disk where the browser allows it, so the tab never holds the file. The
// fallback holds it twice — once from `getData` and again in the Blob copy — which is why a large
// file used to be undownloadable rather than slow.
import { getData } from './proxy'
import { basename } from './paths'
import { download } from './preview'
import { canStreamToDisk, streamToDisk } from './saveToDisk'

export async function downloadFile(path: string): Promise<string | null> {
  if (canStreamToDisk()) {
    const outcome = await streamToDisk(path, basename(path))
    if (outcome.kind === 'saved' || outcome.kind === 'cancelled') return null
    if (outcome.kind === 'failed') return outcome.reason
    // 'unsupported' → the in-memory path below.
  }
  const read = await getData(path)
  // ⚠ A failed read and a file with no bytes here are different things, and the user can act on
  // only one of them. Naming the failure beats "Couldn't download this file" for both.
  if (!read.ok) return `Couldn't download this file (${read.error.kind}).`
  const bytes = read.bytes
  if (!bytes) return "This file's contents aren't on this device yet."
  // Copy into a plain ArrayBuffer (not the Kotlin/JS heap's shared buffer) before the Blob.
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  const url = URL.createObjectURL(new Blob([copy]))
  download(url, basename(path))
  URL.revokeObjectURL(url)
  return null
}
