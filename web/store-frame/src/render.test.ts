/**
 * End to end, without Apple's files: a synthetic bezel and a synthetic capture are drawn by Chromium
 * at test time (nothing is committed), framed by the real renderer, and the PNG it writes is read
 * back pixel by pixel.
 */
import type { Browser, Page } from 'playwright-core'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { decodePng, launch, pixelAt, type Pixels } from './browser.ts'
import { FrameError, layout, pngSize, sha256, type ReadyFrame } from './frames.ts'
import { findScreen } from './inspect.ts'
import { renderFrame } from './render.ts'

// The synthetic device: a 600 × 1240 dark body, its screen a transparent rounded rectangle.
const BEZEL = { width: 600, height: 1240 }
const SCREEN = { x: 40, y: 60, width: 520, height: 1120, cornerRadius: 64 }
const BODY = [30, 30, 34] as const
const APP = [20, 160, 90] as const // the capture's fill
const MARK = [220, 40, 40] as const // a 40 px square in the capture's top-left corner region
const OUTPUT = { width: 660, height: 1434 } // half the 6.9" size: quick, same aspect
const TOKENS = ':root { --primary-soft: #E6DEFF; --text: #1C1B1F; }'

async function draw(page: Page, width: number, height: number, script: string): Promise<Uint8Array> {
  const b64 = await page.evaluate(
    ({ width, height, script }) => {
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')!
      new Function('ctx', script)(ctx)
      return canvas.toDataURL('image/png').split(',')[1]
    },
    { width, height, script },
  )
  return new Uint8Array(Buffer.from(b64, 'base64'))
}

const near = (got: readonly number[], want: readonly number[], tolerance = 6) =>
  want.every((v, i) => Math.abs(got[i] - v) <= tolerance)

describe('renderFrame, with a synthetic bezel', () => {
  let browser: Browser
  let page: Page
  let bezel: Uint8Array
  let capture: Uint8Array
  let frame: ReadyFrame
  let out: Pixels

  beforeAll(async () => {
    browser = await launch()
    page = await browser.newPage()
    bezel = await draw(page, BEZEL.width, BEZEL.height, `
      ctx.fillStyle = 'rgb(${BODY})'
      ctx.beginPath(); ctx.roundRect(0, 0, ${BEZEL.width}, ${BEZEL.height}, 90); ctx.fill()
      ctx.globalCompositeOperation = 'destination-out'
      ctx.beginPath(); ctx.roundRect(${SCREEN.x}, ${SCREEN.y}, ${SCREEN.width}, ${SCREEN.height}, ${SCREEN.cornerRadius}); ctx.fill()
    `)
    capture = await draw(page, 260, 560, `
      ctx.fillStyle = 'rgb(${APP})'; ctx.fillRect(0, 0, 260, 560)
      ctx.fillStyle = 'rgb(${MARK})'; ctx.fillRect(40, 40, 40, 40)
    `)
    frame = {
      id: 'synthetic',
      device: 'Synthetic phone',
      source: 'drawn by render.test.ts',
      file: 'synthetic.png',
      licence: 'none — generated',
      sha256: sha256(bezel),
      fetch: null,
      screen: SCREEN,
      output: OUTPUT,
    }
    const png = await renderFrame(browser, { frame, bezel, capture, caption: 'Your files, encrypted', tokensCss: TOKENS })
    out = await decodePng(page, png)
  })

  afterAll(async () => {
    await browser?.close()
  })

  it('inspect finds the synthetic screen area', async () => {
    const found = findScreen(await decodePng(page, bezel))
    expect([found.x, found.y, found.width, found.height]).toEqual([SCREEN.x, SCREEN.y, SCREEN.width, SCREEN.height])
    expect(Math.abs(found.cornerRadius - SCREEN.cornerRadius)).toBeLessThanOrEqual(6)
  })

  it('renders exactly the output size', () => {
    expect([out.width, out.height]).toEqual([OUTPUT.width, OUTPUT.height])
  })

  it('puts the capture in the screen area, the bezel around it and the token background behind it', () => {
    const l = layout(OUTPUT, BEZEL, true)
    const at = (bx: number, by: number) => pixelAt(out, l.device.left + bx * l.device.scale, l.device.top + by * l.device.scale)
    // The middle of the screen shows the app.
    expect(near(at(SCREEN.x + SCREEN.width / 2, SCREEN.y + SCREEN.height / 2), APP)).toBe(true)
    // The marker is where the capture's own top-left region maps to: the capture fills the screen area.
    const sx = SCREEN.width / 260
    const sy = SCREEN.height / 560
    expect(near(at(SCREEN.x + 60 * sx, SCREEN.y + 60 * sy), MARK)).toBe(true)
    // The bezel's body is drawn over its frame area.
    expect(near(at(SCREEN.x / 2, BEZEL.height / 2), BODY)).toBe(true)
    // The background is --primary-soft (#E6DEFF) from the token stylesheet.
    expect(near(pixelAt(out, 2, out.height - 2), [0xe6, 0xde, 0xff])).toBe(true)
  })

  it('draws the caption above the device', () => {
    const l = layout(OUTPUT, BEZEL, true)
    let dark = 0
    for (let y = l.caption!.top; y < l.caption!.top + l.caption!.height; y += 2) {
      for (let x = l.caption!.left; x < l.caption!.left + l.caption!.width; x += 2) {
        const [r, g, b] = pixelAt(out, x, y)
        if (r + g + b < 200) dark++
      }
    }
    expect(dark).toBeGreaterThan(50) // --text is #1C1B1F on a light background: the glyphs are there
  })

  it('refuses a capture whose aspect ratio does not fit the screen', async () => {
    const square = await draw(page, 300, 300, `ctx.fillRect(0, 0, 300, 300)`)
    await expect(renderFrame(browser, { frame, bezel, capture: square, caption: null, tokensCss: TOKENS })).rejects.toThrow(/aspect ratio/)
  })

  it('refuses a screen area outside the bezel', async () => {
    const wrong = { ...frame, screen: { ...SCREEN, width: 900 } }
    await expect(renderFrame(browser, { frame: wrong, bezel, capture, caption: null, tokensCss: TOKENS })).rejects.toBeInstanceOf(FrameError)
  })

  it('a rendered frame is a PNG of the right size by its header too', async () => {
    const png = await renderFrame(browser, { frame, bezel, capture, caption: null, tokensCss: TOKENS })
    expect(pngSize(png)).toEqual(OUTPUT)
  })
})
