// The apex's ONE lazy entry into the Kotlin runtime (docs/web/architecture.md "Cross-tab").
//
// ⚠⚠ Starting `JsRuntime` takes the one-per-origin `tenshu-runtime` Web Lock, so it must happen on
// the pages that need the runtime and nowhere else — never on the landing, marketing, support or
// legal pages, which would otherwise lock a Drive tab (or another app tab) out just by being open.
// It used to be a side effect of importing `realOnboardingSdk.ts`, i.e. of loading ANY apex page.
//
// Callers: `RequiresRuntime` (the route gate around every app/onboarding route), the proxy
// gateway (the hidden iframe inside web Drive, on its first request), and the one action a public
// page offers that needs it (the landing banner's "Lock"). `configure` is also what must precede
// every sync-touching SDK call, which is why it is the entry point and not a separate step.
import { TenshuSdk } from '@tenshu/sdk/core'
import { RELAY_URL } from './relayUrl'
import { markRuntimeRequested } from './runtimeRequested'

let started = false

export function ensureRuntime(): void {
  if (started) return
  TenshuSdk.configure(RELAY_URL)
  started = true
  // The root then loads RuntimeLeaseNotice, which reports a refused lease.
  markRuntimeRequested()
}
