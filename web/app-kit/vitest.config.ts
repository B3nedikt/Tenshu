// The app-kit's own tests. Not `vite.config.ts`: the web gate treats a package with one as an app.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    globals: false,
    restoreMocks: true,
    setupFiles: ['./src/testing/setup.ts'],
  },
})
