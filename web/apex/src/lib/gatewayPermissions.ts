// F5 (`web-app-suite.md` §3) — what each app origin may call on the apex gateway.
//
// ⚠⚠ This file IS the web trust boundary between apps (threat model §"Web apps"). Before F5 the
// gateway checked only that a caller's origin was a Tenshu app, then served every method — so an
// XSS on `drive.tenshu.app` could call `contact.setVerified(true)` (a key marked verified without
// anyone comparing digits, A24), `contact.merge` or `contact.delete`. Now each origin gets the
// methods its own UI calls, and `serveProxyGateway` refuses the rest with `not permitted …`.
//
// Since F4 each app's list and origin live in its registry file, `apps/registry/apps/<id>.ts`,
// beside everything else the apex knows about it; this file composes them. Rules for editing a list:
//  - an app's list is what its UI CALLS, not what it might one day want — exact names, grep
//    `ProxyMethod` in `web/<id>/src/lib/proxy.ts`; a new call adds its name there AND here;
//  - a `.*` prefix only for an app that owns the whole namespace;
//  - never a bare `'*'` — `isMethodPermitted` does not even understand one, on purpose.
//
// Kept free of `@tenshu/sdk` value imports so it is testable (and readable) without the runtime.
import type { ProxyMethodAllowlist } from '@tenshu/sdk/bridge'
import { findHostedApp, HOSTED_APPS, SESSION_METHODS } from '../apps/registry'

export { SESSION_METHODS }

function methodsOf(id: string): ProxyMethodAllowlist {
  const app = findHostedApp(id)
  if (!app) throw new Error(`gateway: no registered app ${id}`)
  return app.methods
}

// The two first apps' lists, by name — what the F5 tests pin (`apps/registry/apps/<id>.ts` holds
// them, with the reasoning for each entry).
export const DRIVE_METHODS: ProxyMethodAllowlist = methodsOf('drive')
export const CONTACTS_METHODS: ProxyMethodAllowlist = methodsOf('contacts')

// Each registered app's configured origin(s), by app id; production's fixed host is always included.
export type AppOrigins = Readonly<Record<string, readonly string[]>>

// Origin → allowlist. ⚠ Throws when one origin is configured for two apps: merging their lists
// would hand each app the other's surface, which is exactly what this file exists to prevent, and
// letting the last one win would silently break the first app instead.
export function gatewayPermissions(
  origins: AppOrigins,
): Readonly<Record<string, ProxyMethodAllowlist>> {
  const permissions: Record<string, ProxyMethodAllowlist> = {}
  const owner = new Map<string, string>()
  const add = (app: string, list: readonly string[], methods: ProxyMethodAllowlist) => {
    for (const origin of list) {
      const existing = owner.get(origin)
      if (existing !== undefined && existing !== app) {
        throw new Error(`gateway: origin ${origin} is configured for both ${existing} and ${app}`)
      }
      owner.set(origin, app)
      permissions[origin] = methods
    }
  }
  for (const app of HOSTED_APPS) add(app.id, origins[app.id] ?? [], app.methods)
  return permissions
}

// The origins the apex reaches each app at (production is the app subdomain; local dev the app's
// Vite server). ⚠ A production build never falls back to a localhost origin (`appOrigin`): that
// origin would be handed the app's whole allowlist.
export function configuredAppOrigins(): AppOrigins {
  return Object.fromEntries(HOSTED_APPS.map((app) => [app.id, [app.prodOrigin, app.origin()]]))
}
