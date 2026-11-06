# Place geocoding — executing P4 (labels for captured & imported places)

**Status:** v0.3 — **G1 + G2 + G3 SHIPPED (2026-07-17); G4–G5 open.** G1: the §3 seam +
enricher + fakes + 11 tests + the "Unnamed place" fallback + the bound-only Settings toggle;
as-built deviation: the integration test rides the `TestProxy` three-service stack
(`InProcessTimelineProxy` no longer exists — Timeline moved onto the Control proxy), and
the enrich hooks run inline in the already-suspend write path. G2 + G3: see the as-built notes
in §4 (G3 is desktop-only — the Android Photon fallback was **deliberately not shipped**; it
would cost `:timeline-android` its stripped INTERNET permission, a maintainer opt-in, §4.3).
Executes **P4** of
[`timeline-services.md`](timeline-services.md) §3.3–3.4 (the reverse-geocoding seam) as a
phased implementation. Companion to [`timeline-ingestion.md`](timeline-ingestion.md) §5
(which declared geocoding "rides along" but did not size it). This is the design that
precedes the code, per the repo's docs-first convention.

**Why now:** every live-captured place and every *new-format* (2024+) Google Timeline import
lands with a **blank label** (`GoogleTimelineParser` KDoc: new-format visits are label-less
until geocoded; `VisitDetector.capturedVisitsToTimeline`: "Labels are blank (geocoding is a
separate rail)"). The projector then renders empty names + the `#8B7BB8` placeholder square.
Geocoding is the single highest-leverage fix for the app's bareness — and it is the only
path to names for anything captured going forward.

---

## 1. Scope

**In scope:** reverse geocoding only — `(lat, lng) → label (+ city/country)` — for `Place`
rows whose `label` is blank; the consent gate; the per-platform implementations (Android
device geocoder, common Photon client, iOS `CLGeocoder`); surfacing the result in the
Places tab / place detail; user rename.

**Out of scope (each has its own home):**

- **Forward `PlaceSearch`** (`query → candidates`) — §3.3 sketched it alongside; no consumer
  exists yet (no place-search UI). Deferred until one does.
- **`Place.externalRefs` storage** — Photon returns `osm_id`; storing it is blocked on the
  ref-identity decision (`timeline-services.md` §3.2 as-built). Park the id, don't persist.
- **Offline tier-1** (bundled Overture/Foursquare extract, k-d tree, city-level) — the
  §3.4 chain's first link. Genuinely valuable (zero egress) but a data-pipeline project of
  its own; it should ride the Maps region-delivery infrastructure (R2+CF) and gets its own
  proposal when prioritised. The seam here is designed so it slots in as just another
  `ReverseGeocoder` at the front of the chain.
- **Photos / covers** — the other half of the bareness fix; separate track.

## 2. Decisions

- **D1 — Direct platform implementations, not `jordond/compass`.** §3.3 named compass as
  the KMP candidate. The surface we need is *one suspend function*; compass brings a
  dependency, its own provider abstractions, and a Kotlin/Native iOS path where this repo's
  convention for iOS platform integrations is **native Swift behind the SDK seam** (the
  `HealthKitImporter` precedent). Direct impls are ~50 lines each and keep throttling under
  our control. Revisit only if we ever need its web providers.
- **D2 — App-side (client-plane), not proxy-side.** §3.3 said "proxy-side / platform
  capability"; as-built, Timeline runs against the **Control** proxy over IPC, and the
  capture + import write path already lives app-side in `:timeline-shared-data`
  (`TimelineImportWriter`, `CapturedVisitWriter`). Putting geocoding in Control would move
  location egress into a *different app's* process and split the consent surface from the
  app that owns it. The geocoder is DI-bound in the Timeline app, mirroring
  `HealthImporter` / `LocationCaptureSource` ("DI-bound, not `expect`/`actual`").
- **D3 — Blank-only fill.** Enrichment writes a label **only when the stored label is
  blank**, re-checked immediately before the write. `RecordPlace` is an idempotent upsert
  by `placeId`, so enrichment is just a second `recordPlace` with the label filled. A
  user-authored rename (G4) is therefore never overwritten. (A `labelSource` field that
  would let re-geocoding improve *geocoded* labels is deferred — see §6.)
- **D4 — Single-result `resolve`.** §3.3's sketch returned `List<PlaceCandidate>`; reverse
  geocoding for auto-labelling wants the best answer or nothing. A candidate list returns
  when a *picker UI* exists (rename screen "suggestions", forward search).
- **D5 — One consent gate for all enrichment egress.** A `PlaceGeocodingControl`
  (mirror of `TimelineCaptureControl` — persisted flag, platform-bound via Koin
  `getOrNull`, toggle appears only where an implementation is bound). It gates capture-time
  *and* import-time enrichment: an import backfill sends the coordinates of the user's
  entire history to a provider, which is exactly as sensitive as live capture egress.
  "Native ≠ private" (§3.3): Android `Geocoder`/`CLGeocoder` are server-backed — the
  coordinate reaches Google/Apple. Default: **off until the user enables it**, offered in
  the capture opt-in flow and after an import completes ("Look up place names?"). The
  threat-model amendment (§5) lands with the first real geocoder (G2), per House Rule #2.

## 3. The seam (all in `:timeline-shared-data`, package `…data.geocode`)

v1 home is `:timeline-shared-data` (the only consumer; already has
`commonMain`/`androidMain` + the capture/import machinery). Relocate toward
`:place-service-client` when a second app wants it — mechanical, nothing else imports it.

```kotlin
// commonMain
data class GeocodeResult(
    val label: String,          // a POI name or a street name — best available name
    val city: String = "",      // locality, when the provider gives one
    val countryCode: String = "",
)

interface ReverseGeocoder {
    /** Best answer for the coordinate, or null (unavailable / nothing there / throttled). */
    suspend fun resolve(lat: Double, lng: Double): GeocodeResult?
}

/** Tiered chain per timeline-services §3.4 — first non-null answer wins. */
class ChainedReverseGeocoder(private val tiers: List<ReverseGeocoder>) : ReverseGeocoder

class PlaceLabelEnricher(
    client: TenshuClient, context: TContext,
    private val geocoder: ReverseGeocoder,
    private val consent: PlaceGeocodingControl,
) {
    /** Scan blank-label places, resolve throttled, blank-only re-record (D3). */
    suspend fun backfill()
    /** Best-effort enrichment for freshly written places (capture / import hook). */
    suspend fun enrich(placeIds: List<String>)
}
```

Behavioural contract for the enricher (all unit-testable device-free):

- reads places via the same `client.placeService.places.select { … }` the projector uses;
- **throttles** (~1 request/sec default — matches Android Geocoder etiquette and the Photon
  public-instance policy) and remembers failed/empty lookups **per session** so a
  no-result coordinate isn't re-queried in a loop;
- checks `consent.enabled` before *every* network tier (an offline tier-1 would be exempt);
- re-reads the row and skips if the label is no longer blank (D3);
- never throws on provider garbage — untrusted-input posture, same as the parsers.

Hooks: `CapturedVisitWriter.writeAll` and the import completion path call
`enrich(newPlaceIds)` fire-and-forget when a geocoder + consent are present (Koin
`getOrNull` — a platform with nothing bound is a silent no-op, the capture-control pattern).

## 4. Phases (each independently shippable)

1. **G1 — seam + enricher, device-free.** Everything in §3 with a `FakeReverseGeocoder`
   fixture; `PlaceGeocodingControl` interface + Settings toggle (rendered only when bound,
   like the capture toggle). Integration test over `InProcessTimelineProxy`: import a
   label-less new-format Google fixture → `backfill()` → the projector's Places flow emits
   named places. **Also ships the honesty fix:** the projector/UI renders label-less places
   as a localised "Unnamed place" (`stringResource`, ADR 0016) instead of blank text — the
   app stops looking broken even before a real geocoder lands. *No egress in this phase.*
2. **G2 — Android device geocoder + consent + threat-model amendment.**
   `AndroidReverseGeocoder` over `android.location.Geocoder`: gate on `Geocoder.isPresent()`,
   `GeocodeListener` on API 33+, the blocking call dispatched to IO below; map
   feature-name/thoroughfare/locality → `GeocodeResult`. Bound in `:timeline-android`
   alongside a persisted `PlaceGeocodingControl`. Consent copy added to the capture opt-in
   flow + the import result dialog (D5). **Threat-model amendment lands here** (§5).
   Note: this is the platform `Geocoder` API (address lookup, results storable), **not**
   the Places SDK (billed, no-store ToS) — we never touch the latter (§3.1 content rules).
   HR#8: smoke gate. On-device: capture a visit → name appears on the Places tab.
   **As-built (2026-07-17):** `AndroidReverseGeocoder` (`:timeline-shared-data` androidMain)
   extracts fields only; the precedence logic is a pure `commonMain` `geocodeResultOf(…)`
   (feature name unless it degrades to the house number → street → locality), unit-tested
   device-free. `AndroidPlaceGeocodingControl` mirrors the capture control (SharedPreferences);
   binding it is what makes the G1 Settings toggle appear. Backfill triggers as designed via a
   new common `GeocodingBackfillTrigger` collected in an application-lifetime scope
   (`TimelineApplication`): every opt-in edge — including app start with the toggle already on —
   backfills; freshly captured places enrich inline (`LocationCaptureEngine` gained an optional
   `enricher` param the capture FGS fills). *Deviation:* no extra consent copy was added to the
   capture flow — the capture opt-in *is* the Settings screen, and the geocoding toggle with its
   disclosure sits directly beneath; the desktop import-dialog offer waits for G3 (no desktop
   geocoder exists until then). On-device functional pass (real visit → named place) remains a
   maintainer item; the smoke gate + Timeline launch check passed.
3. **G3 — common Photon tier (desktop + Android fallback).** `PhotonReverseGeocoder` in
   `commonMain` — Ktor client + kotlinx-serialization against Photon's `GET /reverse`
   (`lat`/`lon` params; parse `features[0].properties.{name,street,city,countrycode}` +
   park `osm_id`). Endpoint is **config, not code**: desktop binds it as its only tier;
   Android chains device-first → Photon. Until a self-hosted instance exists the default
   config may point at the public `photon.komoot.io` **only behind the same explicit
   consent + 1 rps throttle** (their fair-use policy); the ops sub-slice — a Photon
   container on the existing DO/Terraform relay box — swaps the endpoint with zero code
   change and upgrades the privacy tier (queries stay on our infra). Tests: Ktor
   `MockEngine` fixtures incl. malformed responses.
   **As-built (2026-07-17):** `PhotonReverseGeocoder` (`:timeline-shared-data` commonMain) takes
   an **injected `HttpClient`** — only `ktor-client-core` rides in commonMain (`api`, the
   epc-rendezvous-client pattern); each platform builds the client with its engine at the
   binding site (OkHttp on both desktop JVM and Android). Parsing = `bodyAsText` +
   `@Serializable` internal DTOs (`Json { ignoreUnknownKeys }`), no content-negotiation plugin;
   precedence reuses the pure `geocodeResultOf(…)` (Photon `housenumber` ≙ `subThoroughfare`);
   `osm_id` parked in a comment, not persisted (§1); ~10 s `withTimeoutOrNull` request bound;
   HTTP errors / timeouts / malformed JSON → `null`, never a throw. Desktop:
   `DesktopPlaceGeocodingControl` (`java.util.prefs`, shared-data desktopMain — binding it is
   what makes the G1 Settings toggle appear) + `timelineDesktopModule()` (Photon = only tier) +
   the `GeocodingBackfillTrigger` launched in the Koin app scope in `Main.kt`; the desktop
   `FileTimelineImporter` now receives the enricher, and the **import-result dialog** (moved to
   `:timeline-shared-ui` for localised strings) gains the D5 offer — after a successful import
   with lookups off: a disclosure line + *Look up place names* / *Not now*; enabling flips the
   same persisted consent the Settings toggle drives and the backfill trigger fires by itself.
   ⚠ **Deviation — Android stays device-tier-only; the §4.3 device→Photon chain was NOT
   shipped.** `:timeline-android` deliberately strips INTERNET at the OS boundary
   (`tools:node="remove"`, "all egress is Control's" — the strongest posture available to the
   app that holds the location history), and a direct app-process Photon call would lift that
   strip. The device geocoder egresses via the GMS process, so the app itself needs no network
   grant. Adding the chain back is a two-line maintainer opt-in (manifest strip removal +
   `ChainedReverseGeocoder(device, Photon)` rebind in `timelineAndroidModule`); A35(d) already
   covers the consent story if that day comes, but expanding the most sensitive app's
   permissions is opted *into*, not out of. Until the self-hosted instance lands the desktop
   default endpoint is the komoot public one; the enricher's 1 rps throttle carries its
   fair-use policy (the geocoder adds no second throttle — never call it outside the enricher).
4. **G4 — structured fields + rename UX.** Extend the `Place` schema with optional
   `city` + `countryCode` (⚠ positional composites make added fields **peer-breaking** —
   ship lockstep across devices; acceptable in the pre-release no-compat window, same
   posture as deferred-properties). Enricher fills them; projector stops hardcoding
   `neighborhood`/`city` to `""`; Places tab rows + place detail show them. **User rename**
   in place detail: writes a non-blank label via the same `recordPlace` upsert — which D3
   then protects forever.
   **As-built (2026-07-17):** `city`/`countryCode` appended to the `Place` composite
   (⚠ lockstep — old peers can't parse the longer row; local rows only-grow). The enricher
   writes them with the label fill, `ifBlank`-preserving values another tier already stored;
   the projector maps `city` through (presentation `neighborhood` stays `""` — city-level is
   all geocoding stores; both render sites blank-collapse). Rename = `renamePlace(id, label)`
   on the `TimelineRepository` seam as a **default no-op** (the iOS EPC client stays
   source-compatible and read-only until G5) with real (`recordPlace` upsert preserving
   coords/`capturedAt`/city/country; needs the repo's new optional `TContext` ctor param) and
   mock overrides; place-detail gains an edit affordance + rename dialog. `countryCode` is
   stored but not yet rendered (no design slot); category stays out per §6.
5. **G5 — iOS `CLGeocoder`.** Native Swift behind the SDK seam (the `HealthKitImporter` /
   `CapturedVisitWriter` pattern), gated on the `timeline-ios` shell: the delegate
   reverse-geocodes and hands the label through the same writer path, or an SDK-exposed
   `enrich` is invoked from Swift. Sequenced last — iOS visits already flow without labels,
   and G1–G4 prove the shape first.
   **As-built (2026-07-17), two deviations:** (a) the geocoder is **Kotlin/Native, not
   Swift** — `IosReverseGeocoder` (`:timeline-shared-data` iosMain) wraps `CLGeocoder`
   directly (a small, cleanly-interoping ObjC API); the whole enricher stack is common
   Kotlin, so this beats exporting a Swift-implementable suspend interface through the
   XCFramework. (b) iOS **live capture (ingestion I4) still doesn't exist**, so there is no
   delegate to ride — G5 is the backfill rail over *synced* blank-label places:
   `EpcTimelineClient.assembleClient` takes an optional `PlaceGeocodingControl` and returns
   the enricher assembled over the EPC client (the repository now also carries `TContext`,
   lighting up G4's `renamePlace` over EPC); `TimelineSurface` owns an
   `IosPlaceGeocodingControl` (NSUserDefaults) + launches `GeocodingBackfillTrigger` in a
   session scope on `installRealClient` (torn down with the live session) + exposes
   `geocodingAvailable`/`geocodingEnabled`/`setGeocodingEnabled` to Swift. SwiftUI:
   Settings gains the consent toggle (shown only when the live client is installed — the
   bound-only rule), place detail gains the rename affordance (a `PlaceDetailModel` retains
   the VM, not just its state flow), and the Unnamed-place / neighborhood→city blank
   fallbacks are ported. When I4 lands, its delegate reuses this exact rail (the writer's
   `enricher` hook). Verified: XCFramework + `xcodebuild` simulator BUILD SUCCEEDED;
   on-device/simulator functional pass pending.

G1 is pure common code (fully testable in CI-less local runs); G2 is the first user-visible
payoff; G3 makes desktop imports useful; G4 is the schema/UX rounding; G5 completes
platform parity. The offline tier-0 (§1 out-of-scope) prepends to the chain whenever it
lands, demoting network tiers to fallback.

## 5. Threat model (House Rule #2 — lands with G2)

Location is already a sensitive asset (A35, capture opt-in). Geocoding adds a **new egress
channel** on that asset:

- coordinates leave the device **direct to the provider / self-hosted instance, never via
  the relay** — the relay must not learn locations (HR#1 unaffected: nothing here touches
  the sync plane);
- egress happens **only behind the explicit `PlaceGeocodingControl` consent**, disclosed in
  the capture opt-in and import flows; disabling stops all lookups immediately;
- what is *persisted* is the returned label/city text on the synced (sealed) `Place` row —
  never Google/Apple proprietary content beyond what the platform address API returns, and
  never a raw provider response;
- the self-hosted Photon tier reduces third-party exposure to our own infra; the future
  offline tier reduces it to zero.

### 5.1 One egress gate — the transit-stop leak (2026-10-17)

⚠⚠ **The consent claim above was false for two callers until 2026-10-17.** `PlaceLabelEnricher`
checked `PlaceGeocodingControl` before every lookup, but `TimelineProjectionRepository` called the
`nearestTransitStop` op (transit-leg stop names, `timeline-movement-capture.md` §32.14) and the
picker's `reverseGeocodeCandidates` **directly**. The proxy answers `nearestTransitStop` from its
tiles first and falls back to komoot's public Photon, so every transit leg whose stop the tiles could
not name sent both endpoints to Photon — opt-in off, or never asked. On iOS (no tile tier: no gzip
binding) that was every leg.

**As-built:** `PlaceGeocodingEgress` (`:timeline-shared-data` `…data.geocode`) is now the only code
in Timeline that calls `nearestTransitStop`, `reverseGeocodeCandidates` or `reverseGeocodePlace`; it
re-reads the opt-in on every call. The repository takes it via an optional `geocodingConsent`
constructor parameter, the Android `ProxyReverseGeocoder` tier speaks only through it, and
`GeocodingEgressGuardTest` fails on any other direct call in a `timeline-*` module or
`:tenshu-sdk-ios-timeline`. `GeocodingConsentEgressTest` drives the real repository over the real
map-region proxy with a recording egress where Photon would be.

- ⚠⚠ **Absence is NO.** A `null` control (no platform binding, a harness) refuses, exactly like
  `false`. DI passes `getOrNull()` on Android/desktop and the `TimelineSurface` control on both iOS
  tiers (route-1 and EPC).
- **Refusal degrades:** a stop-less transit leg reads as bare "Transit", the picker is free text.
  The stop cache is keyed on the opt-in too, so attempts spent while it was off never use up the
  budget an opt-in later needs (`StopLookup.MAX_ATTEMPTS`).
- ⚠ **Follow-up (a) — cost: with the opt-in off, the tile tier is skipped as well**, though it involves no third
  party. The op cannot be asked for "tiles only" without a new `NearestTransitStop` parameter, which
  is a `MapRegionService` reshape (HR #9: version bump + empty migration, and a lockstep move of
  Control and `:hub`). That is the follow-up if offline stop names are wanted without consent.
  Tracked in `tech-debt.md` *"With the place-name opt-in off, transit stops lose the offline tile
  tier too"*.
  **Deferred past the production reset (decided 2026-10-29).** The parameter is small (a `bool`,
  ~5 files), but it is not built now, for three reasons:
  1. It needs a product decision first. "Tiles only" is **not** network-free:
     `TileTransitStopSource` range-fetches tiles of *uninstalled* regions from Tenshu's CDN, which
     discloses a tile-granular location. Whether that is egress needing the opt-in, or whether the mode
     should be "installed regions only", is undecided.
  2. iOS gains nothing, since it has no tile tier.
  3. The post-release cost is bounded. An op parameter is not stored data, so later it costs an empty
     migration and a Control/`:hub` lockstep: no wipe and no data loss.
- ⚠ **Follow-up (b) — iOS route-1 shows no opt-in toggle** (`TimelineSurface.geocodingAvailable()` is true only with
  an enricher, which route-1 does not assemble), so its transit legs carry no stop names until the
  toggle is surfaced there. Before this fix they were named only by leaking to Photon — iOS has no tile
  tier — so nothing private was lost, but the gap is real. Tracked in `tech-debt.md` *"iOS route-1
  never shows the place-name toggle"*.
- `linesServing` (§33 L4) is **not** gated: it reads Tenshu's own catalogue/overlay artefacts, with
  no third-party geocoder behind it.

## 6. Open questions (none block G1)

- **`labelSource` (user vs geocoded)** — D3's blank-only rule means a *better* geocoder tier
  can never improve an already-geocoded label. A one-byte source marker would allow
  "re-geocode" without risking user renames. Defer until it hurts.
- **Consent default once capture is on** — strict opt-in (current plan) vs default-on with
  disclosure inside the capture consent. Maintainer call at G2; the code shape is identical.
- **Backfill scheduling** — v1 triggers on import-complete + capture-write + a manual
  Settings action. A periodic sweep belongs to bgproc Phase 6 (`PlatformScheduler`) once
  that lands; don't hand-roll one here.
- **Category** — Photon/OSM return a POI class (`amenity=cafe`); a `category` field would
  unlock per-category glyphs (the thumbnail ladder's tier 3). Natural G4 rider, but it
  drags a taxonomy decision — keep out of v1 unless trivially mappable.

## 7. Version

v0.1 · 2026-07-16 · plan distilled from `timeline-services.md` §3.3–3.4 + `timeline-ingestion.md` §5;
no code. Phase G1 is the next actionable slice.

v0.5 · 2026-07-17 · ALL PHASES SHIPPED same-day (as-built notes in §4): seam + enricher (G1),
Android device tier + consent + A35(d) (G2), Photon tier desktop-only (G3 — the Android chain +
INTERNET grant is a documented maintainer opt-in), structured fields + rename (G4), iOS
`CLGeocoder` backfill rail + Settings consent + rename parity (G5 — Kotlin/Native, not Swift).
Remaining: on-device functional passes (Android visit→named place; iOS toggle+rename), the
self-hosted Photon ops sub-slice (config swap), and the future I4 iOS capture reusing this rail.
