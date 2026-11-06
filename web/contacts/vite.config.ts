// Contacts subdomain (`contacts.tenshu.app`) — the second app on its own origin (ADR 0020), on the
// shared app preset (`@tenshu/app-kit`, web-app-suite.md F3): Vite + Vitest + Tailwind, the dev
// server's `fs.allow` and the served `manifest.webmanifest`. It value-imports `@tenshu/sdk` ONLY
// through `@tenshu/sdk/bridge` (web-app-suite.md §7). See web-app-suite.md §4.2.
//
// ⚠ Imported by its file path, not `@tenshu/app-kit/vite`: Node loads this config and refuses to
// strip types from a `.ts` it resolved through `node_modules`.
import { tenshuAppConfig } from '../app-kit/src/vite.ts'

// ⚠ No `chunkSizeWarningLimit` override, unlike Drive: this bundle must NOT contain the Kotlin
// runtime, so Vite's default 500 kB warning is the tripwire for a stray SDK value import.
export default tenshuAppConfig({
  // apex 5173, Drive 5174, Contacts 5175 — `web/apex/src/apps/registry/apps/contacts.ts` names the same.
  port: 5175,
  manifest: {
    id: 'contacts',
    name: 'Contacts',
    description: 'The people you know, end-to-end encrypted.',
    semanticPermissions: [
      {
        id: 'tenshu/contact/service',
        humanDescription: 'Read and manage your contacts',
        scope: ['/'],
      },
    ],
  },
})
