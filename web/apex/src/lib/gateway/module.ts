// One gateway module per method namespace (`fs`, `contact`, `sync`, …), composed in
// `gateway/index.ts` (web-app-suite.md F4). A new service's bridge adds a module file and one line
// there, instead of growing one 700-line `proxyGateway.ts` every app's run edits.
//
// ⚠ Handlers here are raw: `proxyGateway.ts` wraps every one (except `bridge.ping`) so the first
// call starts the runtime — a module must not call `ensureRuntime` itself, and must not do anything
// at import time but declare its handlers.
// ⚠ Which origin may call what is not decided here: that is each app's allowlist
// (`apps/registry/apps/<id>.ts`). A handler no app's list names is unreachable, not exposed.
import type { ProxyHandler, ProxySubscriptionHandler } from '@tenshu/sdk/bridge'

export interface GatewayModule {
  readonly handlers: Readonly<Record<string, ProxyHandler>>
  readonly subscriptions?: Readonly<Record<string, ProxySubscriptionHandler>>
}
