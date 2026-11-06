# Track A — Control → Apps UI (+ Rei parts)

**Goal:** Ship the **Apps** section of Control: a list of installed apps and a per-app detail
view showing semantic permissions, install info, and an uninstall/deregister action. Back it
with real synced `AppRegistration` data and the permission model from `tenshu-rei.md`.

**Owning surfaces**
- Kotlin: `control-shared/.../ui/screens/apps/**`, a new `AppRegistry` data client, and the
  `AppRegistration` / `AppGrant` schemas + `RegisterApp` / `RemoveApp` operations.
- Web: `web/apex/src/control/apps/**`, routes `/control/apps` + `/control/apps/:appId`.
- Docs (same PR): `tenshu-rei.md` §2.2 + §3.3, `control-app-registration.md` status update,
  `docs/sync/threat-model.md` T-C amendment.

**Primary references**
- `docs/proposals/control-app-registration.md` — three-layer model (§2), registration flow
  (§3), `AppRegistration` schema (§4), removal (§5).
- `docs/proposals/control-ia.md` §4 — Apps list (§4.1) + App detail (§4.2).
- `docs/proposals/web-control-plane.md` §2 (routes), §3 (chrome), §4.2 (Apps rendering).
- `docs/proposals/tenshu-rei.md` §2.2 (semantic permissions / capability classes / Q43
  traits) + §3.3 (permission widget in Control).
- Existing code to mirror: `PublisherClient`/`MockPublisherClient` (client pattern),
  `ServicesViewModel` (StateFlow VM), `settings/backgroundsync/*` (reactive VM + tests).

---

## Current state (from exploration)

- **Nothing of Apps is built.** `Destination` has placeholder `Apps`/`Data` objects but no
  screens; `web-control-plane` §4.2 is "Phase B, not started".
- `AppRegistration`, `AppGrant`, `RegisterApp`, `RemoveApp` **do not exist** in code.
- The permission-class traits (`AmbientCapability` / `ScopedCapability` / `GestureExempt`)
  are decided in `tenshu-rei.md` (Q43, 2026-05-29) but **not implemented**.
- `:rei-service*` modules **not started**.
- Existing Rei-named code (`ReiKeyScreen`/`ReiKeyViewModel`/`PublisherClient.validateReiKey`)
  is the *publisher onboarding* "Rei Key" link flow — **unrelated to the Apps surface**; do
  not touch it.

> **Scope decision for this track:** do **not** build the whole `:rei-service` + verifier +
> manifest signing. Apps UI only needs (a) installed-app data (`AppRegistration`) and (b) a
> permission descriptor per app. Define the permission traits and a *manifest-backed*
> `AppManifest` read surface, but keep verification/ledger out (it's a later Rei phase). The
> data client interface is shaped so a real Rei-backed impl can drop in later.

---

## Phase A0 — Schemas & operations (Kotlin, no UI) — ✅ LANDED 2026-05-31

> **Done.** Schemas (`AppRegistration`, `AppGrant`, `AppService`, `AppManifest` descriptor),
> the three operations, the relation maintainer (`initAppService`, wired into Control's
> `PlatformProxyModule`), and registration in `coreCommonModule` + the core
> `implementationFactory` all landed in `:core` / `:core-common` (package
> `org.tenshu.core.app` / `org.tenshu.core.common.app`). Module-home decided `:core` (A0.1).
> Threat-model amendment (A17 + T-C-14) + `tenshu-rei.md` §2.2/§3.1/§3.3 +
> `control-app-registration.md` status all updated in-PR. Verified
> `:core-common:desktopTest` (`AppServiceLocalTest`, 6/6) + `:control-desktop:compileKotlinJvm`
> + `:control-shared:desktopTest` (regression). Android `PlatformProxyModule` edit unverified
> (no SDK). **Not built (deferred):** the `BundledAppManifestSource` impl with Drive's
> first-party manifest (belongs with A1's data client), and the per-binding handshake trigger
> that calls `RegisterApp` (gated on the IPC handshake landing). Next: **A1** (`AppRegistry`).

Land the synced data the UI reads. Mirror the `DeviceSetting` composite-key trick from
`preference-service` and the `Syncable` declaration pattern in `docs/agent.md`.

### A0.1 `AppRegistration` (Syncable composite)

Decision to make first (Open Q in `control-app-registration.md`): module home. **Default:
`:core`** (it is platform data, not Rei-service-specific). If you later create `:rei-service`,
it can move; keep the package `org.tenshu.core.app`.

```kotlin
// :core/.../app/AppRegistration.kt  (schema in :core, DTO/impl in :core-common)
// Fields (per control-app-registration.md §4):
//   compositeKey: TString   // "$deviceId/$appId"  -> stringDataId provider
//   appId:        TString
//   deviceId:     TString
//   createdAt:    UnixInstant
//   state:        TString (enum: "Active" | "Removed")  // tombstone, not delete
//   verified:     TBoolean  // false for dev-mode apps -> badge
// Traits: Syncable(Eager) + DataIdProvider(keyed by compositeKey).
```

### A0.2 `AppGrant` (Syncable composite, realm-wide)

One row per `(appId, serviceId, operationId)` carrying the tri-state grant.

```kotlin
// :core/.../app/AppGrant.kt
//   compositeKey: TString  // "$appId/$serviceId/$operationId"
//   appId, serviceId, operationId: TString
//   grant: TString (enum: "Granted" | "Denied" | "AskEachTime")
//   lastUsedAt: UnixInstant (0L = never)
// Traits: Syncable + DataIdProvider(compositeKey).
```

### A0.3 Operations

- `RegisterApp(appId, deviceId, verified)` — **proxy-only, idempotent**. No-op if an `Active`
  row exists; refuse (or re-prompt) if a `Removed` row exists. Stamps `createdAt`, `state =
  Active`. Wire it into the proxy handshake (`docs/protocol/desktop-ipc.md` step after the
  code-signing check). First-run is **silent** (decided 2026-05-31) — no connect modal.
- `RemoveApp(appId, deviceId | allDevices)` — user-initiated. Sets `state = Removed`
  (tombstone); purges the app's `AppGrant` rows. Per-device by default; `allDevices` variant
  tombstones every `(appId, *)` row.
- `SetAppGrant(appId, serviceId, operationId, grant)` — user-initiated, writes `AppGrant`.

All three are non-suspend at the interface (House Rule #3); `RegisterApp`/`RemoveApp` are
`IoOperation`s.

### A0.4 Permission descriptor read surface

Apps detail needs the *full declared permission set* of an app (not just the grants that
exist). For v1, source this from a **bundled `AppManifest`** for first-party apps (Drive),
read via a `ReiSource`-shaped interface so the future ledger impl is a drop-in:

```kotlin
// :core/.../app/AppManifest.kt
data class AppPermission(
    val serviceId: String,
    val operationId: String,
    val capabilityClass: CapabilityClass,   // Ambient | Picker | Scoped
    val gesture: GestureRequirement,        // None | RequiresUserGesture | RequiresExplicitConfirmation
    val humanLabel: String,                 // "Read your files"
)
data class AppManifest(
    val appId: String, val name: String, val iconName: String,
    val version: String, val shortDescription: String,
    val permissions: List<AppPermission>,
)
interface AppManifestSource { fun manifest(appId: String): AppManifest? }
// v1 impl: BundledAppManifestSource (first-party manifests, e.g. Drive) — compiled in.
```

### A0.5 Tests (gate for A0)
- `:core-common:jvmTest` (or `:rei-service-common`) — `AppRegistration`/`AppGrant` round-trip,
  DataId derives from compositeKey, Syncable trait present.
- `:*-proxy:jvmTest` — `RegisterApp` idempotency (second call is no-op), `Removed` blocks
  re-register, `RemoveApp` purges grants, `SetAppGrant` round-trips. Use the
  `startAppIntegrationKoin` SDK-chain pattern from `AppIntegrationTestUtils`.

---

## Phase A1 — `AppRegistry` data client (control-shared) — ✅ LANDED 2026-05-31

> **Done.** `AppRegistry` interface + `InstalledApp` / `AppDetail` / `PermissionRow` /
> `GrantState` / `UninstallScope` (`control-shared/.../data/AppRegistry.kt`).
> `TenshuAppRegistry(client, manifestSource)` reads `AppService.appRegistrations` /
> `.appGrants` via `.flow()`, joins the bundled manifest, routes writes through
> `SetAppGrant` / `RemoveApp`; resolves the current device for `ThisDevice` uninstall via
> `syncService.getSyncStatus().deviceId`. `BundledAppManifestSource` carries Drive's
> first-party `AppManifest` (6 real file-service permissions; `DRIVE_APP_ID = "app.tenshu.drive"`
> — contract: the handshake's `RegisterApp` must use the same id). `MockAppRegistry`
> (Drive + a dev-mode app; `apps=emptyList()` for empty state). Typed `TenshuClient.appService`
> accessor added in `:core-common`. Wired in `ProxyModule` (`single<AppManifestSource>` +
> `single<AppRegistry> { TenshuAppRegistry(...) }` — real impl is the default now that A0 has
> landed). Verified `:control-shared:desktopTest` (`AppRegistryIntegrationTest` 3/3 over the
> real SDK chain + `MockAppRegistryTest` 5/5) + `:control-desktop:compileKotlinJvm`. Next: **A2**
> (Apps list + detail Compose screens + VMs). Spec below is the original plan.

Mirror `PublisherClient` (interface + mock + Koin single). This is what the VMs talk to.

```kotlin
// control-shared/.../data/AppRegistry.kt
interface AppRegistry {
    /** Installed apps, grouped by appId across devices, newest install first. */
    fun installedApps(): Flow<List<InstalledApp>>          // joins AppRegistration + AppManifest
    fun appDetail(appId: String): Flow<AppDetail?>          // manifest perms × AppGrant state
    suspend fun setGrant(appId: String, serviceId: String, operationId: String, grant: GrantState)
    suspend fun revokeAllPermissions(appId: String)         // reset all grants -> AskEachTime
    suspend fun uninstall(appId: String, scope: UninstallScope) // ThisDevice | AllDevices
}
data class InstalledApp(
    val appId: String, val name: String, val iconName: String, val version: String,
    val shortDescription: String, val installedAt: Long,
    val verified: Boolean, val deviceCount: Int,
)
data class AppDetail(
    val app: InstalledApp,
    val permissions: List<PermissionRow>,   // human label + scope + grant tri-state + lastUsedAt
    val services: List<String>,             // distinct serviceIds -> cross-link to /control/services/:id
)
```

- Real impl `TenshuAppRegistry(tenshuClient, appManifestSource)` reads `AppRegistration` via
  the SDK list/paging path, joins with `AppGrant` and the manifest.
- `MockAppRegistry` returns Drive + one synthetic dev-mode app for screenshot/test/empty-state
  coverage. Register both behind a flag in `ProxyModule` (mock by default until A0 lands).

---

## Phase A2 — Control Apps UI (Compose, control-shared) — ✅ LANDED 2026-05-31

> **Done.** `Destination.AppDetail(appId)` data-class route (getter-form `label`/`icon` so
> kotlinx-serialization only serializes `appId`). `AppsScreen`/`AppsScreenContent` (cards via
> `AppListItem`, `UnverifiedBadge` on dev builds, `AppEmptyState`, tap → `AppDetail`);
> `AppsViewModel(appRegistry)` StateFlow. `AppDetailScreen`/`AppDetailScreenContent` (header +
> install date, per-permission `GrantTriState` Granted/Denied/Ask, services `AssistChip`s →
> Services tab, Revoke-all, Uninstall confirm `AppDialog` with this-device/all-devices scope +
> OS hint); `AppDetailViewModel(appId, appRegistry)` with optimistic grant updates. Shared
> `AppsComponents.kt` (`UnverifiedBadge`, `GrantTriState`). Wired `viewModel { AppsViewModel(get()) }`
> + `viewModel { (appId) -> AppDetailViewModel(appId, get()) }` in `ViewModelsModule`,
> `entry<Destination.Apps>`/`entry<Destination.AppDetail>` in `App.kt` (+ `HomeScreenTest`
> Koin module updated for the new VM signatures + `MockAppRegistry`). Verified
> `:control-shared:desktopTest` (AppsScreenContentTest 4/4 + AppDetailScreenContentTest 5/5 +
> full suite regression) + `:control-desktop:compileKotlinJvm`. Android nav/DI inherits
> commonMain — **unverified** on-device (no SDK). Next: **A3** (web `/control/apps`). Spec below.

Follow the "add a Control section" cheat-sheet (`settings/backgroundsync` is the template).

1. **Destinations** (append to `Destination.kt`):
   `Apps` already exists as a top-level tab object — give it the real screen. Add
   `@Serializable data class AppDetail(val appId: String) : Destination()` (note: detail
   carries an arg, so it's a `data class`, not `data object`).
2. **`AppsScreen` / `AppsScreenContent`** (`screens/apps/`): grid/list of `InstalledApp`
   cards — icon, name, install date, version, short description; dev-mode apps get a
   "Unverified" badge (`verified = false`). Empty state when only the management surface
   exists. Apex/Control itself is **not** listed (IA §4.1). Tapping a card →
   `backStack.add(Destination.AppDetail(appId))`.
3. **`AppDetailScreen` / `AppDetailScreenContent`** (`screens/apps/`): per IA §4.2 —
   - **Permissions** list: each row = human label, scope (if `Scoped`), grant tri-state
     control (Granted / Denied / Ask each time), last-used timestamp.
   - **Services consumed**: chips cross-linking to `/control/services/:id` (native: push the
     Services destination).
   - **Install date** + **version/source**.
   - Actions: **Revoke all permissions** (→ `revokeAllPermissions`), **Uninstall**
     (default *this device*; offer *Remove from all devices*; hint "to fully remove, uninstall
     from your OS"). Confirm destructive actions (reuse the `DisconnectConfirm` pattern).
   - **Deferred (do not build):** Rei publication chain / source, per-app data summary, per-app
     activity log, billing state.
4. **VMs:** `AppsViewModel(appRegistry)` exposes `installedApps()`; `AppDetailViewModel`
   takes the `appId` arg + `appRegistry`, exposes `appDetail(appId)` and the action methods
   (optimistic grant updates).
5. **Wire-up:** add `viewModel { AppsViewModel(get()) }` and
   `viewModel { (appId: String) -> AppDetailViewModel(appId, get()) }` to
   `ViewModelsModule.kt`; add `entry<Destination.Apps>` / `entry<Destination.AppDetail>` in
   `App.kt`; `Apps` already in the top tab list.

### A2 tests (gate)
- `:control-shared:desktopTest` — `AppsScreenContentTest` (renders cards, empty state, badge,
  click emits navigation callback), `AppDetailScreenContentTest` (tri-state control toggles
  call back, uninstall confirm path).
- `:control-desktop:compileKotlinJvm`.
- Android nav/DI edits: mark **unverified** (no SDK).

---

## Phase A3 — Web Apps UI (web/apex) — ✅ LANDED 2026-05-31

> **Done (upgraded the Phase-B static placeholder to interactive).** The `/control/apps` +
> `/control/apps/:appId` routes already existed but read a hard-coded `manifest.ts`
> with read-only permission badges and *disabled* actions. A3 added the mockable data seam +
> interactivity, mirroring native + the `publisherClient`/`PublisherContext` pattern:
> `appRegistry.ts` (`AppRegistry` interface + observable `MockAppRegistry`, immutable updates
> so `useSyncExternalStore` snapshots stay stable, seeded from `manifest.ts`),
> `AppRegistryContext.tsx` (`AppRegistryProvider` + `useInstalledApps`/`useAppDetail` hooks,
> default singleton + test-injectable), `AppRegistryProvider` mounted in `App.tsx`.
> `AppsListPage` reads `useInstalledApps()` + empty state; `AppDetailPage` reads
> `useAppDetail()` with an **interactive tri-state** `GrantControl` (aria-labelled buttons →
> `setGrant`), working **Revoke all** (→ `revokeAll`) and **Uninstall** (a confirm dialog with
> this-device/all-devices scope + OS hint → `uninstall` → navigate to list). Services chips
> already cross-link to `/control/services/:id`; apex itself stays unlisted. Tests rewritten
> (`AppsListPage.test` 3, `AppDetailPage.test` 6) for the interactive behaviour via a fresh
> `MockAppRegistry` per render. Verified `pnpm -F apex build` (tsc -b + vite build) + full
> `pnpm -F apex test` (34 files / 163 green) — **needs the worktree-local SDK bundle built
> first** (`./gradlew :tenshu-sdk-js:jsBrowserDistribution`). **Deferred:** the real KMP→JS
> `Apps.installed()` surface (swap the production binding in `AppRegistryContext`); last-used
> from a real audit log; per-subdomain manifest fetch (web Phase 8). **Track A complete** through A3.

Per `web-control-plane.md` §2 + §4.2. Mount under the existing `<ControlShell />`.

- Routes: `/control/apps` (list) and `/control/apps/:appId` (detail). 
- Use the SDK to read `AppRegistration` (add an `Apps.installed()` surface to `@tenshu/sdk`
  via the schema-walker pattern, mirroring `Notifications.observe`/`Files.list`). Permission
  descriptors from the bundled per-subdomain manifest; last-used from apex's audit log.
- Tri-state permission control; Apex not listed; Services chips link to
  `/control/services/:serviceId`.
- Tests: component tests next to the pages (mirror `ReiKeyPage.test.tsx`).

> Web can proceed against a mocked SDK surface in parallel with A0/A1; swap to the real
> `Apps.installed()` once the schema lands.

---

## Phase A4 — Handshake-triggered `RegisterApp` (the missing writer) — ✅ BUILT 2026-06-02

> **Done.** `AppIdentityResolver` (`fun interface`, `:core` `org.tenshu.core.app`) +
> `BundledAppIdentityResolver` (`:control-shared`, allowlist `app.tenshu.drive.android` /
> `drive-desktop-client` → `DRIVE_APP_ID`). `ClientRegistry` gained a generic
> `onSessionEstablished(clientId, context)` hook fired once per newly-minted session (not on a
> cache hit). `appRegistrationTrigger(...)` (`core-common` `app/proxy`) is the wired callback:
> resolve `clientId`→`appId`, skip if unknown or no realm (`deviceIdProvider()` null), else run
> the proxy-only idempotent `RegisterApp` impl **synchronously** (best-effort, `runCatching` —
> never throws into the transport), `verified = false`. Wired in `ProxyModule`
> (`single<AppIdentityResolver>` + `onSessionEstablished` with a lazy
> `deviceIdProvider = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } }` so
> SyncManager is only touched for an allowlisted client and only when a realm exists). Verified
> `:core-common:desktopTest` (`AppRegistrationTriggerTest` 4/4 — registers for a mapped client
> with a realm, no-ops for unknown client / no realm, idempotent re-bind; `ClientRegistryTest`
> new hook test + full suite green) + `:control-shared:desktopTest` (full suite green) +
> `:control-shared:compileKotlinDesktop` + `:control-desktop:compileKotlinJvm`. Threat-model
> T-C-14 amended to the honest allowlist-only posture (Medium-on-desktop residual until the
> verifier lands). **Two notes:** (1) registration runs synchronously rather than launched —
> it is a cheap idempotent local write that must not outlive the binder/socket-thread call;
> (2) Android nav/DI inherits commonMain — the on-device check (Drive shows up in Apps) is the
> open manual step per [[feedback_no_worktree_for_android_smoketest]]. Spec below is the plan.

> **Why this exists.** A0–A3 shipped the schema, ops, relation maintainer, manifest, data
> client, and Apps UI — but the *writer* was deferred (A0 "Not built": *"the per-binding
> handshake trigger that calls `RegisterApp`"*). `RegisterApp` is invoked **nowhere** in
> production (only in `AppServiceLocalTest`), so no `AppRegistration` row is ever created and
> **Drive never appears in the Apps screen on any platform** (reported on Android 2026-06-02).
> This is that trigger. **Identity-gate decision (2026-06-02): allowlist-only** — full
> code-signing verification stays deferred to a Rei phase (see A4.3).

### Findings that shape the design

- **Single hook point.** Android `ProxyService.call` (`control/.../server/ProxyService.kt:145`)
  and desktop `UnixSocketServer` (`core-common/.../transport/UnixSocketServer.kt:56`) both
  funnel into `ProxyGateway.call(clientId, …)` → `ClientRegistry.getExecutionContext(clientId,
  sessionIdentity)`. The **new-session branch** of `getExecutionContext`
  (`ClientRegistry.kt:94-124`, after the `clients[clientId]` cache miss) is the once-per-
  connection moment. Trigger registration there — do **not** edit both transports.
- **`clientId` ≠ `appId` — a mapping is mandatory.** The registry/manifest key is
  `DRIVE_APP_ID = "app.tenshu.drive"`, but the `clientId` reaching the proxy is the Android
  `applicationId` `app.tenshu.drive.android` or the desktop literal `"drive-desktop-client"`.
  Registering the raw `clientId` makes the manifest join in `TenshuAppRegistry` miss. A4 needs
  a `clientId → appId` resolver — the same seam the future Rei verifier replaces.
- **`deviceId` (proxy-side)** = `syncManager.currentDeviceId` (`sync-service-proxy`). Null
  before a realm exists.
- **`registerApp` is a suspend `IoOperation`** → launch on the session scope, never inline in
  the non-suspend `getExecutionContext`. Idempotent, so per-new-session firing is safe.
- **Realm precondition.** Drive can connect *before* onboarding/realm creation. The trigger
  must no-op when `currentDeviceId == null` and rely on a later reconnect to register.

### A4.1 — `clientId → appId` resolver (the allowlist seam)

```kotlin
// :core/.../app/AppIdentityResolver.kt   (interface, next to AppManifestSource)
interface AppIdentityResolver {
    /** Canonical appId for a transport-level clientId, or null for unknown clients. */
    fun resolveAppId(clientId: String): String?
}
// :control-shared/.../data/BundledAppIdentityResolver.kt  (v1 impl, static allowlist)
//   "app.tenshu.drive.android" -> DRIVE_APP_ID
//   "drive-desktop-client"     -> DRIVE_APP_ID
```

A `null` return means *don't register* — the allowlist is the gate (see A4.3). Mirror
`AppManifestSource`'s home split exactly (interface in `org.tenshu.core.app`, bundled impl in
`control-shared`, bound `single<AppIdentityResolver>` in `ProxyModule`).

### A4.2 — registration trigger

In the `ClientRegistry` new-session branch, after the session is created:
`resolveAppId(clientId)`; if non-null **and** `deviceIdProvider()` is non-null, launch
`registerApp(appId, deviceId, verified = false)` on the new `sessionScope`. Keep `core-common`
free of sync-service/app-op deps by injecting two seams into `ClientRegistry` (constructor or
`ProxyGateway`):

```kotlin
deviceIdProvider: () -> String?,                 // wire to { syncManager.currentDeviceId }
registerApp: suspend (appId: String, deviceId: String) -> Unit,  // wire to appService.registerApp(...)
```

Wire the real lambdas in each app's `ProxyModule` (Android + desktop). No-realm-yet ⇒ no-op;
registers on the next reconnect once a device id exists. **Known limitation (v1):** a client
that stays connected across realm creation won't register until it reconnects — acceptable for
now; a realm-create hook is a later refinement if it bites.

### A4.3 — identity gate + threat-model honesty (decided: allowlist-only)

There is **no code-signing check today** (the Rei verifier is deferred), so the existing
T-C-14 wording — *"a forged local client cannot self-register because `RegisterApp` is
proxy-mediated behind the code-signing check"* — is **not yet accurate**. A4 amends
`docs/sync/threat-model.md` to the true posture:

- Registration is gated by (a) the static `BundledAppIdentityResolver` allowlist and (b) on
  Android, the kernel-trustworthy peer-UID→package resolution (`getCallingPackageName`).
- `verified = false` **always** — the badge stays "unverified" until the verifier lands.
- Full code-signing verification + desktop peer-cred (`SO_PEERCRED`/`LOCAL_PEERPID`,
  `desktop-ipc.md` §3) + `verified = true` are **explicitly deferred to a Rei phase**
  (`tenshu-rei.md` §5.F). The `verified` param stays plumbed end-to-end so that flip is a
  one-line change.

### A4.4 — tests (gate)

- `:core-common:*Test` — new-session trigger fires `registerApp` exactly once for a mapped
  `clientId`; never for an unknown `clientId`; never when `deviceIdProvider()` returns null.
- `:*-proxy:jvmTest` (or `:control-shared:desktopTest` over the SDK chain) — connecting a
  mapped client produces an `Active` `AppRegistration` visible via
  `appService.appRegistrations`; reconnect is idempotent (one row, not two).
- Android: nav/DI inherits commonMain — mark **unverified** (no SDK); the logic is commonMain
  so desktop covers it. On-device confirmation (Drive shows up in Apps) is the final manual
  check, per [[feedback_no_worktree_for_android_smoketest]].

### A4 definition of done
- `AppIdentityResolver` + `BundledAppIdentityResolver` (Drive entries) landed; bound in DI.
- Connecting Drive to the proxy creates an `Active` `AppRegistration` (`verified=false`); the
  Apps screen lists Drive on desktop; idempotent across reconnects.
- `threat-model.md` T-C-14 amended to the allowlist-only posture (same PR, House Rule #2).
- Android wiring added, marked unverified; on-device check pending a testing day.

---

## `tenshu-rei.md` parts this track lands

Land these doc edits **in the same PR** as the code that implements them (House Rule #2).

1. **§2.2 (Semantic permissions derived from the IDL)** — mark the capability-class +
   gesture-requirement model as **implemented for the read/grant path**: record that
   `CapabilityClass {Ambient, Picker, Scoped}` and `GestureRequirement {None,
   RequiresUserGesture, RequiresExplicitConfirmation}` now exist as the descriptor consumed by
   Control's permission widget, with **safe defaults** = `Picker` + `RequiresUserGesture` when
   a trait is absent. Note that the *source-time scanning* (KSP/compiler plugin) that
   populates these from real IDL is still pending — v1 reads them from the bundled
   `AppManifest`.
2. **§3.3 (Permission widget in Control)** — replace the sketch with the shipped surface: the
   tri-state grant control (Granted / Denied / Ask each time), per-permission human label +
   scope + last-used, the "Revoke all permissions" reset, and the cross-link to Services.
   State that grants are stored as `AppGrant` rows (realm-wide, `Syncable`).
3. Add a short **"Apps UI status"** line under §3.1 noting `AppRegistration`/`AppGrant`/
   `RegisterApp`/`RemoveApp` landed in `:core` (or `:rei-service`) ahead of the full verifier,
   and that `AppManifestSource` is the `ReiSource`-shaped seam the ledger impl will replace.

Also: update `control-app-registration.md` status (schema + ops + UI landed) and add the
threat-model **T-C amendment** — a forged local client cannot self-register because
`RegisterApp` is proxy-mediated behind the code-signing check.

---

## Definition of done
- `AppRegistration`/`AppGrant` schemas + `RegisterApp`/`RemoveApp`/`SetAppGrant` ops, with
  proxy-side tests green (`:*-proxy:jvmTest`).
- Apps list + detail render real synced data on desktop; permission tri-state writes grants;
  uninstall tombstones + purges grants.
- Web `/control/apps` + `/control/apps/:appId` render against the SDK (or mock pending schema).
- `tenshu-rei.md` §2.2/§3.3 updated, `control-app-registration.md` status updated,
  threat-model T-C amendment added — all in-PR.
- Android wiring added but marked unverified.
