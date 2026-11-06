// Every gateway module, composed into the one handler table `serveProxyGateway` gets. ⚠ This
// module imports the Kotlin runtime (through the modules' `@tenshu/sdk/*` imports), so only the
// lazily loaded `proxyGateway.ts` may import it (`BundleSplit.test.ts`).
import type { ProxyHandler, ProxySubscriptionHandler } from '@tenshu/sdk/bridge'
import type { GatewayModule } from './module'
import { sessionGateway } from './session'
import { fsGateway } from './fs'
import { sharesGateway } from './shares'
import { syncGateway } from './sync'
import { storageGateway } from './storage'
import { contactGateway } from './contact'
import { membershipGateway } from './membership'
import { sharedFileGateway } from './sharedFile'
import { timelineGateway } from './timeline'
import { calendarGateway } from './calendar'
import { photoGateway } from './photo'
import { mapRegionGateway } from './mapRegion'
import { placeGateway } from './place'
import { fitGateway } from './fit'
import { homeGateway } from './home'
import { miaGateway } from './mia'

export const GATEWAY_MODULES: readonly GatewayModule[] = [
  sessionGateway,
  fsGateway,
  sharesGateway,
  syncGateway,
  storageGateway,
  contactGateway,
  membershipGateway,
  sharedFileGateway,
  timelineGateway,
  calendarGateway,
  photoGateway,
  mapRegionGateway,
  placeGateway,
  fitGateway,
  homeGateway,
  miaGateway,
]

/**
 * The modules' handlers and subscriptions, merged. ⚠ Throws on a method two modules both define:
 * a silent last-one-wins would replace a handler some app already depends on.
 */
export function composeGateway(modules: readonly GatewayModule[]): {
  handlers: Record<string, ProxyHandler>
  subscriptions: Record<string, ProxySubscriptionHandler>
} {
  const handlers: Record<string, ProxyHandler> = {}
  const subscriptions: Record<string, ProxySubscriptionHandler> = {}
  for (const module of modules) {
    merge(handlers, module.handlers)
    merge(subscriptions, module.subscriptions ?? {})
  }
  return { handlers, subscriptions }
}

function merge<H>(into: Record<string, H>, from: Readonly<Record<string, H>>) {
  for (const [method, handler] of Object.entries(from)) {
    if (method in into) throw new Error(`gateway: ${method} is defined by two modules`)
    into[method] = handler
  }
}
