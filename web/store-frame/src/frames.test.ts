import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  FRAMED_SUFFIX,
  FrameError,
  isCssFrame,
  layout,
  loadFramesManifest,
  manifestProblems,
  parseCaptions,
  readBezel,
  readyFrame,
  sha256,
  type FrameEntry,
  type ReadyFrame,
} from './frames.ts'
import { escapeHtml } from './template.ts'

const entry = (over: Partial<FrameEntry> = {}): FrameEntry => ({
  device: 'Test phone',
  source: 'generated at test time',
  file: 'test.png',
  licence: 'none — synthetic',
  sha256: 'a'.repeat(64),
  fetch: null,
  screen: { x: 10, y: 20, width: 100, height: 200, cornerRadius: 12 },
  output: { width: 330, height: 717 },
  ...over,
})

describe('the committed frames manifest', () => {
  it('parses: two App Store bezels and the CSS-drawn Android phone, each at its store size', () => {
    const doc = loadFramesManifest()
    expect(Object.keys(doc.frames).sort()).toEqual(['ipad13', 'iphone69', 'phone'])
    expect(doc.frames.iphone69.output).toEqual({ width: 1320, height: 2868 })
    expect(doc.frames.ipad13.output).toEqual({ width: 2064, height: 2752 })
    const phone = doc.frames.phone
    expect(isCssFrame(phone)).toBe(true)
    // 9:16 — Play refuses a screenshot whose long side is over twice its short one (a raw 1080 × 2400 capture).
    expect(phone.output).toEqual({ width: 1242, height: 2208 })
    if (isCssFrame(phone)) expect(phone.capture).toEqual({ width: 1080, height: 2400 })
  })

  it('pins no bezel and names no bezel URL until the maintainer has downloaded one', () => {
    // Pinning is the maintainer's step (Apple's licence). This test changes with that commit.
    for (const f of Object.values(loadFramesManifest().frames)) {
      if (isCssFrame(f)) continue // drawn by us: nothing to pin
      expect(f.sha256).toBeNull()
      expect(f.fetch).toBeNull()
    }
  })
})

describe('manifestProblems', () => {
  it('accepts a complete frame and an unpinned one', () => {
    expect(manifestProblems({ version: 1, frames: { phone: entry(), later: entry({ sha256: null, screen: { x: null, y: null, width: null, height: null, cornerRadius: null } }) } })).toEqual([])
  })

  it('checks a CSS frame by its own fields, and refuses one with no capture size', () => {
    const css = { kind: 'css', platform: 'android', device: 'a phone', licence: 'ours', capture: { width: 1080, height: 2400 }, output: { width: 1242, height: 2208 } } as const
    expect(manifestProblems({ version: 1, frames: { phone: css } })).toEqual([])
    expect(manifestProblems({ version: 1, frames: { phone: { ...css, capture: { width: 0, height: 0 } } } }).join('\n')).toMatch(/capture.width\/height/)
    expect(() => readyFrame({ version: 1, frames: { phone: css } }, 'phone')).toThrow(/drawn in CSS/)
  })

  it('refuses a framed id, a fetch without a pin and a bad hash', () => {
    const problems = manifestProblems({
      version: 1,
      frames: { [`phone${FRAMED_SUFFIX}`]: entry(), b: entry({ sha256: null, fetch: 's3://tenshu-store-fixtures/x.png' }), c: entry({ sha256: 'xyz' }) },
    })
    expect(problems.join('\n')).toMatch(/plain device id/)
    expect(problems.join('\n')).toMatch(/fetch URL without a sha256 pin/)
    expect(problems.join('\n')).toMatch(/64 hex digits/)
  })
})

describe('readyFrame / readBezel', () => {
  let dir = ''
  afterEach(() => dir && rmSync(dir, { recursive: true, force: true }))

  it('says what to do when the bezel is not pinned or measured', () => {
    const doc = { version: 1, frames: { phone: entry({ sha256: null, screen: { x: 1, y: 1, width: null, height: 1, cornerRadius: 1 } }) } }
    expect(() => readyFrame(doc, 'phone')).toThrow(/sha256 is null, screen.width unmeasured[\s\S]*store-frame inspect/)
    expect(() => readyFrame(doc, 'tablet')).toThrow(/no frame 'tablet'/)
  })

  it('fails clearly on a missing bezel and on one that does not match its pin', () => {
    dir = mkdtempSync(join(tmpdir(), 'frames-'))
    const bytes = new TextEncoder().encode('not really a bezel')
    const frame: ReadyFrame = { ...entry({ sha256: sha256(bytes) }), id: 'phone', sha256: sha256(bytes), screen: entry().screen as ReadyFrame['screen'] }
    expect(() => readBezel(frame, dir)).toThrow(/bezel for 'phone' is missing[\s\S]*never holds it/)
    writeFileSync(join(dir, 'test.png'), bytes)
    expect(readBezel(frame, dir)).toEqual(bytes)
    writeFileSync(join(dir, 'test.png'), 'another file')
    expect(() => readBezel(frame, dir)).toThrow(FrameError)
    expect(() => readBezel(frame, dir)).toThrow(/does NOT match its pin/)
  })
})

describe('parseCaptions', () => {
  it('reads `NN-screen: text` and skips comments', () => {
    const c = parseCaptions('# about\n\n01-files: Your files, encrypted\n02-photos:  Your photos \n')
    expect([...c]).toEqual([['01-files', 'Your files, encrypted'], ['02-photos', 'Your photos']])
  })

  it('refuses any other line', () => {
    expect(() => parseCaptions('01-files Your files')).toThrow(/line 1/)
  })

  it('the committed captions parse', async () => {
    const { readFileSync } = await import('node:fs')
    const { STORE_DIR } = await import('./frames.ts')
    for (const app of ['control', 'drive']) {
      expect(parseCaptions(readFileSync(join(STORE_DIR, app, 'en-US', 'captions.txt'), 'utf8')).size).toBeGreaterThan(0)
    }
    for (const locale of ['en-US', 'de-DE']) {
      expect(parseCaptions(readFileSync(join(STORE_DIR, 'control', 'play', locale, 'captions.txt'), 'utf8')).size).toBe(5)
    }
  })
})

describe('layout', () => {
  it('fits the bezel under the caption, centred, inside the margins', () => {
    const out = { width: 1320, height: 2868 }
    const l = layout(out, { width: 1500, height: 3000 }, true)
    const w = 1500 * l.device.scale
    const h = 3000 * l.device.scale
    expect(l.caption!.top + l.caption!.height).toBeLessThanOrEqual(l.device.top)
    expect(l.device.left).toBeGreaterThanOrEqual(Math.round(out.width * 0.06) - 1)
    expect(Math.abs(l.device.left - (out.width - w - l.device.left))).toBeLessThanOrEqual(1)
    expect(l.device.top + h).toBeLessThanOrEqual(out.height)
  })

  it('without a caption a height-bound device gets the room', () => {
    const out = { width: 1320, height: 2868 }
    const tall = { width: 1000, height: 3000 }
    expect(layout(out, tall, false).device.scale).toBeGreaterThan(layout(out, tall, true).device.scale)
  })
})

it('escapes a caption', () => {
  expect(escapeHtml(`<b>"Tom's" & co</b>`)).toBe('&lt;b&gt;&quot;Tom&#39;s&quot; &amp; co&lt;/b&gt;')
})
