// Drive subdomain (`drive.tenshu.app`) — the first app on its own origin (ADR 0020). The shared
// app preset (`@tenshu/app-kit`, web-app-suite.md F3) carries the Vite + Vitest + Tailwind setup,
// the dev server's `fs.allow` and the served `manifest.webmanifest`. See
// docs/proposals/drive-web-app.md §6 (Phase A) + docs/web/roadmap.md Phase 8.1.
//
// ⚠ Imported by its file path, not `@tenshu/app-kit/vite`: Node loads this config and refuses to
// strip types from a `.ts` it resolved through `node_modules`.
import { tenshuAppConfig } from '../app-kit/src/vite.ts'

export default tenshuAppConfig({
  // apex 5173, Drive 5174 — the apex registry entry (`web/apex/src/apps/registry/apps/drive.ts`) names
  // the same port as Drive's dev origin.
  port: 5174,
  manifest: {
    id: 'drive',
    name: 'Drive',
    description: 'Your end-to-end encrypted file system.',
    semanticPermissions: [
      {
        id: 'tenshu/file/service/listFiles',
        humanDescription: 'List files in folders you select',
        scope: ['/'],
      },
    ],
  },
  // ⚠ Drive's bundle is ~580 kB WITHOUT the Kotlin runtime (it holds no SDK value import — only
  // `@tenshu/sdk/bridge`), so it sits above Vite's 500 kB default. Kept from before the app-kit;
  // a stray SDK value import would add ~1.7 MB and still trip this.
  chunkSizeWarningLimit: 1000,
})
