import { describe, expect, it } from 'vitest'
import { glyphAssets, GLYPHS_ROOT, resolveGlyphRequest } from './vite'

describe('tenshuMapGlyphs', () => {
  it("ships native's glyph ranges for both fontstacks the style names, with the font licence", () => {
    expect(glyphAssets().map(([path]) => path)).toEqual([
      'Noto Sans Medium/0-255.pbf',
      'Noto Sans Medium/256-511.pbf',
      'Noto Sans Regular/0-255.pbf',
      'Noto Sans Regular/256-511.pbf',
      'OFL.txt',
    ])
  })

  it('answers an encoded fontstack path, and nothing outside the glyph directory', () => {
    expect(resolveGlyphRequest(GLYPHS_ROOT, 'Noto%20Sans%20Regular/0-255.pbf')).toMatch(/Noto Sans Regular\/0-255\.pbf$/)
    expect(resolveGlyphRequest(GLYPHS_ROOT, '..%2F..%2Fpackage.json')).toBeNull()
    expect(resolveGlyphRequest(GLYPHS_ROOT, '../tenshu_basemap.pmtiles')).toBeNull()
    expect(resolveGlyphRequest(GLYPHS_ROOT, 'Noto Sans Regular/../../x/0-255.pbf')).toBeNull()
    expect(resolveGlyphRequest(GLYPHS_ROOT, '%E0%A4%A')).toBeNull()
  })
})
