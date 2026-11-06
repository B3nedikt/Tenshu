/**
 * The CSS-drawn Android frame, end to end: a synthetic capture is drawn by Chromium at test time,
 * framed by the real renderer, and the PNG read back pixel by pixel. No network: the caption falls
 * back to a local face here (fontCss null), and the font guard is tested on its own.
 */
import type { Browser, Page } from 'playwright-core'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { androidFrameHtml, androidLayout, nonBreaking, renderAndroidFrame, type AndroidFrame } from './android.ts'
import { decodePng, launch, pixelAt, requiredFaceLoaded, type Pixels } from './browser.ts'
import { FrameError, pngSize } from './frames.ts'

const FRAME: AndroidFrame = {
  id: 'phone',
  kind: 'css',
  platform: 'android',
  device: 'test phone',
  licence: 'ours',
  capture: { width: 1080, height: 2400 },
  output: { width: 1242, height: 2208 },
}
const SURFACE = [255, 251, 255] as const // #FFFBFF, the site's light --surface
const TOKENS = ':root { --surface: #FFFBFF; --text: #1C1B1F; }'
const APP = [20, 160, 90] as const

async function draw(page: Page, width: number, height: number, fill: readonly number[]): Promise<Uint8Array> {
  const b64 = await page.evaluate(
    ({ width, height, fill }) => {
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')!
      ctx.fillStyle = `rgb(${fill.join(',')})`
      ctx.fillRect(0, 0, width, height)
      return canvas.toDataURL('image/png').split(',')[1]
    },
    { width, height, fill },
  )
  return new Uint8Array(Buffer.from(b64, 'base64'))
}

const near = (got: readonly number[], want: readonly number[], tolerance = 6) => want.every((v, i) => Math.abs(got[i] - v) <= tolerance)

describe('renderAndroidFrame', () => {
  let browser: Browser
  let page: Page
  let capture: Uint8Array
  let out: Pixels
  let bare: Pixels

  beforeAll(async () => {
    browser = await launch()
    page = await browser.newPage()
    capture = await draw(page, 1080, 2400, APP)
    const local = { tokensCss: TOKENS, fontCss: null, requireFont: null }
    const png = await renderAndroidFrame(browser, { frame: FRAME, capture, caption: 'Your data, synced end-to-end encrypted', ...local })
    expect(pngSize(png)).toEqual(FRAME.output)
    out = await decodePng(page, png)
    bare = await decodePng(page, await renderAndroidFrame(browser, { frame: FRAME, capture, caption: null, ...local }))
  }, 60_000)

  afterAll(async () => {
    await browser?.close()
  })

  it('is the Play canvas, on the website background', () => {
    expect([out.width, out.height]).toEqual([1242, 2208])
    expect(near(pixelAt(out, 4, 4), SURFACE)).toBe(true)
    expect(near(pixelAt(out, 1237, 2203), SURFACE)).toBe(true)
  })

  it('puts the capture inside the phone, centred, with a dark bezel round it', () => {
    const l = androidLayout(FRAME.output)
    const left = (1242 - l.phoneWidth) / 2
    expect(near(pixelAt(out, 621, 1300), APP)).toBe(true)
    // Just inside the phone's left edge, mid-height: the black bezel, not the app and not the page.
    const bezel = pixelAt(out, left + 0.015 * l.phoneWidth, 1300)
    expect(Math.max(bezel[0], bezel[1], bezel[2])).toBeLessThan(80)
    // Left of the phone, past its drop shadow: the page.
    expect(near(pixelAt(out, left - 120, 1300), SURFACE)).toBe(true)
  })

  it('draws the caption above the phone; without one the phone takes its place', () => {
    const l = androidLayout(FRAME.output)
    let dark = 0
    for (let y = l.captionTop; y < l.captionTop + 2 * l.fontSize; y += 2) {
      for (let x = 100; x < 1142; x += 2) if (pixelAt(out, x, y)[0] < 100) dark++
    }
    expect(dark).toBeGreaterThan(500)
    // Between the caption's two lines and the phone: the page.
    expect(near(pixelAt(out, 621, l.captionTop + Math.round(2 * l.fontSize * 1.12) + l.gap / 2), SURFACE)).toBe(true)
    // No caption: the phone's top bezel sits where the caption would start.
    expect(near(pixelAt(bare, 621, l.captionTop + 0.012 * l.phoneWidth), SURFACE)).toBe(false)
  })

  it('shrinks the phone for a long caption instead of cutting its bottom off', async () => {
    const long = 'A caption long enough to wrap onto a third and even a fourth line of text, which no real one should'
    const png = await renderAndroidFrame(browser, { frame: FRAME, capture, caption: long, tokensCss: TOKENS, fontCss: null, requireFont: null })
    const p = await decodePng(page, png)
    const l = androidLayout(FRAME.output)
    // The phone's bottom bezel (the lowest near-black row down its middle) ends above the canvas edge.
    let bottomEdge = -1
    for (let y = 2207; y > 1000 && bottomEdge < 0; y--) if (Math.max(...pixelAt(p, 621, y).slice(0, 3)) < 60) bottomEdge = y
    expect(bottomEdge).toBeGreaterThan(1500)
    expect(bottomEdge).toBeLessThan(2208 - l.bottom / 2)
    // And the phone is still there, narrower than the two-line one: its full-width left edge is page now.
    expect(near(pixelAt(p, 621, 1500), APP)).toBe(true)
    expect(Math.max(...pixelAt(p, (1242 - l.phoneWidth) / 2 + 8, 1500).slice(0, 3))).toBeGreaterThan(200)
  })

  it('refuses a capture of another aspect ratio rather than stretch it', async () => {
    const square = await draw(page, 1000, 1000, APP)
    await expect(renderAndroidFrame(browser, { frame: FRAME, capture: square, caption: null, tokensCss: TOKENS, fontCss: null })).rejects.toThrow(
      /aspect ratio would stretch/,
    )
  })

  it('fails, rather than fall back, when the caption face does not load', async () => {
    await expect(
      renderAndroidFrame(browser, { frame: FRAME, capture, caption: 'x', tokensCss: TOKENS, fontCss: null, requireFont: '600 16px NoSuchFace' }),
    ).rejects.toThrow(FrameError)
  })
})

describe('requiredFaceLoaded', () => {
  it('needs a loaded face of the named family — not check() alone, not any other face', () => {
    // check() is true with no matching face at all: the case the guard exists for.
    expect(requiredFaceLoaded('600 16px Roboto', true, [])).toBe(false)
    expect(requiredFaceLoaded('600 16px Roboto', true, [{ family: 'Inter', status: 'loaded' }])).toBe(false)
    expect(requiredFaceLoaded('600 16px Roboto', true, [{ family: 'Roboto', status: 'error' }])).toBe(false)
    expect(requiredFaceLoaded('600 16px Roboto', false, [{ family: 'Roboto', status: 'loaded' }])).toBe(false)
    expect(requiredFaceLoaded('600 16px Roboto', true, [{ family: '"Roboto"', status: 'loaded' }])).toBe(true)
    expect(requiredFaceLoaded('600 16px "Roboto Flex"', true, [{ family: 'Roboto Flex', status: 'loaded' }])).toBe(true)
  })
})

/** A PNG signature and IHDR — all androidFrameHtml reads of a capture besides its bytes. */
function pngHeader(width: number, height: number): Uint8Array {
  const b = new Uint8Array(24)
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52])
  new DataView(b.buffer).setUint32(16, width)
  new DataView(b.buffer).setUint32(20, height)
  return b
}

describe('androidFrameHtml', () => {
  it('escapes the caption and keeps hyphenated words whole', () => {
    const html = androidFrameHtml({ frame: FRAME, capture: pngHeader(1080, 2400), caption: '<b> end-to-end', tokensCss: TOKENS, fontCss: null })
    expect(html).toContain('&lt;b&gt; end‑to‑end')
    expect(nonBreaking('Ende-zu-Ende - ok')).toBe('Ende‑zu‑Ende - ok')
  })

  it('loads Roboto by default, never the system face', () => {
    const html = androidFrameHtml({ frame: FRAME, capture: pngHeader(1080, 2400), caption: 'x', tokensCss: TOKENS })
    expect(html).toContain('fonts.googleapis.com/css2?family=Roboto:wght@600')
    expect(html).toMatch(/font-family: "Roboto", sans-serif/)
    expect(html).not.toMatch(/system-ui/)
  })
})
