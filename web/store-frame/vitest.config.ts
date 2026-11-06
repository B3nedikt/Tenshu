import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    // The end-to-end test starts Chromium and renders a full-size frame.
    testTimeout: 60_000,
  },
})
