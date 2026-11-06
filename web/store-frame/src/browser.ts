/**
 * Headless Chromium through playwright-core: rendering HTML to a PNG, and reading pixels back.
 * playwright-core downloads no browser on install; see docs/how-to/store-listings.md §6 for the one
 * `playwright-core install` it needs on a new machine.
 */
import { chromium, type Browser, type Page } from 'playwright-core'
import { FrameError } from './frames.ts'

export async function launch(): Promise<Browser> {
  try {
    return await chromium.launch()
  } catch (e) {
    throw new FrameError(
      `could not start Chromium (${(e as Error).message.split('\n')[0]}).\n` +
        '  Install the browser this playwright-core expects: pnpm -C web --filter @tenshu/store-frame exec playwright-core install chromium',
    )
  }
}

/**
 * Renders `html` at exactly width × height CSS pixels, device scale 1, to PNG bytes. With
 * `requireFont` (`<weight> <size> <family>`, e.g. `600 16px Roboto`) the render FAILS unless a face
 * of that family loaded: a web font that did not arrive falls back silently, and the PNG would look nearly right.
 */
export async function renderPng(browser: Browser, html: string, width: number, height: number, requireFont?: string): Promise<Uint8Array> {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 })
  try {
    await page.setContent(html, { waitUntil: 'load' })
    const fonts = await page.evaluate(async (font?: string) => {
      if (font) await document.fonts.load(font)
      await document.fonts.ready
      for (const img of Array.from(document.images)) {
        if (!img.complete || img.naturalWidth === 0) await img.decode()
      }
      return { check: font ? document.fonts.check(font) : true, faces: [...document.fonts].map((f) => ({ family: f.family, status: f.status })) }
    }, requireFont)
    if (requireFont && !requiredFaceLoaded(requireFont, fonts.check, fonts.faces)) throw new FrameError(`the font '${requireFont}' did not load — no network, or the stylesheet moved. Nothing rendered.`)
    return new Uint8Array(await page.screenshot({ type: 'png', fullPage: false }))
  } finally {
    await page.close()
  }
}

/**
 * Whether `font` (`<weight> <size> <family>`) has a loaded face. ⚠ `document.fonts.check()` is true
 * when NO face matches at all — with nothing loaded, or only another family — so it proves nothing
 * alone: a face of the named family itself must have loaded.
 */
export function requiredFaceLoaded(font: string, check: boolean, faces: { family: string; status: string }[]): boolean {
  const unquote = (s: string) => s.replace(/["']/g, '').trim()
  const family = unquote(font.trim().split(/\s+/).slice(2).join(' '))
  return check && faces.some((f) => unquote(f.family) === family && f.status === 'loaded')
}

export interface Pixels {
  width: number
  height: number
  /** RGBA, row-major. */
  data: Uint8ClampedArray
}

/** Decodes a PNG in the browser and returns its pixels — no image library in Node needed. */
export async function decodePng(page: Page, png: Uint8Array): Promise<Pixels> {
  const result = await page.evaluate(async (b64: string) => {
    const img = new Image()
    img.src = `data:image/png;base64,${b64}`
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = img.naturalWidth
    canvas.height = img.naturalHeight
    const ctx = canvas.getContext('2d', { willReadFrequently: true })!
    ctx.drawImage(img, 0, 0)
    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
    // Base64 through the bridge: an array of millions of numbers would be far slower.
    let binary = ''
    for (let i = 0; i < data.length; i += 0x8000) binary += String.fromCharCode(...data.subarray(i, i + 0x8000))
    return { width: canvas.width, height: canvas.height, b64: btoa(binary) }
  }, Buffer.from(png).toString('base64'))
  return { width: result.width, height: result.height, data: new Uint8ClampedArray(Buffer.from(result.b64, 'base64')) }
}

export function pixelAt(p: Pixels, x: number, y: number): [number, number, number, number] {
  const i = (Math.round(y) * p.width + Math.round(x)) * 4
  return [p.data[i], p.data[i + 1], p.data[i + 2], p.data[i + 3]]
}
