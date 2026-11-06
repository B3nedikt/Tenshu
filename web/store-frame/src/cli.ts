/**
 * scripts/store-frame — framed, captioned store screenshots (store-presence-and-support-pages.md §3.2).
 *
 *   render <app> [--device iphone69] [--lang en] [--screen NN-name …] [--no-caption]
 *       every plain capture of that device and language (local, else fetched by manifest sha256) into
 *       store/<app>/screenshots/ios/<device>framed/<lang>/ — then scripts/store-publish.sh publishes it
 *   render <app> --device phone [--lang en] [--screen NN-name …]
 *       the Play set: Android captures in the website's CSS phone (android.ts), captioned from
 *       store/<app>/play/<locale>/captions.txt — by default exactly the screens that file lists —
 *       into store/<app>/screenshots/android/phoneframed/<lang>/
 *   inspect <bezel.png>     sha256 and the transparent screen area, to paste into store/frames/manifest.json
 *   check                   the frames manifest's shape, and which frames are ready
 *
 * Runbook: docs/how-to/store-listings.md §6.
 */
import { existsSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { renderAndroidFrame, type AndroidFrame } from './android.ts'
import { decodePng, launch } from './browser.ts'
import { FrameError, REPO, STORE_DIR, isCssFrame, loadFramesManifest, pngSize, readBezel, readyFrame, sha256 } from './frames.ts'
import { findScreen } from './inspect.ts'
import { availableScreens, captionsFor, captureFor, framedPath, playCaptionsFor, renderFrame, writePng } from './render.ts'

const USAGE = readFileSync(new URL(import.meta.url), 'utf8').split('*/')[0].replace(/^\/\*\*|^ \* ?/gm, '').trim()

function option(args: string[], name: string, fallback: string): string {
  const i = args.indexOf(name)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

async function render(args: string[]): Promise<void> {
  const app = args[0]
  if (!app || app.startsWith('-') || !existsSync(join(STORE_DIR, app, 'screens.json'))) {
    throw new FrameError(`render: which app? (a folder of store/ with a screens.json)\n\n${USAGE}`)
  }
  const device = option(args, '--device', 'iphone69')
  const lang = option(args, '--lang', 'en')
  const withCaption = !args.includes('--no-caption')
  const picked = args.flatMap((a, i) => (args[i - 1] === '--screen' ? [a] : []))
  const doc = loadFramesManifest()
  const entry = doc.frames[device]
  if (entry && isCssFrame(entry)) return renderAndroid(app, { ...entry, id: device }, lang, withCaption, picked)
  // Every check that needs no browser first: a missing bezel or caption fails before Chromium starts.
  const frame = readyFrame(doc, device)
  const bezel = readBezel(frame)
  const captions = withCaption ? captionsFor(app, lang).captions : null
  const screens = picked.length ? picked : availableScreens(app, device, lang)
  if (!screens.length) throw new FrameError(`no ${device}/${lang} captures for ${app} — run scripts/store-screenshots.sh, or publish some first`)
  for (const s of screens) {
    if (captions && !captions.has(s)) throw new FrameError(`no caption for ${s} in ${app}'s captions.txt (or pass --no-caption)`)
  }
  const browser = await launch()
  try {
    for (const screen of screens) {
      const capture = await captureFor(app, device, lang, screen)
      const png = await renderFrame(browser, { frame, bezel, capture: capture.bytes, caption: captions?.get(screen) ?? null })
      const out = framedPath(app, device, lang, screen)
      writePng(out, png)
      console.log(`store-frame: ${relative(REPO, out)}  ← ${capture.from.startsWith('http') ? capture.from : relative(REPO, capture.from)}`)
    }
  } finally {
    await browser.close()
  }
  console.log(`store-frame: ${screens.length} framed. Publish with scripts/store-publish.sh ${app} (as ios/${device}framed/${lang}/…); the listing takes them with "framed": true in store/${app}/listing.json.`)
}

async function renderAndroid(app: string, frame: AndroidFrame, lang: string, withCaption: boolean, picked: string[]): Promise<void> {
  const captions = withCaption ? playCaptionsFor(app, lang).captions : null
  const screens = picked.length ? picked : captions ? [...captions.keys()] : availableScreens(app, frame.id, lang, 'android')
  if (!screens.length) throw new FrameError(`no ${frame.id}/${lang} screens for ${app} — captions.txt lists none, or nothing is captured`)
  for (const s of screens) {
    if (captions && !captions.has(s)) throw new FrameError(`no caption for ${s} in ${app}'s Play captions.txt (or pass --no-caption)`)
  }
  // Every capture first: a caption naming a screen with no capture fails before any PNG is replaced.
  const captures = new Map<string, Awaited<ReturnType<typeof captureFor>>>()
  for (const screen of screens) captures.set(screen, await captureFor(app, frame.id, lang, screen, 'android'))
  const browser = await launch()
  try {
    for (const screen of screens) {
      const capture = captures.get(screen)!
      const png = await renderAndroidFrame(browser, { frame, capture: capture.bytes, caption: captions?.get(screen) ?? null })
      const out = framedPath(app, frame.id, lang, screen, 'android')
      writePng(out, png)
      console.log(`store-frame: ${relative(REPO, out)}  ← ${capture.from.startsWith('http') ? capture.from : relative(REPO, capture.from)}`)
    }
  } finally {
    await browser.close()
  }
  console.log(`store-frame: ${screens.length} framed. Publish with scripts/store-publish.sh (as android/${frame.id}framed/${lang}/…); the Play listing names those keys in store/${app}/play/listing.json.`)
}

async function inspect(args: string[]): Promise<void> {
  if (!args[0]) throw new FrameError(`inspect: which bezel file?\n\n${USAGE}`)
  // pnpm runs this inside web/store-frame; a relative path means the caller's directory.
  const bytes = new Uint8Array(readFileSync(resolve(process.env.STORE_FRAME_CWD ?? process.cwd(), args[0])))
  const size = pngSize(bytes)
  const browser = await launch()
  try {
    const page = await browser.newPage()
    const screen = findScreen(await decodePng(page, bytes))
    console.log(JSON.stringify({ sha256: sha256(bytes), bezel: size, screen }, null, 2))
    console.error('store-frame: copy sha256 and screen into store/frames/manifest.json; check cornerRadius against Apple\'s published screen radius.')
  } finally {
    await browser.close()
  }
}

function check(): void {
  const doc = loadFramesManifest()
  for (const [id, f] of Object.entries(doc.frames)) {
    if (isCssFrame(f)) {
      console.log(`  ready     ${id} (drawn in CSS)`)
      continue
    }
    try {
      readyFrame(doc, id)
      console.log(`  ready     ${id}`)
    } catch (e) {
      console.log(`  not ready ${id} — ${(e as Error).message.split('\n')[0]}`)
    }
  }
}

async function main(argv: string[]): Promise<number> {
  const [cmd, ...args] = argv
  try {
    if (cmd === 'render') await render(args)
    else if (cmd === 'inspect') await inspect(args)
    else if (cmd === 'check') check()
    else {
      console.log(USAGE)
      return cmd && cmd !== '--help' && cmd !== '-h' ? 2 : 0
    }
    return 0
  } catch (e) {
    if (e instanceof FrameError) {
      console.error(`store-frame: ${e.message}`)
      return 1
    }
    throw e
  }
}

process.exitCode = await main(process.argv.slice(2))
