# Maps — Places (slim): pre-design decision record

**Status:** v0.2 (2026-07-08). **No code, pre-design.** This is the durable record that
seeds the `claude.ai/design` handoff for the Tenshu **Maps** app (slim form). It locks the
list-vs-map question, cross-references the basemap architecture decisions that live in
[`milestones.md`](../milestones.md), phases the build (§6 — Ph0–Ph8 app track over the mock +
MapLibre isolated to Ph7, Ph9–Ph11 gated service track), and carries the questions design
still has to answer.
Sibling to [`timeline-app.md`](timeline-app.md) and [`fit-app.md`](fit-app.md); companion to
[`timeline-services.md`](timeline-services.md) (the `:place-service` substrate).

**Owner's note:** decision- and scope-oriented. When the design handoff lands under
`docs/design/maps/` (local-only, matching the `timeline/` and `tenshu-icons/` convention),
*that* becomes the source of truth for layout, vocabulary, and data shape; this proposal is
the durable record of what was decided going in, so the app can be rebuilt if the handoff
drifts or is lost.

---

## 1. Goal & scope

Ship **Maps in *slim* form first**: the **Places** tab only, from the long-term Maps vision
([`milestones.md`](../milestones.md) → *Maps — Places (slim)*). Places covers *Your places*
(home, work, other important locations), *Saved places* (bookmarked), *Recommended places*
(system-proposed from history / contacts / curated lists), and *Contribute* (POI submissions
+ map-mistake reports).

**In scope:** the Places surface over the existing `:place-service` (graduated to full
strength), list-first with a per-place map detail (see §2).

**Out of scope (the deferred *Maps full* milestone):** the Explore and Navigate tabs — tile
*routing*, turn-by-turn navigation, live traffic. Note the 2026 basemap reassessment
([`milestones.md`](../milestones.md) → *Out of Scope → Maps full*): tile **rendering** is no
longer the hard part, so a read-only basemap is available to slim; what stays deferred is
routing, Navigate UX, and POI/search *depth*.

## 2. Decision D1 — list-first Places, single-pin detail; routes belong to Timeline (locked 2026-07-06)

**Resolved.** *Maps — Places (slim)* is **list-first**, not map-first.

- **Places list → place detail.** The Places tab is a list of place cards (grouped, e.g. by
  category / recency / section). Tapping a card opens a **place detail** view centred on a
  **single pin**, plus that place's history (visit count / last visit), photos, and
  saved-state. There is **no route** on a place detail — a place is a *point*, not a journey.
- **Routes over a basemap are a *trip*, and a trip is a Timeline concern**, not a Places one.
  The polyline-over-map view lives in **Timeline's trip detail**
  ([`timeline-app.md`](timeline-app.md) screen 4/5), never bolted onto a Maps place card.
  Keeping the two concepts separate is the load-bearing part of this decision.

**Rationale.** Both incumbents converged here in their privacy-era redesigns, which also
validates the Tenshu thesis: Apple's *Visited Places* (iOS 26) is a list grouped by month
with search, tapping into a per-place detail, on-device + E2E encrypted; Google retired cloud
Timeline (June 2025) and keeps trips on the device. List-first is the current state of the art
for this surface, not a compromise. (Positioning line worth reusing: Apple and Google both had
to *retrofit* on-device location history — Tenshu started there.)

**Where the basemap first appears.** The **place-detail map snippet is the first home for
MapLibre + one bundled offline region tile** — small, contained, and enough to prove the
basemap stack from the reassessment without committing to a full pannable map. Sequencing:

1. Place detail = single-pin map snippet (first MapLibre surface).
2. Full-screen "map of all places" (the Timeline mock's deferred *See map of all places*,
   [`timeline-app.md`](timeline-app.md) §10) = optional step two.
3. Route-over-basemap polyline = **Timeline trip detail** — the strongest first justification
   to wire the basemap in anger, since it is exactly the "draw a GPS track over a basemap"
   case the reassessment sized at days-to-weeks.

## 3. Basemap architecture (inherited, see milestones)

These are decided in [`milestones.md`](../milestones.md) → *Out of Scope → Maps full* and
apply to any map surface slim renders. Summary so design has the constraint in one place:

- **Organic Maps region-bundle model, not a live tile server** — download region PMTiles
  extracts once, render fully offline; infra never sees a live movement trace (House Rule #1).
  Can ride the existing content-delivery machinery.
- **MapLibre Native everywhere, incl. iOS** (Android/iOS/desktop); **MapLibre GL JS + React**
  on web (House Rule #5) — **not** Apple MapKit as renderer (third-party leakage from inside
  the privacy-flagship app; forks styling). ⚠ **Narrowed for iOS only, 2026-08-13:**
  [`ios-apple-maps-option.md`](ios-apple-maps-option.md) is **accepted**, so MapKit may render the
  three detail heroes as a **user-elected, off-by-default, revocable** alternative — never as *the*
  renderer, and never on the region picker, macOS or Android. Everything else on this line stands.
- **Apple/Google Maps only as a *Navigate* hand-off** — launch the native app with a
  destination; deferred with the Navigate tab regardless.

Stack, for reference: Planetiler → planet PMTiles → HTTP range serving → MapLibre.

## 4. Open questions for the design handoff

D1 (§2) and the basemap posture (§3) are locked. D2–D4 were **settled by the design handoff**
(`docs/design/maps/maps-agent.md`, 2026-07-07 — gitignored, so recorded here as the durable record):

- **D2 — who owns the place-detail screen? RESOLVED: Maps owns it, embeddable** (handoff §0.6).
  Maps builds the canonical place detail as a reusable, embeddable composable; Timeline
  (visit moments) and Photos (place linking) embed the *same* view. One place, one record — no
  drifting copies. Ph4 builds the detail embeddable to honour this.
- **D3 — is *Contribute* in v1 slim scope? RESOLVED: yes, as a queued stub** (handoff §0.8).
  Add-a-place / report-a-mistake save to the user's own realm and queue; nothing publishes to
  others until Tenshu Rei's moderation lands. The surface says so (no moderation yet).
- **D4 — is search in slim scope? RESOLVED: local yes, world gated** (handoff §0.7). Local
  search over your own + saved places works fully offline; world search ("find a place you
  haven't been") is gated on `:place-service` geocoding
  ([`timeline-services.md`](timeline-services.md) P4, **not built**) and shows an explicit
  "coming" state — never a broken box.

## 5. Dependencies

- `:place-service` at full strength (graduated from Photos / Timeline;
  [`timeline-services.md`](timeline-services.md) §3 — P1 scaffold shipped 2026-07-02).
- POI database — initially user-contributed with a Rei-shaped moderation flow (gates D3).
- Per-platform current-position source.
- Cross-links: Timeline (saved-from-trip places), Contacts (a place can be a contact's
  home/work).

## 6. Phases

The build follows the **UI-only-over-mock** shape proven by
[`timeline-app.md`](timeline-app.md) §7 and [`fit-app.md`](fit-app.md): four standalone
modules mirroring Drive, everything behind a repository seam, real service swapped in later.
The one thing Maps adds that Timeline did not is the **MapLibre basemap** — so it gets its
own isolated phase (Ph7), sequenced per §2, and kept off the critical path of a runnable app.

**Two tracks.** Ph0–Ph8 are the **app** track (self-contained over the mock, no new service
work). Ph9–Ph11 are the **service** track (graduate `:place-service`, then light up real data
behind the seam); they are gated on decisions and substrate that are not the app's to own, and
each ships independently once its gate clears.

**Gating.** Ph0 (scaffold) can start before the design handoff. Ph1+ need the handoff's data
shape and mock dataset (`docs/design/maps/`, local-only per §Owner's note), so they do not
begin until it lands. D2/D3/D4 (§4) gate specific phases as noted.

### App track (UI-only over the mock)

- **Ph0 — Scaffolding.** ✅ In this PR. `docs/design/maps/` was already gitignored; this
  proposal; the four modules by analogy to Timeline — `:maps-shared-logic` (Compose-free,
  android / `jvm("desktop")` / iOS targets — empty, the Ph1 model's home), `:maps-shared-ui`
  (`App()` placeholder + empty `mapsModules()` DI + Compose-resource wiring; android /
  desktop), `:maps-desktop` (`jvm` `main()` with the desktop ViewModelStore/SavedState shims,
  no import menu), `:maps-android` (`ComponentActivity` + `Application` shell);
  `settings.gradle.kts` includes; package root `app.tenshu.maps`. Reuses `:ui-core` unchanged.
  Both shells compile green (desktop + Android); the placeholder surface proves the
  shells → `:ui-core` theme → `App()` wiring end to end. **Not gated on the handoff.**
- **Ph1 — Domain model** (`:maps-shared-logic`). ✅ In this PR. Compose-free `model/`:
  `Place` (id, name, `PlaceCategory`, `PlaceSection`, label?, address, `Coord`, seed, saved,
  savedList?, visitCount, `LocalDate?` last/first visit, recentVisits, `PlacePhoto` tones,
  `Reason`/`ReasonKind`, note) + `SavedList`, ported from `maps-agent.md` §5. String-free
  `MapsFormat.relativeVisit(today, visit)` → `RelativeVisit(RelativeForm, value)` (the
  locale-independent "N days/weeks/months ago" split; words are the UI's job — ADR 0016,
  mirrors `TimelineFormat`). Shape kept **aligned with `:place-service`'s `Place`**
  ([`timeline-services.md`](timeline-services.md) §3.1) so the Ph9 swap is mechanical.
  As-built deviations: dates typed `LocalDate` (mock ISO parses at the Ph2 seam); `coord` is
  the mock's normalized `mx/my` for the schematic `MapSnippet` (real store keys off SI
  lat/lng). Tests: `MapsModelTest` + `MapsFormatTest` (relative-former boundaries) — green on
  desktop + iOS.
- **Ph2 — Mock repository.** ✅ In this PR. `data/`: `PlaceRepository` (interface — the
  swappable `:place-service` seam, per handoff §7; `places()`/`savedLists()` as `Flow`,
  `place(id)` + `setSaved(id, saved)` as `suspend`), `MapsMockData` (the 12-place Lisbon
  dataset + 3 saved lists, transcribed from `mockup/maps-data.js`), and `MockPlaceRepository`
  (in-memory `StateFlow`; `setSaved` re-emits). Koin `single<PlaceRepository> {
  MockPlaceRepository() }` bound in `mapsModules()`. Tests: `MockPlaceRepositoryTest` (seed
  counts + section split + id lookup + `setSaved` re-emit) — green.
- **Ph3 — ViewModels.** ✅ In this PR. Compose-free in `ui/screens/*`: `PlacesViewModel`
  (`places` package — the four tabs `MapsTab.{Yours,Saved,ForYou,Contribute}` folded per D1,
  one VM; section split derived from the single `places()` stream; `selectTab` + `setSaved`;
  Contribute carries no mock data — a queued stub per D3) + `PlaceDetailViewModel` (`detail`
  package — sealed `Loading/Loaded/NotFound`, collected **reactively** from `places()` so the
  header bookmark toggle reflects `setSaved` live). Koin `viewModelsModule()` bound (detail
  takes the id via `parametersOf`). Tests: `MapsViewModelsTest` (seed/sections, tab select,
  `setSaved` propagation through both VMs, detail load + NotFound) — green.
- **Ph4 — Shared composables** (`:maps-shared-ui`, **no map yet**). ✅ In this PR.
  `ui/components/`: `CategoryAvatar`, `SavedToggle`, `PhotoFrame`+`PhotoStrip`, `VisitHistory`,
  `PlaceRow` (`+PlaceRowTrailing`), `TrustCue`, `MapsDetailScaffold`, `MapsIcons`
  (Lucide→material stand-in), and the **`MapSnippet` STUB** (placeholder box + "Offline ·
  MapLibre" attribution; the single-pin/`seed` contract kept so the Ph7 MapLibre swap is
  drop-in). `ui/theme/`: the by-category **accent seam** `categoryAccent(...)` (§4 — pure,
  default Amethyst no-op, derives per-category oklch marks when on) + `parseHexColor`.
  Localisation split landed: `ui/text/MapsText.kt` (`relativeVisitText` / `visitCountText`)
  over `strings.xml` (ADR 0016; `:maps-shared-logic` stays string-free). `App()` now previews
  the components over `MapsMockData`. Tokens read straight from `:ui-core` (`MaterialTheme.
  {colorScheme,typography,spacing,shapes}`), sans only (no serif — that's Timeline's, ADR
  0021). Tests: `CategoryAccentsTest` (no-op default + derived oklch) — green; Android + desktop
  compile. ⚠ The Ph5 place-detail body is built **embeddable** (D2) over `MapsDetailScaffold`.
- **Ph5 — Screens + Ph6 — Navigation.** ✅ In this PR (Ph5+Ph6 folded — inseparable for a
  runnable app, as Timeline found). `ui/screens/places/PlacesScreen` — small-caps top bar +
  `SecondaryScrollableTabRow` over the four tabs (Your places / Saved / For you / Contribute),
  list-first per tab (Your = label-chip cards + Add-a-place; Saved = per-list cards of
  `PlaceRow`s w/ bookmark toggle + New list; For you = `RecommendedCard` w/ reason chip +
  photo strip + Save/Dismiss; Contribute = action cards + the D3 queued-stub note). Wired to
  `PlacesViewModel`. `ui/screens/detail/PlaceDetailScreen` wraps the **embeddable
  `PlaceDetailContent`** (D2 — no VM dependency; Timeline & Photos call it directly): MapSnippet
  stub + on-device line + identity (owner label chip / bookmark toggle) + action pills
  (Edit·Directions·Share for yours; Save·Directions·Share otherwise) + `VisitHistory` + "First
  seen {date}" + Directions hand-off note + `PhotoStrip` + the "Trips through here → Timeline"
  cross-link (routes are Timeline's, D1). `ui/navigation/Destination` (`Places` / `PlaceDetail`)
  + `App()` = `KmpNavHost` over the back stack (`today` sourced from the mock anchor at the root
  so screens stay store-agnostic). New strings + `mediumDateText` (localised month names).
  Both shells compile; desktop click-through / on-device pass pending a human.
- **Ph6 — Navigation + `App()` + DI + shells.** ✅ Folded into Ph5 (above) — `KmpNavHost`
  back-stack + both shells calling the same `App()`; DI (`mapsModules`) landed in Ph0/Ph3.
- **Ph7 — Basemap: the single-pin snippet.** §2 step 1. **Ph7a (schematic render) ✅ + Ph7b
  (live MapLibre) ✅ — both shipped** (Ph7b unblocked by the CMP 1.11.1 upgrade).
  - **Ph7a — schematic offline basemap (shipped).** The `MapSnippet` stub body is replaced by a
    **deterministic Compose-Canvas schematic** — a jittered/skewed street grid + block fills, an
    occasional diagonal river (`seed % 3`) or park (`seed % 2`), road casings under thinner roads
    with two wider arterials, a single centre pin, an "Offline · MapLibre" attribution chip, and
    a scale-bar label — a faithful port of the handoff's `MapSnippet` (`maps-shared.jsx`). Low-
    chroma theme-aware `MapPalette` (light/dark). **No dependency, no binary asset, no CMP-pin
    risk.** The public API (`seed`, `height`) is the seam — the deterministic layout is stable
    per `seed` (unit-tested via the internal `Lcg`), the contract the live map inherits.
  - **Ph7b — live MapLibre (shipped).** `MapSnippet` is a single-pin snippet over the shared
    basemap engine. **That engine was extracted into [`:ui-map`](../../ui-map/ui-map-agent.md)**
    (`OfflineBasemap` + `BasemapChrome`) so Timeline/Photos reuse it — `MapSnippet` is now just
    `OfflineBasemap(seed) { BasemapChrome() }`. Inside `:ui-map`, `OfflineBasemap` is a platform
    **`expect`/`actual`**: **Android** renders a **live MapLibre Native
    basemap** (`org.maplibre.compose:maplibre-compose:0.13.0` → maplibre-android-sdk 13.0.2)
    over a **bundled offline PMTiles region** (a small Firenze extract, Protomaps v4 schema,
    ODbL — `ui-map/src/androidMain/assets/tenshu_basemap.pmtiles`, ~1.3 MB, clipped to the
    z12–14 zoom band the fixed-zoom snippet actually needs via `pmtiles extract`). **Desktop**
    keeps the Ph7a schematic `actual` — the lib's desktop target is ~15% complete (offline
    sources unsupported), so a live map there would be blank or would breach House Rule #1;
    live desktop MapLibre drops in behind the same `actual` once the lib's desktop offline path
    lands. The two renders share `BasemapChrome` (pin + attribution chip + scale) so they
    are pixel-interchangeable, and the Android `actual` falls back to the schematic while the
    tiles copy in / if the load fails.
    - **As-built decisions.** (1) The style is a **theme-aware, label-free** MapLibre JSON built
      at runtime from the `MapPalette` — **no `glyphs`/`sprite`/symbol layers**, so no font/icon
      fetch → the render is fully offline and keyless (House Rule #1). (2) The archive is loaded
      via **`pmtiles://file://…`** after a one-time copy from APK assets to `filesDir` —
      `pmtiles://asset://` triggers a native **SIGABRT** in sdk 13.0.2 that `onMapLoadFailed`
      can't catch. (3) Gestures + ornaments are **disabled** (a snippet is a static point, D1;
      MapLibre's own attribution/logo are replaced by the shared chip). (4) The mock dataset
      carries only normalized coords, so `seed` just **jitters the camera** within the bundled
      region; real per-place centring (SI lat/lng) + the production region-bundle *delivery*
      (rides the content-delivery machinery) arrive with the real `:place-service` (Ph9).
    - **House Rule #1 hardening.** The MapLibre AAR merges in `INTERNET` +
      `ACCESS_FINE/COARSE_LOCATION`; `maps-android`'s manifest **strips all three** with
      `tools:node="remove"`, so a fully-offline, position-free maps app *cannot* phone home —
      HR#1 enforced at the OS boundary, not just by convention. (`ACCESS_NETWORK_STATE` stays
      for MapLibre's connectivity receiver — state read only, no egress.)
    - **Threat model.** No amendment needed: the snippet introduces **no current-position**
      source (bundled tiles only). When a live position pin lands (the optional full "map of all
      places", §5), amend `docs/sync/threat-model.md` with that code.
    - **Verified on emulator:** Android install → launch → place detail renders real Firenze
      vector tiles (streets/blocks/landcover/water in the low-chroma palette), no crash, with
      `INTERNET` removed. Maintainer on-device pass (real hardware, dark theme) still pending.
- **Ph8 — Verify + close-out.** Tests over the mock repo / VMs / formatter per House Rule #7;
  desktop `run` smoke (every destination renders); on-device Android pass pending (needs a
  device). Catalogue deferred affordances.

### Service track (gated — real data behind the seam)

- **Ph9 — Real `:place-service` behind the `PlaceRepository` seam.** Graduate `:place-service`
  from the P1 scaffold to full strength (externalRefs storage, app-graph Koin wiring — the
  named-remaining P1 items in [`timeline-services.md`](timeline-services.md) §3.1) and swap
  `MockPlaceRepository` → a client repo over it. Threat-model: location becomes a
  synced sensitive asset — amend with the code (House Rule #2).
  **As-built (2026-07-13; desktop-green + Android smoke) — flipped, with an honest thin-screens
  tradeoff.** Mirrors Timeline's / Fit's projector-over-in-process-proxy exactly: a new
  `InProcessMapsPlaceProxy` (`:maps-shared-data`) hosts `:place-service` in-process, a
  `MapsPlaceSeeder` writes the mock POIs' Firenze coords + names through the real `recordPlace` op
  (**seed == import**, the `ReferenceTimelineSource` pattern), and `PlaceProjectionRepository`
  reads them back through the same `PlaceRepository` seam the mock used — **zero VM/UI change**.
  `placeRepositoryModule()` is flipped Mock→Projection (mock kept as `mockPlaceRepositoryModule()`
  for the rich demo / previews). **⚠ The doc's "No UI change" is true only of the seam *signature*,
  not the rendered *content*:** `:place-service` stores only `{placeId, latE7, lngE7, capturedAt,
  label}`, so `category` / `section` / `saved`/`savedList` / `photos` / `reason` / `note` / visit
  history have **no home** and project to honest defaults (documented on `MapsPlaceProjection`). The
  deliberate, accepted consequence: **all places land under *Yours* (Saved / For-you tabs empty),
  detail loses photos + visit history** — until `:place-service` graduates those fields (or a real
  POI/geocoding source lands, P4). What it *does* light up: the **real per-place coordinate** from
  the store, so every snippet centres on the actual place. The same "thinner than the mock" tradeoff
  Timeline/Fit made verbatim. **Threat-model:** no amendment yet — `InProcessMapsPlaceProxy` is
  standalone (no relay/sync, seeded fixtures, no real captured location), so no new exposure; the
  A35-style "synced location" amendment lands when the place store actually syncs real captured
  places via Control (a later, larger slice). Green: `MapsPlaceProjectionTest` (E7→location + honest
  empties) + `MapsPlacePipelineIntegrationTest` (seed→project→real Firenze location, idempotent) +
  Maps desktop compile; `:maps-desktop:run` boots clean; Android smoke.
- **Ph10 — Search** (*gated on D4 + P4*). Only if the design handoff puts a search box in slim
  scope. Gated on the geocoding seam ([`timeline-services.md`](timeline-services.md) P4 —
  **not built**). If slim ships without search, this phase is dropped, not deferred-in-place.
- **Ph11 — Contribute** (*gated on D3*). Either a plain "submit, no moderation yet" stub over
  `:place-service`, or cut from slim entirely (the Rei attestation/moderation shape is out of
  scope). Design makes the call before this phase opens.

**Optional, post-Ph7:** the full-screen *map of all places* (§2 step 2 / Timeline mock §10) —
a second MapLibre surface, pannable, over the same bundled region. Not on the slim critical
path. The route-over-basemap polyline (§2 step 3) is **Timeline's**, not this app's.

## 7. As-built — Maps' places run on the Control proxy

**Landed 2026-07-13**, alongside the same migration for Fit (`fit-app.md` §10) and Timeline
(`timeline-app.md` §10). Maps' *region* client already reached Control; its **place** data did not.

`InProcessMapsPlaceProxy` hosted `:place-service` in-process over an `InMemoryObjectStore` (hardcoded
in `:core-common`), so Maps' place store was RAM — invisible only because mock POIs were re-seeded at
every launch. Control already hosts `:place-service` (schema + ops + relation maintainers) over its
durable store, and Maps already had the `TenshuClient` reaching it (`mapsClientModule`, used for
`MapRegionService`).

**What changed:** `mapsClientModule` registers the place schema/client modules alongside the region
ones; `placeRepositoryModule` binds `PlaceProjectionRepository` over that client (the `PlaceRepository`
seam is untouched — zero VM/UI change); `InProcessMapsPlaceProxy` is deleted; `:place-service-proxy`
drops to a test-only dependency.

**Places are now shared with Timeline** — which is the real prize, and the design intent all along: a
place is a place, whichever app is looking at it. Timeline's ingestion turns visits into places in the
same `:place-service`, and Maps reads them.

**The mock-POI seeding is gone.** Against a throwaway in-memory store it was the only way the screens
had content; against Control's durable, *synced* store it would write fabricated places into the
user's real realm and push them to every paired device. `MapsPlaceSeeder` survives for tests and
`mockPlaceRepositoryModule()`. Consequence: with no Timeline capture/import yet, Maps' Places screen
is legitimately **empty** rather than full of demo POIs — that is the honest state, not a regression.
Threat-model **A35** carries the as-built note.

### 7.1 Control-unreachable signal + shell banner (2026-07-18)

The migration's known gap — Control unreachable ⇒ the place read folds to empty ⇒ the map is
silently blank — is closed with the shared **`:app-reachability`** recipe (fit-app.md §9.14):
`PlaceRepository.controlReachable(): StateFlow<Boolean?>` (default always-`true` for the mock),
derived in `PlaceProjectionRepository` from the place-flow fold + `.catch` + a slow one-row
probe that owns detection (dead streams hang, they don't fail); recovery re-subscribes the
stream via `readEpoch`. The shell stacks `:ui-core`'s `ErrorBanner` ("Can't reach Tenshu
Control — your places can't load…") above the nav host. Verified live on the emulator
(disable → banner; enable → same process clears).

**Region reads wired in (2026-07-18 follow-up).** The engine is now the app's **one Koin
single** (`mapsControlReachability` — place probe inside), injected into *both* repositories:
`PlaceProjectionRepository` takes it instead of constructing its own, and
`ClientRegionRepository` feeds it with a **load-bearing failure taxonomy** — `installed()` /
`deleteRegion` are proxy-local, so their failures ARE a transport signal (both ways);
`catalog()` / `downloadRegion` can fail with Control perfectly healthy (the proxy fetches from
the region host), so they mark only *success* — a tile-host outage must not flash the "Can't
reach Control" banner (the probe still owns the definitive answer, and a *thrown* dispatch is
transport-shaped either way). All region reads also gained try/catch degradation — a dead
Binder previously **threw** out of `regions()` into the caller's scope — and `deleteRegion`'s
result is no longer silently discarded. The `RegionTerminus` delegate paths stay outside the
signal (shared `:map-region-app` module, three consumers). `MapsRegionReachabilityTest` covers
the dead-proxy (marks + degrades, never throws) and healthy (shared-engine marks) directions.

**Maps now requires Control** for places (Drive's contract). The region picker's existing
degrade-to-demo-basemap behaviour when Control is unreachable is unchanged.

## Web scope

**Status:** DECIDED 2026-10-28 (see "Decided" below). Was a question PR (`web-app-suite.md` §8.1, Wave 3 Maps run A); run B cites the answers.

What every item shares: a browser tab is a **foreground-only sync peer** with no OS sensors, and per
`web-app-suite.md` §7 an app makes **no third-party fetch**. The renderer and `mapRegion` are W-MAP's
(a parallel run), so anything that needs a map surface waits for it.

### 1. Search / add a place by name

- **Native today:** D4 — *local* search over your own places is in scope and offline; *world* search
  ("find a place you have not been") is gated on forward geocoding, which does not exist. P4 /
  `place-geocoding.md` built **reverse** geocoding only (on-device tiles, then the Photon fallback),
  and every network lookup passes the one opt-in gate `PlaceGeocodingEgress` — **device-local**,
  off by default. Timeline's place-name picker (`placeSuggestions`) is the only forward lookup and
  rides the same gate.
- **Can:** filter the already-synced list in TS (`String.prototype.normalize` + `Intl.Collator` for
  accent/case folding). No API needed, no egress.
- **Cannot (by rule):** call Photon or any geocoder from the page (§7). The native opt-in is per
  device, so a browser has never been asked, and a relay-side proxy would show the relay a query or
  a coordinate (HR #1/#2, a threat-model entry).
- **Recommendation:** **build** local search (run B, TS only); **drop** world search / add-by-name on
  web — no opt-in, no gate, no "coordinates only" field (adding by coordinate is item 2's problem).
  Show D4's explicit "coming" state, never a broken box.

### 2. Add a place by dropping a pin, or from "my location"

- **Can:** a pin needs W-MAP's map (a click → `LngLat`). "My location" is one-shot Geolocation
  `getCurrentPosition` behind the permission prompt, secure origins only; on a laptop the fix is
  Wi-Fi/IP-derived (20 m to several km).
- **What adding costs:** it is not "save a coordinate". A new place is a minted `Place`, and place
  identity (the ~74 m identity grid, the snap to an existing place, the clobber guard) is capture code
  plus `PlaceReconciler` — and the tab runs neither (`PlacePlugin` KDoc; Timeline §Web scope 1
  reached the same wall). A web-minted place would be a duplicate a phone later folds. And "saved"
  has nowhere to live yet (item 5a), so "add to my saved places" cannot be honoured at all.
- **Recommendation:** **drop for v1.** Revisit both together once 5a has a store and W-MAP ships;
  even then a "you are here" dot is a **current-position source** and owes the threat-model
  amendment this doc's Ph7b note already names.

### 3. Offline regions

- **Confirmed:** no region *download* into browser storage (§2 of `web-app-suite.md`). IndexedDB /
  Cache Storage are evictable (`drive-web-parity.md` §1.2d) and a region is tens to hundreds of MB.
- **What Maps web shows instead of Control's *Offline maps*:** nothing to manage. The map (W-MAP)
  streams the tiles it draws through the runtime's PMTiles range stream; what the browser caches is
  incidental. Where native would link to Offline maps, a one-line note: *"Offline maps are managed
  in Tenshu Control on your phone or computer."* No region list, no sizes, no delete.
- **Recommendation:** confirm, and the note above. `mapRegion` is W-MAP's; Maps web adds nothing to it.

### 4. Directions / open in another app

- **Native today:** the *Directions* pill is **inert** (`PlaceDetailScreen`'s `ActionPill(…) {}`),
  with a note promising a hand-off "to your system navigation app"; §3 defers the hand-off with the
  Navigate tab. Nothing is built.
- **Can:** a `geo:lat,lng` link (handled by Android Chrome; desktop browsers mostly do nothing), or
  copy the coordinates (Clipboard API `navigator.clipboard.writeText`). A link to Google / Apple Maps
  is a **third-party egress the user's click performs**, carrying the coordinate.
- **Recommendation:** **build reduced** — *Copy coordinates* only (no egress). Drop map-provider
  links on web. Revisit with native's hand-off, so both platforms answer the same question.

### 5. Anything else

**5a. ⚠⚠ Saved state, notes and saved lists have no store — the one that matters.** The brief asked
for save/unsave, notes, list CRUD and list membership. `:place-service`'s `Place` stores
`{placeId, latE7, lngE7, capturedAt, label, city, countryCode, labelSource, accuracyM}` and nothing
else; no type in any service holds a bookmark, a note or a list. Natively `PlaceProjectionRepository`
answers this with `savedLists() = flowOf(emptyList())` and `setSaved` a **documented no-op** (§6
Ph9's thin-screens tradeoff). So "as native allows" is: *none of these*. Building them is a schema
change (new synced types, a `place` service version bump + `SchemaMigration`, a regenerated
`SchemaShapeBaseline`, lockstep — House Rule 9), and the web would then hold data no native app can
show. Options:
  1. a cross-platform proposal that adds them to `:place-service` (e.g. `SavedPlace { placeId,
     savedAt, note }` + `PlaceList { listId, name, members }`, list membership as its own keyed rows
     so deleting a list never touches a place), built native-first or in the same PR;
  2. defer saved / notes / lists on every platform.

  **Recommendation: 1, as its own PR before Maps run B**, and **not** from a web run. Run A does not
  build them and the bridge exposes no method for them (a method that can only fail is noise).
**5b. Visit stats on the detail.** Native Maps renders `visitCount = 0` and no last visit — the store
has no visit history of its own. Timeline's web read already joins Visit moments (`visitCount`,
`lastVisitMs`) for the same places. **Recommendation:** run B may read them from `timeline.place`
(a second, existing method) rather than run A copying the join; or the maintainer decides Maps web
matches native and shows none.
**5c. Merge folding diverges from native.** Native Maps lists every stored place, including ones a
`PlaceMerge` folded into another, so a merged place shows twice. Run A follows Timeline's web read
(`rootsOnly`, a folded id followed to its survivor) — one place, one record (D2). **Recommendation:**
keep the web on the folded read and fix native in a follow-up.
**5d. Role.** Native Maps draws a **confirmed** role as the category (`home` / `work`) and never asks
the question; the user-answered `setPlaceRole` is Timeline's. Run A projects the confirmed role only.
**5e. Contribute (D3) and photos on the detail.** Contribute is a queued stub with no store;
the detail's photo strip is empty natively (no photo→place link in the projection). **Drop** both
on web for v1.

### Decided (maintainer, 2026-10-28)

**Maps web v1 ships thin; saving waits for a schema.**

- **1** Local search over your own places, TS only. No world search, no add-by-name, no egress.
- **2** No add-by-pin and no "my location" in v1.
- **3** No offline regions on web; where native links to Offline maps, the web says *"Offline maps are
  managed in Tenshu Control on your phone or computer."*
- **4** *Copy coordinates* only; no map-provider links.
- **5a** Saved places, notes and lists get their own **cross-platform proposal** (new synced types in
  `:place-service`, version bump + `SchemaMigration`, native first). It is not a web run, and Maps web
  does not wait for it. Run A exposes no method for them.
- **5b** The detail shows visits, read from Timeline (no second join) — `timeline.placeVisitSummary`,
  not `timeline.place` (which ships every visit moment).
- **5c** The web keeps the folded read (one place, one record); fix native in a follow-up.
- **5d** Confirmed role only. **5e** No Contribute, no photo strip.

So Maps web v1 is: the place list with local search, place detail (visits via Timeline), rename, the
map (W-MAP), and copy coordinates.

### As built — Wave 3, Maps run A: the SDK boundary + bridge (2026-10-28)

`Maps` (`tenshu-sdk-js` `Maps.kt` + `PlacesBook.kt`), `web/sdk/src/maps.ts` and the `place.*` gateway
methods (`place.list`, `place.list.observe`, `place.get`, `place.rename`). No app allowlist names them
yet; run B adds them.

- ⚠⚠ **The read is Timeline's, not a copy.** `PlacesBook.kt` runs `timelineFlowOf` over Timeline's
  `placesRead` / `mergesRead` / `rolesRead`, with the moment, trace and trip sources stubbed empty. So it
  inherits Timeline's failure semantics (a failed relation is `{ ok: false }`, a silent one is
  `error_timeline_source_silent`) and the 5c fold. A change to Timeline's place read changes Maps.
- **Rename is `Timeline.renamePlace`, delegated**, so the error keys are Timeline's (`error_timeline_*`).
- **A missing id is `place: null`**, an answer; `ok: false` means only that the read failed.
- **Rows are degrees, and carry a confirmed role only.** They have no saved, note or list field (5a).
- **No moments are read**, so a moment write does not re-emit the list. Visits come from Timeline's `placeVisitSummary` (5b).

### As built — Wave 3, Maps run B: the web app (2026-10-28)

`web/maps` (`maps.tenshu.app`, dev port 5179). It is the Decided list above and nothing more.

- **List.** `place.list.observe`, subscribed once at the root (`LiveProvider`). It is sorted by name in
  the UI language's `Intl.Collator` (base sensitivity, numeric), with unnamed places last. The SDK's
  store order means nothing to a reader.
- **Search (1).** It is TS only. `fold()` is NFD + strip marks + lower case + a small transliteration
  table for letters NFD leaves whole (`ß`→`ss`, `ø`, `æ`, `œ`, `ł`, `đ`, `ð`, `þ`, `ı`), so "kobenhavn"
  finds "København". ⚠ The list is sorted and folded **once** per list and language (`searchIndex`); a
  keystroke only filters, and `Intl.DisplayNames` / `Intl.Collator` are cached per locale — with
  thousands of places, building either per call was the lag. The list is virtualised (fixed row height). Every word of a
  query must appear in the name, city, country code, country name (`Intl.DisplayNames`, so "Germany"
  and "Deutschland" both find `DE`) or confirmed role. A query also shows D4's "searching for places you
  haven't been is coming" line.
- **Detail.** It shows the name, and whether the user gave it (`nameIsUsers`) or a device stored it. It
  shows city / country, the coordinate, the confirmed role as a chip (5d) and the fold notes
  (`redirectedFrom`, `mergedPlaceCount`).
  - Visits are `timeline.placeVisitSummary`'s `visitCount` / `lastVisitMs` (5b): the same counting as
    `timeline.place`'s row, without its visit moments (thousands, for a home place). The relative "last visit" is
    native `MapsFormat.relativeVisit`, ported. ⚠ The detail reads visits with the id `place.get`
    **returned**, never the one it was opened with. A stale link has been followed to its survivor.
  - Rename is two steps (the name, then a confirm) over `place.rename`, again with the returned id.
  - *Copy coordinates* (4) is `navigator.clipboard.writeText` of `lat, lng` to five decimals, always
    dot-separated. A missing or refused clipboard is said, never swallowed.
- **Map.** A `<TenshuMap>` sits on the list, with a pin per shown place, and a click opens the place.
  ⚠⚠ It is **anchored on one place** (`lib/anchor.ts`): the confirmed home, else the densest cluster,
  else (clusters tied) the most recently visited — `timeline.places` is read for that tie only. A map
  draws one region, looked up at one point, and the box's middle is the wrong point (Berlin + New York
  → the Atlantic, no region). map-kit fits the places inside the anchor's region; the rest keep pins.
- **Back** keeps the search and the scroll: the list stays mounted (hidden) behind an open place. Another sits on the detail, one pin at zoom 16. ⚠⚠ Whenever a map cannot draw (no WebGL,
  no transport, a failed tile), it is map-kit's explicit box, and the list never depends on it.
- **Offline maps (3).** The one-line note, on the list and the detail.
- ⚠ **CSP.** The Maps site is the first with a full policy (`hosted_map_app`, `how-to/new-web-app.md` §2b).

## 8. Version

Maps-app v0.3 · 2026-07-13 · **place data moved onto the Control proxy** (§7);
`InProcessMapsPlaceProxy` deleted, mock-POI seeding removed, places now shared with Timeline via a
single `:place-service` in Control. Threat-model A35 amended.

Maps-app v0.2 · pre-design record; phased 2026-07-08 (v0.1 2026-07-06). No code. Locks D1
(list-first Places, single-pin detail, routes→Timeline); inherits the §3 basemap decisions
from `milestones.md`; carries D2–D4 into the design handoff; §6 phases the build as an
app track (Ph0–Ph8, UI-only over mock, MapLibre isolated to Ph7) + a gated service track
(Ph9–Ph11). Research provenance: 2026 basemap reassessment (Planetiler / PMTiles / MapLibre /
Organic Maps / Valhalla); Apple *Visited Places* iOS 26 + Google Timeline on-device migration
(list-first validation).
