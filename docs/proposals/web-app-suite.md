# The web app suite — bringing the other eight apps to the browser

**Status:** Draft v0.2 (2026-10-23). Wave 1 runs A, B1 and B2 built (§4.1, §4.2, §3 F3/F4 as-built);
Wave 4 Mia runs A and B built (§4.A, §4.B). Order,
hosting, rollout and the writes policy were decided 2026-10-23 (§8); two questions remain open.

**Execution model (maintainer, 2026-10-23):** the apps are *implemented by cloud Claude sessions*
(Claude Code on the web), **including their gates**; **deploys run locally** from the maintainer's
Mac. §6 is the cloud/local split; §7 is the brief every cloud session gets.

**Relationship:** extends [ADR 0020](../adr/0020-web-app-structure.md) (one own-origin package per
app, apex hosts it in an iframe) and repeats, per app, the Drive recipe in
[`drive-web-app.md`](drive-web-app.md). The bundle-weight question is
[`web-sdk-bundle-splitting.md`](web-sdk-bundle-splitting.md); the live gaps of Drive itself are
[`drive-web-parity.md`](drive-web-parity.md).

---

## 1. Where we are (measured 2026-10-23)

| App | Services compile to JS? | In `:tenshu-sdk-js` runtime? | JS export / TS / gateway | `web/<app>` |
|-----|------|------|------|------|
| Drive | yes | yes | full | ✅ `web/drive` |
| Control | — | — (apex *is* Control) | `FileSystem.inventory()` (Wave 4, §4.C) | apex; Settings index + Data→Files built (§4.C); Models waits on a model |
| Contacts | yes | ✅ `ContactPlugin` | 9 handshake-shaped ops (~25–30 % of the app) | — |
| Timeline | yes | ✅ timeline + `PlacePlugin` + `HealthPlugin` (run A) | projected feed/detail/places/route + place & suggestion writes (`timeline-services.md` §6.1) | ✅ `web/timeline` (run B; units pending, `timeline-app.md` §Web scope) |
| Calendar | yes | — | — | — |
| Photos | yes | — | — (Drive's `getPreview`/`getLargePreview`/`ContentStream` reusable) | — |
| Fit | yes (`health-*`, `:fit-projection`, `:fit-shared-file`) | ✅ `HealthPlugin` (run A) | `Fit.kt`: aggregates, keyset History, detail + streams, goals/profile, data sources, GPX/TCX import (`fit-app.md` §Web scope) | ✅ `web/fit` (run B; route map live since W.1) |
| Maps | yes (`place-*`, `map-region-*`, `basemap-core`, `vector-tile`) | ✅ Timeline's `PlacePlugin` + `MapRegionPlugin` (Maps run A, W-MAP) | `place.*` (list/observe/get/rename) + `mapRegion.*` (`maps-app.md` §Web scope) | ✅ `web/maps` (run B) |
| Home | yes (`home-device`, `routine`, `hub-management`) | ✅ `HomePlugins` (run A: maintainer only, **no ops**) | `Home.kt`: devices, hub storage tile, automations, keyset activity feed — **read-only** (§4.H) | — |

| Mia | yes (`mia-*`) | ✅ `MiaPlugin` (run A): native `askMia`, planner only, actions withheld | `Mia.kt`: ask, in-memory history (keyset turns), grants + their switches (`mia-query-layer.md` §20) | ✅ `web/mia` (run B, §4.B) |

**The Kotlin side is not the hard part.** Every feature service already declares `js { browser() }`
with a clean commonMain; none of the `*-shared-logic`/`-ui` modules do, and by House Rule 5 none
should. Each app is the Drive recipe again: **plugin + `@JsExport` facade → `native.d.ts` →
`web/sdk/src/<svc>.ts` → apex gateway handler → `web/<app>` React UI**, with the native
`*Repository` as the behavioural reference.

### 1.1 ⚠⚠ Drive itself has never been deployed to production

`drive.tenshu.app` has no DNS record (`deploy/dns/records.json`), no Caddy site block
(`web/apex/Caddyfile` serves `{$APEX_DOMAIN}` only), no build stage (`web/apex/Dockerfile` builds
apex only), and the apex's `DriveEmbed` falls back to `http://localhost:5174` in a production build.
`curl https://drive.tenshu.app` fails to connect. So the "deploy path for subdomain apps" this plan
needs does not exist for app #1 either — **F2 builds it, and Drive is its first customer.**

### 1.2 ⚠⚠ Cloud sessions could not run Gradle (2026-10-13/14)

Every RemoteTrigger run died configuring the root project on `repo.maven.apache.org … 429`
(plugin portal too); pnpm/tsc/vitest/eslint worked. `scripts/cloud-setup.sh` installs JDKs + the
Android SDK + the Gradle *distribution*, but never warms the **dependency cache**, so every session
resolves the whole graph from Maven Central through a shared egress IP. Without a fix, "cloud
sessions gate their own work" is true for the TypeScript half only — **F1 is the precondition for
the user's execution model.**

---

## 2. Scope per app — the minimum viable web version

Browser-impossible pieces are dropped, not emulated: live capture, sensor recording, OS
notifications in the background, device address books, Health Connect/HealthKit, in-browser ML.
Read-only first where the native app's writes live in a background executor.

| App | MVP on web | Explicitly not on web (v1) | New SDK surface | Size |
|-----|------|------|------|------|
| **Contacts** | list · detail · edit · own profile · invite/redeem (bridged) · vCard import/export (file input / Blob) · SAS compare | device address-book import (no browser API) | ~13 ops on the existing plugin | S |
| **Calendar** | month/week/agenda · event CRUD incl. recurrence (RRULE is commonMain) · show/hide + colours · ICS import/export | reminders while the tab is closed; ICS *subscriptions* (CORS) until decided (§8 Q5) | `CalendarPlugin` + ~20 ops | M |
| **Timeline** | day list of moments + trips · moment/trip/place detail · place rename | capture, geocoding; route map until W-MAP | place + health plugins so subjects resolve; polyline export for W-MAP | M |
| **Photos** | capture-date feed · viewer on the large-preview rungs · albums · favourites · trash · `<video>` for `MOTION` | HEIC originals (show rungs), faces/labels/semantic search (no browser backend — read stored labels only) | `PhotosPlugin` (+ place) + ~20 ops; keyset paging | L |
| **Fit** | dashboard (daily aggregates) · workout history · workout detail stats · goals/profile · GPX/TCX upload via `:fit-shared-file`; HR/route after W-MAP | recording, Health Connect, binary `.FIT` (JVM-only) | `HealthPlugin` + ~25 read ops; TS keyset paging (~4.5k workouts) | L |
| **Maps** | saved places + lists + place detail, then the streamed map | offline region *download* into browser storage | place + map-region plugins; PMTiles range stream | L (W-MAP) |
| **Home** | read-only, mirroring the iOS route-1 precedent: devices, hub storage tile, automations list, activity feed | device commands, editor, pairing, routine evaluation (a tab is a bad executor) | home-device + routine + hub-management plugins, read ops only | M |
| **Mia** | text chat + history over the deterministic planner, grants with their switches (§8 item 10) | voice, widget, assistant role, generative layer | mia plugin; needs calendar/health/place/inference registered first | M |
| **Control** | Settings index; Data → Files; Models/ModelFeature once Mia lands | the native-only screens (EPC, OEM background, offline maps) | small | S |

Correction to a sub-agent finding: the personal content plane **is** wired on web (W1/W2 shipped
and were verified cross-device 2026-08-31, `drive-web-parity.md` §5), so Fit's workout streams and
Photos' rungs are not blocked on it.

---

## 3. Foundation — what every app needs first (F1–F6)

These are the shared costs. Paying them before the apps is what lets several cloud sessions work
in parallel without colliding in the same five files.

**F1 · Cloud Gradle works (owner: local, then one cloud probe).**
- Add a Gradle init script to `scripts/cloud-setup.sh` that mirrors Maven Central (and the plugin
  portal's Maven Central fallthrough) to Google's mirror
  `https://maven-central.storage-download.googleapis.com/maven2/` — `storage.googleapis.com` was
  reachable from the cloud environment on 2026-10-14.
- Warm the dependency cache *in the setup script*
  (`./gradlew :tenshu-sdk-js:compileTestKotlinJs --dry-run` does not download — run the real
  compile once), so the environment snapshot carries it.
- Install headless Chromium: `:tenshu-sdk-js:jsTest` runs in Karma/ChromeHeadless.
- **Exit check:** one RemoteTrigger probe runs `scripts/run-web-gate.sh` (all lanes) and
  `./gradlew compileTestKotlinJs --continue` green, reported in its PR body with the case counts.
- **Fallback if it stays red:** cloud sessions gate the TS lanes (`web`, `build`) and write the
  Kotlin; the `sdk` lane runs locally on each PR before merge (the pattern that worked through
  October: "cloud writes, Mac gates").

**F2 · A deploy path for subdomain apps — first customer Drive (owner: local).**
- One image, one box: build every `web/<app>` into the apex image and give Caddy one site block per
  app host (`{$APEX_DOMAIN}` + `drive.{$APEX_DOMAIN}` + …), each with its own `root`. The apps are
  static; a box per app buys nothing.
- `deploy/build-apex-image.sh` (the native-build, COPY-only path — the only one usable from the arm
  Mac) learns to build all apps and pass `VITE_APEX_ORIGIN` / `VITE_<APP>_ORIGIN`.
- DNS rows in `deploy/dns/records.json`; `frame-ancestors` so only the apex may frame an app.
- Ship to the **dev lab (`tenshu.xyz`) first**, then production.
- **Exit check:** `https://drive.tenshu.app` loads inside `tenshu.app` in production, signed in.

**As built (2026-10-23), dev-lab half:**
- **Image.** It carries Drive's build at **`/srv-apps/drive`**. ⚠ Not under `/srv`: the apex serves
  `/srv/apps/<app>/support/…`, so a Drive tree at `/srv/apps/drive` would also answer at
  `<apex>/apps/drive`.
- **Inner Caddy.** `web/apex/Caddyfile`'s `{$DRIVE_SITE}` block serves it, with
  `frame-ancestors {$DRIVE_FRAME_ANCESTORS}`.
- **Production compose.** It defaults `DRIVE_SITE` to a placeholder (`http://drive.disabled.invalid`),
  so production is unchanged until `DRIVE_DOMAIN` is set in `apex.env` and its DNS record exists.
- **Origins.** `build-apex-image.sh` takes `APEX_ORIGIN`/`DRIVE_ORIGIN` (production defaults
  `https://tenshu.app` / `https://drive.tenshu.app`). It fails the build if either bundle does not
  contain its partner origin, because a wrong origin is otherwise invisible until a browser refuses
  the frame.
- **Dev box.** `ship-dev-stack.sh` passes the `tenshu.xyz` origins. The front Caddy gains a
  `{$DRIVE_DOMAIN}` block. The apex container gets `DRIVE_SITE=http://drive.<domain>`, which wins
  over its `:80` catch-all because the hostname is more specific.
- **DNS.** `drive.tenshu.xyz` was created in Cloudflare (DNS-only), like the other dev records. The
  Scaleway record in `deploy/scaleway-dev/main.tf` exists only for `manage_dns = true`.
- **Verified locally** (arm64 image, `*.localhost`):
  - host routing: Drive deep links serve Drive, and `/apps/drive` on the apex host still serves the apex;
  - the CSP header is on Drive only (since 2026-10-28 the apex has its own: `docs/web/architecture.md` §5.3);
  - in Chrome, standalone Drive completed the bridge handshake with the hidden apex frame;
  - the apex may frame Drive, and another origin is refused.
- **Production half still owed:** `deploy/dns/records.json` for `drive.tenshu.app`, and
  `DRIVE_DOMAIN` in the production `apex.env`.

**F3 · `@tenshu/app-kit` + scaffold — extracted from app #2 (Contacts), per ADR 0020.**
Bridge bootstrap, session guard, Tailwind preset, manifest helper, `ErrorBoundary`, the query-key
factory pattern, and `@tenshu/ui` promotion (design-system Phase 5). Plus a generic
`AppEmbed({origin,title})` replacing `DriveEmbed`, and a launcher/Apps list driven by one manifest
instead of three hard-coded lists.

**As built (2026-10-23, Wave 1 run B2).** `web/app-kit` (`@tenshu/app-kit`) holds what Drive and
Contacts had each written, and both apps are retrofitted onto it. The runbook for a new app is
[`how-to/new-web-app.md`](../how-to/new-web-app.md). The entries:

| Entry | What it holds |
|---|---|
| `/bridge` | `createApexBridge(ProxyMethod)`: the memoised `mountApexProxy` over `VITE_APEX_ORIGIN`, and typed `call` / `subscribe`. The subscribe is async setup with synchronous teardown. |
| `/ui` | `AppErrorBoundary`, `RuntimeLeaseBanner` ("Use here"), `SyncHealthNotice` and `StreamNotice`. Each takes its copy as props, so Drive, which has no i18n, shares the markup with localised apps. |
| `/live` | `useLive` + `createLiveContext`: Contacts' `LiveProvider` pattern. |
| `/query` | `createAppQueryClient` (no retries) and `defineQueryKeys` (merges in `runtimeLease`). |
| `/i18n` | `initAppI18n`: i18next + ICU, en/de/fr. |
| `/errors` | `knownErrorKind`, `bridgeErrorKind`. |
| `/root` | `mountApp`. |
| `/vite` | `tenshuAppConfig({ port, manifest })`: Vite + Vitest + Tailwind, `fs.allow`, `resolve.dedupe`, and the manifest plugin. |
| configs and test helpers | ESLint and tsconfig bases, the Vitest setup, a render helper, `catalogueKeys`, and the static import-graph walker. |

The scaffold is `scripts/new-web-app.sh <id> <port>` over `web/app-kit/template/*.tmpl`.

- ⚠⚠ **The app-kit may value-import `@tenshu/sdk/bridge` and nothing else from the SDK.**
  `app-kit/src/sdkBoundary.test.ts` walks every file and fails on any other value import. Each app
  has the same guard on its `main.tsx`.
- ⚠ **`manifest.webmanifest` is generated** from `vite.config.ts` by the preset's plugin. The
  hand-written `public/` files are gone. The emitted bytes were checked identical for both apps.
- ⚠ **An app's `vite.config.ts` imports the preset by file path** (`../app-kit/src/vite.ts`), not
  by package name, because Node refuses to strip types from a `.ts` file under `node_modules`.
  An app's `index.css` must import `@tenshu/app-kit/styles.css`, or Tailwind drops the kit
  components' classes. That file's `@source` covers `src/ui` only, because Tailwind reads every
  word of a source (comments included) as a candidate class.
- **Size:** Drive JS 582.43 → 583.40 kB and Contacts 485.62 → 486.88 kB. Both CSS files are
  byte-identical.
- **Not done:** `web/shared` stays where it is. The `@tenshu/ui` promotion is design-system Phase 5,
  not this run.

**F4 · De-hotspot the shared files (owner: one cloud session, before any app work fans out).**
Today every app edits the same files: `FirstPartyPlugins.kt`, `tenshu-sdk-js/build.gradle.kts`,
`web/sdk/src/native.d.ts`, `web/apex/src/lib/proxyGateway.ts`, `.github/workflows/web.yml`,
`scripts/run-web-gate.sh`. Split them so an app adds files rather than editing shared ones:
one plugin file per app, `native/<svc>.d.ts` fragments, `proxyGateway/<app>.ts` handler modules,
and a gate script + CI job that **iterate over `web/*/package.json`** instead of naming apex and
drive. What stays shared (`build.gradle.kts` deps, the two `web.yml` path lists, the lockfile) is
one line per app and rebases trivially.

**As built (2026-10-23, Wave 1 run B2), web half.** A new app now adds files and edits no shared
list:

- **Apex registry.** Everything the apex knows about an app is one file,
  `web/apex/src/apps/registry/apps/<id>.ts`: origin, env name, dev port, the F5 allowlist, route,
  redirects and the Apps-section entry.
  - `registry/index.ts` collects the files with `import.meta.glob`, so registering is adding the
    file. The launcher, the Apps list, the `HostedAppEmbed` routes (which replaced `DriveEmbed` /
    `ContactsEmbed`) and `gatewayPermissions.ts` all read it.
  - ⚠⚠ `registry.test.ts` holds every entry to the id conventions the deploy scripts derive:
    `VITE_<ID>_ORIGIN`, `https://<id>.tenshu.app`, the port in the app's own `vite.config.ts`, and
    production never falling back to localhost. It also requires a `web/<id>` for every entry, and
    an entry for every `web/<id>` app.
- **Gateway.** The handlers are one module per namespace, `web/apex/src/lib/gateway/<ns>.ts`,
  composed by `gateway/index.ts`. Composition throws on a method defined twice. `gateway.test.ts`
  fails if any app's allowlist names a method no module serves. The gateway is still lazily loaded
  (`BundleSplit.test.ts`) and `ensureProxyGateway` is unchanged.
- **SDK types.** `web/sdk/src/native.d.ts` is an index of `native/<svc>.d.ts` fragments. Each
  fragment carries its own `declare module` block, and TypeScript merges the repeats.
  `_bundle.ts`'s export list stays one line per service.
- **Workspace.** `web/pnpm-workspace.yaml` is the glob `"*"`.
- **Gate, CI and image** find apps as "a `web/<id>` with a `vite.config.ts`"
  (`deploy/lib/web-apps.sh`):
  - `run-web-gate.sh` and the `web.yml` step iterate that list. CI uses one loop step rather than a
    matrix, because each matrix job would rebuild the Gradle bundle. Both also run the app-kit's
    lint, typecheck and tests.
  - `build-apex-image.sh` builds every app, checks both origins per app, and stages them as
    `web/.apps-dist/<id>`. The Dockerfile copies that whole tree to `/srv-apps`.
  - The Caddyfiles serve each app through one snippet (`hosted_app` / `hosted_app_proxy`), with a
    two-line site block per app. `caddy adapt` gives byte-identical JSON before and after, with one
    and with two frame-ancestor origins.
- **Deliberately still per app:**
  - compose env (`<ID>_SITE`, `<ID>_FRAME_ANCESTORS`, and on the dev overlay `<ID>_DOMAIN`), because
    compose cannot loop and a missing default takes the apex down;
  - the Caddy site blocks themselves, because each production host needs its own certificate;
  - DNS;
  - launcher copy in `control.json`, which is optional because the launcher falls back to the
    registry's English label.

  The scaffold prints these.
- **Owed outside the web half:** `FirstPartyPlugins.kt` / `tenshu-sdk-js/build.gradle.kts` (one
  plugin file per app) were not split in this run.

**F5 · Per-app gateway permissions.** `serveProxyGateway` checks the caller's *origin* and then
serves every handler. With one app that is fine; with nine, a defect in `photos.tenshu.app` reads
the whole Drive. Minimum: each allowed origin maps to the method namespaces its manifest declares
(`calendar.*` + the shared session/notification ones), enforced in `web/sdk/src/bridge.ts`, with a
test per app that a foreign namespace is refused. ⚠ This changes what an app origin can reach, so
it gets a threat-model note in the same PR (House Rule 2 spirit: the web app trust boundary is
same-origin, `architecture.md` §3.1).

**F6 · Split the apex bundle, then hold a budget.** Measured 2026-10-23: the apex build is **one
chunk**, `index-*.js` = 4.7 MB (1.08 MB gzip), and contains the whole Kotlin runtime (3.9 MB,
830 KB gzip). `web/apex/src` has no dynamic `import()` and no `React.lazy`. So the public landing
and support pages download the runtime too. (`RuntimeLaziness.test.tsx` guards when the runtime
*starts*, which is the Web Lock, not when it *downloads*.)

Two levels, and only the first is needed now:

1. **Idiomatic Vite splitting (do it in Wave 0).** Load the runtime with a dynamic `import()` in
   the runtime provider, and wrap the signed-in routes (`/control/*`, onboarding, `/s/:id`) in
   `React.lazy`. Landing and support pages then ship without Kotlin, and the runtime becomes its
   own long-cached chunk that is fetched once at sign-in.
2. **Per-app service loading: the dependency graph exists; three gaps remain.** Each
   `JsRuntimePlugin` already declares its schema modules and `dependsOn`, and
   `SchemaRegistry.registerConfig` walks prerequisites. So "an app lists its services, and the
   runtime resolves the closure" is mostly wiring. The gaps:
   - **Code loading.** The SDK ships as one webpack file (`_bundle.ts`), so a plugin cannot be
     fetched on its own yet. That is Path A 2a–2c in `web-sdk-bundle-splitting.md`: per-module
     ESM through Vite, with one dynamic `import()` per plugin. The webpack-externals spike was a
     NO-GO, so this is the route.
   - **Late registration.** `JsRuntimeBuilder` composes every plugin once at start. A plugin
     that arrives later needs either a way to add it to the live runtime, or a runtime restart with
     the larger set. A restart is simpler, and the migrator already handles it: it stamps a service
     it has never seen and never judges a recorded service the build does not declare
     (`StoreSchemaMigrator`, `readableFloorOf`).
   - ⚠⚠ **Sync drops rows for services that aren't loaded, and the cursor still advances.**
     `SchemaPullFence` KDoc: blocks for unknown types are "dropped as unparseable by the ordinary
     ingest path". A session with only Drive loaded would discard the Calendar rows it pulls, and
     loading Calendar later would not bring them back. The relay still has them, but nothing
     re-pulls them for a newly loaded service.

   **iOS already hit this and settled it:** `ios-per-app-frameworks.md` **D4** + §6.2. Every
   app's in-process runtime keeps the *full* service graph, "no exceptions until per-service
   cursors exist". Each `:tenshu-sdk-ios-<app>` has a `DeclaredServiceVersionsTest` against one
   committed list, `PinnedServiceVersions` (`tenshu-sdk-ios-core/src/serviceVersionPin`). The web
   follows the same rule:
   - **F7 (Wave 0): the web runtime declares the full pinned service set.** Today it declares
     about nine services and is missing calendar, health, home-device, hub-management, inference,
     map-region, mia, photo, place, routine and others. ⚠⚠ So **every browser device drops those
     services' rows at ingest today**, and each wave that later adds a service would find no
     history for it in existing browser stores. Register every service's plugin in the JS runtime
     before any app work. Move `PinnedServiceVersions` to a source set the JS test lane also
     compiles, and add a jsTest guard that the assembled `JsRuntime` declares exactly that list.
     Browser stores that already exist recover by re-onboarding ("forget this device"): a web
     device is a cache.
   - **Loading services per app is therefore blocked on per-service cursors**, the same condition
     that blocks slimming the iOS binaries. Build it once and both platforms benefit. Until then
     the runtime chunk carries every service, and the budget above measures its real cost. A
     cheaper variant to evaluate then: always load each service's schema + mergers, and load only
     its operations per app. It is unproven whether ingest needs anything beyond schema + mergers.

Budget: the gate prints the runtime chunk's gzip size and fails above a ceiling set from the
post-split baseline plus headroom for the planned services. Path A starts when a wave would cross
it.

**As built, level 1 (2026-10-23).** The landing page now loads `index` + `react` only:
209 KB gzip, down from 1.08 MB. The Kotlin chunk is 822 KB gzip and loads with the first
runtime route. The pieces:
- `App.tsx` holds only the public routes; everything else is the lazy `RuntimeApp.tsx`.
- The root `OnboardingSdkProvider` is a facade that loads the real or mock SDK on first call;
  `peek` stays the localStorage hint.
- The lease notice loads once something calls `ensureRuntime`.
- The banner's "Lock" loads the runtime on click.
- Dev tools load only in dev builds.
- The gateway mounts lazily, in a framed apex from `main.tsx` and in a host apex from
  `RuntimeApp`. ⚠ That made the late mount possible, so the bridge's hidden-iframe dial now
  re-sends its hello every 250 ms. Seen in a real browser: the first hello went unanswered and
  the re-send was answered.
- Guard: `BundleSplit.test.ts` walks the static import graph from `main.tsx`, fails on any
  `@tenshu/sdk` value import, and names the chain. A control case proves the walker sees one
  where it exists.

**App UIs need none of this.** Each app is its own origin in an iframe, so `web/photos`'s code is
downloaded only when someone opens Photos. Building all apps into one image changes nothing for the
browser.

---

## 4. Waves

```
Wave 0  F1 cloud Gradle ─┐   F2 deploy path + ship Drive   F4 de-hotspot
                         │   F6 split apex + budget        F7 full service set (first!)
                         └─► everything below is gated by the cloud
Wave 1  Contacts (+ F3 app-kit extraction, F5 permissions)        ← one session, sequential
Wave 2  Calendar · Timeline (read) · Photos (read) · Control gaps  ← parallel, one session each
Wave 3  W-MAP shared web map renderer ─► Maps · Timeline routes · Fit (+ routes/HR)
Wave 4  Home (read-only) · Mia (text, planner only) · Control gaps (Wave 2 leftover)
```

**W-MAP** is its own slice because three apps need it: MapLibre GL JS fed by `:basemap-core`'s
style JSON, glyphs served from the app's own origin (the style points at `asset://`/`file://`
today), and a MapLibre `addProtocol` source backed by the runtime's PMTiles range stream
(`RegionStreamHandle`). ⚠ No third-party tile or glyph fetch — the native maps are offline-first by
design, and the web must not quietly become the exception.

Each app is cut into **three PRs**, so a cloud session's diff stays reviewable and the Kotlin half
can be gated separately if F1 falls back:

1. **SDK boundary** — plugin, `@JsExport` facade, `jsTest` contract tests (row shapes, not just
   "returns something": the 0 B family shipped behind green suites, `run-web-gate.sh` header).
2. **Bridge** — `native/<svc>.d.ts`, `web/sdk/src/<svc>.ts`, gateway handlers + permission entry.
3. **App** — `web/<app>` from the scaffold, vitest component tests, launcher entry, i18n en/de/fr.

### 4.1 As built — Wave 1 run A: the Contacts SDK boundary + bridge (2026-10-23)

Every `ContactsRepository` op now reaches the web through `Contacts` (`tenshu-sdk-js`
`Contacts.kt` + `ContactsBook.kt`), `web/sdk/src/contact.ts` and `contact.*` gateway methods. The
decisions run B builds on:

- **The seam is re-implemented, not compiled.** `ContactsBook.kt` is a second reader of the same
  relations as `TenshuContactsRepository`. ⚠⚠ The rules it copies (unlinked `personId == ""`
  attaches to nobody, verification fails closed, bare invites are `intentKind == ""`) are pinned in
  `ContactsBookMappingTest`; change one side and the other must follow.
- **Reactive:** `contacts`, `contact` and `ownProfile` are genuine push (`contact.list.observe`,
  `contact.get.observe`, `contact.ownProfile.observe`) over the relations' Flows. ⚠ The profile
  observe seeds from `readOnce`, because `PreferenceService.read` emits nothing on a realm that never
  held the row. The invite reads stay snapshot + refetch, like the handshake's `listInvites`.
- **Errors:** every write is `{ ok: true, … } | { ok: false, error: { kind, message } }`, `kind` a
  catalogue key. ⚠ Unlike native, a **failed read is a failure**, not an empty list: the web has no
  reachability banner to explain a blank book. ⚠ A service `false` is never dropped: writes carry
  `changed`, and `relinkIdentity`'s `false` (unknown key) is `error_contacts_unknown_key`.
- **Avatars** are a `data:` URL capped at `PersonAvatar.MAX_IMAGE_BYTES`, with an `avatar` state
  (`none | present | tooLarge | unknownFormat`) so a withheld photo never reads as no photo.

### 4.2 As built — Wave 1 run B1: the Contacts app, F5, deploy wiring (2026-10-23)

`web/contacts` is the second own-origin app (dev port 5175, `contacts.<domain>` in the image), hosted
at `/contacts/*` by the generic `AppEmbed` that replaced `DriveEmbed`'s body. Decisions later runs
build on:

- **F5 is per-origin allowlists** (`web/apex/src/lib/gatewayPermissions.ts`, enforced in
  `serveProxyGateway`). Drive's list is the exact `ProxyMethod` table of `web/drive/src/lib/proxy.ts`
  (a test reads that table and fails on drift); Contacts gets `contact.*` + the session methods +
  `sync.health.observe`. ⚠⚠ A new call an app makes must be added to its list, or it is refused with
  `not permitted …`. ⚠ No bare `'*'` exists, and one origin configured for two apps throws.
- **Producer-side stream failure.** A `ProxySubscriptionHandler` gets a third argument, `fail`, which
  ends the subscriber's stream with the terminal error event. The `Contacts.observe*` collectors
  report every end except the subscriber's own cancel — a throw, or a flow that completes.
- **`ContactItem.verified` is Q2b's `verified`** (`connectedVerified`), no longer "any key VERIFIED".
- **Live data:** the app subscribes once at the root (`LiveProvider`) and writes pushes into the query
  cache; a detail page is derived from the book, as natively. The invite reads stay snapshot + refetch.
- **i18n:** `web/drive` has none, so Contacts follows the apex (i18next + ICU, en/de/fr, a key-parity
  test).
- **Not in the app:** the device address book (no browser API, §8.1); redeem-on-open, which stays on
  the apex's `/i#…` route; the own identity code (no client-readable op, run A).

---

### 4.W As built — Wave 3 W-MAP: the shared web map renderer (2026-10-28)

There is no app in this slice. It is the renderer that Maps, Timeline routes and Fit routes draw
through. The open questions are in `maps-region-delivery.md` §Web scope (the question PR). Only the
undisputed parts are built:

- **SDK.** `mapRegion` graduated from F7 sync-only to `MapRegionPlugin.kt`. It maps exactly two ops,
  `catalog` and `streamRegionRange`, and both are served by the native `commonMain` classes:
  `FetchingRegionCatalogSource` (the code under `HttpRegionCatalogSource`) and `StreamingRegionSource`.
  The JS Ed25519 backend verifies the catalog against the **same pinned key** Control uses, which now
  lives in `:map-region-service-proxy` (`TilesManifestKey.kt`) so it is spelled once.
  - The facade is `MapRegion`: `catalog` / `regionAt` / `regionsIn` / `streamRange`. Lookups reuse
    `RegionResolver` / `GeoBounds` (`:map-region-app` gained a `js` target for that).
  - ⚠⚠ Every tile-host byte goes through **one** seam, `WebTileTransport`. The shipped implementation
    refuses with `error_map_transport_unconfigured`. The maintainer's W.1 answer plugs in at
    `MapRegionPlugin.useTransport`.
  - ⚠ No background work: no `initialize`, no download, no prefetch, no catalog refresh. The cache is
    memory-only, 16 MiB.
- **Bridge.** `mapRegion.*` gateway handlers. `streamRange`'s `ArrayBuffer` is **transferred**: a
  handler can now return `transferring(result, [buffer])` (`web/sdk/src/bridge.ts`), and the gateway
  posts with that transfer list. No app allowlist names `mapRegion.*` yet.
- **`@tenshu/map-kit`** (`web/map-kit`).
  - `<TenshuMap>` runs over MapLibre GL JS 5.24.0 and pmtiles 4.3.0, through our own `tenshu-pmtiles`
    protocol handler. ⚠ It is not `pmtiles`' `Protocol`, which `fetch()`es any key it has not seen.
  - ⚠ The style is `:basemap-core`'s, **generated at build time** into
    `src/style/basemap-styles.generated.json` (`./gradlew :basemap-core:generateWebBasemapStyles`)
    and pinned by `WebStyleBundleParityTest`. `BASEMAP_MAX_ZOOM` rides in it.
  - Glyphs come from the app's own origin, via the `tenshuMapGlyphs()` Vite plugin over the native
    glyph set.
  - MapLibre is lazy (`lazySplit.test.ts`).
  - The map never renders a blank box: no WebGL, no transport, a failed tile and no region each say so.
  - A dev harness renders the Firenze demo over a fixture: `pnpm -F @tenshu/map-kit harness`.
  - How a map app uses it, and its CSP: [`how-to/new-web-app.md`](../how-to/new-web-app.md) §2b.

### 4.M As built — Wave 3, Maps run B: the Maps app (2026-10-28)

`web/maps` on port 5179, scoped by `maps-app.md` §Web scope → Decided. Detail there (§As built — run B).

- **Allowlist.** `place.list`, `place.list.observe`, `place.get` and `place.rename`, plus
  `timeline.placeVisitSummary` for the visit stats, `timeline.places` (the anchor's tie-break), and the two `<TenshuMap>` calls, `mapRegion.regionAt` and
  `mapRegion.streamRange`. There is no `regionsIn` or `catalog`: the web manages no offline regions.
- ⚠⚠ **The first full app CSP.** The new `hosted_map_app` Caddy snippet is hosted_app's body with the
  §2b map policy in place of the frame-ancestors-only one, so a site carries one policy, not two. A
  headless-Chromium pass served the map-kit harness behind the exact header, and the Firenze tiles drew
  with **zero** violations (blob: workers, own-origin glyphs). Timeline routes and Fit switch with a
  one-word change to their site block.
- **Bundle.** MapLibre stays in its own lazily loaded chunk (`MapView-*.js`, ~1.08 MB). The list route's
  static graph never reaches it (`sdkBoundary.test.ts`).

### 4.H As built — Wave 4, Home run A: the read-only SDK boundary + bridge (2026-10-28)

PRs 1+2 of the §4 cut; `web/home` is run B. The spec is the iOS route-1 Home
(`home-and-mia-apps.md` §8.1): `Home` (`tenshu-sdk-js` `Home.kt` + `HomeBook.kt`), `@tenshu/sdk/home`,
and the `home.*` gateway module.

- ⚠⚠ **Read-only is enforced below the UI, by absence.** `routine`, `homeDevice` and `hubManagement`
  graduated from F7 sync-only entries to `HomePlugin.kt`. Each adds the relation maintainer (all three
  native `init*Service()` are only that) and **maps no operation**. So every Home write fails in a tab
  as "no implementation": device command, rename, hub command, routine save / delete / toggle /
  run-now. So does the executor `EvaluateDueRoutines`. `HomeWebContractTest` dispatches all eight
  through the real client. The facade's exported names are pinned to the eight reads, and the gateway
  module to `HOME_METHODS`.
- **Reads.** Devices carry their state row as stored (sentinels → `null`; `state: null` until it
  syncs). The hub tile is the freshest `HubStatus` with disk figures, else `hub: null`. Its `stale`
  is judged at read time, and it carries `staleAfterMs`: a tab runs no ticker. Automations carry the
  engine's `RoutineProgram`, not the editor's model. ⚠ So unlike native, a device-event / geofence /
  voice routine is listed, and an unparseable one is listed with `program: null` + `programStatus`.
- **Activity feed: keyset-paged** (`a1.` cursor over `(claimedAt desc, firingId asc)`, Fit's `w1.`
  rules). ⚠ `RoutineRun` retention is declared but never enforced (`tech-debt.md`), so the feed is
  unbounded. Only outcome states are rows, as native. `home.activity.changes` says when to refetch the
  head. ⚠ An exactly-full last page carries a cursor; the page after it is `[]` + `null`, the normal end.
- ⚠⚠ **No run read touches the whole relation**. Every `RoutineRun` read is a sorted,
  windowed chain shaped to fuse as top-N (`core-ordered-window-fusion.md` F1/F4): `filter` (never
  `searchAll`, which the gate does not recognise), allowlisted predicates on stored fields, `take`.
  A page filters outcomes in the chain (one read, no client-side skip loop); `lastRunAtMs` is one
  `take(1)` per routine; the observe forms watch the newest `RUN_SIGNAL_WINDOW` (32) runs, not the
  relation. `HomeWebContractTest.everyRunRead_fusesAsTopN` dispatch-counts it. ⚠ What the head
  cannot see: a synced run sorting below it, alone — the next change inside it heals that.
- Observe `onError` is typed `{ kind, message }` (`error_home_service_unavailable` /
  `error_home_stream_ended`); the gateway flattens it to `"kind: message"` for the bridge's `fail`.
- ⚠ **A failed read is a failure**: a service the runtime cannot reach is
  `error_home_service_unavailable`, never `[]`.
- **No app may call `home.*` yet.** `gatewayPermissions.test.ts` and `gateway.test.ts` pin that, and
  run B replaces the pin with its exact allowlist. The wire names run B's `ProxyMethod` table copies
  are `HOME_METHODS` in `gateway/home.ts`.

### 4.HB As built — Wave 4, Home run B: the Home app (2026-10-28)

`web/home` on port 5181, PR 3 of the cut. Read-only (§8 items 2 and 9); the spec is the iOS
read-only Home (`home-and-mia-apps.md` §8.1). Three tabs: Home (devices + hub tile), Automations,
Activity. iOS's fourth, Settings, is a placeholder on every platform and is not built.

- **Allowlist.** Exactly `HOME_METHODS` by name, plus the session methods; no `home.*` prefix.
  `gatewayPermissions.test.ts` now pins that Home, and only Home, reaches `home.*`.
- ⚠⚠ **No control, anywhere.** No switch, slider, menu, pairing or edit; the enabled flag is a label.
  The Home tab says where control happens (Home on Android or a computer). `App.test.tsx` walks every
  tab and fails on any button that is not navigation or the activity filter.
- ⚠⚠ **Automations mirror native, not run A.** Run A lists every routine; the list shows only what
  native's editor model maps (`programStatus: 'parsed'` and a `timeOfDay` / `sun` trigger,
  `AutomationProgramMapper.toAutomation`), with iOS's "trigger · first action" line. Native shows no
  `lastRunAtMs`, suspension state or conditions in the list, so neither does the web.
- **Devices** are grouped under room headings (run A's `rooms`, then *Unassigned*). ⚠ Native shows a
  flat list with the room in each row's subtitle. State is as stored: `state: null` is *No reading
  yet*, never native's defaults.
- ⚠ **Hub tile staleness is re-checked on the app's clock**: `stale || now - freshAsOfMs >
  staleAfterMs`, with a one-shot timer at the boundary and a minute tick for the "as of" line. Render
  only: it reads and writes nothing (`HubTile.test.tsx`, fake timers).
- **Activity** is Fit's History pattern: virtualised, deduped by `firingId`, the exact returned
  cursor, a head re-read on `home.activity.changes`, and `fetchMore` gated on `!isFetching` so it
  cannot cancel that re-read. ⚠ The change version counts per subscription (each starts at 1), so it is
  never compared across subscriptions: a resubscribe over a cached feed re-reads the head instead. A
  failed head re-read is retried as the head, not as a next page; ⚠ a bad cursor's retry is *Start from the top* (the head), never the same cursor; and Retry is disabled while any read runs, so it cannot cancel one. An empty page or a `null` cursor is the end. Day headers and native's
  All / Devices / Automations / Voice filter; Devices and Voice say nothing is recorded and read nothing.
- **Errors by kind.** A stream's terminal error arrives as `"kind: message"`; the notice reads the
  kind back (`streamErrorKind`) and tells a bridge failure apart.
- ⚠ **CSP: plain `hosted_app`.** No shared Dialog and no map, so no `style-src-elem 'unsafe-inline'`.
  `csp/caddyfile.test.ts` pins Home off the map policy. Deploy wiring copies Fit's. `HOME_SITE`,
  `HOME_DOMAIN`, `HOME_ORIGIN` and `HOME_FRAME_ANCESTORS` never collide with the shell's `$HOME`,
  because every use carries a suffix. No DNS record yet.
- **Threat model.** One paragraph under A4: the Home origin is an F5 reader of device, routine and hub
  rows, holds no custody, and can reach no write.

### 4.C As built — Wave 4, the Control gaps (2026-10-28)

The apex's last two `PlaceholderPage`s are gone (and the component with them).

- **Settings index** (`control/settings/settingsEntries.ts`). Native `SettingsScreen`'s order, but only
  the entries the apex has a page for: *Devices* (→ `/control/data/devices`; ⚠ labelled *Devices*, not
  native's *Sync & devices* — the web page is the device list, with no sync dashboard), *Background ingest*, *Account recovery*, then the privacy policy.
  ⚠ Omitted, never shown dead: background sync, offline maps, the desktop menu-bar section (native-only);
  Models (item 8); unlock, forget-device, errors, advanced → security and open-source licences (no web
  page yet — `OMITTED_ON_WEB` is the record). The test checks every row's route against `RuntimeApp`.
- **Data → Files** (`control/data/files/`). ⚠ **Native has no such screen** — its Data screen is type
  cards plus Shares, and `control-ia.md` §9 leaves the Files scope open. Built is §5.1's inventory:
  file and folder counts, total size, newest change, and a breakdown by kind (grouped from extensions in
  TS). Not built: growth over time and source apps (no read carries either). Read-only, like native —
  "Delete all" is withheld from `File` there (`DataScreen.kt`), and files are deleted in Drive.
- ⚠⚠ **One new read, result-shaped**: `FileSystem.inventory()` → `{ ok, inventory }` or
  `{ ok: false, error: { kind: 'read' } }`. The listing reads beside it fold a failure into `[]`, which
  for an inventory would read "0 files". A full `traverse()` per page open over its OWN minimal select
  (`INVENTORY_FIELDS`/`INVENTORY_RELATIONS`: path, size, content-modified + created) — not the listing's
  `withRowFields()`, whose `Folder.FileCount` is a dynamic resolve per folder; never `File.Preview`
  (`drive-web-parity.md` §1.2i). Apex-only — not in any app's gateway allowlist.
- ⚠⚠ **The read cannot be cancelled** (a `GlobalScope.promise`) and runs on the tab's one JS thread, so
  `readInventoryOnce` keeps one module-level in-flight read that a second open joins (navigate back,
  StrictMode); the slot clears on settle, so the next open reads fresh. An unmounted page drops the answer.
- ⚠ **The size is logical bytes of what this browser has synced**: a content-addressed copy counts
  again, and files shared into your realm from others are not counted. The copy says so; it is not
  the plan's storage use (Services).
- **i18n.** The two screens are their own namespaces (`settings`, `dataFiles`) so they could ship in
  en/de/fr with key parity (`i18nParity.test.ts`) while `control` stays en/de.

---

### 4.A As built — Wave 4, Mia run A: the SDK boundary + bridge (2026-10-28)

The native query layer runs in the tab, as iOS route 1 runs it in Mia's process. Detail is in
`mia-query-layer.md` §20.

- **SDK.** `MiaPlugin` maps native `askMia` only, with `MiaActionOffer.WITHHELD`. ⚠ `confirmMiaAction`
  is not mapped, and neither is any inference op. So there is no model (§8 item 8) and no write.
  `Mia` = `ask` / `history` / `observeHistory` / `turnsPage` / `grants` / `observeGrants`.
- ⚠⚠ **Verdicts are native's three.** A refusal (a revoked grant, a withheld action) is `noMatch`,
  the same verdict as "not understood"; only the explanation tells them apart. It is never an
  empty `answered`.
- ⚠ **History is in memory** (MQ-D8), one conversation per session. `Session.clear` drops it.
- **Bridge.** `mia.*` handlers. The utterance is bounded at 500 characters, trimmed, at the gateway
  and in the SDK. ⚠⚠ **No app's allowlist names `mia.*`** (`proxyGateway.mia.test.ts` pins it).
- ⚖️ **Threat model.** No amendment in run A: no app origin can reach `mia.*`, and the runtime that
  answers already holds the realm (the apex is Control). ⚠⚠ **Run B owes one** (paid: A39's web
  amendment, §4.B). An origin
  allowlisted for `mia.ask` can read, through questions, every source the grants allow: file names,
  places, calendar, workouts, devices. That is a new reader under F5, so it needs an A39 note in
  the PR that adds the allowlist.

### 4.B As built — Wave 4, Mia run B: the Mia app (2026-10-28)

`web/mia` on port **5182** (5181 is left for Home run B, which ran in parallel). PR 3 of the §4 cut,
over run A's `Mia` surface plus one SDK addition. Decided by the maintainer the same day: §8 items 8,
10 and 11.

- **Screens** (native `ChatScreen` / `HistoryScreen` / Settings' `GrantsEntry`, as three tabs):
  - **Chat.** An input bounded to the gateway's 500 characters (counted down from 400). An answer
    renders by verdict exactly as `TenshuConversationRepository.bodyOf` does: `answered` with hits →
    native's citations cell (the explanation as caption, a glyph per type — event, workout, place,
    moment, device, else file); `answered` with none → "<explanation> — nothing matched."; `noMatch`
    and `unavailable` → the explanation alone. ⚠⚠ A `noMatch` never renders a result list, empty or
    not (`mia-query-layer.md` §14.1; `ChatView.test.tsx`). A refusal before asking (every
    `error_mia_*`, `error_mia_session_cleared` included) renders by kind and keeps the text. ⚠ A
    citation does not open its row: there is no cross-app open on the web yet.
  - **History.** The conversation list (at most one, `MiaBook.kt` rule 2) and its read-only thread,
    keyset-paged. ⚠ The screen says in words that the history lives in this browser's memory, is
    never saved and is cleared on sign-out or lock (MQ-D8).
  - **What Mia can read.** One switch per source plus Calendar's write line (disabled while read is
    off, as natively). ⚠⚠ Never optimistic: a switch draws the set the runtime read back after the
    write; a failed write keeps the old state and shows the error by kind under its row.
- **SDK.** `Mia.setGranted` / `setWritable` — native Settings' `MiaGrantsRepository` writes, the same
  `PreferenceService.set` on the same `mia.grant.*` rows — answering `{ ok, grants }` read back after
  the write. An unknown source is `error_mia_unknown_source`; a write switch on a source with no write
  tier is `error_mia_no_write_grant` (native returns silently; a bridge call cannot). `MiaWebContractTest`
  turns `FITNESS` off through the web call, asserts the next question is `noMatch` naming workouts
  and that exactly one write op was dispatched (`WriteSetting`, counted over every settings, event,
  device and routine write mapped in a tab; watched red with a second grant write added), then turns
  it on again and the question answers.
- **Bridge.** `mia.grants.setGranted` / `mia.grants.setWritable`; the gateway refuses a source outside
  `MIA_SOURCES` and any non-boolean `granted` before the runtime.
- ⚠⚠ **Allowlist and threat model.** Mia's list is the session methods plus the eight `mia.*` names,
  exactly; every other app's is pinned to none (`proxyGateway.mia.test.ts`), and Mia's `ProxyMethod`
  table to no other namespace (`sdkMirror.test.ts`). A39 gained its web amendment and T-MQ-01b a web
  note: the origin reads, through questions, whatever the grants allow — and, holding the switches,
  the grants bound the app, not a compromised origin. No wider than native Mia on any platform.
- **CSP:** `hosted_app` — Mia draws no map and opens no `Dialog`, so no inline `<style>`.
- **Deploy wiring** copied from Maps/Fit: `{$MIA_SITE}` site block + the apex `frame-ancestors`,
  `{$MIA_DOMAIN}` on the dev front Caddy, compose (`MIA_SITE` defaulting to
  `http://mia.disabled.invalid`, `MIA_FRAME_ANCESTORS`) and the dev overlay. No DNS yet.

## 5. Risks that cut across apps

- **Schema lockstep.** The browser runtime is a sync peer. Registering a service the store has never
  seen is safe (stamped at current, touches no rows), but every later **service version bump owes an
  apex redeploy**, or the web becomes the stale client that silently half-syncs. F2's script makes
  that cheap; the release checklist must say it.
- **No push channel.** The bridge is snapshot + refetch (`drive-web-parity.md` §1.2g). Fine for
  Contacts/Calendar; Photos and Fit need keyset paging designed in TS, not a port of androidx paging.
- **The tab must be in front.** Web sync is a page timer (§1.2b there). Reminders and anything
  "background" are tab-open only; say so in the UI.
- **Browser storage can be evicted** (§1.2d there). A web device is a cache with an identity, not a
  durable replica — already true for Drive, more visible with nine apps.
- **Device verification.** No gate covers cross-device sync, eviction or background throttling.
  Each wave ends with a browser pass against the dev lab (§6).

---

## 6. Who does what — cloud vs local

| Step | Cloud session | Local (maintainer's Mac) |
|------|------|------|
| Write code, tests, i18n | ✅ | — |
| `scripts/run-web-gate.sh` (sdk + contracts + web + build lanes) | ✅ after F1 · else web + build only | sdk lane if F1 fell back |
| `./gradlew compileTestKotlinJs --continue` | ✅ after F1 | fallback |
| PR with the gate output pasted (case counts, not "green") | ✅ | — |
| Review | — | ✅ `/code-review` on the PR |
| Re-run the gate on the PR **merged with master** | — | ✅ (a green gate certifies the tree it ran on) |
| Merge | — | ✅ |
| Browser pass against the dev lab | partly: HTTPS to apex/relay/commerce works from cloud | ✅ two devices + a browser |
| Image build + ship to dev lab, then prod | — | ✅ `deploy/build-apex-image.sh` |

Launching: RemoteTrigger `create` against environment `env_012RTVXXbvCyd9BvqiQKhaqe` (⚠ not
`Agent isolation:"remote"`, which has silently run locally twice). Results come back only as a PR;
poll `gh pr list --head <branch>`.
⚠⚠ Every run's `session_context.sources` lists **two** repos: `B3nedikt/Tenshu` **and `Kotlin/karma`**.
Without the second, `:kotlinNpmInstall` gets 403 and no Kotlin/JS test or SDK bundle can be built
(`docs/how-to/claude-web-environment.md` §5). With both, the full web gate ran green in the cloud on
2026-10-23: sdk 95 cases, apex 896, Drive 243, build lane, and `compileTestKotlinJs` for every module.

Parallelism: at most **three** concurrent cloud sessions in Wave 2, one per app, after F4 has made
the collisions one-liners. Wave 1 is deliberately serial: it creates the app-kit everybody else
builds on.

---

## 7. The cloud brief (template)

Every brief carries, verbatim:

1. **The slice**: app, PR number in its 3-PR cut (§4), the MVP row from §2, the native
   `*Repository` to mirror.
2. **Read first**: `AGENTS.md`, this doc, `drive-web-app.md` §3 (the recipe), ADR 0020,
   `drive-web-parity.md` §1.2g/§1.2i, `docs/testing.md` §9.
3. **Hard rules**: React + TS, no Compose Web (HR 5) · `web/<app>` value-imports only
   `@tenshu/sdk/bridge`; everything else type-only (a value import drags the Kotlin runtime in — a
   >1 MB chunk warning is the tell) · no third-party network fetch from an app · no schema reshape
   without the HR 9 bump + migration · `pnpm -F <app> build`, never `tsc --noEmit` (project
   references hide app files).
4. **Gate, then report**: run `scripts/run-web-gate.sh` and paste its summary lines with case counts
   into the PR body. If Gradle cannot resolve, **say so in the first line of the PR body** and list
   which lanes did not run — a missing lane must never read as a pass.
5. **Branch**: `web/<app>-<n>`, PR against `master`, body ends with the report. No deploy, no
   secrets, no edits outside the slice's files plus the one-line shared entries.
6. **Scope cuts are not yours to make.** If a feature is listed in §8.1, or you find another one
   whose browser support is doubtful, open a question PR first (§8.1) and build only the
   undisputed parts of the slice.
7. **Start from the scaffold** (the app PR, 3 in §4): `scripts/new-web-app.sh <id> <port>` creates
   `web/<id>` on `@tenshu/app-kit` and its apex registry file, and prints what is left
   ([`how-to/new-web-app.md`](../how-to/new-web-app.md)). Use the app-kit's pieces rather than
   copying Drive's or Contacts': `createApexBridge`, `RuntimeLeaseBanner`, `useLive`,
   `defineQueryKeys` and `initAppI18n`. Deploy wiring (Caddy, compose, DNS) is the maintainer's,
   not the slice's.

---

## 8. Decisions

**Decided by the maintainer, 2026-10-23:**

1. **Order:** Contacts first (Wave 1, app-kit extracted from it), then the waves as in §4.
2. **Not read-only: build writes too, as far as the browser makes sense.** Where a feature is
   doubtful on web, the cloud session **does not decide it**: it opens a *question PR* (§8.1) and
   the maintainer answers locally before the feature PR is written. This overrides the "read-only
   first" column in §2 for Timeline, Photos and Fit. **Home stays read-only**: routines execute in
   the proxy, and a browser tab is a poor executor.
3. **Hosting:** one image, one Caddy site block per app subdomain. Bundle weight is handled by F6,
   not by the hosting choice.
4. **Rollout:** each wave goes to the dev lab (`tenshu.xyz`), then to production.

5. **Contacts redeems invite links on the apex, not in the app** (2026-10-23). A link opens
   `tenshu.app/i#…` as before; `web/contacts` has no paste field, and `contact.redeem` stays out of
   its gateway allowlist.
6. **Wave 1 status (2026-10-23):** Contacts runs A and B1 merged and live on the
   dev lab at `https://contacts.tenshu.xyz` (apex image `971e8ccd`), with exact per-origin
   allowlists (F5). Verified live in Chrome: both apps' bridge handshakes and responses with no
   refusals, framing allowed for the apex and refused elsewhere. Not yet exercised: a signed-in
   browser with real contacts and a cross-device handshake. Next: B2 (the shared app-kit).

7. **Wave 3 status (2026-10-28):** W-MAP, tile transport W.1, Timeline routes, Maps and Fit
   merged, plus the apex `connect-src` fix for the tile host: the runtime runs in the apex page, so a host it fetches must be in the APEX policy, not
   only in the bucket's CORS. Live on the dev lab (apex `bd261737`, `maps.` / `fit.tenshu.xyz`).
   The three map apps share one Caddy policy (`hosted_map_app`, with `style-src-elem 'unsafe-inline'`
   for the shared Dialog). Not yet exercised: a map drawn from a signed-in account's data.
8. **Mia on web runs the deterministic planner only** (2026-10-28, was open question 6). Native Mia
   has no model either (`mia-query-layer.md`), so the web matches it exactly. ORT Web is the IN
   track's web runtime (`inference-service.md` IN-D2, "deliberately last"); when an intent model
   ships (MQ-5a), it enters through the same seam on every platform, web included. Nothing in
   Wave 4 waits for it.
9. **Wave 4 = Home (read-only, D2 above) · Mia (text, item 8) · the Control gaps** (2026-10-28). The
   gaps were Wave 2's and never ran: the apex Settings index and Data → Files were still
   `PlaceholderPage`. Built in §4.C.

10. **Mia's grants are switchable on the web, as on native** (2026-10-28, Mia run B). Run A made
   them a view; the web now writes them with native Settings' own op (`PreferenceService.set` on
   `mia.grant.*`, realm-scoped), so a switch flipped in a browser changes Mia on every device.
   Threat model A39 (web amendment) and T-MQ-01b say what that does and does not bound.
11. **One `noMatch` for refusal and not-understood, as native** (2026-10-28, Mia run B). No `REFUSED`
   kind is added: the app shows the planner's explanation sentence, which already says why (which
   grant is off, which action this device withholds). ⚠⚠ A `noMatch` never renders as an empty
   "nothing found" list (`mia-query-layer.md` §14.1). Item 8 (planner only, no model) holds for
   run B unchanged.

**Still open:**

5. **ICS subscriptions on web**: drop them, or fetch through a relay-side passthrough. The
   passthrough means the relay sees the subscription URL, which needs a threat-model entry.

### 8.1 Question PRs — how feature cuts reach the maintainer

A question PR is a docs-only PR that adds a `§Web scope` section to the app's own proposal (e.g.
`fit-app.md`). It lists each doubtful feature with:

- **what the browser can do**, with the API named (e.g. Geolocation `watchPosition`, Screen Wake
  Lock, Page Visibility, Notification);
- **what it cannot do** (e.g. a background tab, a closed tab, OS sensors);
- **a recommendation**: build, build reduced, or drop.

The maintainer answers locally in a review or on the PR. The answers are merged into the doc,
and the feature PRs cite that section. Known candidates, so briefs can say "open a question PR for
these":

| App | Feature | Browser reality (to confirm in the question PR) |
|-----|---------|------|
| Fit | Record a workout **without GPS** (timer, sets, manual HR) | Feasible in a foreground tab; Wake Lock keeps the screen on; the tab must stay open |
| Fit | Record a **GPS** workout | `watchPosition` stops or throttles when the tab is backgrounded or the screen locks, so routes would have gaps. Likely drop |
| Fit | Import | GPX/TCX via `:fit-shared-file` (has jsMain); binary `.FIT` is JVM-only; Takeout zip needs a JS unzip |
| Timeline | Location tracking | No background location on the web at all. At most a one-shot "I'm here now" check-in while the tab is open |
| Timeline | Place rename / merge / role, trip accept/dismiss | Pure data writes, so likely feasible |
| Calendar | Reminders | Notification API fires only while a tab is open |
| Photos | Upload from the browser | Feasible (Drive does it); thumbnails are minted on upload (`drive-web-parity.md`) |
| Photos | Faces, labels, semantic search | No inference backend in a browser. Read stored results only? |
| Contacts | Device address book | No API outside Chrome-Android's Contact Picker. Use `.vcf` file import |
