// What the apex knows about one hosted app, in ONE place (web-app-suite.md F4). Before this, a new
// app edited five lists: the launcher, the Apps manifest, the `AppEmbed` routes, the gateway's
// origins and the env names. Now it adds one file, `registry/apps/<id>.ts`, and edits nothing.
//
// ⚠⚠ Kept free of `@tenshu/sdk` value imports: the launcher reaches this from the lazily loaded
// runtime chunk and the gateway from its own, and neither may pull the Kotlin bundle in through it.
import type { ProxyMethodAllowlist } from '@tenshu/sdk/bridge'
import type { AppManifest } from '../../control/apps/manifest'

export interface HostedApp {
  // The subdomain, the `web/<id>` package and the `tenshu/app/<id>` app id.
  readonly id: string
  // Launcher / Apps-list position, ascending (ties by id). Leave gaps (10, 20, …) so a new app
  // slots in without renumbering another app's file.
  readonly order: number
  // The iframe's accessible title.
  readonly title: string
  // ⚠⚠ The app's own origin. `origin()` must read `import.meta.env.<envName>` LITERALLY (Vite inlines
  // only static references) and go through `appOrigin`, which never falls back to localhost in a
  // production build. `registry.test.ts` pins both, and that `envName`/`prodOrigin` follow the
  // `VITE_<ID>_ORIGIN` / `https://<id>.tenshu.app` convention the deploy scripts derive.
  readonly prodOrigin: string
  readonly envName: string
  // The app's Vite dev server (`tenshuAppConfig({ port })` in `web/<id>/vite.config.ts`).
  readonly devPort: number
  origin(): string
  // ⚠⚠ F5 — exactly the methods the app's UI calls: its `ProxyMethod` table in
  // `web/<id>/src/lib/proxy.ts` (`gatewayPermissions.test.ts` fails on drift). A method missing
  // here is refused with `not permitted …`; an extra one is surface an XSS on that origin gets.
  readonly methods: ProxyMethodAllowlist
  // Where the apex hosts it: the route that frames it (the embedded app owns its in-app
  // navigation, so it ends in `/*`), and older paths that redirect to `manifest.launchPath`.
  readonly routePath: string
  readonly redirects?: readonly string[]
  // The Apps section's display data (IA §4). ⚠ DISPLAY data — what the app can actually call is
  // `methods`. `manifest.launchPath` is where the launcher and the Apps list open it.
  readonly manifest: AppManifest
}

/**
 * The origin the apex reaches an app at: the configured one, else production's host in a
 * production build and the app's Vite dev server otherwise.
 * ⚠ A production build never falls back to a localhost origin: that origin would be handed the
 * app's whole allowlist.
 */
export function appOrigin(configured: string | undefined, prodOrigin: string, devPort: number): string {
  return configured ?? (import.meta.env.PROD ? prodOrigin : `http://localhost:${devPort}`)
}

// The tab/runtime plumbing every app needs before any of its own calls can work: the liveness
// probe, and the one-runtime-per-origin lease + "Use here" handover (docs/web/architecture.md
// "Cross-tab"). Every app's `methods` starts with these.
export const SESSION_METHODS: ProxyMethodAllowlist = [
  'bridge.ping',
  'runtime.lease',
  'runtime.useHere',
]

// V1 fixed install timestamp — placeholder until the realm schema surfaces a real "added to
// realm" field. Picked recently enough that the formatted date isn't jarring; the value isn't
// load-bearing.
export const V1_INSTALL_AT_MS = Date.UTC(2026, 3, 15, 9, 0, 0)
