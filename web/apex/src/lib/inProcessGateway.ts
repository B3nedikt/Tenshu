// The gateway's handlers, called in this page — what the overview's cards read through
// (docs/proposals/web-apex-home.md §3.6). The same modules the postMessage gateway serves to the
// app iframes (`proxyGateway.ts`), so a card and its app read the same code path.
//
// ⚠⚠ UNSCOPED: holding this function is holding every method. Only `overview/glance/scopedCall`
// may wrap it, narrowed to one app's allowlist; nothing else should be handed it.
// ⚠ Answers are returned as is: a handler's `transferring(…)` wrapper (map tiles) is not unwrapped,
// so a card must not load through a transferring method until this learns to.
// ⚠ Imports the Kotlin runtime (via the gateway modules), so only the runtime root imports this.
import { composeGateway, GATEWAY_MODULES } from './gateway'
import { ensureRuntime } from './runtime'

const { handlers } = composeGateway(GATEWAY_MODULES)

export async function callInProcess(method: string, params?: unknown): Promise<unknown> {
  // `Object.hasOwn`, as the bridge does: a prototype key (`constructor`) is not a method.
  const handler = Object.hasOwn(handlers, method) ? handlers[method] : undefined
  if (handler === undefined) throw new Error(`unknown method ${method}`)
  // The same first-call rule `proxyGateway.ts` applies: a handler may need the runtime started.
  ensureRuntime()
  return handler(params)
}
