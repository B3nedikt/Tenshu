// Which stored rung a grid tile needs (web-apps-layout.md §4.8, "Retina tiles").
//
// A square tile is drawn with `object-cover`, so the photograph's SHORT side must cover it, in
// DEVICE pixels: a 160 px tile on a 2× screen is 320 device px, and a 4:3 photograph's 256 px
// `LARGE` rung is only 192 px on its short side — stretched 1.7×, which is the blur. A whole-photo
// tile (`fit`, §4.9) is drawn with `object-contain`: there the LONG side spans the tile, and the
// rung's long side is all it needs.
//
// ⚠ The web cannot hand this to the browser with `srcset`: the bytes come over the bridge, not
// from a URL, so the choice the browser would make from `devicePixelRatio` is made here instead.
import { useEffect, useState } from 'react'

// `GetStoredThumbnail.LARGE_PX` / `XLARGE_PX`.
export const LARGE_PX = 256
export const XLARGE_PX = 1024
// Within this much of covering, `LARGE` is kept: a few percent of stretch is invisible, and the
// next rung is 16× the pixels.
const SLACK = 1.1
// Stored dimensions missing: assume the common 4:3.
const DEFAULT_ASPECT = 4 / 3
// The scaled square is rounded UP to this step: a tile's size moves with every pixel of window width,
// and each new size is a new cache key — a re-pull of every visible rung on each resize.
export const DEVICE_PX_STEP = 64

export type TilePlan =
  | { readonly rung: 'large' }
  // `XLARGE`, scaled down before it is drawn: to a [devicePx]-square, or with [fit] to fit within one.
  | { readonly rung: 'xlarge'; readonly devicePx: number; readonly fit: boolean }

export function planTile(
  cssPx: number,
  devicePixelRatio: number,
  pixelWidth: number | null = null,
  pixelHeight: number | null = null,
  fit = false,
): TilePlan {
  const ratio = devicePixelRatio > 0 ? devicePixelRatio : 1
  const devicePx = Math.ceil(cssPx * ratio)
  const known = pixelWidth && pixelHeight && pixelWidth > 0 && pixelHeight > 0
  // Orientation-free: the stored dimensions are before EXIF rotation, and only long / short matters.
  const aspect = known ? Math.max(pixelWidth, pixelHeight) / Math.min(pixelWidth, pixelHeight) : DEFAULT_ASPECT
  // ⚠ A rung is stored fit within LARGE_PX, so its long side is LARGE_PX whatever the aspect.
  const largeSpan = fit ? LARGE_PX : LARGE_PX / aspect
  if (devicePx <= largeSpan * SLACK) return { rung: 'large' }
  // ⚠ Capped at what XLARGE itself covers (its short side cropped, its long side whole): the worker
  // never scales up, so a 3-per-row tile on a wide Retina screen asking 1700 px would get the same
  // pixels under a new cache key per 64 px of window — a re-pull of every visible rung per resize.
  const xlargeSpan = Math.floor((fit ? XLARGE_PX : XLARGE_PX / aspect) / DEVICE_PX_STEP) * DEVICE_PX_STEP
  const stepped = Math.ceil(devicePx / DEVICE_PX_STEP) * DEVICE_PX_STEP
  return { rung: 'xlarge', devicePx: Math.min(stepped, xlargeSpan), fit }
}

function currentRatio(): number {
  return typeof window !== 'undefined' && window.devicePixelRatio > 0 ? window.devicePixelRatio : 1
}

/**
 * `window.devicePixelRatio`, kept current: moving the window to another screen (or zooming) fires
 * the `(resolution: Ndppx)` query's change, and the tiles re-plan.
 */
export function useDevicePixelRatio(): number {
  const [ratio, setRatio] = useState(currentRatio)
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const query = window.matchMedia(`(resolution: ${ratio}dppx)`)
    const onChange = () => setRatio(currentRatio())
    query.addEventListener?.('change', onChange)
    return () => query.removeEventListener?.('change', onChange)
  }, [ratio])
  return ratio
}
