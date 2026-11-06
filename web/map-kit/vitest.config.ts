// The map-kit's own tests. Not `vite.config.ts`: the web gate and the image build treat a package
// with one at its root as an app (`deploy/lib/web-apps.sh`). The dev harness keeps its config in
// `harness/` for the same reason.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    globals: false,
    restoreMocks: true,
    setupFiles: ['./src/testSetup.ts'],
  },
})
