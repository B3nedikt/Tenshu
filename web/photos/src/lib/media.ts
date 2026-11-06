// The viewer's choices, kept pure: which rung to ask for, what to draw from the answer, when to
// offer the original, and how a video is typed.
import type { LargePreview } from '@tenshu/sdk/files'

// `GetStoredThumbnail.XLARGE_PX` — the hero rung; and native's `HERO_BUCKET_PX` / `MAX_HERO_PX`.
const XLARGE_PX = 1024
export const HERO_BUCKET_PX = 512
export const MAX_HERO_PX = 4096

/**
 * The viewer's `getLargePreview` size: the photograph's long edge as drawn in a viewport of
 * [viewportWidthPx] × [viewportHeightPx] PHYSICAL pixels, stepped to 512, within 1024..4096.
 * Ported from native `heroPreviewRequestPx` (`HeroPreviewSize.kt`): measured, never a constant;
 * never above the photograph's own long edge; and both orientations fitted, because the stored
 * dimensions are before EXIF orientation and an understatement costs sharpness.
 */
export function heroPreviewRequestPx(
  viewportWidthPx: number,
  viewportHeightPx: number,
  pixelWidth: number | null = null,
  pixelHeight: number | null = null,
): number {
  if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return XLARGE_PX
  const w = pixelWidth && pixelWidth > 0 ? pixelWidth : null
  const h = pixelHeight && pixelHeight > 0 ? pixelHeight : null
  let drawn: number
  if (w === null || h === null) {
    drawn = Math.max(viewportWidthPx, viewportHeightPx)
  } else {
    const asStored = Math.min(viewportWidthPx / w, viewportHeightPx / h)
    const rotated = Math.min(viewportWidthPx / h, viewportHeightPx / w)
    drawn = Math.min(Math.max(w, h) * Math.max(asStored, rotated), Math.max(w, h))
  }
  const stepped = Math.ceil(drawn / HERO_BUCKET_PX) * HERO_BUCKET_PX
  return Math.min(MAX_HERO_PX, Math.max(XLARGE_PX, stepped))
}

export type StillPlan =
  // Draw [bytes] — the largest stored rung the read found.
  | { readonly kind: 'rung'; readonly bytes: Int8Array; readonly offerOriginal: boolean }
  // Nothing to draw: a placeholder, and the original as a download when nothing better exists.
  | { readonly kind: 'none'; readonly offerOriginal: boolean }
  | { readonly kind: 'failed'; readonly error: { readonly kind: string } }

/**
 * What the viewer draws for a `getLargePreview` answer (photos-app.md §42.2, decided §42.6).
 *
 * ⚠⚠ The original is NEVER decoded. HEIC does not decode in Chrome or Firefox, and a whole original
 * is 6-18 MB in the tab; the viewer draws the largest rung there is. Only when the covering rung is
 * `absent` — known not to exist, not merely `unknown` (a blip must not push a download) — does it
 * OFFER "Download original", which saves the file and renders nothing.
 */
export function stillPlan(preview: LargePreview): StillPlan {
  if (!preview.ok) return { kind: 'failed', error: preview.error }
  const offerOriginal = preview.coveringRung === 'absent'
  const bytes = preview.bytes
  if (bytes && bytes.length > 0) return { kind: 'rung', bytes, offerOriginal }
  return { kind: 'none', offerOriginal }
}

const VIDEO_MIME: Record<string, string> = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  webm: 'video/webm',
  ogv: 'video/ogg',
  mov: 'video/quicktime',
  '3gp': 'video/3gpp',
}

/** The `Content-Type` the stream Service Worker serves a video under (Drive's `previewKind`). */
export function videoMime(name: string): string {
  const ext = name.slice(name.lastIndexOf('.') + 1).toLowerCase()
  return VIDEO_MIME[ext] ?? 'video/mp4'
}

/** `m:ss` for a clip length; `h:mm:ss` past an hour. */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** An E7 coordinate pair as decimal degrees, 5 places (~1 m). */
export function formatCoordinate(latE7: number, lngE7: number): string {
  return `${(latE7 / 1e7).toFixed(5)}, ${(lngE7 / 1e7).toFixed(5)}`
}
