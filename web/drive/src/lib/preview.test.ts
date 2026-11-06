import { describe, expect, it } from 'vitest'
import { previewKind } from './preview'

describe('previewKind', () => {
  it('maps raster images to image + a sensible MIME', () => {
    expect(previewKind('pic.png')).toEqual({ kind: 'image', mime: 'image/png' })
    expect(previewKind('PHOTO.JPG')).toEqual({ kind: 'image', mime: 'image/jpeg' })
    expect(previewKind('a.jpeg').mime).toBe('image/jpeg')
  })

  it('maps pdf', () => {
    expect(previewKind('report.pdf')).toEqual({ kind: 'pdf', mime: 'application/pdf' })
  })

  it('maps text-ish formats to text', () => {
    expect(previewKind('note.txt').kind).toBe('text')
    expect(previewKind('data.json')).toEqual({ kind: 'text', mime: 'application/json' })
    expect(previewKind('README.md').kind).toBe('text')
  })

  it('maps video formats to video (streamed via the SW, D2b)', () => {
    expect(previewKind('clip.mp4')).toEqual({ kind: 'video', mime: 'video/mp4' })
    expect(previewKind('CLIP.MOV').kind).toBe('video')
    expect(previewKind('a.webm').mime).toBe('video/webm')
  })

  it('falls back to none for unknown / extension-less names', () => {
    expect(previewKind('archive.zip').kind).toBe('none')
    expect(previewKind('binary').kind).toBe('none')
    // SVG is deliberately not previewable.
    expect(previewKind('logo.svg').kind).toBe('none')
  })
})
