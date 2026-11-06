// The session methods every app may call (`SESSION_METHODS`): transport liveness and the
// one-runtime-per-origin lease (docs/web/architecture.md "Cross-tab").
import { TenshuSdk } from '@tenshu/sdk/core'
import type { GatewayModule } from './module'

export const sessionGateway: GatewayModule = {
  handlers: {
    // Transport liveness — answers without touching the session, so the
    // subdomain can confirm the channel before any signed-in op.
    'bridge.ping': () => ({ pong: true, apexOrigin: window.location.origin }),

    // One runtime per origin (docs/web/architecture.md "Cross-tab"). This iframe shares the
    // apex origin's IndexedDB — and so its lock — with every apex tab, so a Drive tab is just
    // another contender. ⚠ Acquires: an embedded apex exists to run the runtime.
    'runtime.lease': () => TenshuSdk.awaitRuntimeLease(),
    // "Use here" from the subdomain. Resolves once the holder handed over; Drive then reloads
    // its whole page, which reloads this iframe with a fresh runtime that takes the lock.
    'runtime.useHere': () => TenshuSdk.takeOverRuntime(),
  },
}
