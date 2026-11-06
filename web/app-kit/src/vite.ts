// The Vite + Vitest config every app shares (web-app-suite.md F3). An app's `vite.config.ts` is:
//
//   export default tenshuAppConfig({ port: 5176, manifest: { id: 'calendar', … } })
//
// ⚠ An app's `vite.config.ts` imports this file by RELATIVE path (`../app-kit/src/vite.ts`), not
// by package name: Vite loads the config through Node, which refuses to strip types from a `.ts`
// file it resolved through `node_modules` (the apex's `vite.config.ts` does the same for the theme).
import { defineConfig, type ViteUserConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { tenshuManifestPlugin, type TenshuAppManifest } from './manifest.ts'

export interface TenshuAppConfig {
  // The dev server's port — apex 5173, Drive 5174, Contacts 5175, … one per app so all of them run
  // side by side. ⚠ The apex's registry entry for the app names the same port (its dev origin).
  readonly port: number
  readonly manifest: TenshuAppManifest
  // Vite warns above 500 kB by default. ⚠ Leave it: an app's bundle must NOT contain the Kotlin
  // runtime, and the warning is the tripwire for a stray `@tenshu/sdk` value import.
  readonly chunkSizeWarningLimit?: number
}

export function tenshuAppConfig(app: TenshuAppConfig): ViteUserConfig {
  return defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      globals: false,
      restoreMocks: true,
      // The app's own file, which imports `@tenshu/app-kit/testing/setup` (jest-dom + cleanup) —
      // through the app's program, so tsc sees the matcher types too.
      setupFiles: ['./src/test/setup.ts'],
    },
    plugins: [react(), tailwindcss(), tenshuManifestPlugin(app.manifest)],
    resolve: {
      // ⚠ One copy of each: the app-kit's components and the app must share React's dispatcher,
      // the QueryClient context and the i18next instance, or hooks break across the boundary.
      dedupe: ['react', 'react-dom', '@tanstack/react-query', 'i18next', 'react-i18next'],
    },
    server: {
      port: app.port,
      // `@tenshu/sdk` resolves into the repo (`tenshu-sdk-js/build`, two levels up from an app), and
      // the dev server refuses files outside its root otherwise. docs/web/architecture.md §12.8.
      fs: {
        allow: ['..', '../..'],
      },
    },
    ...(app.chunkSizeWarningLimit !== undefined
      ? { build: { chunkSizeWarningLimit: app.chunkSizeWarningLimit } }
      : {}),
  })
}
