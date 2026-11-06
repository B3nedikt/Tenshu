// The `manifest.webmanifest` every app serves from its own origin (ADR 0020; docs/web/architecture.md
// §6): the PWA fields plus the `tenshu.*` block the apex verifies (`web/apex/src/lib/tenshuManifest.ts`).
// Written once, here, so an app declares only what differs — its id, copy and semantic permissions —
// and `tenshuManifestPlugin` (wired by `tenshuAppConfig`) serves and emits it.
import type { Plugin } from 'vite'

export interface TenshuSemanticPermission {
  readonly id: string
  readonly humanDescription: string
  readonly scope: readonly string[]
}

export interface TenshuAppManifest {
  // The subdomain and the `tenshu/app/<id>` app id: `drive` → `drive.tenshu.app`.
  readonly id: string
  readonly name: string
  readonly description: string
  readonly version?: string
  readonly semanticPermissions: readonly TenshuSemanticPermission[]
}

// ⚠ Production origins, in every build (dev included) — as the hand-written manifests had them.
// The manifest names the app's home, not wherever this build happens to run.
const PRODUCTION_APEX = 'https://tenshu.app'

export function tenshuWebManifest(app: TenshuAppManifest) {
  const home = `https://${app.id}.tenshu.app/`
  return {
    name: app.name,
    short_name: app.name,
    description: app.description,
    display: 'standalone',
    start_url: home,
    scope: home,
    background_color: '#ffffff',
    theme_color: '#0f172a',
    icons: [{ src: '/icons/tenshu.svg', type: 'image/svg+xml', sizes: 'any' }],
    tenshu: {
      appId: `tenshu/app/${app.id}`,
      version: app.version ?? '0.1.0',
      proxyOrigin: PRODUCTION_APEX,
      semanticPermissions: app.semanticPermissions,
    },
  }
}

export const MANIFEST_FILE = 'manifest.webmanifest'

// Two-space JSON with short arrays inline — the layout the hand-written files had, so the served
// bytes did not change when they moved here.
export function serializeManifest(app: TenshuAppManifest): string {
  const json = JSON.stringify(tenshuWebManifest(app), null, 2)
  return `${json.replace(/\[\s+("[^"\n]*"(?:,\s+"[^"\n]*")*)\s+\]/g, (_m, items: string) => `[${items.replace(/,\s+/g, ', ')}]`)}\n`
}

/** Serves `/manifest.webmanifest` in dev and emits it into the build. */
export function tenshuManifestPlugin(app: TenshuAppManifest): Plugin {
  const source = serializeManifest(app)
  return {
    name: 'tenshu-app-manifest',
    configureServer(server) {
      server.middlewares.use(`/${MANIFEST_FILE}`, (_req, res) => {
        res.setHeader('Content-Type', 'application/manifest+json')
        res.end(source)
      })
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: MANIFEST_FILE, source })
    },
  }
}
