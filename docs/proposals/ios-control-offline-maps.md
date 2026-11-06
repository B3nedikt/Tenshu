# Offline maps management on Control-on-iOS

**Status:** v0.1 — **design, no code (2026-08-12).** Compose Control ships an *Offline maps*
management surface; Control-on-iOS does not. This doc is mostly **not** about porting a screen —
the port is the small part. It is about the fact that **the premise the screen rests on
(`maps-region-delivery.md` D5: "Control owns the shared region store") does not hold on iOS**, and
what to do about that.

Companions: [`maps-region-delivery.md`](maps-region-delivery.md) (§7 Ph4 as-built — the surface
being ported; D5/D6/D10), [`ios-control-handover.md`](ios-control-handover.md) (the C0–C6 roadmap
this adds to, which never mentions regions), [`ios-route1-replica-scope.md`](ios-route1-replica-scope.md)
(§2 — every route-1 app registers every service), [`control-services-surface.md`](control-services-surface.md)
(§§66–74 — why this is a *data-availability* surface, not an account one),
[`how-to/ios-signing.md`](../how-to/ios-signing.md) §6 (the App-Group gate that decides everything
below).

**Trigger (2026-08-12):** *"iOS Control is missing our entire features to manage maps, right?"*
Yes — and the interesting part is why porting it would currently ship a lie.

---

## 1. The gap, precisely

**Compose Control ships it** — `control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/offlinemaps/`,
landed as `maps-region-delivery.md` §7 **Ph4** (2026-07-13):

| File | Role |
|---|---|
| `OfflineMapsScreen.kt` | Settings → **Offline maps**; catalog list, sizes, download/delete, search |
| `OfflineMapsViewModel.kt` | snapshot + re-fetch after mutation; per-row busy/progress state |
| `OfflineMapsRepository.kt` | the seam + `ClientOfflineMapsRepository` over Control's in-process client |
| `OfflineMapsGrouping.kt` | the pure grouping/filter rule |
| 4 × `desktopTest` | VM, grouping, render, staleness |

Reached via `Destination.OfflineMaps` + a Settings row. It joins the **signed catalog** with the
**local install index**, offers whole-region download with byte progress, delete, and per-region
sha256 **staleness** ("a newer build of this region exists" — keyed on per-region sha256, not the
catalog-wide `buildDate`, so a rebuild doesn't flag every resident region for a 200 MB re-download).

**Control-on-iOS does not.** `control-ios/Sources/` is 16 SwiftUI views (Apps, Data, EPC, Home,
Locked, Onboarding, Pairing, QR, Recovery, Services, Settings, SyncDashboard, …) and none of them is
this one. `SettingsView.swift` has Account / Services / Devices / Apps / Recovery / Security / Lock /
About — no entry point. Nothing under `control-ios/` mentions regions at all.

**But the data plane is already there**, which is what makes this look like a pure UI gap:
`control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt:812` binds
`mapRegionServiceProxyImplementationFactory` with the catalog source, store, downloader, streaming
source and progress holder; `IosRegionBundleStager` landed 2026-08-01; `IosFileDatabase` made
downloaded bytes durable 2026-08-03. Control-on-iOS **can** download and hold regions today. It just
has no screen — and, as §2 shows, no maps to render them with.

⚠ *Adjacent, not this doc:* the same comparison shows **`notifications` and `shares`** are also
absent from `control-ios/Sources/`. Worth their own slices; not folded in here.

---

## 2. ⚠⚠ The premise does not hold on iOS

`maps-region-delivery.md` **D5** makes Control the canonical owner of region management, and on
Android that is simply true: **Control is the proxy**. `MapRegionService` is explicitly *not* a
synced-entity service — its own KDoc says *"nothing here syncs across devices, the service declares
no data relations… the proxy owns the canonical region store"* — so on Android there is exactly one
store on the device, Control holds it, and Maps/Timeline/Fit reach it over Binder. One store, one
owner, one management surface. The screen is correct by construction.

**On iOS route 1, every first-party app is its own proxy.** `ios-route1-replica-scope.md` §2 ground
fact 1: `FirstPartyLocalRuntime` assembles the *production* Control module set in every route-1 app
— *"registering file, health, timeline, place, contact, ingest, notifications, **map-region**,
logging, home-device, routine, hub-management, preference, sync, storage and identity."* So Maps,
Timeline, Fit **and Control** each run their own `MapRegionService` over their own store.

Whether those stores are one directory or four depends entirely on the **App Group**:

| Regime | `sharedReplicaDirectory()` resolves to | Region stores | Where it applies |
|---|---|---|---|
| **App Group granted** | the `group.app.tenshu.shared` container | **one directory, N proxies over it** | simulator (ad-hoc signing honours it), and real devices on a **paid** team |
| **No App Group** | app-local Documents | **N independent stores** | every real device **until 2026-10-02** — `com.apple.security.application-groups` is unavailable on a free personal team. Device builds now declare the group and a device build without it throws (`ios-background-scheduling.md` §4.1.1), so this row is the simulator test host only |

So **on hardware, right now, a region downloaded in Maps does nothing for Timeline**, and a region
downloaded by *Control* does nothing for anybody — because:

> **⚠⚠ Control-on-iOS renders no maps at all.** `control-ios/project.yml` depends only on
> `TenshuSdk.xcframework`; it does not link `:ios-basemap`. There is no `TenshuMapView` in Control.

Port the screen naively and you ship a download manager for a store **no map reads from**, showing
sizes and progress for hundreds of megabytes that render nothing. That is worse than the current
absence: absence is honest.

⚠ One layer down, the split is real even in the entitled regime: the **staged** bundle — the file
MapLibre actually opens — is app-local by design. `IosRegionBundleStager` uses
`NSApplicationSupportDirectory` (not `sharedReplicaDirectory`), and its KDoc explains why
(Application Support, never Caches; excluded from iCloud backup; the atomic-commit dance). That is
correct — a per-app render copy drained from a canonical store — but it means "shared" only ever
described the canonical bytes, never what the renderer holds.

---

## 3. Three ways to close it

**Option A — port the screen as-is.** Control manages Control's own store. Rejected: §2. It manages
a store nothing renders from, and on a free-team device it isn't even the store the apps use.

**Option B — port it, gated on the App Group.** Control manages the shared container's store, and
the surface is hidden (or shows an explanatory empty state) when no App-Group container resolves.
Honest, and it degrades where the sharing does. Two costs: it is invisible on exactly the devices we
can build for today (so it cannot be exercised on hardware until the paid tier), and it raises §4.

**Option C — the surface belongs where the maps are, until the store is genuinely shared.** On iOS
the canonical management surface is **Maps' region picker** (already shipped, P5, 2026-08-01), and
Control grows the screen when — and only when — one store genuinely backs every app.

### 3.1 ⭐ The project already reached this conclusion once, on Android

**The 2026-08-12 change `feat(ui-map): file the streamed tile cache as a cache`** moved the streamed
range cache from `filesDir` to `cacheDir`, and its rationale is this doc's argument arrived at
independently from the other direction:

> *"the cache is in **each app's sandbox**, so **Control structurally cannot manage it** — an
> in-Control aggregate would need every app to report its usage and honour a deferred clear,
> eventually-consistent machinery whose 'cleared' would routinely be a lie until the app next
> launched."*

That is the same shape as §2, one plane down: **when the thing lives per-app, a central management
surface can only lie about it.** On Android the fix was to let the OS's own per-app storage UI own
it. iOS has the same per-app storage UI, and — without the App Group — the *canonical region store*
is per-app too, which is precisely the condition that change says Control cannot manage.

⭐ **And it shipped the answer to "what does Control show instead?"** — the Offline maps screen now
**states its own boundary**: every row is a region the user chose to download and Control keeps,
which is *not* all the map data on the device. Without that, a short list reads as the whole story.
**CM-1 is that same move, one step further**: on iOS the honest boundary statement is that Control
keeps *nothing yet*, and management lives in the app that renders the map.

**Recommendation: C now, B when the paid team lands**, and say so in the docs rather than leaving
D5 reading as if it were platform-neutral. C is not a workaround; on a device with four independent
stores, per-app management is the *accurate* model, and a Control surface would be the lie. What C
owes is the honest empty state in Control (§5 CM-1) so a user looking for the setting learns where
it lives instead of finding nothing.

---

## 4. The question Option B must answer first

**N proxies, one directory.** In the App-Group regime, Maps, Timeline, Fit and Control each hold a
`DefaultRegionStore` over a `FileDatabase` rooted at the *same* container path, with no cross-process
coordination. The install index is *"the store's own JSON"* (`ClientOfflineMapsRepository.regions()`),
so:

- Two apps downloading the same region concurrently both stage, both write, and the last index write
  wins — plausibly losing the other's entry while its bytes sit on disk unreferenced.
- Control deleting a region while Maps is mid-render deletes bytes out from under an open fd. (⚠ On
  the *staged* copy this is survivable — it is app-local and re-drainable — but the canonical bytes
  are what the streamed path reads.)
- `retention-quota-model.md` tier 3 eviction is unbuilt on every platform, so nothing arbitrates
  space between four writers either.
- ⚠ The **streamed range cache** is a separate, genuinely per-app thing (`cacheDir`-filed on
  Android) and stays per-app on iOS regardless of how this resolves — so even a fully shared region
  store leaves map bytes Control cannot see. A Control surface must keep saying so (§3.1).

None of this is exotic — it is the ordinary multi-writer problem — but it is **unanalysed**, and
shipping a *management* surface is exactly what turns a latent race into a user-triggered one. A
single-writer rule (only Control mutates; apps read) is the obvious shape and would need the apps'
`MapRegionService` bindings to become read-only in the presence of a Control-owned store.

---

## 5. The port, if and when

Mechanically small, and the shape is the `PairingWizardViewModel` precedent exactly:

| Phase | Work | Size |
|---|---|---|
| **CM-1** | Control-on-iOS Settings gains an **Offline maps** row that explains where region management lives on iOS (Maps) and why — the honest empty state, no store access | **XS** |
| **CM-2** | Move `OfflineMapsViewModel` + `OfflineMapsRepository` + `OfflineMapsGrouping` from `:control-shared` → `:control-shared-logic` (the Compose-free, iOS-capable layer). ⚠ Needs a new `:map-region-service-client` dependency there — it has none today. Tests move with them (they are `desktopTest` and pure) | **S** |
| **CM-3** | `ControlSurface.offlineMapsViewModel()` + `OfflineMapsView.swift` — the list, per-row progress, delete, staleness badge, catalog-unavailable state. ⚠ `downloadProgress` is a `Flow<Long>`; check it crosses SKIE as a stream the Swift side can consume, or add a callback bridge (the `pairingStepKey` precedent for anything sealed) | **S** |
| **CM-4** | The App-Group gate (§3 option B) + the single-writer decision (§4) + amend `maps-region-delivery.md` D5 to say it is Android-shaped | **M** |

Everything `:control-shared-logic` needs is already true of it: iOS targets (`iosArm64` +
`iosSimulatorArm64`), `lifecycle.viewmodel` on the api surface, and it is `export`ed from
`:tenshu-sdk-ios`. The view models there are already driven from Swift through `ControlSurface`
factories (`dataViewModel()`, `syncDashboardViewModel()`, `pairingWizardViewModel()`, …).

**CM-1 is the only phase worth doing before the App-Group question resolves**, and it is the one
that actually helps a user today.

---

## 6. Decisions proposed

- **D1 — D5 is Android-shaped, and the docs should say so.** "Control owns the shared region store"
  is a statement about a device with *one* proxy. It must be restated per-platform rather than left
  to be discovered by whoever ports the screen next.
- **D2 — No Control offline-maps surface on iOS until one store genuinely backs every app.** A
  management UI whose object is not the thing being rendered is a defect, not a partial feature.
- **D3 — CM-1's empty state ships regardless.** A user who goes looking for the setting should be
  told where it is, not shown nothing. This is the cheap, always-correct half.
- **D4 — Maps' region picker is the canonical iOS surface in the interim**, and its copy should stop
  implying a device-wide store (the "un-caveat Maps' region copy" work already queued as parity
  **P4-2** should say per-app, not device-wide, until B lands).
- **D5 — When Control does take the surface, it takes the writes too** (§4 single-writer), rather
  than N equal writers with a management screen on one of them.
- **D6 — No schema, wire or threat-model change.** `MapRegionService` is operation-only and syncs
  nothing; regions are public signed extracts, and the catalog is Ed25519-verified against a pinned
  key. Nothing here touches the sync plane or House Rule #1. (⚠ If D5's single-writer rule is
  implemented by making app-side mutation ops fail, *that* is an IPC-surface change and needs its
  own note.)

---

## 7. Open questions

- **Q1.** Does the paid team actually land? Everything in Option B is gated on it
  (`ios-signing.md` §6, and §192's note that a personal team fits only three of the eight apps on
  one device at a time — itself a reason multi-app region sharing cannot be exercised today).
- **Q2.** Should `IosRegionBundleStager` move to the App-Group container too, making even the staged
  render copy shared? Probably not — per-app staging is a feature (independent lifetimes, no
  cross-app fd hazard) — but it should be a decision, not an accident of which directory helper was
  reached for.
- **Q3.** Does Control-on-iOS want `:ios-basemap` eventually? If Control ever renders a map (a
  coverage preview on the region row, say), the linkage question from
  `ios-fit-timeline-maps-parity.md` §3.3 returns.
- **Q4.** How does this interact with `retention-quota-model.md` tier 3? Four independent stores on
  a phone with no eviction is a worse posture than one, and the management surface is where a user
  would expect to see the total.
