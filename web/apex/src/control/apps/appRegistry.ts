// Web-side `AppRegistry` — the mockable data seam behind the Apps section
// (mirrors the native `AppRegistry` in `:control-shared` and the established
// `publisherClient.ts` observable-mock pattern). Reads its initial state from
// the bundled `manifest.ts` (per `web-control-plane.md` §4.2 — permission
// descriptors come from the per-subdomain manifest; last-used from the apex
// audit log). Writes are local-and-observable so the UI is interactive ahead
// of the real KMP→JS `Apps.installed()` surface, which drops in by swapping the
// production binding in `appRegistryContext` with no page changes.
import { listApps, type AppManifest, type PermissionState } from './manifest'

export type GrantState = PermissionState

/** Uninstall scope (`control-app-registration.md` §5.2). On web the realm view
 *  drops the app either way; the per-device nuance is a native concern. */
export type UninstallScope = 'thisDevice' | 'allDevices'

export interface AppRegistry {
  /** Installed apps (apex/Tenshu itself omitted — IA §4.1). Stable reference between writes. */
  listInstalled(): readonly AppManifest[]
  /** Full detail for one app, or `null` if it isn't installed. */
  getDetail(appId: string): AppManifest | null
  /** Set one permission's tri-state grant. */
  setGrant(appId: string, permissionId: string, state: GrantState): void
  /** Reset every permission of an app to "ask each time". */
  revokeAll(appId: string): void
  /** Deregister the app (drops it from the realm-wide list). */
  uninstall(appId: string, scope: UninstallScope): void
  /** Subscribe to state changes; returns an unsubscribe fn (for `useSyncExternalStore`). */
  subscribe(listener: () => void): () => void
}

/**
 * In-memory observable registry seeded from the bundled manifest. Updates are
 * immutable (new array / object refs only for what changed) so
 * `useSyncExternalStore` snapshots stay referentially stable between writes —
 * unchanged apps keep their identity and don't trigger spurious re-renders.
 */
export class MockAppRegistry implements AppRegistry {
  private apps: readonly AppManifest[]
  private readonly listeners = new Set<() => void>()

  constructor(initial: readonly AppManifest[] = listApps()) {
    this.apps = initial
  }

  listInstalled(): readonly AppManifest[] {
    return this.apps
  }

  getDetail(appId: string): AppManifest | null {
    return this.apps.find(a => a.id === appId) ?? null
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  setGrant(appId: string, permissionId: string, state: GrantState): void {
    this.apps = this.apps.map(app =>
      app.id !== appId
        ? app
        : {
            ...app,
            permissions: app.permissions.map(p =>
              p.id === permissionId ? { ...p, state } : p,
            ),
          },
    )
    this.emit()
  }

  revokeAll(appId: string): void {
    this.apps = this.apps.map(app =>
      app.id !== appId
        ? app
        : {
            ...app,
            permissions: app.permissions.map(p => ({ ...p, state: 'askEachTime' as const })),
          },
    )
    this.emit()
  }

  uninstall(appId: string, _scope: UninstallScope): void {
    this.apps = this.apps.filter(a => a.id !== appId)
    this.emit()
  }

  private emit(): void {
    for (const listener of this.listeners) listener()
  }
}
