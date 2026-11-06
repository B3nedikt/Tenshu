// Puts the Content-Security-Policy <meta> on the SPA's index.html (csp.ts says why a <meta>).
// Build-only: the dev server injects its own inline HMR client, and is never served by Caddy.
// The support pages get theirs from support-pages/generate.ts, which emits them as finished assets.
import { readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
import { applyCsp, connectOrigins, hostedAppOrigins, screenshotOrigins, type BuildEnv } from './csp.ts'

const REGISTRY_APPS_DIR = join(dirname(fileURLToPath(import.meta.url)), '../src/apps/registry/apps')

/**
 * The hosted app ids, read the way the registry reads them (`import.meta.glob('./apps/*.ts')`,
 * src/apps/registry/index.ts): one file per app. ⚠ csp.origins.test.ts pins this list to
 * HOSTED_APPS, so an app the registry knows can never be missing from `frame-src`.
 */
export function hostedAppIds(dir: string = REGISTRY_APPS_DIR): string[] {
  return readdirSync(dir)
    .filter((file) => file.endsWith('.ts') && !file.includes('.test.'))
    .map((file) => file.slice(0, -'.ts'.length))
    .sort()
}

export function apexCsp(): Plugin {
  let env: BuildEnv = {}
  return {
    name: 'tenshu-csp',
    apply: 'build',
    configResolved(config) {
      // Vite's resolved env: `.env*` files plus the process's VITE_* vars — exactly what it inlines.
      env = config.env
    },
    transformIndexHtml: {
      // Last, so the hashes cover every inline script another transform added (the theme boot).
      order: 'post',
      handler: (html) =>
        applyCsp(html, { connect: connectOrigins(env), frame: hostedAppOrigins(env, hostedAppIds()), images: screenshotOrigins() }),
    },
  }
}
