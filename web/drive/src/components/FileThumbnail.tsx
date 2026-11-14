import { useEffect, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getPreview } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { previewKind } from '../lib/preview'
import { useInView } from '../lib/useInView'
import { sniffImageMime } from '../lib/imageMime'

// A 20px row box at 2× for retina. getPreview fits within the bounds keeping
// aspect ratio, so a non-square source still comes back small.
const THUMB_PX = 40

// A file row's leading icon. For image files it lazily fetches a small WEBP
// thumbnail (getPreview) once the row scrolls into view and shows it; for
// everything else — and while the thumbnail is loading or if it fails — it
// falls back to the generic file glyph. The fetch is cached per path and gated
// on visibility so a long folder doesn't fire a request per row up front.
export function FileThumbnail({ path, name }: { path: string; name: string }) {
  const isImage = previewKind(name).kind === 'image'
  const [ref, inView] = useInView<HTMLSpanElement>()

  const { data } = useQuery({
    queryKey: queryKeys.preview(path, THUMB_PX),
    queryFn: () => getPreview(path, THUMB_PX, THUMB_PX),
    enabled: isImage && inView,
    // A thumbnail doesn't change under a path within a session.
    staleTime: Infinity,
  })

  // Wrap the bytes in an object URL, derived from the query result rather than
  // mirrored into state: minting it in an effect meant a setState per row (and
  // a second one to clear it for every non-image row), i.e. a cascading render
  // on a list that can be thousands of rows long. The effect now only frees it.
  const url = useMemo(() => {
    // ⚠ A row icon must never become an error surface — thousands of rows, and a missing preview
    // is ordinary. But a FAILED read is not ordinary, so it is logged rather than rendered as
    // the same blank as "this file has no preview".
    if (!data) return null
    if (!data.ok) {
      console.warn(`preview failed for ${path}: ${data.error.kind}: ${data.error.message ?? ''}`)
      return null
    }
    const bytes = data.bytes
    if (!bytes) return null
    const copy = new Uint8Array(bytes.length)
    copy.set(bytes)
    // ⚠ Sniffed, not assumed. This read `'image/webp'` for months while the normal answer was a
    // stored class minted as JPEG (W2b; LIST moved off PNG on 2026-08-31). Browsers sniff anyway,
    // so it decoded — but a wrong label is a wrong label, and it makes the URL useless elsewhere.
    return URL.createObjectURL(new Blob([copy], { type: sniffImageMime(copy, 'image/jpeg') }))
  }, [data, path])
  useEffect(() => {
    if (!url) return
    return () => URL.revokeObjectURL(url)
  }, [url])

  return (
    <span ref={ref} className="flex size-5 shrink-0 items-center justify-center">
      {url ? (
        // Not draggable: a dragged <img> can carry `Files`, and dropping it on Drive would upload it.
        <img src={url} alt="" draggable={false} className="size-5 rounded object-cover" />
      ) : (
        <FileGlyph />
      )}
    </span>
  )
}

function FileGlyph() {
  return (
    <svg className="size-5 text-text-3" viewBox="0 0 20 20" fill="currentColor" aria-hidden>
      <path d="M5 3a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5H5Z" />
    </svg>
  )
}
