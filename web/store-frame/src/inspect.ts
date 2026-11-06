/**
 * Measures a bezel: its sha256 and the transparent screen area the capture goes into. The maintainer
 * runs it once per downloaded bezel and copies the result into store/frames/manifest.json.
 */
import type { Pixels } from './browser.ts'
import { FrameError, type Rect } from './frames.ts'

const TRANSPARENT = 128

/**
 * The transparent region around the bezel's centre (a flood fill, so the transparent margin OUTSIDE the
 * device is never reached), as its bounding box, and the corner radius estimated on the top-left
 * diagonal: a rounded corner of radius r leaves the first transparent diagonal pixel at r(1 − 1/√2).
 */
export function findScreen(p: Pixels): Rect {
  const { width, height, data } = p
  const alpha = (x: number, y: number) => data[(y * width + x) * 4 + 3]
  const cx = Math.floor(width / 2)
  const cy = Math.floor(height / 2)
  if (alpha(cx, cy) >= TRANSPARENT) throw new FrameError('the bezel is not transparent at its centre — is this a product bezel with an empty screen?')
  const seen = new Uint8Array(width * height)
  const stack = [cy * width + cx]
  seen[stack[0]] = 1
  let minX = cx, maxX = cx, minY = cy, maxY = cy
  while (stack.length) {
    const i = stack.pop()!
    const x = i % width
    const y = (i - x) / width
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
    for (const [nx, ny] of [[x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]]) {
      if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
        throw new FrameError('the transparent centre reaches the image edge — the screen area is not enclosed by the bezel')
      }
      const j = ny * width + nx
      if (!seen[j] && alpha(nx, ny) < TRANSPARENT) {
        seen[j] = 1
        stack.push(j)
      }
    }
  }
  let d = 0
  while (d < Math.min(maxX - minX, maxY - minY) / 2 && !seen[(minY + d) * width + minX + d]) d++
  const cornerRadius = Math.round(d / (1 - Math.SQRT1_2))
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1, cornerRadius }
}
