// How an overview card reaches the runtime (docs/proposals/web-apex-home.md §3.6).
//
// ⚠ Not imported from the SDK here: the in-process gateway (`lib/inProcessGateway.ts`) drags the
// Kotlin bundle in, so the runtime root injects it and tests inject a fake (the
// `shownAppsContext.ts` pattern).
import { createContext, useContext } from 'react'
import { isMethodPermitted, NOT_PERMITTED } from '@tenshu/sdk/bridge'
import type { GlanceCall, HostedApp } from '../../apps/registry'

/** Calls one gateway handler in this page, by method name — unscoped. Only `scopedCall` may hold it. */
export type GatewayCall = (method: string, params?: unknown) => Promise<unknown>

export const GlanceGatewayContext = createContext<GatewayCall | null>(null)

export function useGlanceGateway(): GatewayCall | null {
  return useContext(GlanceGatewayContext)
}

/**
 * [gateway] narrowed to [app]'s allowlist. ⚠⚠ The SAME `isMethodPermitted` the gateway applies to the
 * app's origin, so a card can read exactly what its app can — and an error that says so, in the
 * gateway's own words, for anything else.
 */
export function scopedCall(app: HostedApp, gateway: GatewayCall): GlanceCall {
  return (method, params) =>
    isMethodPermitted(app.methods, method)
      ? gateway(method, params)
      : Promise.reject(new Error(`${NOT_PERMITTED}: ${app.id} may not call ${method}`))
}
