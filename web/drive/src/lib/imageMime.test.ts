import { describe, expect, it } from 'vitest'
import { sniffImageMime } from './imageMime'

/**
 * ⚠ `FileThumbnail` labelled every preview `image/webp` for months. It decoded anyway — browsers
 * sniff — but the label was wrong for the common case: since W2b the normal answer is a stored
 * class, and every rung is minted as JPEG.
 */
describe('sniffImageMime', () => {
  const bytes = (...head: number[]) => new Uint8Array([...head, 0, 0, 0, 0, 0, 0, 0, 0])

  it('recognises JPEG — what every stored rung actually is', () => {
    expect(sniffImageMime(bytes(0xff, 0xd8, 0xff, 0xe0))).toBe('image/jpeg')
  })

  it('recognises PNG and GIF', () => {
    expect(sniffImageMime(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d))).toBe('image/png')
    expect(sniffImageMime(bytes(0x47, 0x49, 0x46, 0x38, 0x39))).toBe('image/gif')
  })

  it('recognises WebP, whose marker is split across the size field', () => {
    const webp = new Uint8Array([
      0x52, 0x49, 0x46, 0x46, // RIFF
      0x24, 0x00, 0x00, 0x00, // size
      0x57, 0x45, 0x42, 0x50, // WEBP
    ])
    expect(sniffImageMime(webp)).toBe('image/webp')
    // ⚠ RIFF alone is not WebP — a RIFF/WAVE must not be claimed as an image.
    const wave = new Uint8Array([
      0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45,
    ])
    expect(sniffImageMime(wave)).toBe('application/octet-stream')
  })

  it('keeps the caller’s fallback rather than asserting a wrong type', () => {
    expect(sniffImageMime(bytes(0x00, 0x01, 0x02), 'image/png')).toBe('image/png')
  })

  it('does not read past the end of a short buffer', () => {
    expect(sniffImageMime(new Uint8Array([0xff]))).toBe('application/octet-stream')
    expect(sniffImageMime(new Uint8Array([]))).toBe('application/octet-stream')
  })
})
