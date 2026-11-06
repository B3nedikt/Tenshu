// Vitest setup wired in `vite.config.ts` (`test.setupFiles`).
//
// `@testing-library/jest-dom/vitest` registers DOM matchers
// (`toBeInTheDocument`, `toHaveAttribute`, …) on Vitest's `expect`.
//
// We register RTL's `cleanup` manually because `vite.config.ts` runs
// vitest with `globals: false`. Without globals, RTL can't detect the
// test framework and skips its automatic-cleanup `afterEach` — leaving
// the DOM polluted between tests.
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

// react-i18next is initialised synchronously here so component tests can
// `screen.getByText('Cancel')` without awaiting i18next-ready. See
// `src/i18n.ts` (ADR 0016).
import '../i18n'

afterEach(() => {
  cleanup()
})
