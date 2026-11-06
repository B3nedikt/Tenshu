import { describe, expect, it } from 'vitest'
import { formatCoordinate, formatDuration, heroPreviewRequestPx, stillPlan, videoMime } from './media'

const bytes = new Int8Array([1, 2, 3])

describe('heroPreviewRequestPx (ported from HeroPreviewSize.kt)', () => {
  it('asks for the drawn long edge, stepped to 512, within 1024..4096', () => {
    expect(heroPreviewRequestPx(1920, 1080)).toBe(2048)
    expect(heroPreviewRequestPx(800, 600)).toBe(1024)
    expect(heroPreviewRequestPx(6000, 4000)).toBe(4096)
    expect(heroPreviewRequestPx(0, 0)).toBe(1024)
  })

  it('never above the photograph, and fits both orientations', () => {
    expect(heroPreviewRequestPx(3456, 2234, 1200, 900)).toBe(1536)
    // A rotated photo: drawn 1920 px tall, against 1440 as stored.
    expect(heroPreviewRequestPx(1440, 3200, 4032, 3024)).toBe(2048)
  })
})

describe('stillPlan — rung choice and the HEIC fallback', () => {
  it('draws the rung that answered, and offers nothing more while one covers', () => {
    expect(stillPlan({ ok: true, bytes, thumbnailClass: 'xxlarge', coveringRung: 'present' })).toEqual({
      kind: 'rung',
      bytes,
      offerOriginal: false,
    })
  })

  it('a HEIC with no covering rung: draws the largest rung there is and offers the original', () => {
    expect(stillPlan({ ok: true, bytes, thumbnailClass: 'large', coveringRung: 'absent' })).toEqual({
      kind: 'rung',
      bytes,
      offerOriginal: true,
    })
  })

  it('no rung at all and none coming: a placeholder and the original offered — never decoded', () => {
    expect(stillPlan({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })).toEqual({
      kind: 'none',
      offerOriginal: true,
    })
  })

  it('"unknown" is not "absent": a blip must not push a download', () => {
    expect(stillPlan({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'unknown' })).toEqual({
      kind: 'none',
      offerOriginal: false,
    })
  })

  it('a failed read is a failure, not a missing photo', () => {
    expect(stillPlan({ ok: false, error: { kind: 'error_generic' } })).toEqual({
      kind: 'failed',
      error: { kind: 'error_generic' },
    })
  })
})

describe('formatting', () => {
  it('types a video by extension, durations and coordinates', () => {
    expect(videoMime('clip.MOV')).toBe('video/quicktime')
    expect(videoMime('clip.webm')).toBe('video/webm')
    expect(videoMime('noext')).toBe('video/mp4')
    expect(formatDuration(65_000)).toBe('1:05')
    expect(formatDuration(3_725_000)).toBe('1:02:05')
    expect(formatCoordinate(525_200_000, 134_050_000)).toBe('52.52000, 13.40500')
  })
})
