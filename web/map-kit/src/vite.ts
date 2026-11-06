/// <reference types="node" />
// `tenshuMapGlyphs()` — the Vite plugin a map app adds so its OWN origin serves the basemap's glyphs
// (web-app-suite.md §4: no font CDN). The `.pbf` ranges are the ones native ships
// (`ui-map/src/androidMain/assets/glyphs`, single source of truth — the iOS bundle and the Android
// assets mirror it), read in place rather than copied into the repo a second time:
//  - in dev, a middleware answers `<base>/<fontstack>/<range>.pbf`;
//  - in a build, every range (and the font's OFL licence) is emitted under `<base>/`.
// The style's glyph URL is `<own origin><base>/{fontstack}/{range}.pbf` (`MapView`), so `base` must
// match what the app passes as `glyphsBase` (both default to `/glyphs`).
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'

export const GLYPHS_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../ui-map/src/androidMain/assets/glyphs')

/** `[urlPath under base, absolute file]` for every shipped glyph range and the licence. */
export function glyphAssets(root: string = GLYPHS_ROOT): Array<[string, string]> {
  const out: Array<[string, string]> = []
  for (const entry of readdirSync(root).sort()) {
    const path = join(root, entry)
    if (statSync(path).isDirectory()) {
      for (const range of readdirSync(path).sort()) {
        if (range.endsWith('.pbf')) out.push([`${entry}/${range}`, join(path, range)])
      }
    } else if (entry === 'OFL.txt') {
      out.push([entry, path])
    }
  }
  return out
}

/** The file a request path names, or null — never anything outside [root]. */
export function resolveGlyphRequest(root: string, requestPath: string): string | null {
  let decoded: string
  try {
    decoded = decodeURIComponent(requestPath)
  } catch {
    return null
  }
  if (!/^[^/\\]+\/\d+-\d+\.pbf$/.test(decoded)) return null
  const file = resolve(root, decoded)
  return file.startsWith(root + sep) ? file : null
}

export function tenshuMapGlyphs(options: { base?: string; root?: string } = {}): Plugin {
  const base = (options.base ?? '/glyphs').replace(/\/$/, '')
  const root = options.root ?? GLYPHS_ROOT
  return {
    name: 'tenshu-map-glyphs',
    configureServer(server) {
      server.middlewares.use(base, (req, res, next) => {
        const file = resolveGlyphRequest(root, (req.url ?? '').replace(/^\//, '').split('?')[0] ?? '')
        if (!file) return next()
        try {
          const body = readFileSync(file)
          res.setHeader('Content-Type', 'application/x-protobuf')
          res.end(body)
        } catch {
          next()
        }
      })
    },
    generateBundle() {
      for (const [path, file] of glyphAssets(root)) {
        this.emitFile({ type: 'asset', fileName: `${base.replace(/^\//, '')}/${path}`, source: readFileSync(file) })
      }
    },
  }
}
