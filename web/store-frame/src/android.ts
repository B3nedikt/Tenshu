/**
 * Framed Play screenshots: an Android capture in the website's own CSS-drawn phone, a caption above it,
 * on the website's background. No vendor art, so nothing to pin or fetch (frames.ts CssFrameEntry).
 *
 * ⚠ The phone is a copy of the website's Android frame — `.shot-frame` / `.hub-shot--android` in
 * web/apex/src/landing/carousel/appCarousel.css (bezel, rail, punch-hole, keys, radii, in cqi). Change
 * the two together, or the store and the site show different phones.
 *
 * The caption is Roboto, the site's heading style (semibold, tracking-tight). The site itself sets no
 * web font — it uses the system stack, which on Android IS Roboto — so the Play listing gets what an
 * Android visitor of tenshu.app sees. ⚠ Never the system font here: on a Mac that is San Francisco,
 * licensed for Apple-platform mockups only. Roboto comes from Google Fonts at render time, and the
 * render fails if it did not load (browser.ts renderPng).
 */
import { readFileSync } from 'node:fs'
import type { Browser } from 'playwright-core'
import { renderPng } from './browser.ts'
import { FrameError, pngSize, type CssFrameEntry } from './frames.ts'
import { TOKENS_CSS, escapeHtml } from './template.ts'

export const CAPTION_FONT = 'Roboto'
const FONT_CSS = 'https://fonts.googleapis.com/css2?family=Roboto:wght@600&display=block'

export interface AndroidFrame extends CssFrameEntry {
  id: string
}

export interface AndroidInput {
  frame: AndroidFrame
  capture: Uint8Array
  caption: string | null
  /** The token stylesheet; the real one unless a test passes its own. */
  tokensCss?: string
  /** Where the caption face comes from; a test may pass none and a local fallback. */
  fontCss?: string | null
  requireFont?: string | null
}

const dataUrl = (png: Uint8Array) => `data:image/png;base64,${Buffer.from(png).toString('base64')}`

/** The capture's own size, so the phone's height budget is exact (the aspect check allows 2%). */
function captureSize(png: Uint8Array): { width: number; height: number } {
  try {
    return pngSize(png)
  } catch {
    throw new FrameError('the capture is not a PNG')
  }
}

/** Hyphens that may not break a line: "end-to-end" stays whole when the caption wraps. */
export const nonBreaking = (text: string) => text.replace(/(\w)-(\w)/g, '$1‑$2')

/** Proportions of a 1242 × 2208 canvas, so another output size keeps the same picture. */
export function androidLayout(output: { width: number; height: number }) {
  const u = output.width / 1242
  return {
    captionTop: Math.round(150 * u),
    captionWidth: Math.round(1060 * u),
    fontSize: Math.round(80 * u),
    gap: Math.round(96 * u),
    bottom: Math.round(60 * u),
    phoneWidth: Math.round(770 * u),
  }
}

/**
 * The frame's height over its width: the capture inset 2.6% of the width on every side
 * (2.6cqi each), so (1 − 0.052) × the capture's aspect + 0.052.
 */
export function phoneAspect(capture: { width: number; height: number }): number {
  return (1 - 0.052) * (capture.height / capture.width) + 0.052
}

export function androidFrameHtml(input: AndroidInput): string {
  const { output } = input.frame
  const l = androidLayout(output)
  const tokens = (input.tokensCss ?? readFileSync(TOKENS_CSS, 'utf8')).replace(/^@source[^;]*;/gm, '')
  const fontCss = input.fontCss === undefined ? FONT_CSS : input.fontCss
  const caption = input.caption === null ? '' : `<div class="caption">${escapeHtml(nonBreaking(input.caption))}</div>`
  return `<!doctype html>
<html data-theme="light">
<head>
<meta charset="utf-8">
${fontCss ? `<link rel="stylesheet" href="${fontCss}">` : ''}
<style>${tokens}</style>
<style>
  :root {
    /* appCarousel.css's light-theme device tokens — except the shadow: deeper here on purpose, so
       the phone lifts off a full-bleed white canvas (the site's sits in a card). Approved 2026-10-30. */
    --device-body: #0b0b0d;
    --device-rail-hi: color-mix(in srgb, var(--text) 12%, var(--surface));
    --device-rail-lo: color-mix(in srgb, var(--text) 44%, var(--surface));
    --device-shadow: 0 2px 6px rgb(0 0 0 / 0.10), 0 30px 80px rgb(0 0 0 / 0.18);
  }
  html, body { margin: 0; width: ${output.width}px; height: ${output.height}px; overflow: hidden; }
  body {
    background: var(--surface); display: flex; flex-direction: column; align-items: center;
    -webkit-font-smoothing: antialiased;
  }
  /* Whatever height the caption leaves. The phone is as wide as fits it, up to its full width, so a
     caption that wraps to a third line shrinks the phone rather than push its bottom off the canvas. */
  .stage {
    flex: 1 1 auto; min-height: 0; align-self: stretch; container-type: size;
    display: flex; justify-content: center;
  }
  .caption {
    margin-top: ${l.captionTop}px; width: ${l.captionWidth}px; text-align: center; text-wrap: balance;
    font-family: "${CAPTION_FONT}", sans-serif; font-weight: 600; font-size: ${l.fontSize}px;
    line-height: 1.12; letter-spacing: -0.025em; color: var(--text);
  }
  .phone {
    margin-top: ${input.caption === null ? l.captionTop : l.gap}px;
    width: min(${l.phoneWidth}px, (100cqh - ${(input.caption === null ? l.captionTop : l.gap) + l.bottom}px) / ${phoneAspect(captureSize(input.capture)).toFixed(4)});
    align-self: flex-start;
    position: relative; container-type: inline-size;
    border-radius: 7% / 3.24%; background: var(--device-body); box-shadow: var(--device-shadow);
  }
  /* The rail: the outer 0.7cqi of the bezel, brushed, lighter where the light catches it. */
  .phone::before {
    content: ""; position: absolute; inset: 0; padding: 0.7cqi; border-radius: inherit;
    background: linear-gradient(135deg, var(--device-rail-hi), var(--device-rail-lo) 28%,
      var(--device-rail-hi) 50%, var(--device-rail-lo) 74%, var(--device-rail-hi));
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask: linear-gradient(#000 0 0) content-box exclude, linear-gradient(#000 0 0);
  }
  /* The keys, just outside the right edge: the volume rocker, then the power key. */
  .phone::after {
    content: ""; position: absolute; top: 46cqi; right: -0.7cqi; width: 0.7cqi; height: 44cqi;
    border-radius: 0 0.35cqi 0.35cqi 0;
    background: linear-gradient(to bottom, var(--device-rail-lo) 0 23cqi, transparent 23cqi 30cqi,
      var(--device-rail-lo) 30cqi 44cqi);
  }
  .screen { display: block; width: calc(100% - 5.2cqi); margin: 2.6cqi; border-radius: 4.4cqi; background: #000; }
  .lens {
    position: absolute; top: 3.7cqi; left: 50%; width: 3cqi; height: 3cqi; translate: -50% 0; border-radius: 50%;
    background: radial-gradient(circle at 42% 40%, #24262e 0 18%, #0a0a0c 46%, #030304 100%);
    box-shadow: 0 0 0 0.25cqi rgb(255 255 255 / 0.05);
  }
</style>
</head>
<body>
${caption}
<div class="stage"><div class="phone"><img class="screen" src="${dataUrl(input.capture)}" alt=""><div class="lens"></div></div></div>
</body>
</html>`
}

export async function renderAndroidFrame(browser: Browser, input: AndroidInput): Promise<Uint8Array> {
  const { frame } = input
  const capture = pngSize(input.capture)
  const want = frame.capture.width / frame.capture.height
  const got = capture.width / capture.height
  if (Math.abs(want - got) / want > 0.02) {
    throw new FrameError(
      `frame '${frame.id}': the capture is ${capture.width}×${capture.height}, the frame takes ` +
        `${frame.capture.width}×${frame.capture.height} — a different aspect ratio would stretch the app.`,
    )
  }
  const requireFont = input.requireFont === undefined ? `600 16px ${CAPTION_FONT}` : input.requireFont
  const png = await renderPng(browser, androidFrameHtml(input), frame.output.width, frame.output.height,
    input.caption === null ? undefined : (requireFont ?? undefined))
  const size = pngSize(png)
  if (size.width !== frame.output.width || size.height !== frame.output.height) {
    throw new FrameError(`rendered ${size.width}×${size.height}, expected ${frame.output.width}×${frame.output.height}`)
  }
  return png
}
