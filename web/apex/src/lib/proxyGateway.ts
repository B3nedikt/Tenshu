// Apex-side proxy gateway. When the apex (`tenshu.app`) is embedded as a
// subdomain app's hidden iframe, this turns the apex window into the proxy
// the app dials over postMessage (docs/web/architecture.md §3.2 + §5.2).
// The apex holds the master keys and runs the in-process SDK runtime; the
// subdomain has none of that and must route every backend call here.
//
// The handlers live in one module per method namespace, `gateway/<ns>.ts`, composed by
// `gateway/index.ts` (web-app-suite.md F4): a new SDK boundary adds a module there, not a block
// here. The admission check (allowed origins) is the §5.2 boundary — only declared Tenshu app
// subdomains may reach the proxy — and, since F5, each origin reaches only its own allowlist
// (`gatewayPermissions.ts`, from each app's registry file). Semantic-permission enforcement per
// the app's manifest grants layers on top in a later phase.
import {
  serveProxyGateway,
  type ProxyGateway,
  type ProxyHandler,
  type ProxySubscriptionHandler,
} from '@tenshu/sdk/bridge'
import { ensureRuntime } from './runtime'
import { composeGateway, GATEWAY_MODULES } from './gateway'
import { configuredAppOrigins, gatewayPermissions } from './gatewayPermissions'

// ⚠ The gateway is mounted on every apex page but must not start the runtime by being mounted
// (that takes the one-per-origin lock — docs/web/architecture.md "Cross-tab"). Each handler starts
// it on first use instead; only `bridge.ping` answers without it.
function withRuntime<H extends (...args: never[]) => unknown>(
  handlers: Readonly<Record<string, H>>,
): Record<string, H> {
  return Object.fromEntries(
    Object.entries(handlers).map(([method, handler]) => [
      method,
      method === 'bridge.ping'
        ? handler
        : (((...args: Parameters<H>) => {
            ensureRuntime()
            return handler(...args)
          }) as H),
    ]),
  )
}

let mounted: ProxyGateway | null = null

/**
 * Mounts the gateway once per page, however many callers ask. Two do: `main.tsx` when the apex is
 * the hidden iframe inside an app, and `RuntimeApp` when it is the host page around one. Both load
 * this module lazily, because it imports the Kotlin runtime (web-app-suite.md F6).
 */
export function ensureProxyGateway(): ProxyGateway {
  return (mounted ??= mountProxyGateway())
}

export function mountProxyGateway(): ProxyGateway {
  const { handlers, subscriptions } = composeGateway(GATEWAY_MODULES)
  return serveProxyGateway({
    // ⚠⚠ F5: per-origin allowlists, not one admitted set — a new app's registry file carries its
    // origin AND its list, and a new method an existing app calls must be added to that app's list.
    permissions: gatewayPermissions(configuredAppOrigins()),
    handlers: withRuntime<ProxyHandler>(handlers),
    subscriptions: withRuntime<ProxySubscriptionHandler>(subscriptions),
  })
}
