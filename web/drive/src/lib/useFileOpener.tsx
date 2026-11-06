import { useEffect, useState } from 'react'
import { getData, getLargePreview } from './proxy'
import { openVideoStream } from './videoStream'
import { buildPreview, buildStandInPreview, download, previewKind, type Preview } from './preview'
import { needsOriginal } from './largePreview'
import { basename } from './paths'
import { FilePreview } from '../components/FilePreview'
import { ErrorToast, OpeningOverlay } from '../components/FileOpenerOverlays'

// Open a personal file by path: fetch its bytes on demand and show the preview
// lightbox (image / pdf / small text), or fall back to a download for binaries.
// This is the primary file action on web — a row click opens straight into the
// preview, matching the native/desktop apps rather than stopping at a details
// step first. Metadata + activity live behind a separate "Details" affordance.
//
// Returns `open(path)` plus an `overlay` node the caller renders once; the
// overlay owns the opening indicator, the error toast, and the lightbox.
// The edge the lightbox asks for. ⚠ Above `XLARGE`'s 1024 px, so the SDK names XXLARGE and a
// retina viewport gets the 2048 px rung rather than a 1024 px one stretched to fit.
const LIGHTBOX_PX = 2048

export function useFileOpener() {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [opening, setOpening] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)

  // X-5: the lightbox is showing a thumbnail class, and the user asked for the file. Fetch the
  // real bytes now. ⚠ The preview on screen is deliberately left alone — swapping it for the
  // original mid-download would be a visible flash for no gain; the user asked to SAVE, not to
  // re-render.
  async function downloadOriginal(target: Preview) {
    setDownloading(true)
    try {
      const read = await getData(target.path ?? '')
      if (!read.ok || !read.bytes) {
        setError(
          !read.ok
            ? `Couldn't download “${target.name}” (${read.error.kind}).`
            : `“${target.name}” isn't downloaded to this device yet.`,
        )
        return
      }
      const copy = new Uint8Array(read.bytes.length)
      copy.set(read.bytes)
      const { mime } = previewKind(target.name)
      const url = URL.createObjectURL(new Blob([copy], { type: mime }))
      download(url, target.name)
      URL.revokeObjectURL(url)
    } finally {
      setDownloading(false)
    }
  }

  // The open preview owns its URL/session; on close / unmount free the object URL
  // (a no-op for the virtual video stream URL) and run any `cleanup` (the video
  // kind closes its proxy-side streaming session).
  useEffect(() => {
    if (!preview) return
    return () => {
      URL.revokeObjectURL(preview.url)
      preview.cleanup?.()
    }
  }, [preview])

  async function open(path: string) {
    setOpening(path)
    setError(null)
    try {
      const name = basename(path)
      // Video streams on demand (D2b) — open a Service-Worker-backed session instead of
      // loading the whole file into memory. Everything else reads its bytes whole.
      if (previewKind(name).kind === 'video') {
        const stream = await openVideoStream(path, previewKind(name).mime)
        if (!stream) {
          setError(`Couldn't open “${name}”.`)
          return
        }
        setPreview({ name, url: stream.url, kind: 'video', cleanup: () => void stream.close() })
        return
      }

      // X-5: for an image, try the stored large classes before the original
      // (`drive-xxlarge-thumbnail-rung.md` §4.8). A 2048 px rung is ~0.5 MB against 6–18 MB, and
      // on a device that holds neither it is one relay read instead of a whole file.
      //
      // ⚠⚠ The original is still fetched when `needsOriginal` says the covering class is
      // CONFIRMED absent and what we got is smaller than the viewport. Nothing on web runs the
      // thumbnail backfill, so in a web-only realm an old file may never have a large class at
      // all — without that fallback this would swap today's sharp lightbox for a permanent blur.
      if (previewKind(name).kind === 'image') {
        // ⚠⚠ Guarded, and not merely for tidiness: this is a NEW bridge method, and the apex the
        // browser has cached may predate it. An unknown method REJECTS rather than answering
        // `{ ok: false }`, so an unguarded call would turn "your apex is a version behind" into
        // "this file will not open at all" — a worse outcome than the blur, on a surface that
        // worked before X-5 existed. Any failure here falls through to the original below.
        // ⚠ try/catch, not `.catch(...)`: an apex that does not know the method can fail
        // synchronously, before any promise exists, and a rejection handler never sees that.
        let large: Awaited<ReturnType<typeof getLargePreview>> | null = null
        try {
          large = await getLargePreview(path, LIGHTBOX_PX)
        } catch {
          large = null
        }
        if (large?.ok && large.bytes && !needsOriginal(large, LIGHTBOX_PX)) {
          setPreview(buildStandInPreview(path, name, large.bytes))
          return
        }
      }

      const read = await getData(path)
      if (!read.ok) {
        setError(`Couldn't open “${name}” (${read.error.kind}).`)
        return
      }
      const bytes = read.bytes
      if (!bytes) {
        setError(`“${name}” isn't downloaded to this device yet.`)
        return
      }
      // A non-previewable kind (binary, oversized text) downloads itself and
      // returns null — there's nothing to show in the lightbox.
      const built = buildPreview(name, bytes)
      if (built) setPreview(built)
    } finally {
      setOpening(null)
    }
  }

  const overlay = (
    <>
      {opening && <OpeningOverlay name={basename(opening)} />}
      {error && <ErrorToast message={error} onDismiss={() => setError(null)} />}
      {preview && (
        <FilePreview
          preview={preview}
          onClose={() => setPreview(null)}
          onDownloadOriginal={() => void downloadOriginal(preview)}
          downloading={downloading}
        />
      )}
    </>
  )

  return { open, opening, overlay }
}
