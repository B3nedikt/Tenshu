// `shares.*` — Mode-A link sharing.
import { Shares } from '@tenshu/sdk/share'
import type { GatewayModule } from './module'
import { asRecord } from './params'

export const sharesGateway: GatewayModule = {
  handlers: {
    // Phase B3 — Mode-A link sharing (drive-file-sharing.md §3.3/§6.5).
    // `create` mints a link; `list`/`revoke` are the realm owner's *My
    // shares* management surface.
    'shares.create': async (params) => {
      const { path, expiresAtMillis, maxOpens } = readShareCreate(params)
      return Shares.create(path, expiresAtMillis, maxOpens)
    },
    'shares.list': async () => {
      return Shares.list()
    },
    'shares.revoke': async (params) => {
      return Shares.revoke(readShareId(params))
    },
  },
}

function readShareCreate(
  params: unknown,
): { path: string; expiresAtMillis: number; maxOpens: number } {
  const r = asRecord(params)
  if (typeof r.path !== 'string') {
    throw new Error('shares.create requires { path }')
  }
  // 0 = no expiry / no cap (the Kotlin sentinel).
  return {
    path: r.path,
    expiresAtMillis: typeof r.expiresAtMillis === 'number' ? r.expiresAtMillis : 0,
    maxOpens: typeof r.maxOpens === 'number' ? r.maxOpens : 0,
  }
}

function readShareId(params: unknown): string {
  const v = asRecord(params).shareId
  if (typeof v !== 'string') throw new Error('shares.revoke requires { shareId }')
  return v
}
