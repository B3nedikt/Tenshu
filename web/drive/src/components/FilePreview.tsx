import { useEffect } from 'react'
import type { Preview } from '../lib/preview'

// Full-width lightbox over a single decrypted file. Escape or the backdrop
// closes (which frees the object URL upstream); Download saves the same bytes.
export function FilePreview({
  preview,
  onClose,
  onDownloadOriginal,
  downloading = false,
}: {
  preview: Preview
  onClose: () => void
  // X-5: called instead of the plain download link when `preview.standIn` is set — the bytes on
  // screen are a thumbnail class and must not be saved as the file.
  onDownloadOriginal?: () => void
  downloading?: boolean
}) {
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={preview.name}
        className="relative z-10 flex max-h-[90vh] w-full max-w-3xl flex-col rounded-xl bg-surface-2 shadow-xl"
      >
        <div className="flex items-center gap-3 border-b border-line px-4 py-3">
          <h2 className="min-w-0 flex-1 truncate text-sm font-medium text-text">
            {preview.name}
          </h2>
          {/* The video kind streams a virtual URL — a plain download would get only a partial
              (one-window 206), so hide Download for it. The object-URL kinds download fully. */}
          {preview.kind !== 'video' &&
            (preview.standIn ? (
              // ⚠⚠ X-5: what is on screen is a downscaled thumbnail class, not the file. Linking
              // `preview.url` here would save a 2048 px JPEG under the original's name — silent
              // data loss dressed as a download, and worse than the blur the class fixes. So the
              // stand-in case fetches the real bytes first (`drive-xxlarge-thumbnail-rung.md`
              // §4.8: the original loads for download or an explicit full-resolution view).
              <button
                type="button"
                onClick={onDownloadOriginal}
                disabled={downloading}
                className="shrink-0 rounded-lg border border-line px-3 py-1 text-sm font-medium text-text hover:bg-surface-3 disabled:opacity-60"
              >
                {downloading ? 'Downloading…' : 'Download'}
              </button>
            ) : (
              <a
                href={preview.url}
                download={preview.name}
                className="shrink-0 rounded-lg border border-line px-3 py-1 text-sm font-medium text-text hover:bg-surface-3"
              >
                Download
              </a>
            ))}
          <button
            type="button"
            onClick={onClose}
            aria-label="Close preview"
            className="shrink-0 rounded p-1 text-text-3 hover:bg-surface-3 hover:text-text-2"
          >
            ✕
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-auto bg-surface p-4">
          {preview.kind === 'image' ? (
            <img
              src={preview.url}
              alt={preview.name}
              className="mx-auto max-h-full max-w-full object-contain"
            />
          ) : preview.kind === 'video' ? (
            // Streamed on demand through the Service Worker: the browser issues Range requests
            // against this virtual URL and only the played chunks are fetched + decrypted.
            <video
              src={preview.url}
              controls
              autoPlay
              className="mx-auto max-h-[75vh] max-w-full"
            />
          ) : preview.kind === 'pdf' ? (
            <iframe
              src={preview.url}
              title={preview.name}
              className="h-[75vh] w-full rounded border border-line bg-surface-2"
            />
          ) : (
            <pre className="whitespace-pre-wrap break-words font-mono text-xs text-text">
              {preview.text}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
