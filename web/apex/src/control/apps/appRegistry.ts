// Web-side `AppRegistry` — the data seam behind the Apps section and the overview's "Your apps"
// dialog (mirrors the native `AppRegistry` in `:control-shared-logic`).
//
// Two halves, deliberately kept apart:
//
// - **The apps this apex hosts** (`listInstalled` / `getDetail`) — the bundled manifests, one per
//   registry file (`web-control-plane.md` §4.2). On the web every hosted app is available to open,
//   so this list is not "installed somewhere"; it is "openable here".
// - **The realm's registrations** (`registrations`) — the synced `AppRegistration` rows: which app
//   has run on which *native* device (`control-app-registration.md` §4). Production reads them through
//   `@tenshu/sdk/apps` (`realAppRegistry.ts`); tests script them on `MockAppRegistry`.
//
// ⚠⚠ The browser writes no registration of its own (it has no native IPC handshake), so "not
// registered" means "not used on any native device yet" — never "not used here".
// ⚠ Grants are DISPLAY state on the web: what an app may call is its gateway allowlist (F5), and the
// realm's `AppGrant` rows are keyed by native op ids the manifests do not carry. `setGrant` /
// `revokeAll` stay local to this tab (web-apex-home.md §4.7).
import { listApps, type AppManifest, type PermissionState } from './manifest'

export type GrantState = PermissionState

/** One device an app is registered on. `name` is `null` when the roster cannot name it. */
export interface RegisteredDevice {
  readonly deviceId: string
  readonly name: string | null
}

/** One app's registrations across the realm's native devices. */
export interface AppRegistrationInfo {
  /** Earliest registration — native's install date. */
  readonly installedAtMs: number
  readonly verified: boolean
  readonly devices: readonly RegisteredDevice[]
}

export type RegistrationsState =
  /** No answer yet. ⚠ Say nothing about devices — not "on no devices". */
  | { readonly kind: 'loading' }
  /** ⚠⚠ The read failed or ended: a failure, never an empty success. Say nothing about devices. */
  | { readonly kind: 'unavailable'; readonly message: string }
  /** Keyed by the WEB app id (`photos`); an app no device registered is absent. */
  | { readonly kind: 'ready'; readonly byApp: ReadonlyMap<string, AppRegistrationInfo> }

export interface AppRegistry {
  /** The apps this apex hosts (apex/Tenshu itself omitted — IA §4.1). Stable between writes. */
  listInstalled(): readonly AppManifest[]
  /** Full detail for one hosted app, or `null` if this apex does not host it. */
  getDetail(appId: string): AppManifest | null
  /** The realm's registrations. Stable reference between changes. */
  registrations(): RegistrationsState
  /** Set one permission's tri-state grant (display state — see the header). */
  setGrant(appId: string, permissionId: string, state: GrantState): void
  /** Reset every permission of an app to "ask each time" (display state — see the header). */
  revokeAll(appId: string): void
  /**
   * Deregisters the app on EVERY native device (tombstones + grant purge). Resolves `false` when the
   * write failed. ⚠⚠ No "this device" form: the browser holds no row of its own, so it would be a
   * silent no-op. The app stays openable on the web — this apex hosts it either way.
   */
  removeFromAllDevices(appId: string): Promise<boolean>
  /** Subscribe to state changes; returns an unsubscribe fn (for `useSyncExternalStore`). */
  subscribe(listener: () => void): () => void
}

/**
 * The hosted-app half, shared by every registry: an in-memory observable list seeded from the bundled
 * manifests. Updates are immutable (new refs only for what changed) so `useSyncExternalStore`
 * snapshots stay referentially stable between writes.
 */
export abstract class ManifestAppRegistry implements AppRegistry {
  private apps: readonly AppManifest[]
  private readonly listeners = new Set<() => void>()

  protected constructor(initial: readonly AppManifest[] = listApps()) {
    this.apps = initial
  }

  abstract registrations(): RegistrationsState
  abstract removeFromAllDevices(appId: string): Promise<boolean>

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

  protected get listenerCount(): number {
    return this.listeners.size
  }

  setGrant(appId: string, permissionId: string, state: GrantState): void {
    this.apps = this.apps.map(app =>
      app.id !== appId
        ? app
        : { ...app, permissions: app.permissions.map(p => (p.id === permissionId ? { ...p, state } : p)) },
    )
    this.emit()
  }

  revokeAll(appId: string): void {
    this.apps = this.apps.map(app =>
      app.id !== appId
        ? app
        : { ...app, permissions: app.permissions.map(p => ({ ...p, state: 'askEachTime' as const })) },
    )
    this.emit()
  }

  protected emit(): void {
    for (const listener of this.listeners) listener()
  }
}

/** A registry with no registration source: hosted apps only, registrations `unavailable`. */
export class UnconfiguredAppRegistry extends ManifestAppRegistry {
  private static readonly UNAVAILABLE: RegistrationsState = { kind: 'unavailable', message: 'no registry source' }

  constructor() {
    super()
  }

  registrations(): RegistrationsState {
    return UnconfiguredAppRegistry.UNAVAILABLE
  }

  async removeFromAllDevices(): Promise<boolean> {
    return false
  }
}

/**
 * Test registry: the bundled manifests plus scripted registrations. Starts `ready` with none, so a
 * test that does not care about devices sees the "used on no device" state, never a fake one.
 */
export class MockAppRegistry extends ManifestAppRegistry {
  private state: RegistrationsState
  /** Each `removeFromAllDevices` call, in order — for assertions. */
  readonly removed: string[] = []
  /** Set to make `removeFromAllDevices` resolve `false`. */
  failRemoval = false

  constructor(
    initial: readonly AppManifest[] = listApps(),
    registrations: RegistrationsState = { kind: 'ready', byApp: new Map() },
  ) {
    super(initial)
    this.state = registrations
  }

  registrations(): RegistrationsState {
    return this.state
  }

  setRegistrations(next: RegistrationsState): void {
    this.state = next
    this.emit()
  }

  async removeFromAllDevices(appId: string): Promise<boolean> {
    this.removed.push(appId)
    if (this.failRemoval) return false
    if (this.state.kind === 'ready') {
      const byApp = new Map(this.state.byApp)
      byApp.delete(appId)
      this.setRegistrations({ kind: 'ready', byApp })
    }
    return true
  }
}

/** A ready registrations state from `{ webAppId: deviceNames }` — test shorthand. */
export function registrationsOf(
  apps: Readonly<Record<string, readonly (string | null)[]>>,
  installedAtMs = Date.UTC(2026, 9, 1),
): RegistrationsState {
  return {
    kind: 'ready',
    byApp: new Map(
      Object.entries(apps).map(([appId, names]) => [
        appId,
        {
          installedAtMs,
          verified: true,
          devices: names.map((name, i) => ({ deviceId: `${appId}-device-${i}`, name })),
        },
      ]),
    ),
  }
}
