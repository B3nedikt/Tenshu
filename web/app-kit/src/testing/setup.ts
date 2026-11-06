// Vitest setup every app shares (`tenshuAppConfig` wires it as `test.setupFiles`).
//
// `@testing-library/jest-dom/vitest` registers DOM matchers (`toBeInTheDocument`,
// `toHaveAttribute`, …) on Vitest's `expect`.
//
// RTL's `cleanup` is registered manually because the apps run vitest with `globals: false`.
// Without globals, RTL can't detect the test framework and skips its automatic-cleanup
// `afterEach` — leaving the DOM polluted between tests.
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

afterEach(async () => {
  cleanup()
  // ⚠ Unmounting a Radix overlay (the shared Dialog and Menu use FocusScope) queues a
  // `setTimeout(0)` that builds `new CustomEvent(…)` from the global and dispatches it on the old
  // container. Nothing clears that timer. After a file's last test, Vitest tears jsdom down ~1–2 ms
  // later and puts Node's own `CustomEvent` back, so if the timer loses that race jsdom rejects the
  // foreign event: "parameter 1 is not of type 'Event'" — an unhandled error that fails the run
  // with every test green. Waiting one real tick lets it fire while jsdom is still installed: Node
  // runs timers of the same duration in the order they were set, so this one always runs after it.
  // Under fake timers that timer is fake too and never fires on its own, so there is nothing to wait for.
  if (!vi.isFakeTimers()) await new Promise((resolve) => setTimeout(resolve, 0))
})
