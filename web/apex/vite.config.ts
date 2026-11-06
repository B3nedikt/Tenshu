// `vitest/config` re-exports `defineConfig` with the `test` field
// merged into the schema so tsc accepts it without a triple-slash
// reference. Vite picks the same defaults at dev/build time.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
// Relative, not the package name: Vite loads this config through Node, which would have to strip
// a .ts file inside node_modules (refused) if the import resolved there.
import { THEME_BOOT_SCRIPT } from '../shared/theme.ts'
import { supportPages } from './support-pages/vitePlugin.ts'
import { apexCsp } from './csp/vitePlugin.ts'

// The pnpm workspace root is `web/`, but `@tenshu/sdk` re-exports from the
// Gradle-built bundle at `tenshu-sdk-js/build/...` (one level up). Vite's
// dev server refuses files outside its detected root by default, so allow
// the repo root explicitly. See docs/web/architecture.md §12.8.
export default defineConfig({
  // Vitest config — pure-logic tests today (Phase T's first slice). The
  // jsdom environment is needed for tests that touch sessionStorage,
  // matchMedia, etc. via the apex's helpers; node would suffice for the
  // truly pure files but mixing environments per-file is a maintenance
  // burden we don't need yet.
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{ts,tsx}', 'support-pages/**/*.test.ts', 'csp/**/*.test.ts'],
    globals: false,
    restoreMocks: true,
    setupFiles: ['./src/test/setup.ts'],
  },
  // supportPages(): the static /apps/<app>/support/<platform>/<lang> pages, emitted beside the
  // SPA — docs/proposals/store-presence-and-support-pages.md §4, support-pages/generate.ts.
  //
  // themeBoot: the Light / Dark / Auto choice applied in <head> before first paint (no flash of
  // the wrong theme) — @tenshu/web-tokens/theme, the same script the support pages inline.
  //
  // apexCsp(): the Content-Security-Policy <meta>, hashing the theme boot above — csp/csp.ts.
  plugins: [
    react(),
    tailwindcss(),
    supportPages(),
    {
      name: 'tenshu-theme-boot',
      transformIndexHtml: () => [{ tag: 'script', children: THEME_BOOT_SCRIPT, injectTo: 'head-prepend' }],
    },
    apexCsp(),
  ],
  server: {
    fs: {
      allow: ['..', '../..'],
    },
  },
  build: {
    // Vite's default warns above 500 KB. Apex's bundle is ~715 KB minified
    // (~210 KB gzipped) because it pulls in the full Kotlin/JS in-process
    // runtime — proxy infra + per-service schemas + crypto. Transfer cost
    // over the wire is fine (gzip + HTTP/2); parse cost is the only real
    // concern, and apex is a logged-in app where users have it cached
    // after first load. Real per-service Kotlin-side bundle splitting
    // (multiple Gradle webpack outputs + splitChunks, or lazy-loading the
    // in-process runtime) is the right fix when third-party SDK consumption
    // becomes real — see docs/tech-debt.md "Kotlin-side bundle splitting"
    // and docs/web/architecture.md §12.11. Until then, raise the limit so
    // it stops nagging.
    chunkSizeWarningLimit: 1000,
  },
})
