// Apex takes the runtime LAZILY (docs/web/architecture.md "Cross-tab").
//
// ⚠⚠ Starting `JsRuntime` takes the one-per-origin `tenshu-runtime` Web Lock. A public page that
// started it would lock a Drive tab out just by being open — and the start used to be a side
// effect of importing `realOnboardingSdk.ts`, i.e. of loading any apex page at all.
//
// ⚠ Deliberately the REAL bundle and the real App tree, not mocks: the regression this guards is
// "something on the public path reaches the runtime", and only the real modules can show that.
// Web Locks are stubbed with a request that never grants, so a runtime that does start stays
// parked before any store opens (it awaits the lock) — nothing touches IndexedDB.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

const lockRequest = vi.fn(() => new Promise<never>(() => undefined))
Object.defineProperty(window.navigator, 'locks', {
  configurable: true,
  value: { request: lockRequest },
})

// After the stub: the eager-configure regression ran at IMPORT time, so it must be observable.
// ⚠ The lock request is the observable, not a spy on `TenshuSdk.configure`: the exported Kotlin
// object's methods cannot be replaced (`vi.spyOn` leaves the original in place), and the lock is
// the thing that actually matters. `lib/runtime.test.ts` pins configure itself.
const { default: App } = await import('./App')
const { OnboardingSdkProvider } = await import('./lib/onboardingSdk')

function renderAt(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <OnboardingSdkProvider>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </OnboardingSdkProvider>
    </QueryClientProvider>,
  )
}

const flush = () => new Promise(resolve => setTimeout(resolve, 20))

describe('apex runtime laziness', () => {
  // ⚠ Order matters: the runtime is a page singleton, so the public route goes first.
  it('the landing route mounts without starting the runtime or asking for the lock', async () => {
    renderAt('/')
    await flush()

    expect(screen.getAllByRole('link').length).toBeGreaterThan(0)
    expect(lockRequest).not.toHaveBeenCalled()
  })

  // ⚠ Both lazy-route cases carry a 30 s test timeout: their `waitFor` allows 15 s for the RuntimeApp
  // chunk (the whole Kotlin bundle, transformed cold), and Vitest's 5 s default used to cut that off.
  it.each(['/de', '/s/test-share-id', '/vision', '/account/delete'])('the public route %s starts nothing either', async (path) => {
    renderAt(path)
    // ⚠ Wait for the page itself: `/s/:shareId` lives in the lazy RuntimeApp chunk, and asserting
    // before it has loaded would pass without testing anything.
    await vi.waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(0), {
      timeout: 15_000,
    })
    await flush()
    expect(lockRequest).not.toHaveBeenCalled()
  }, 30_000)

  // The gateway mounts in every framed apex (main.tsx) and in RuntimeApp. Mounting must acquire
  // nothing; only a request from an embedded app does (each handler calls ensureRuntime).
  it('mounting the proxy gateway starts nothing', async () => {
    const { mountProxyGateway } = await import('./lib/proxyGateway')
    const gateway = mountProxyGateway()
    await flush()
    gateway.dispose()
    expect(lockRequest).not.toHaveBeenCalled()
  })

  it('an app route starts it and asks for the lock', async () => {
    renderAt('/control/home')
    // App routes live in the lazily loaded RuntimeApp chunk (web-app-suite.md F6), and loading it
    // here evaluates the whole Kotlin bundle, so wait for the request rather than a fixed tick.
    await vi.waitFor(() => expect(lockRequest).toHaveBeenCalled(), { timeout: 15_000 })
    expect(lockRequest).toHaveBeenCalledTimes(1)
    expect(lockRequest).toHaveBeenCalledWith('tenshu-runtime', expect.anything(), expect.any(Function))
  }, 30_000)
})
