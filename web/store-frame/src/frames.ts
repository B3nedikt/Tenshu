/**
 * The frame manifest, captions and layout — everything about a framed screenshot that needs no browser.
 *
 * Design: store-presence-and-support-pages.md §3.2 (Apple's product bezels, composited by our own
 * HTML renderer, not fastlane frameit) and §3.3 (no image in git: the bezels live in the private
 * bucket `tenshu-store-fixtures`, `store/frames/manifest.json` only names and pins them).
 * Runbook: docs/how-to/store-listings.md §6.
 */
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

export const REPO = resolve(import.meta.dirname, '../../..')
export const STORE_DIR = join(REPO, 'store')
export const FRAMES_MANIFEST = join(STORE_DIR, 'frames', 'manifest.json')
/** Where the bezels are looked for: gitignored, filled by hand or by `scripts/store-frame fetch`. */
export const FRAMES_DIR = process.env.STORE_FRAMES_DIR ? resolve(process.env.STORE_FRAMES_DIR) : join(REPO, 'build', 'store', 'frames')
/** Shared with scripts/store-listing.py: a capture fetched by its manifest sha256. */
export const SCREENSHOT_CACHE = join(REPO, 'build', 'store', 'screenshot-cache')
/**
 * A framed set's device id in the screenshots manifest: `ios/iphone69framed/en/01-files`. It fits the
 * existing key form (`<platform>/<device>/<lang>/NN-screen`, MANIFEST_KEY_RE in
 * web/apex/support-pages/generate.ts), so the published plain keys stay valid, store-publish.sh needs
 * no change, and the support pages — which read SUPPORT_DEVICE (`iphone69`) only — keep showing the
 * unframed captures.
 */
export const FRAMED_SUFFIX = 'framed'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
  cornerRadius: number
}

export interface FrameEntry {
  /** Absent on the Apple bezels: a vendor PNG composited under the capture. */
  kind?: 'bezel'
  device: string
  source: string
  file: string
  licence: string
  sha256: string | null
  fetch: string | null
  /** The bezel's transparent screen area, in the bezel file's own pixels. */
  screen: { [K in keyof Rect]: number | null }
  /** The rendered PNG's size: a size App Store Connect accepts for this device's slot. */
  output: { width: number; height: number }
}

/**
 * A frame drawn in CSS by us (android.ts) — nobody's device art, so there is no file to pin, fetch or
 * measure. Android's is the website's phone (web/apex/src/landing/carousel/appCarousel.css).
 */
export interface CssFrameEntry {
  kind: 'css'
  platform: 'android'
  device: string
  licence: string
  /** The size of the captures it frames; another aspect ratio would stretch the app. */
  capture: { width: number; height: number }
  /** The rendered PNG's size: one Play accepts for a phone screenshot. */
  output: { width: number; height: number }
}

export type AnyFrameEntry = FrameEntry | CssFrameEntry

export const isCssFrame = (f: AnyFrameEntry): f is CssFrameEntry => f.kind === 'css'

export interface FramesManifest {
  version: number
  frames: Record<string, AnyFrameEntry>
}

/** A frame whose bezel is pinned and measured — the only kind the renderer accepts. */
export interface ReadyFrame extends FrameEntry {
  id: string
  sha256: string
  screen: Rect
}

export class FrameError extends Error {}

const SHA256 = /^[0-9a-f]{64}$/

export function sha256(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex')
}

export function loadFramesManifest(path = FRAMES_MANIFEST): FramesManifest {
  const doc = JSON.parse(readFileSync(path, 'utf8')) as FramesManifest
  const problems = manifestProblems(doc)
  if (problems.length) throw new FrameError(`${path}:\n  ${problems.join('\n  ')}`)
  return doc
}

/** Shape problems of the manifest itself; an unpinned frame (sha256 null) is allowed here. */
export function manifestProblems(doc: FramesManifest): string[] {
  const problems: string[] = []
  if (doc.version !== 1) problems.push(`version ${doc.version}, expected 1`)
  for (const [id, f] of Object.entries(doc.frames ?? {})) {
    if (!/^[a-z0-9]+$/.test(id) || id.endsWith(FRAMED_SUFFIX)) problems.push(`${id}: a frame id is a plain device id (iphone69, ipad13)`)
    if (isCssFrame(f)) {
      if (f.platform !== 'android') problems.push(`${id}: a CSS frame's platform is 'android'`)
      for (const key of ['device', 'licence'] as const) {
        if (typeof f[key] !== 'string' || !f[key]) problems.push(`${id}: '${key}' is required`)
      }
      if (!(f.capture?.width > 0 && f.capture?.height > 0)) problems.push(`${id}: capture.width/height are required`)
      if (!(f.output?.width > 0 && f.output?.height > 0)) problems.push(`${id}: output.width/height are required`)
      continue
    }
    if (f.kind !== undefined && f.kind !== 'bezel') problems.push(`${id}: kind is 'css' or absent`)
    for (const key of ['device', 'source', 'file', 'licence'] as const) {
      if (typeof f[key] !== 'string' || !f[key]) problems.push(`${id}: '${key}' is required`)
    }
    if (f.sha256 !== null && !SHA256.test(f.sha256)) problems.push(`${id}: sha256 is 64 hex digits or null`)
    if (f.fetch !== null && !/^(s3|https):\/\//.test(f.fetch)) problems.push(`${id}: fetch is an s3:// or https:// URL, or null`)
    if (f.fetch !== null && f.sha256 === null) problems.push(`${id}: a fetch URL without a sha256 pin`)
    const s = f.screen ?? {}
    for (const key of ['x', 'y', 'width', 'height', 'cornerRadius'] as const) {
      const v = s[key]
      if (v !== null && !(typeof v === 'number' && v >= 0)) problems.push(`${id}: screen.${key} is a number ≥ 0 or null`)
    }
    if (!(f.output?.width > 0 && f.output?.height > 0)) problems.push(`${id}: output.width/height are required`)
  }
  return problems
}

/** The frame, or a FrameError that says exactly what the maintainer still has to do. */
export function readyFrame(doc: FramesManifest, id: string): ReadyFrame {
  const f = doc.frames[id]
  if (!f) throw new FrameError(`no frame '${id}' in store/frames/manifest.json (have: ${Object.keys(doc.frames).join(', ')})`)
  if (isCssFrame(f)) throw new FrameError(`frame '${id}' is drawn in CSS and has no bezel — render it through android.ts`)
  const missing = (['x', 'y', 'width', 'height', 'cornerRadius'] as const).filter((k) => f.screen[k] === null)
  if (f.sha256 === null || missing.length) {
    throw new FrameError(
      `frame '${id}' is not set up yet: ${f.sha256 === null ? 'sha256 is null' : ''}${f.sha256 === null && missing.length ? ', ' : ''}` +
        `${missing.length ? `screen.${missing.join('/')} unmeasured` : ''}.\n` +
        `  Download "${f.file}" (${f.source}) after accepting Apple's licence, put it in ${FRAMES_DIR}/,\n` +
        `  run \`scripts/store-frame inspect "${join(FRAMES_DIR, f.file)}"\` and copy its sha256 and screen into the manifest\n` +
        `  (docs/how-to/store-listings.md §6).`,
    )
  }
  return { ...f, id, sha256: f.sha256, screen: f.screen as Rect }
}

/**
 * The bezel's bytes, verified against the pin. Looked for as `<sha256>.png` (what `scripts/store-frame
 * fetch` writes) and then under its vendor file name, both in FRAMES_DIR. Never in the repo.
 */
export function readBezel(frame: ReadyFrame, dir = FRAMES_DIR): Uint8Array {
  const candidates = [join(dir, `${frame.sha256}.png`), join(dir, frame.file)]
  const path = candidates.find((p) => existsSync(p))
  if (!path) {
    throw new FrameError(
      `bezel for '${frame.id}' is missing — looked for:\n  ${candidates.join('\n  ')}\n` +
        `  Fetch it from the private bucket (\`scripts/store-frame fetch\`${frame.fetch ? `, ${frame.fetch}` : ', once the manifest has a fetch URL'})\n` +
        `  or download "${frame.file}" from Apple Design Resources. The repo never holds it (Apple's licence, design §3.2).`,
    )
  }
  const bytes = new Uint8Array(readFileSync(path))
  const got = sha256(bytes)
  if (got !== frame.sha256) {
    throw new FrameError(
      `bezel ${path} does NOT match its pin\n  pinned: ${frame.sha256}\n  got:    ${got}\n` +
        `  A different file is a different bezel, with a geometry nobody measured. Re-run inspect before re-pinning.`,
    )
  }
  return bytes
}

/** `NN-screen: caption` lines; `#` comments and blank lines are skipped. */
export function parseCaptions(text: string): Map<string, string> {
  const captions = new Map<string, string>()
  text.split('\n').forEach((line, i) => {
    if (!line.trim() || line.startsWith('#')) return
    const m = /^(\d\d-[a-z0-9-]+):\s+(\S.*)$/.exec(line)
    if (!m) throw new FrameError(`captions line ${i + 1}: not \`NN-screen: caption\``)
    captions.set(m[1], m[2].trim())
  })
  return captions
}

/** The PNG's size from its IHDR — enough to place it, without decoding it. */
export function pngSize(bytes: Uint8Array): { width: number; height: number } {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length < 24 || sig.some((b, i) => bytes[i] !== b)) throw new FrameError('not a PNG')
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  return { width: view.getUint32(16), height: view.getUint32(20) }
}

export interface Layout {
  caption: { left: number; top: number; width: number; height: number; fontSize: number } | null
  /** Where the bezel's top-left lands, and its scale. */
  device: { left: number; top: number; scale: number }
}

/**
 * Caption on top, the device below it, scaled to fit and centred. All in output pixels.
 * Proportions are of the output width, so a phone and an iPad frame look like one family.
 */
export function layout(output: { width: number; height: number }, bezel: { width: number; height: number }, hasCaption: boolean): Layout {
  const pad = Math.round(output.width * 0.06)
  const fontSize = Math.round(output.width * 0.062)
  const captionHeight = hasCaption ? Math.round(fontSize * 1.15 * 2 + pad) : 0
  const boxTop = pad + captionHeight
  const boxWidth = output.width - 2 * pad
  const boxHeight = output.height - boxTop - pad
  const scale = Math.min(boxWidth / bezel.width, boxHeight / bezel.height)
  const left = Math.round((output.width - bezel.width * scale) / 2)
  const top = Math.round(boxTop + (boxHeight - bezel.height * scale) / 2)
  return {
    caption: hasCaption ? { left: pad, top: pad, width: boxWidth, height: captionHeight, fontSize } : null,
    device: { left, top, scale },
  }
}
