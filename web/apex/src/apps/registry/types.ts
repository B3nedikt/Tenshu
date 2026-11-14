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
  // ⚠⚠ The app's sections — the ONLY in-app paths the apex will show in its URL (web-apps-layout.md
  // §3.4): `''` is the app's home (`/drive`), `files` is `/drive/files`. A path the app reports
  // that is not listed here is dropped, so user data (a folder name) can never reach the apex
  // URL's path, which the host logs. Keep it in step with the app's section ids.
  readonly sections: readonly string[]
  // The Apps section's display data (IA §4). ⚠ DISPLAY data — what the app can actually call is
  // `methods`. `manifest.launchPath` is where the launcher and the Apps list open it.
  readonly manifest: AppManifest
  // The app's preview card on the overview (docs/proposals/web-apex-home.md §3.6). Optional: an app
  // without one shows as an icon only.
  readonly glance?: AppGlance
}

// ── Glances (web-apex-home.md §3.6, decision C) ─────────────────────────────────────────────────
//
// ⚠⚠ The apex DRAWS every card from a few fixed layouts; an app contributes DATA only, never markup —
// realm data is user-controlled, so a card's XSS surface stays React's normal escaping.
// ⚠⚠ A loader reaches the runtime only through [GlanceCall], which refuses any method outside the
// app's own `methods` — the SAME `isMethodPermitted` check the gateway applies to the app's origin.
// So Photos' card can read nothing the Photos app cannot; a card is never a way around F5.

/** A gateway call scoped to one app's allowlist. Rejects with `not permitted …` outside it. */
export type GlanceCall = (method: string, params?: unknown) => Promise<unknown>

export interface GlanceListRow {
  readonly title: string
  /** A short type tag drawn in the row's icon slot (`PDF`, `PNG`); '' for none. */
  readonly badge: string
  /** Epoch ms; the apex formats it. */
  readonly timeMs: number
  readonly sizeBytes: number | null
}

export interface GlanceAgendaRow {
  /** User data (an event's title, a place's name); '' when [titleKey] words the row instead. */
  readonly title: string
  /**
   * A `control` catalogue key under `glance.` the apex words the title with — for anything the app
   * itself would localise (a movement mode, a home/work role, an activity type). ⚠ Never send an
   * ingest-time English word as [title]: the apps never show those either. '' for none.
   */
  readonly titleKey: string
  /** Local wall time `YYYY-MM-DDTHH:mm` (or `YYYY-MM-DD` when [allDay]); the apex says "Today 14:00". */
  readonly startLocal: string
  readonly allDay: boolean
  /** A short second line (a place's city, a duration) — data, not copy; '' for none. */
  readonly detail: string
}

export interface GlanceTile {
  /** A path the card's own `call('fs.getPreview', …)` can draw — so only an app allowed that may use tiles. */
  readonly path: string
  readonly isVideo: boolean
  readonly durationMs: number | null
}

export type GlanceData =
  | {
      readonly layout: 'list'
      readonly rows: readonly GlanceListRow[]
      /** A usage bar under the rows (Drive's storage); null for none. */
      readonly usage: { readonly usedBytes: number; readonly limitBytes: number } | null
    }
  | { readonly layout: 'grid'; readonly tiles: readonly GlanceTile[] }
  | { readonly layout: 'agenda'; readonly rows: readonly GlanceAgendaRow[] }
  | {
      readonly layout: 'stat'
      /** The headline number; `null` when there is none yet (draws the empty state). */
      readonly value: number | null
      /** `control` catalogue key under `glance.unit.` (`steps`). */
      readonly unit: string
      /** 0..1 towards a goal, or null for no bar. */
      readonly progress: number | null
      /** One line under the number (Fit: the last workout). */
      readonly footnote: GlanceAgendaRow | null
    }

export interface AppGlance {
  /** `wide` spans half the grid on large screens, `narrow` a third. */
  readonly size: 'wide' | 'narrow'
  /** The header's second line, as a `control` catalogue key under `glance.subtitle.` (`recents`). */
  readonly subtitle: string
  /**
   * The card's data. ⚠ An EMPTY result (`rows: []`, `tiles: []`) is a real answer and draws the empty
   * state; a THROW is a failure and draws the error state with a retry — never fold a failure into
   * an empty result (web-apex-home.md §3.6).
   */
  load(call: GlanceCall): Promise<GlanceData>
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

