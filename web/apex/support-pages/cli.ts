// Standalone entry: `pnpm -F apex build:support [outDir]` (default `dist`).
//
// The same pages `vite build` emits through vitePlugin.ts, without the SPA build — which
// needs the Gradle-built SDK bundle. Run with plain Node (≥ 22.18 strips the types); the landing
// header and footer come in through Vite's module runner (loadChrome.ts).
import { cpSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { APEX_DIR, PUBLIC_DIR, buildSupportSite } from './generate.ts'
import { loadSupportChrome } from './loadChrome.ts'

const outDir = resolve(APEX_DIR, process.argv[2] ?? 'dist')
const site = await buildSupportSite({ chrome: await loadSupportChrome() })
for (const { file, content } of site.files) {
  const target = join(outDir, file)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, content)
}
// What Vite's public/ copy would have brought along: the icons the pages link. Screenshots are
// not files of the site — the pages link them on R2 (store/<app>/screenshots.manifest.json).
for (const dir of ['icons']) {
  const from = join(PUBLIC_DIR, dir)
  if (existsSync(from)) cpSync(from, join(outDir, dir), { recursive: true })
}
console.log(`support pages: ${site.pages.length} pages, ${site.files.length} files → ${outDir}`)
