import { useEffect, useState } from 'react'
import type { Preview } from './preview'
import { disposePreview, loadPreview, saveOriginal } from './usePreview'
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
export function useFileOpener() {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [opening, setOpening] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)

  // X-5: the lightbox is showing a thumbnail class, and the user asked for the file. Fetch the
  // real bytes now (`saveOriginal`). ⚠ The preview on screen is deliberately left alone —
  // swapping it for the original mid-download would be a visible flash for no gain; the user
  // asked to SAVE, not to re-render.
  async function downloadOriginal(target: Preview) {
    setDownloading(true)
    try {
      const failure = await saveOriginal(target.path ?? '', target.name)
      if (failure) setError(failure)
    } finally {
      setDownloading(false)
    }
  }

  // The open preview owns its URL/session; on close / unmount free it.
  useEffect(() => {
    if (!preview) return
    return () => disposePreview(preview)
  }, [preview])

  // Which bytes to show — stored large class first for an image, a stream for video — is
  // `loadPreview`'s, shared with the full-screen viewer. `autoDownload`: a kind the lightbox
  // cannot render (binary, oversized text) downloads itself, leaving nothing to show.
  async function open(path: string) {
    setOpening(path)
    setError(null)
    try {
      const loaded = await loadPreview(path, { autoDownload: true })
      if (loaded.status === 'error') setError(loaded.message)
      else if (loaded.status === 'preview') setPreview(loaded.preview)
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
