// Loads the landing page's header and footer renderer (src/landing/supportChrome.tsx) for the
// support-page build.
//
// ⚠ Through Vite's module runner, because plain Node cannot: the chrome is TSX, imports a
// workspace package written in TypeScript (@tenshu/web-tokens) and reads `import.meta.env`.
// `runnerImport` transforms exactly as the SPA build does, but needs no bundle — and the SDK is
// never imported at runtime, so this works where Gradle has not run (cli.ts).
import { join } from 'node:path'
import { runnerImport } from 'vite'
import { APEX_DIR, type ChromeRenderer } from './generate.ts'

/** [mode] picks the `.env` files and so the build-time flags (VITE_SIGNUP_DISABLED). */
export async function loadSupportChrome(mode = 'production'): Promise<ChromeRenderer> {
  const { module } = await runnerImport<{ renderSupportChrome: ChromeRenderer }>(
    join(APEX_DIR, 'src', 'landing', 'supportChrome.tsx'),
    { configFile: false, root: APEX_DIR, mode, logLevel: 'warn' },
  )
  return module.renderSupportChrome
}
