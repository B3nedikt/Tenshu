import { describe, expect, it } from 'vitest'
import type { LargePreview } from '@tenshu/sdk/files'
import { needsOriginal } from './largePreview'

/**
 * ⚠⚠ This is X-5's load-bearing decision (`drive-xxlarge-thumbnail-rung.md` §4.8): whether opening
 * a photograph in the lightbox downloads its whole original. Get it wrong in one direction and the
 * picture stays blurry for ever; in the other, every open spends 6–18 MB.
 *
 * ⚠ It matters most in a **web-only realm**, where nothing registers the thumbnail backfill (that
 * is Android and desktop only), so an old file may never get an `xxlarge` at all. Without the
 * fallback, X-5 would replace today's original-backed lightbox with one that never sharpens.
 */
describe('needsOriginal', () => {
  const ok = (
    thumbnailClass: 'xxlarge' | 'xlarge' | 'large' | 'list' | null,
    coveringRung: 'present' | 'absent' | 'unknown',
  ): LargePreview => ({ ok: true, bytes: new Int8Array(1), thumbnailClass, coveringRung })

  it('downloads the original when the covering class is CONFIRMED absent and nothing covers the request', () => {
    // 256 x 1 does not reach 2048, and LARGE does not stretch.
    expect(needsOriginal(ok('large', 'absent'), 2048)).toBe(true)
  })

  /**
   * ⚠⚠ The ladder's own rule, not a stricter one. `COVER_TOLERANCE = 2.0` says a request up to
   * `edge x 2` is served the stretching rung *"even on a device holding the original"*. A strict
   * `edge >= px` made web fetch a whole original at 2048 px whenever only `xlarge` was present —
   * where all three native platforms serve the 1024 rung. Web disagreeing with the shared ladder
   * about this exact question is where this track's worst defects came from.
   */
  it('serves a stretching rung inside the cover band rather than the original', () => {
    expect(needsOriginal(ok('xlarge', 'absent'), 2048)).toBe(false) // 1024 x 2.0 == 2048
    expect(needsOriginal(ok('xlarge', 'absent'), 2049)).toBe(true) // one px past the band
  })

  /**
   * ⚠⚠ The band is PER RUNG. `LIST` and `LARGE` ship `stretches = false`, so applying the
   * tolerance uniformly would let a 256 px row icon claim to cover a 512 px lightbox — a 2x upscale
   * of a thumbnail the ladder would never serve there.
   */
  it('does not stretch the rungs that do not stretch', () => {
    expect(needsOriginal(ok('large', 'absent'), 512)).toBe(true) // 256 x 2 would wrongly say false
    expect(needsOriginal(ok('large', 'absent'), 256)).toBe(false) // exact cover is still cover
    expect(needsOriginal(ok('list', 'absent'), 200)).toBe(true)
  })

  it('⚠⚠ never downloads on "unknown" — a failed read is not an absence', () => {
    // The whole reason `coveringRung` is tri-state. A boolean here would make an unreachable relay
    // indistinguishable from "nobody ever minted this class", and every open during an outage
    // would fetch a whole original to prove it.
    expect(needsOriginal(ok('xlarge', 'unknown'), 2048)).toBe(false)
    expect(needsOriginal(ok(null, 'unknown'), 2048)).toBe(false)
  })

  it('never downloads when the covering class is present', () => {
    expect(needsOriginal(ok('xxlarge', 'present'), 2048)).toBe(false)
  })

  it('⚠ a 1024 px lightbox served xlarge is COVERED and downloads nothing', () => {
    // Even with the rung reported absent: 1024 is not smaller than 1024, so there is nothing to
    // gain. This is the case that stops a small viewport spending an original for no visible change.
    expect(needsOriginal(ok('xlarge', 'absent'), 1024)).toBe(false)
  })

  it('a small class under a small request still falls back', () => {
    // The ≤1024 path asks for XLARGE as its covering class; when that is absent too, the stand-in
    // is a 256 px row icon, which does not cover a 1024 px lightbox even with the band.
    expect(needsOriginal(ok('large', 'absent'), 1024)).toBe(true)
  })

  it('nothing stored at all, confirmed, falls back', () => {
    expect(needsOriginal(ok(null, 'absent'), 2048)).toBe(true)
  })

  it('a failed read keeps the stand-in rather than spending the original', () => {
    const failed: LargePreview = { ok: false, error: { kind: 'preview' } }
    expect(needsOriginal(failed, 2048)).toBe(false)
  })
})
