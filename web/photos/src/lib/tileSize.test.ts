import { describe, expect, it } from 'vitest'
import { planTile } from './tileSize'

describe('planTile — which rung a square tile needs on this screen', () => {
  it('a 1× screen keeps LARGE for Months and Years (a 4:3 LARGE is 192 px on its short side)', () => {
    expect(planTile(160, 1, 4032, 3024)).toEqual({ rung: 'large' })
    expect(planTile(72, 1, 4032, 3024)).toEqual({ rung: 'large' })
  })

  it('a Retina screen needs XLARGE for Months and Days, scaled to the tile’s device pixels', () => {
    expect(planTile(160, 2, 4032, 3024)).toEqual({ rung: 'xlarge', devicePx: 320, fit: false })
    expect(planTile(220, 2, 4032, 3024)).toEqual({ rung: 'xlarge', devicePx: 448, fit: false })
  })

  it('Years stays on LARGE even on Retina (144 device px)', () => {
    expect(planTile(72, 2, 4032, 3024)).toEqual({ rung: 'large' })
  })

  it('the short side decides: a square photo’s LARGE covers more than a panorama’s', () => {
    expect(planTile(220, 1, 3000, 3000)).toEqual({ rung: 'large' })
    expect(planTile(160, 1, 1920, 1080)).toEqual({ rung: 'xlarge', devicePx: 192, fit: false })
    // Portrait reads the same as landscape: stored dimensions are before EXIF rotation.
    expect(planTile(160, 2, 3024, 4032)).toEqual(planTile(160, 2, 4032, 3024))
  })

  it('the square steps by 64 px, so a resize by a few pixels keeps the same sharp tiles', () => {
    expect(planTile(155, 2)).toEqual({ rung: 'xlarge', devicePx: 320, fit: false })
    expect(planTile(158, 2)).toEqual(planTile(155, 2))
  })

  it('unknown dimensions are taken as 4:3; a bad ratio as 1×', () => {
    expect(planTile(160, 2)).toEqual({ rung: 'xlarge', devicePx: 320, fit: false })
    expect(planTile(160, 0, 4032, 3024)).toEqual({ rung: 'large' })
  })

  it('a whole-photo tile needs the LONG side only: LARGE covers more, and the scaled copy keeps its shape', () => {
    // 220 px on 1×: a square crop of a 4:3 LARGE is 192 px short — too little; the whole photo's
    // 256 px long side is enough.
    expect(planTile(220, 1, 4032, 3024)).toEqual({ rung: 'xlarge', devicePx: 256, fit: false })
    expect(planTile(220, 1, 4032, 3024, true)).toEqual({ rung: 'large' })
    expect(planTile(220, 2, 4032, 3024, true)).toEqual({ rung: 'xlarge', devicePx: 448, fit: true })
  })

  it('never asks for more than XLARGE covers: a huge tile caps at its short side (crop) or long side (whole)', () => {
    // 4:3: XLARGE's short side is 768 → 768; whole, its long side 1024.
    expect(planTile(850, 2, 4032, 3024)).toEqual({ rung: 'xlarge', devicePx: 768, fit: false })
    expect(planTile(850, 2, 4032, 3024, true)).toEqual({ rung: 'xlarge', devicePx: 1024, fit: true })
    // So a resize between two huge sizes keeps the same sharp tiles.
    expect(planTile(700, 2, 4032, 3024)).toEqual(planTile(900, 2, 4032, 3024))
  })
})
