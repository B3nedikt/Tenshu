import { describe, expect, it } from 'vitest'
import { kindOfExtension, totalsByKind } from './fileKinds'

describe('fileKinds', () => {
  it('names kinds by extension, case-insensitively, with Other as the fallback', () => {
    expect(kindOfExtension('jpg')).toBe('image')
    expect(kindOfExtension('HEIC')).toBe('image')
    expect(kindOfExtension('mov')).toBe('video')
    expect(kindOfExtension('flac')).toBe('audio')
    expect(kindOfExtension('pdf')).toBe('document')
    expect(kindOfExtension('zip')).toBe('archive')
    expect(kindOfExtension('')).toBe('other')
    expect(kindOfExtension('bin')).toBe('other')
  })

  it('folds extensions into kinds, largest first, dropping empty kinds', () => {
    const kinds = totalsByKind([
      { extension: 'jpg', count: 2, bytes: 400 },
      { extension: 'png', count: 1, bytes: 100 },
      { extension: 'pdf', count: 3, bytes: 900 },
      { extension: '', count: 1, bytes: 7 },
    ])
    expect(kinds).toEqual([
      { kind: 'document', count: 3, bytes: 900 },
      { kind: 'image', count: 3, bytes: 500 },
      { kind: 'other', count: 1, bytes: 7 },
    ])
  })

  it('is empty for no extensions', () => {
    expect(totalsByKind([])).toEqual([])
  })
})
