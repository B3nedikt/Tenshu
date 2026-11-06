/**
 * The HTML a framed screenshot is rendered from. Reviewed like code (design §3.2): a new language
 * costs a caption, not a designer. Colours come from apex's design tokens (web/shared/tokens.css),
 * pinned to the light theme — the store listing shows the light captures.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { REPO, layout, type Rect } from './frames.ts'

export const TOKENS_CSS = join(REPO, 'web', 'shared', 'tokens.css')

export interface FrameInput {
  output: { width: number; height: number }
  bezel: { png: Uint8Array; width: number; height: number }
  screen: Rect
  capture: Uint8Array
  caption: string | null
  /** The token stylesheet; the real one unless a test passes its own. */
  tokensCss?: string
}

const dataUrl = (png: Uint8Array) => `data:image/png;base64,${Buffer.from(png).toString('base64')}`

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

export function frameHtml(input: FrameInput): string {
  const { output, bezel, screen } = input
  const l = layout(output, bezel, input.caption !== null)
  // Tailwind-only at-rules mean nothing to a browser that is not running Tailwind; drop them so the
  // stylesheet is just the custom properties.
  const tokens = (input.tokensCss ?? readFileSync(TOKENS_CSS, 'utf8')).replace(/^@source[^;]*;/gm, '')
  const caption = l.caption
    ? `<div class="caption" style="left:${l.caption.left}px;top:${l.caption.top}px;width:${l.caption.width}px;height:${l.caption.height}px;font-size:${l.caption.fontSize}px">${escapeHtml(input.caption!)}</div>`
    : ''
  return `<!doctype html>
<html data-theme="light">
<head>
<meta charset="utf-8">
<style>${tokens}</style>
<style>
  html, body { margin: 0; width: ${output.width}px; height: ${output.height}px; overflow: hidden; }
  body { position: relative; background: var(--primary-soft); }
  .caption {
    position: absolute; display: flex; align-items: center; justify-content: center; text-align: center;
    font-family: ui-sans-serif, system-ui, -apple-system, "Helvetica Neue", sans-serif; font-weight: 700;
    line-height: 1.15; letter-spacing: -0.01em; color: var(--text);
  }
  .device {
    position: absolute; left: ${l.device.left}px; top: ${l.device.top}px;
    width: ${bezel.width}px; height: ${bezel.height}px;
    transform: scale(${l.device.scale}); transform-origin: 0 0;
  }
  /* The capture sits UNDER the bezel, filling its transparent screen area; the bezel's own
     rounded screen edge covers the corners, the radius only keeps them from showing through. */
  .screen {
    position: absolute; left: ${screen.x}px; top: ${screen.y}px; width: ${screen.width}px; height: ${screen.height}px;
    border-radius: ${screen.cornerRadius}px; object-fit: fill;
  }
  .bezel { position: absolute; left: 0; top: 0; width: ${bezel.width}px; height: ${bezel.height}px; }
</style>
</head>
<body>
${caption}
<div class="device">
  <img class="screen" src="${dataUrl(input.capture)}" alt="">
  <img class="bezel" src="${dataUrl(bezel.png)}" alt="">
</div>
</body>
</html>`
}
