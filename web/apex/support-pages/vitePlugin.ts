// Emits the static support pages into `vite build`'s output, so `pnpm -F apex build` (and with
// it CI and the Docker image) ships them with no extra step. Build-only: the dev server serves
// the SPA, and `pnpm -F apex build:support` is the way to look at the pages without a full build.
import type { Plugin } from 'vite'
import { APEX_DIR, buildSupportSite } from './generate.ts'
import { loadSupportChrome } from './loadChrome.ts'
import { collectThirdParty, uncoveredBundleModules } from './thirdParty.ts'

export function supportPages(): Plugin {
  let mode = 'production'
  return {
    name: 'tenshu-support-pages',
    apply: 'build',
    configResolved(config) {
      // The build's mode, so the chrome reads the same `.env` flags as the SPA beside it.
      mode = config.mode
    },
    async generateBundle(_options, bundle) {
      // The licences page (licenses.ts) lists what thirdParty.ts walks from package.json; the real
      // bundle is the check that the walk missed nothing the browser gets.
      const moduleIds = Object.values(bundle).flatMap((out) => (out.type === 'chunk' ? out.moduleIds : []))
      const uncovered = uncoveredBundleModules(moduleIds, collectThirdParty(APEX_DIR))
      if (uncovered.length > 0) {
        this.error(`the third-party licences page (support-pages/thirdParty.ts) misses packages in the bundle:\n  ${uncovered.join('\n  ')}`)
      }
      const site = await buildSupportSite({ chrome: await loadSupportChrome(mode) })
      for (const { file, content } of site.files) {
        this.emitFile({ type: 'asset', fileName: file, source: content })
      }
    },
  }
}
