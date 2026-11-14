// The production `AppRegistry`: hosted apps from the bundled manifests, registrations from the
// realm's synced `AppRegistration` rows through `@tenshu/sdk/apps` (web-apex-home.md §3.5, H6).
//
// ⚠ Isolated because importing `@tenshu/sdk/apps` drags the Kotlin bundle into whatever imports it.
// Only the runtime root does (`RuntimeApp.tsx`) — the `realShownApps.ts` pattern.
// ⚠⚠ Subscribes on the FIRST listener and stops on the last, so the registry can sit above the
// public routes without starting the runtime: only signed-in pages (Control › Apps, the overview's
// dialog) ever listen.
import { Apps, type RegisteredApp } from '@tenshu/sdk/apps'
import { ManifestAppRegistry, type AppRegistrationInfo, type RegistrationsState } from './appRegistry'
import { nativeAppId, webAppIdOf } from './appIds'

export class SdkAppRegistry extends ManifestAppRegistry {
  private state: RegistrationsState = { kind: 'loading' }
  private stop: (() => void) | null = null
  private pendingStop: ReturnType<typeof setTimeout> | null = null

  constructor() {
    super()
  }

  registrations(): RegistrationsState {
    return this.state
  }

  override subscribe(listener: () => void): () => void {
    const unsubscribe = super.subscribe(listener)
    if (this.pendingStop !== null) {
      clearTimeout(this.pendingStop)
      this.pendingStop = null
    }
    if (this.stop === null) this.start()
    return () => {
      unsubscribe()
      // ⚠ Deferred: a remount (StrictMode, a route change between two consumers) unsubscribes and
      // resubscribes in one tick, and must not restart the realm read.
      if (this.listenerCount === 0 && this.pendingStop === null) {
        this.pendingStop = setTimeout(() => {
          this.pendingStop = null
          if (this.listenerCount > 0 || this.stop === null) return
          this.stop()
          this.stop = null
          // ⚠ Back to loading, not the last view: a later subscriber must not see a stale answer
          // as current before the fresh seed arrives.
          this.state = { kind: 'loading' }
        }, 0)
      }
    }
  }

  async removeFromAllDevices(appId: string): Promise<boolean> {
    const result = await Apps.removeApp(nativeAppId(appId))
    return result.ok
  }

  private start(): void {
    this.stop = Apps.observeRegistrations(
      result => {
        this.state = result.ok
          ? { kind: 'ready', byApp: byWebAppId(result.apps) }
          : { kind: 'unavailable', message: result.error.message ?? result.error.kind }
        this.emit()
      },
      message => {
        this.state = { kind: 'unavailable', message }
        this.emit()
      },
    )
  }
}

/**
 * Keys by the web id. ⚠ A third-party id (not `app.tenshu.*`) is dropped here: the web hosts no such
 * app, so there is nothing to show it against (web-apex-home.md §4.7).
 */
function byWebAppId(apps: readonly RegisteredApp[]): ReadonlyMap<string, AppRegistrationInfo> {
  const byApp = new Map<string, AppRegistrationInfo>()
  for (const app of apps) {
    const id = webAppIdOf(app.appId)
    if (id !== null) byApp.set(id, { installedAtMs: app.installedAtMs, verified: app.verified, devices: app.devices })
  }
  return byApp
}

export const realAppRegistry = new SdkAppRegistry()
