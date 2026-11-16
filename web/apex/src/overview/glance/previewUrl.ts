// A thumbnail through an overview card's own scoped call (web-apex-home.md §3.6): shared by the grid
// layout's tiles and the list layout's row icons.
//
// ⚠⚠ Thumbnails come from the `fs.getPreview` op, never a projected `File.Preview`: on the web only
// the dispatcher path awaits (drive-web-parity.md §1.2i).
import { useEffect, useState } from 'react'
import type { GlanceCall } from '../../apps/registry'

// `GetStoredThumbnail.LARGE_PX`: the rung `fs.getPreview` reaches; a bigger ask gets the same class.
export const TILE_PX = 256

type PreviewAnswer = { readonly ok: true; readonly bytes: Int8Array | null } | { readonly ok: false }

/**
 * [path]'s thumbnail as an object URL, released on unmount. `null` while loading or without one.
 * ⚠ Through the CARD's call — so it can only preview what the card's app may (`fs.getPreview`).
 */
export function usePreviewUrl(path: string | null, call: GlanceCall, px = TILE_PX): string | null {
  const [url, setUrl] = useState<string | null>(null)
  // ⚠ A new path drops the old picture at once (adjusted during render): the effect's cleanup revokes
  // the old URL, and a row reused for another file with no preview would otherwise draw it broken.
  const [shown, setShown] = useState(path)
  if (shown !== path) {
    setShown(path)
    setUrl(null)
  }
  useEffect(() => {
    let live = true
    let made: string | null = null
    if (path === null) return
    call('fs.getPreview', { path, width: px, height: px }).then(
      answer => {
        const a = answer as PreviewAnswer
        if (!live || !a.ok || a.bytes === null) return
        // Copied out of the Kotlin/JS heap. Unlabelled on purpose: an <img> sniffs the type, and this
        // URL is only ever drawn, never downloaded (a stored class is not the file's own type).
        const copy = new Uint8Array(a.bytes.length)
        copy.set(a.bytes)
        made = URL.createObjectURL(new Blob([copy]))
        setUrl(made)
      },
      // A tile that cannot be previewed stays a blank square; the card itself loaded fine.
      () => {},
    )
    return () => {
      live = false
      if (made !== null) URL.revokeObjectURL(made)
    }
  }, [path, call, px])
  return url
}
