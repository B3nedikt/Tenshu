// Decide whether a shared file's bytes can be shown inline, and under which
// MIME type its Blob should be tagged. Driven purely by the filename extension
// — the shared-file read path hands us raw bytes + a name, no server-side
// content type. Anything we can't render in-page falls back to a download.
import { sniffImageMime } from './imageMime'

export type PreviewKind = 'image' | 'pdf' | 'text' | 'video' | 'none'

// Raster formats <img> renders directly. SVG is intentionally excluded — it
// would be tagged image/svg+xml from untrusted shared content; not worth the
// surface even though <img> won't run its scripts.
const IMAGE_MIME: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  avif: 'image/avif',
  ico: 'image/x-icon',
}

// Video formats a `<video>` element plays. Personal videos stream on demand via the Service
// Worker (D2b, drive-streaming-preview.md §3.5) rather than loading the whole file — see
// `openVideoStream`. Shared videos stay on the download fallback until Mode-B streaming lands.
const VIDEO_MIME: Record<string, string> = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  webm: 'video/webm',
  ogv: 'video/ogg',
  mov: 'video/quicktime',
}

const TEXT_MIME: Record<string, string> = {
  txt: 'text/plain',
  md: 'text/markdown',
  markdown: 'text/markdown',
  csv: 'text/csv',
  tsv: 'text/tab-separated-values',
  json: 'application/json',
  xml: 'application/xml',
  yml: 'text/yaml',
  yaml: 'text/yaml',
  log: 'text/plain',
}

export function previewKind(name: string): { kind: PreviewKind; mime: string } {
  const ext = name.includes('.') ? name.split('.').pop()!.toLowerCase() : ''
  const image = IMAGE_MIME[ext]
  if (image) return { kind: 'image', mime: image }
  if (ext === 'pdf') return { kind: 'pdf', mime: 'application/pdf' }
  const video = VIDEO_MIME[ext]
  if (video) return { kind: 'video', mime: video }
  const text = TEXT_MIME[ext]
  if (text) return { kind: 'text', mime: text }
  return { kind: 'none', mime: 'application/octet-stream' }
}

// Text files above this size aren't worth rendering into a <pre> — show the
// download fallback instead of locking up the page on a huge log.
export const TEXT_PREVIEW_MAX_BYTES = 256 * 1024

// A file opened for inline preview: its name + a URL to render (an object URL over
// the decrypted bytes for image/pdf/text, or a virtual Service-Worker stream URL for
// video — D2b, drive-streaming-preview.md §3.5), plus decoded text for the text kind.
// `cleanup` runs when the preview closes: for the streaming video kind it closes the
// proxy-side session (the object-URL kinds are revoked by the caller). Both the
// shared-folder browser and the personal-file details drawer render through this.
export type Preview = {
  name: string
  url: string
  kind: PreviewKind
  text?: string
  cleanup?: () => void
  // X-5: `url` is a downscaled stored thumbnail class, NOT the file's own bytes.
  //
  // ⚠⚠ Load-bearing for the Download control. It is an `<a href={url} download={name}>`, so
  // without this flag showing a 2048 px rung in the lightbox would silently save that rung under
  // the original's filename — a worse failure than the blur the rung fixes. When this is set the
  // caller must fetch the original before saving (`drive-xxlarge-thumbnail-rung.md` §4.8).
  standIn?: boolean
  // The file this preview is of — carried only for a [standIn], which needs it to fetch the
  // original when the user asks to download.
  path?: string
}

// Turn freshly-read bytes into something the user can see. Previewable kinds
// (image / pdf / small text) return a `Preview` for the caller to render in a
// lightbox; everything else (binaries, oversized text) downloads immediately
// and returns `null`. The caller owns the returned Preview's object URL and
// must revoke it on close — see the `useEffect` revoke pattern in the callers.
export function buildPreview(name: string, bytes: Int8Array): Preview | null {
  // Int8Array → Blob via a freshly-allocated Uint8Array (a plain ArrayBuffer,
  // not the Kotlin/JS heap's possibly-shared buffer).
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  const { kind, mime } = previewKind(name)
  // A big text file isn't worth rendering — treat it like a binary download. Video never comes
  // through the bytes path (personal videos stream via `openVideoStream`; a shared video would be
  // a whole-file blob → OOM), so it falls back to download here.
  const renderable =
    kind === 'video' ? false : kind === 'text' ? copy.length <= TEXT_PREVIEW_MAX_BYTES : kind !== 'none'
  const url = URL.createObjectURL(new Blob([copy], { type: mime }))
  if (!renderable) {
    download(url, name)
    URL.revokeObjectURL(url)
    return null
  }
  return { name, url, kind, text: kind === 'text' ? new TextDecoder().decode(copy) : undefined }
}

// X-5: an image preview built from a stored thumbnail class rather than the file's own bytes.
//
// ⚠ The MIME comes from the BYTES, not the name: a `photo.png` has a JPEG `list` class, so naming
// decides nothing here (`imageMime.ts`).
export function buildStandInPreview(path: string, name: string, bytes: Int8Array): Preview {
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  const url = URL.createObjectURL(new Blob([copy], { type: sniffImageMime(copy, 'image/jpeg') }))
  return { name, url, kind: 'image', standIn: true, path }
}

// Trigger a browser download for an object URL without leaving the page.
export function download(url: string, name: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
}
