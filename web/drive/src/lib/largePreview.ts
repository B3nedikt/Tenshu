// The lightbox's fallback policy (`drive-xxlarge-thumbnail-rung.md` §4.8, X-5).
//
// ⚠ Lives here, not in `@tenshu/sdk`: the SDK reports *what it found* — which class answered and
// whether anything larger exists — and the app decides what to do about it. Keeping the rule in
// the app also keeps it free of the Kotlin/JS bundle, so it is testable without building one.

import type { LargePreview, ThumbnailClass } from '@tenshu/sdk/files'

// What each stored class covers. ⚠ Two numbers per rung, not one: the long edge it is rendered
// at, and whether it STRETCHES — mirrors `ThumbnailRungs` and `GetStoredThumbnail.COVER_TOLERANCE`,
// which are not exported to JS. ⚠⚠ The band is per-rung and applying it uniformly would be wrong:
// `LIST` and `LARGE` do not stretch, so a 256 px row icon never covers a 512 px request even
// though 256 x 2 would say it does.
const CLASS_COVERAGE: Record<ThumbnailClass, { edge: number; stretches: boolean }> = {
  xxlarge: { edge: 2048, stretches: true },
  xlarge: { edge: 1024, stretches: true },
  large: { edge: 256, stretches: false },
  list: { edge: 100, stretches: false },
}

// `GetStoredThumbnail.COVER_TOLERANCE`. ⚠ Chosen against a measured retina worst case of 1.64x,
// not picked as a round number — a tidier 1.5 would have left that surface uncovered.
const COVER_TOLERANCE = 2.0

/**
 * Does [thumbnailClass] cover a [px] request, by the same rule the shared ladder applies?
 *
 * ⚠⚠ This is deliberately the ladder's rule and not a stricter one. A strict `edge >= px` made web
 * fetch a whole original at 2048 px whenever only `xlarge` was present, while all three native
 * platforms serve the 1024 rung there — `COVER_TOLERANCE` says a request up to `edge x 2.0` is
 * served the stretching rung *"even on a device holding the original"*. Platforms disagreeing about
 * this exact question is where this track's worst defects came from.
 */
function covers(thumbnailClass: ThumbnailClass, px: number): boolean {
  const { edge, stretches } = CLASS_COVERAGE[thumbnailClass]
  return px <= edge * (stretches ? COVER_TOLERANCE : 1)
}

export function needsOriginal(preview: LargePreview, px: number): boolean {
  if (!preview.ok) return false
  if (preview.coveringRung !== 'absent') return false
  if (!preview.thumbnailClass) return true
  return !covers(preview.thumbnailClass, px)
}
