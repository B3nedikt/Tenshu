import { describe, expect, it } from 'vitest'
import { blobType, OPAQUE_BLOB_TYPE, previewKind } from './inlineSafety'

describe('inlineSafety', () => {
  it('lets only the allowlist keep its blob type', () => {
    expect(blobType('image/png')).toBe('image/png')
    expect(blobType('application/pdf')).toBe('application/pdf')
    expect(blobType('text/plain; charset=utf-8')).toBe('text/plain')
    for (const type of [
      'text/html',
      ' TEXT/HTML ',
      'image/svg+xml',
      'application/xhtml+xml',
      'text/xml',
      'application/xml',
      'application/json',
      'text/javascript',
      'application/x-unknown',
      '',
    ]) {
      expect(blobType(type), type).toBe(OPAQUE_BLOB_TYPE)
    }
  })

  it('previews raster images, PDF and text; never SVG or a markup document', () => {
    expect(previewKind('image/jpeg')).toBe('image')
    expect(previewKind('application/pdf')).toBe('pdf')
    expect(previewKind('text/plain')).toBe('text')
    // Shown as escaped source text, never parsed — and its blob is opaque (above).
    expect(previewKind('text/html')).toBe('text')
    expect(previewKind('image/svg+xml')).toBe('none')
    expect(previewKind('application/xhtml+xml')).toBe('none')
    expect(previewKind('video/mp4')).toBe('none')
  })
})
