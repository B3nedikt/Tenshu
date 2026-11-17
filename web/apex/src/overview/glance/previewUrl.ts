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
type SharpAnswer =
  | { readonly ok: true; readonly bytes: Int8Array | null; readonly thumbnailClass?: string }
  | { readonly ok: false }

function objectUrl(bytes: Int8Array): string {
  // Copied out of the Kotlin/JS heap. Unlabelled on purpose: an <img> sniffs the type, and this URL
  // is only ever drawn, never downloaded (a stored class is not the file's own type).
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  return URL.createObjectURL(new Blob([copy]))
}

/**
 * [path]'s thumbnail as an object URL, released on unmount. `null` while loading or without one.
 * ⚠ Through the CARD's call — so it can only preview what the card's app may (`fs.getPreview`).
 *
 * [sharp] (a grid tile): once the LARGE rung shows, ask `fs.getTilePreview` for XLARGE and swap it
 * in, as the Photos app does. ⚠⚠ LARGE is 256 px on the LONG edge: a 3:2 photo is 256×171, cropped
 * into a ~180 CSS px square (360 device px on Retina) — a 2× stretch, which was the card's blur.
 * The runtime drops a pulled rung once read; the <img> downscales it smoothly. A stand-in answer
 * (`large` / `list`), a refusal or a failure keeps LARGE.
 */
export function usePreviewUrl(path: string | null, call: GlanceCall, px = TILE_PX, sharp = false): string | null {
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
    const made: string[] = []
    if (path === null) return
    const show = (bytes: Int8Array) => {
      const next = objectUrl(bytes)
      made.push(next)
      setUrl(next)
    }
    call('fs.getPreview', { path, width: px, height: px })
      .then(answer => {
        const a = answer as PreviewAnswer
        if (!live || !a.ok || a.bytes === null) return
        show(a.bytes)
        if (!sharp) return
        return call('fs.getTilePreview', { path }).then(sharpAnswer => {
          const b = sharpAnswer as SharpAnswer
          if (!live || !b.ok || b.bytes === null) return
          if (b.thumbnailClass !== 'xlarge' && b.thumbnailClass !== 'xxlarge') return
          show(b.bytes)
        })
      })
      // A tile that cannot be previewed stays blank (or on LARGE); the card itself loaded fine.
      .catch(() => {})
    return () => {
      live = false
      made.forEach(u => URL.revokeObjectURL(u))
    }
  }, [path, call, px, sharp])
  return url
}
