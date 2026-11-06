# Timeline services — the storage/sync substrate behind the Chapters UI

**Status:** v0.2 — **sketch, no code (2026-07-02).** Distils the "what services does
Timeline need" investigation into a decomposition, three service designs in the Tenshu
idiom, and the two genuinely-hard cross-platform seams (POI identity + health import).
v0.2 folds in the first maintainer review: **names locked** (`:timeline-service`,
`:health-service`, `:place-service`), **TripSuggestion is per-device** with a merge-time
refinement path (§5.2), and **`subjectRef` is left as two costed options** (§5.1) for a
later call. Remaining opens in §10. Companion to [`timeline-app.md`](timeline-app.md)
(the UI-only v1) — that doc's `TimelineRepository` seam is exactly where all of this plugs in.

**Owner's note:** the UI-v1 was deliberately built over a mock `TimelineRepository` so the
real store could swap in behind one Koin binding with no VM/UI change. This proposal is the
real store. The load-bearing framing (§1): **these services are the storage/sync substrate;
the real `TimelineRepository` is a read-model *projector* that maps stored entities → the
existing `Day`/`Trip`/`Moment`/`Place`/`ActivityRollup` presentation model.** Nothing built
in `timeline-app.md` moves.

---

## 1. The shape of the problem

The mock model (`:timeline-shared-logic` `model/TimelineModel.kt`) carries eight types.
The first architectural question is **which are stored-and-synced entities and which are
derived projections** — the lazy-vs-materialised seam the codebase already cares about
(FolderMerger / `resolveListField`; settled in [ADR 0024](../adr/0024-merger-field-resolution.md)).
The answer decomposes cleanly:

| Mock type | Stored entity? | Where | Notes |
|---|---|---|---|
| `Moment` | **Stored** (Syncable, RealmData) | `:timeline-service` | The atom. The spine of the whole model. |
| `Trip` | **Stored** (Syncable, RealmData) | `:timeline-service` | A *curation*: metadata + ordered membership + nested-trip refs. Recursive. |
| `TripSuggestion` | Derived (ephemeral, **per-device**) | on-device detector | Auto-detected cluster; "accept" materialises a `Trip`; refined at merge time (§5.2). |
| `Day` | **Derived** | projector | Group moments by calendar date; `weather`/`movement` = a small optional `DaySummary` (later). |
| `Place` | **Stored** (Syncable, RealmData) | `:place-service` | Canonical POI. See §3 — the hard one. |
| `PlaceVisit` | **Derived** | projector | The Visit-kind moments referencing a place. |
| `Workout` (feeds `ActivityRollup`) | **Stored** (Syncable, RealmData + content plane) | `:health-service` | Provider-neutral workout record. See §4. |
| `ActivityRollup` | **Derived** | projector | Aggregate over workouts by type. |

So there are **three new services** (matching the maintainer's instinct), each owning one
stored entity family, plus **derived projections** the `TimelineRepository` implementation
computes at read time. Photos are **not a new service** — they are Drive files
(`:file-service`); a `Moment` references photo files by id and reuses Drive's content plane
for bytes (§5).

### 1.1 The spine → subject reference model

A `Moment` is a cross-cutting timeline entry that **references** a domain record rather than
duplicating it (`MomentKind` says which):

- `Visit` → references a `Place` (`:place-service`).
- `Activity` → references a `Workout` (`:health-service`).
- `Event` → a manual note / calendar entry (v1: freetext title + note).
- `Quiet` → a detected gap; no subject.

The moment carries its own *presentational essence* (time, title, kind, cover, photo refs)
plus a typed `subjectRef` (kind + id) into the owning service. Loose coupling by id keeps
the services independent modules (no cross-service `serviceDataRelation`); the projector
hydrates the subject by calling the relevant service client. This is also what makes the
Place-detail "visit history" and the Activities rollup *derived* rather than stored: they
are queries over moments/workouts, not a second copy.

### 1.2 Why three services and not one

- **`:place-service`** is genuinely reusable (any app that records "where" — check-ins,
  sharing, Drive geotags) and carries a self-contained, hard cross-platform concern
  (geocoding/POI identity). It should not be welded to Timeline.
- **`:health-service`** is its own domain and the seed of a future **Tenshu Fit** app
  (the maintainer's own note). HealthKit and Health Connect are both "health" umbrellas —
  workouts are one type; sleep/vitals/body could follow. Naming it for the umbrella, not
  the one type, is the future-proof call.
- **`:timeline-service`** is the app-specific spine (`Moment`/`Trip`) — the part that *is*
  the Chapters narrative and would not make sense outside this app.

House Rule #4 forces the hand anyway: all four entities are cross-platform, wire-serialisable
synced data, so each must be **schema-first in a service module**, not an app-local data class.

---

## 2. Service anatomy (the idiom every service below follows)

Verified against `:notification-service` end-to-end. Each service is the standard 5-module
split; the checklist is identical for all three, so it is stated once here and the per-service
sections (§§3–5) only give the **schema + operation shape** that differs.

- **`:X-service`** (→ `:core`) — `Service` interface + `service(id, relations)` schema; one
  `Composite` interface per entity (`composite(id, parent, traits = […DataIdProvider,
  Syncable(SyncStrategy.Eager), RealmData…], fields = { … })`); a `stringDataId` provider per
  keyed entity (content-addressing → idempotent re-writes); one `Operation` interface per op
  (**non-suspend** method returning `RequestObject`, schema traits `IoOperation` +
  `TerminalOperation` per House Rule #3) plus a `suspend fun X.op(…)` DSL extension using
  `.asTaskInteractor<T>().execute()`.
- **`:X-service-common`** (→ `-service` + `:core-common`) — a `Dto` per composite,
  `*DataIdProviderImpl` per key, `xServiceCommonModule = tModule { service(…);
  composite(…, dtoFactory=…); trait(…); serviceDataRelation(…) }`.
- **`:X-service-client`** (→ `-common` + `:core-client`) — an `Ro` per op + a service `Ro`,
  `xServiceClientModule = tModule { operation(…, roFactory=…); type(…, roFactory=…) }`, and
  `val TenshuClient.xService get() = getService(X.ID)`.
- **`:X-service-proxy`** (→ `-common` + `:core-proxy`) — `internal class …Implementation :
  SyncOperationImplementation` per op (**persist via `executionContext.saveData(…)`** — only
  that fires the `SyncObserver`), an `xServiceProxyImplementationFactory(id)` when-switch, and
  `ExecutionContext.initXService()` registering **relation-maintainer** `DataObserver`s
  (relation membership is re-derived per-device, not synced — mirrors
  `NotificationRelationMaintainer`).
- **`:X-service-test-utils`** — registry builders + `TestProxy` factory mirroring prod wiring.

Wiring (per service): add `xServiceCommonModule` + `xServiceClientModule` to the
`coreSchemaConfigs` lists (`TenshuClientModule.kt`, `ProxyModule.kt`); `?:`-chain
`xServiceProxyImplementationFactory` into `platformImplementationFactory`; call `initXService()`
in `platformProxyInitialization` (android/desktop/ios `PlatformProxyModule.kt`). `settings.gradle.kts`
gets the 5 includes per service.

**E2E is transparent to the feature service.** An entity syncs iff its schema carries the
`Syncable` trait; encryption/transport is the sync layer's job. A feature service only opts
in. Large blobs (workout routes/streams, §4) ride the **content plane** (ADR 0017 chunked
AEAD), not inline schema fields — exactly as File bytes do, keeping the metadata plane light.

---

## 3. `:place-service` — POIs and the cross-provider identity problem

This is the hard one, and it maps directly to the maintainer's question: *"is there an
established cross-platform way to handle this?"* — **short answer: yes, an emerging open
one (Overture GERS), but you must design provider-neutral around it, not on top of any one
provider.**

### 3.1 The core finding

There is **no universal canonical POI identity**. The same café is a `place_id` in Google, an
`MKMapItem` identifier in Apple, an OSM node id, an `fsq_place_id` in Foursquare, a GERS id in
Overture. The closest thing to a bridge is **Overture Maps' GERS (Global Entity Reference
System)** — stable UUID-v4 IDs with published "Bridge Files" cross-referencing OSM / Meta /
Microsoft / Foursquare / Esri. Launched 2025-06-25 as an explicit "global standard for
interoperable geospatial IDs." **GERS cannot and does not bridge Google or Apple ids** —
both walled gardens contractually forbid folding their content into a derived database.

The ToS asymmetry is load-bearing for an E2E store:

| Provider | Stable id storable long-term? | Content (name/addr/latlng) storable? | Display constraint |
|---|---|---|---|
| Google `place_id` | **Yes, indefinitely** (explicit ToS carve-out) | **No** — temp ≤30-day latlng only | Must render on a Google map |
| Apple `MKMapItem` id | Yes (no durability SLA) | **No** — no derived DB | Must render on an Apple map |
| OSM node id | Yes | **Yes** (ODbL; attribution) | Any map |
| `fsq_place_id` | Yes | **Yes** (Apache-2.0) | Any map |
| GERS id | Yes | **Yes** (CDLA-Permissive-2.0) | Any map |

Both proprietary providers give a durable *foreign key* you may keep, while forbidding you to
store their human-readable content or render it on a non-native basemap. **Open data
(OSM/Overture/Foursquare) is the only license family that lets you store the answer freely** —
which is exactly what an E2E store you (the operator) cannot inspect needs.

### 3.2 Canonical stored entity (provider-neutral)

```kotlin
interface Place : Composite {                              // :place-service
    companion object {
        const val ID = "tenshu/place/place"
        val Schema = composite(
            id = ID, parent = Composite.Schema,
            traits = listOf(PlaceDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.PLACE_ID)          // OUR stable id — the only primary key
                int(Fields.LAT_E7); int(Fields.LNG_E7)   // coordinate in E7 (deg × 1e7)
                timestamp(Fields.CAPTURED_AT)
                string(Fields.LABEL)             // user-authored name; "" when unset
                // externalRefs (cross-provider ids) — next slice, see as-built note below
            }
        )
    }
}
```

External ids are **decoration/foreign keys**, modelled as `ExternalPlaceRef` (`{provider,
externalId}`) in a `Place.externalRefs` list. They anchor on GERS id (it already bridges
OSM/Foursquare/Meta/Microsoft/Esri); Google/Apple ids ride as isolated foreign keys whose
*content* stays in their walled surfaces. `PlaceVisit` (mock) is **not** stored — the
place-detail history is derived from Visit-moments referencing `placeId`.

**As-built (P1 scaffold shipped 2026-07-02 — the five `:place-service*` modules + a green
`RecordPlaceUnitTest` wire round-trip).** Two deviations from the sketch above, both forced
by the platform and both documented in-code:

- **Coordinates are E7 integers** (`latE7`/`lngE7`, degrees × 1e7), not floats. The schema
  DSL has no `double`/`float` field kind — only `TInteger` (Long-backed). E7 is the standard
  GPS-storage idiom anyway (~1 cm, box-queryable, exact on the wire); convert at the edge.
- **`Syncable(Eager)` only — `RealmData` deferred.** `RealmData` requires fulfilling a
  `CREATED_AT` value requirement (extra property-handle wiring); `Notification` itself is
  `Syncable` but not `RealmData`, so Place follows suit. Opt in when Place should surface in
  Control's Data section.
- **`externalRefs` storage deferred one slice.** An embedded list-of-composite is persisted as
  array elements, each needing its own `DataIdProvider` (as `File` does inside `Folder.files`)
  — so ref storage waits on a ref-identity decision (key by `provider`? by `(provider,
  externalId)`? global vs per-place). The `ExternalPlaceRef` type + Dto + registrations already
  ship for that slice; only the `Place.externalRefs` field is held back.
- **App-graph Koin wiring is the remaining P1 step** (not yet done — deliberately, to land the
  service in isolation first): add `placeServiceCommonModule` + `placeServiceClientModule` to
  the `coreSchemaConfigs` lists (`TenshuClientModule.kt`, `ProxyModule.kt`), `?:`-chain
  `placeServiceProxyImplementationFactory` into `platformImplementationFactory`, and call
  `initPlaceService()` in `platformProxyInitialization` (android/desktop/ios).

### 3.3 Geocoding is a per-platform *capability*, not a synced entity

Reverse-geocoding (latlng → label/external ref) enriches a `Place` at capture time. It is a
proxy-side / platform capability behind a seam, **not** a synced row:

```kotlin
interface ReverseGeocoder { suspend fun resolve(lat: Double, lng: Double): List<PlaceCandidate> }
interface PlaceSearch     { suspend fun search(q: String, near: LatLng?): List<PlaceCandidate> }
```

Per-platform reality (none offers offline, street-accurate geocoding; "native" ≠ "private"):

- **Android** — `android.location.Geocoder` (gate on `isPresent()`; async `GeocodeListener` on
  API 33+); Places SDK is online/billed.
- **iOS** — `CLGeocoder` (deprecated iOS 26 → `MKReverseGeocodingRequest`); `MKLocalSearch`
  for POIs. All server-backed.
- **Web** — **no browser geocoding primitive**; `navigator.geolocation` is latlng only → must
  call a service.
- **Desktop/JVM** — nothing built-in → web API or self-hosted.

**KMP candidate: `jordond/compass`** (MIT, active) is exactly this seam — a device geocoder
wrapping Android `Geocoder` + iOS `CLGeocoder` behind one API, plus pluggable web providers.
Gaps (Nominatim/Photon/MapTiler) are filled with **Ktor + kotlinx.serialization** from
`commonMain`. Maps rendering, *if ever needed*, = **MapLibre Compose** (Android/iOS production;
Desktop/Web experimental; keyless/OSM-clean).

### 3.4 Privacy posture (cheapest-egress-first)

A `Chain` ordered by privacy cost — matches House Rule #1 (relay sees ciphertext) and the
threat model:

1. **On-device / offline** (raw coordinate never leaves device) — a bundled spatial index
   (k-d tree / quadkey over a Foursquare/Overture extract; both storable/redistributable).
   City/area-level; the canonical record keeps exact latlng.
2. **Self-hosted open geocoder** — our own Photon (autocomplete) / Nominatim (accurate
   reverse). Queries stay on our infra; consistent with zero-trust.
3. **Commercial API** — explicit user action only, persisting **at most a foreign-key id**.

**Threat-model impact (House Rule #2):** location becomes a new sensitive asset. Geocoding
egress must go **direct to provider / self-hosted, never via the relay** (the relay must not
learn locations); and the amendment lands with the code. Never store Google/Apple *content*.

---

## 4. `:health-service` — workouts and the no-standard-format problem

The maintainer's instinct is right: **there is no neutral cross-platform health standard.**
Every platform gates data behind a proprietary, entitlement-approved API; the only real
interchange layer is the Garmin-originated file trio (**FIT / TCX / GPX**), and there is **no
production JSON workout standard**. So the design is: **our own provider-neutral record + a
pluggable per-platform import seam** (live APIs on mobile, file import on Desktop/Web).

### 4.1 The common-denominator record (split model)

HealthKit and Health Connect both keep **summary on the session** but **time-series in
separate streams** (and the GPS route as its own series). Copy that split — it lets a
low-fidelity import (bare GPX = a track) and a high-fidelity one (FIT = per-second HR/power/
laps) share one shape without forcing empty fields:

```kotlin
interface Workout : Composite {                            // :health-service — metadata plane
    companion object {
        const val ID = "tenshu/health/workout"
        val Schema = composite(
            id = ID, parent = Composite.Schema,
            traits = listOf(WorkoutDataIdProvider.Schema, Syncable(SyncStrategy.Eager), RealmData.Schema),
            fields = {
                string(Fields.WORKOUT_ID)
                string(Fields.ACTIVITY_TYPE)     // neutral enum: Running/Walking/Cycling/Strength/…
                string(Fields.SOURCE)            // HealthKit | HealthConnect | Fit | Tcx | Gpx | Manual
                timestamp(Fields.START); timestamp(Fields.END)
                int(Fields.DURATION_SEC); int(Fields.TZ_OFFSET_MIN)
                maybe(Fields.TITLE, TString.Schema); maybe(Fields.NOTES, TString.Schema)
                // summary stats — all optional:
                maybe(Fields.DISTANCE_M, TDouble.Schema); maybe(Fields.ACTIVE_ENERGY_KJ, TDouble.Schema)
                maybe(Fields.AVG_HR, TInteger.Schema); maybe(Fields.ASCENT_M, TDouble.Schema)
                // …avg/max speed, steps, cadence, power…
                list(Fields.LAPS, of = WorkoutLap.Schema)
                // route + dense sample streams do NOT live here — see 4.2
            }
        )
    }
}
```

- **Route** (`{time, lat, lng, alt?, hAcc?, vAcc?}[]`) and **typed sample streams**
  (`{metric, unit, points:[{time,value}]}` for HR/cadence/power/speed/elevation) are the
  heavy, dense payload. They ride the **content plane** (ADR 0017 chunked AEAD) as a blob the
  `Workout` metadata references — exactly like File bytes vs File metadata. This keeps the
  synced metadata plane small and fetches the track lazily on open. `WorkoutLap` (small,
  ordered) stays inline.

### 4.2 The import seam (`expect`/`actual`, acquisition only)

Mapping (source model → neutral schema) lives in `commonMain`; only *acquisition* is
platform-specific:

```kotlin
expect interface HealthImporter {
    fun availableSources(): List<HealthSource>
    suspend fun import(request: ImportRequest): List<Workout>   // + route/stream blobs
}
```

- **Android** → **Health Connect** (`androidx.health.connect:connect-client` —
  `ExerciseSessionRecord` + sibling `HeartRateRecord`/`DistanceRecord`/… + `ExerciseRoute`,
  honouring `ExerciseRouteResult.ConsentRequired`). **Google Fit shuts down end-2026 — Health
  Connect is the target, no drop-in Fit-REST replacement.**
- **iOS** → **HealthKit**, read in **native Swift** (not Kotlin cinterop). Our iOS apps are
  native SwiftUI shells over the exported Compose-free `*-shared-logic` seams
  (`TenshuSdk.xcframework`/SKIE; see `drive-ios/` / `control-ios/`), so HealthKit — a native
  Apple framework — is called from Swift, and a Swift `HealthKitImporter` implements the exported
  Kotlin `HealthImporter` (or builds `ImportedHealth` for the shared `HealthImportWriter`),
  mirroring Android's `HealthConnectImporter` on the Swift side of the SDK boundary. (`HKWorkout` /
  `HKWorkoutBuilder` reads; `HKWorkoutRouteQuery`; the direct `HKWorkout` init is deprecated iOS
  17+ → builder/`HKWorkoutActivity` segments.) Gated on exporting the Fit seam into the iOS
  XCFramework + a `fit-ios/` shell — neither exists yet. cinterop would only apply if the read
  had to live in Kotlin `iosMain`, which the native-SwiftUI iOS decision rules out.
- **Desktop/JVM** → **file import** (no live API): sniff FIT vs TCX vs GPX → **Garmin FIT
  Java SDK** (`com.garmin:fit`), **jpx** (GPX), a TCX parser. All normalise into the same
  `Workout` schema.
- **Web** → file upload + JS FIT/GPX decoders. (There is **no Web health API**; even
  Web Bluetooth HR is Chromium-only — file import is the honest web story.)

Optional low-priority sources (Strava/Garmin/Samsung) are OAuth-gated and heavily restricted
(Strava's Nov-2024 terms **ban AI/ML use** and default new apps to single-user) — treat as
later, explicit connectors, not v1.

### 4.3 Privacy

Health data is **GDPR Article 9 special-category**; routes (reveal home/work/patterns) and HR
streams are among the most sensitive payloads on the platform. They encrypt in the same
content plane as file bytes; route/HR metadata must never leak into relay-visible fields.
This warrants a **new sensitive-asset class in the threat model** (House Rule #2), landing
with the first health-service PR. HealthKit-derived data must not be used for ads/AI (Apple
ToS); mirror that as a platform constraint.

---

## 5. `:timeline-service` — the Moment/Trip spine

The app-specific core. Two stored entities; everything else the projector derives.

```kotlin
interface Moment : Composite {                             // Syncable + RealmData
    // id, timestamp, kind (Activity|Visit|Event|Quiet), title,
    // subjectRef: Maybe<SubjectRef> { kind: SubjectKind, id }  — Option A, §5.1 (locked)
    // coverPhoto: Maybe<FileRef>, photos: TList<FileRef>   // Drive file ids (§ below)
    // durationSec: Maybe<Int>, isCurrent: Boolean
}

interface Trip : Composite {                               // Syncable + RealmData
    // id, title, tags, cover: FileRef,
    // membership: ordered day-dates OR moment refs,
    // nestedTripIds: TList<String>   // recursion — Lisbon holds Sintra
}
```

- **`Day`** is derived: group moments by calendar date. `weather`/`movement` come from a
  small optional `DaySummary` (later) + **health-service** daily aggregates (the mock's
  `Movement` steps/distance *is* health data — a nice cross-link, not a new field to invent).
- **`Trip.contents`** (the `DayEntry`/`NestedTrip`/`Collapsed`/`MomentItem` walker the UI
  already consumes) is a **projection** computed from membership + moments, not stored.
- **`TripSuggestion`** — per-device detection; see §5.2.

### 5.1 `subjectRef` — polymorphic ref (**Option A, locked 2026-07-02**)

A `Moment` points at its domain subject (a `Place` for `Visit`, a `Workout` for `Activity`).
**Decision: Option A** — one polymorphic `subjectRef` field. The two options were costed
below; A won for extensibility (the mock's four `MomentKind`s signal more subject kinds
coming — Event→calendar, Media→album — and A absorbs those with no `Moment` schema change)
and a single uniform resolve path in the projector. The DSL cost (a `SubjectRef` composite +
`SubjectKind` constant enum) is one-time boilerplate. Option B kept for the record.

**Option A — one polymorphic `subjectRef` field (CHOSEN)** (`Maybe<SubjectRef>` where `SubjectRef`
= `{ kind: SubjectKind, id: TString }`, `SubjectKind ∈ {Place, Workout, …}`):

- *+ Extensible* — a new subject kind (a future `Event`→calendar-entry, a `Media`→album)
  is a new enum case, **no schema change to `Moment`**. The spine stays closed over its
  subjects.
- *+ One resolve path* — the projector switches on `kind` once; the hydration seam is a
  single `resolveSubject(ref)` dispatch. Uniform empty/absent handling.
- *+ Honest cardinality* — a moment has exactly zero-or-one subject; the type says so.
- *− Heavier in the DSL* — `SubjectRef` is its own `Composite` (a `composite(…)` + `Dto` +
  `Ro` + factory), and `SubjectKind` a `TConstant` enum. More boilerplate than a string
  field. Loosely typed at rest (the `id` is an opaque string until dispatched).
- *− Weaker static guarantees* — nothing at the schema layer stops a `Visit` moment from
  carrying a `Workout` ref; the kind↔subject invariant is enforced in code, not the type.

**Option B — per-kind nullable id fields** (`placeId: Maybe<TString>`,
`workoutId: Maybe<TString>`, …, at most one set):

- *+ Trivially simple* — plain `string`/`maybe` fields, no new composite. Reads self-document
  (`moment.placeId`). Cheapest to author and to query/sort on.
- *+ Directly projectable* — a `serviceDataRelation`-style resolve per field is possible
  later (e.g. `Moment.place` as a dynamic relation into `:place-service`) without a dispatch.
- *− Not extensible without touching `Moment`* — every new subject kind adds a field to the
  spine's schema. The spine has to know all its subjects up front.
- *− Sparse / invariant-by-convention* — N nullable columns where exactly one is set; "at
  most one" is a code rule, not a type rule (same weakness as A's kind mismatch, differently
  shaped).
- *− Cross-kind queries are awkward* — "all moments with any subject" is an OR across N
  fields rather than one `subjectRef != null`.

**Decision: A** (see head of §5.1) — the subject set is expected to grow, and the polymorphic
ref keeps the spine closed over its subjects.

### 5.2 `TripSuggestion` — per-device detection + merge-time refinement

**Locked: detection is per-device; the *pending* suggestion does not sync.** An on-device
detector clusters moments (distance-from-home + time-gap) into a candidate trip; "Read as a
trip" materialises a **`Trip`** (which *does* sync), "Not a trip" discards locally. Rationale:
a suggestion is a cheap, idempotent function of the moments a device already holds — syncing
the ephemeral candidate would just add a reconciliation problem for no gain, and different
devices legitimately hold different moment sets mid-sync.

**The refinement idea builds on machinery we already have.** Because a `Trip` is `Syncable`
and its membership is a collection field, the *materialised* `Trip` is a natural fit for the
existing **custom-merge** path — `ConflictStrategy.Merge` on the `Syncable` trait
(`core/.../trait/Syncable.kt`) dispatching to a `SemanticMerger` keyed by `Trip.ID`
(`:sync-service-common` `SemanticMerger` / `SemanticMergerRegistry`, the same seam
`FolderMerger` uses, with `ExecutionContext.resolveListField` for the lazy membership). So:

- Phone A detects "Lisbon, Apr 12–17" from the moments it has and the user accepts → a
  `Trip` with membership {A's moments} syncs.
- Phone B (which held a few Lisbon moments A didn't — a photo taken on B, a workout logged on
  B) receives that `Trip`. A `TripMerger : SemanticMerger` **re-runs the same detector over
  B's local moments** against the incoming trip's span and *unions* any B-local moments that
  fall inside it into the merged membership — the trip gets *more complete* as device data
  converges, without a second "is this a trip?" prompt.
- This is deliberately the `SemanticMerger` pattern, not a bespoke mechanism: it's the
  documented home for "combine two states of one `Syncable` by domain rules," and
  `sync-merge-strategies.md` (trait-based merge config; renames `SemanticMergerRegistry` →
  `MergerRegistry`) is where a `Trip` merge strategy would be declared. The detector logic
  lives once (`commonMain`) and is reused at both *detect* time and *merge* time.

Caveat to size later: a merger must stay a pure function of `(existing, incoming, context)`
and must not itself trigger IO beyond `resolveList` — re-running the clustering inside the
merge is fine (it reads already-resolved moments), but anything needing a network/service
call would have to move out of the merger. Flagged in §10.

### Photos are Drive files, not a new service

`Moment.photos` / `cover` are **`:file-service` references** (file ids). Byte delivery reuses
Drive's content plane (lazy-on-open, ADR 0017); on-device dominant-colour extraction (the
mock's `Photo.dominantColor`, load-bearing for `personalise()`) happens at import and can
persist as a tiny field on the file or moment. The Timeline app's real repository therefore
depends on `:file-service-client` alongside the three new service clients.

**Expanded into its own doc:** [`timeline-photos.md`](timeline-photos.md) (v0.1, 2026-08-09) sweeps
what is actually left. Two findings amend this section: photo **capture time is already queryable
today** (`File.CreatedAt` is seeded from MediaStore `DATE_TAKEN` on Android, so no EXIF work gates
the time axis), and the join should be **derived at read rather than stored on `Moment`** — the
`photos`/`cover` fields reserved above are deferred indefinitely, since broadcasting a derived join
to every device is the mistake `MomentReconciliationPass` made (`core-deterministic-invalidation.md`
§1). What is genuinely missing is the *query*: there is no range
predicate in the chain DSL and `traverse().sortBy(...)` is un-fused, so the day-bucketed image index
in that doc's §2.2 is the real work.

---

## 6. Where the real `TimelineRepository` lives (closing the loop)

The mock lives in `:timeline-shared-logic`. The **real** `TimelineRepository` is a read-model
**projector**: it reads `Moment`/`Trip` from `:timeline-service`, hydrates subjects via
`:place-service` + `:health-service`, resolves photos via `:file-service`, and assembles the
existing `Day`/`Trip`/`Moment`/`Place`/`ActivityRollup` presentation model the VMs already
consume. It binds behind the **same Koin `single<TimelineRepository>`** — **zero VM/UI
change**, which was the entire point of building UI-v1 over the seam.

This is the concrete first customer of the lazy-vs-materialised pattern
([ADR 0024](../adr/0024-merger-field-resolution.md)):
`Day`/`PlaceVisit`/`ActivityRollup`/`Trip.contents` are all materialised projections
over lazily-stored entities. The projector is where `resolveListField`-style helpers would
first earn their keep outside a `SemanticMerger`.

**As-built (projector slice 1 shipped 2026-07-02).** New module **`:timeline-shared-data`**
(Compose-free, mirrors `:timeline-shared-logic`'s targets), holding:

- **`TimelineProjection`** — the mapping, deliberately **pure Kotlin over lightweight input
  types** (`MomentInput`/`TripInput`/`PlaceInput`/`WorkoutInput`), no Tenshu entity types or
  client, so it unit-tests in isolation. Covers the derivable projections: `days()` groups
  moments by calendar day (newest-first, oldest-within-day) with derived `dateLong`/`dayLabel`;
  `moment()` maps kind-string → `MomentKind` enum + `HH:mm` + `durationSec→min`; `place()`,
  `trip()`; `activityRollups()` aggregates workouts by type. **Green** (`TimelineProjectionTest`).
- **`TimelineProjectionRepository(client)`** — the real `TimelineRepository` over one
  `TenshuClient`: `client.{timeline,place,health}Service.<relation>.select{fields}.flow()` →
  extract `Entity<T>` → input (via `getFieldValue`) → `TimelineProjection`. Compiles on
  desktop/iOS/Android.

**Why the input-type indirection:** client relation reads return `Entity<T>`, not the composite,
so the pure mapping can't take the entity interface directly; the repository does the
`Entity→input` extraction and the projection stays client-free + trivially testable. Slice 1 is
**not yet runtime-exercised** — the Timeline app is still UI-only over the mock with **no
`TenshuClient`**; flipping the DI binding (`MockTimelineRepository` → `TimelineProjectionRepository`)
needs the Timeline app's **first client graph** (a `TenshuClientModule` + platform proxy
connection, mirroring Drive) — the next slice. The projection fills only derivable fields;
subject hydration (place/workout names on moments), place-visit history, trip contents, and the
per-device `TripSuggestion` detector fill in as the deferred service slices land.

**As-built (subject hydration slice shipped 2026-07-06).** Closes the "spine-only → thin
screens" fidelity gap for the parts that need no device APIs — the §1.1 subject-reference
resolution, done entirely in the projector (no schema/wire change):

- **The moment `place` subtitle is now resolved** through the polymorphic `SubjectRef {kind,
  id}` (§5.1 Option A). `MomentInput` gained `subjectKind`/`subjectId`; `TimelineProjection`'s
  one uniform `subtitleResolver(places, workouts)` dispatch fills a `Visit`'s subtitle with its
  `Place.label` and an `Activity`'s with its `Workout.activityType` (a dangling/absent ref →
  `""`). This is the single `resolveSubject(ref)` path §5.1 promised.
- **A `Place`'s visit history is derived** from the `Visit`-moments that point at it:
  `place(p, visits)` computes `visitsCount`, `firstVisit`/`lastVisit`, `avgDuration` and the
  per-visit `PlaceVisit` rows (mock-matching `"1 h 22 min"` / `"Apr 7"` string style, newest-
  first). `PlaceVisit`/`avgDuration` were hand-authored in the mock; now they fall out of the
  moment spine.
- **The reads join across services.** `homeDays()` and `places()` `combine(...)` the moment
  stream with the place + workout streams so the projector has the labels to resolve;
  reactivity across all three services is preserved. `SubjectRef` reads back as an embedded
  composite via `entity.getFieldValue<SubjectRef>(SUBJECT)` (both subfields survive the
  positional-composite wire).
- **Runtime-verified.** `TimelinePipelineIntegrationTest` now asserts the seeded Visit moment
  projects `place == "Blue Bottle"` and the Activity moment `place == "Running"` end-to-end over
  the in-process proxy, and the café's derived `visitsCount`/`history`. `TimelineProjectionTest`
  adds pure coverage (hydration, dangling ref, place derivation). Green on desktop; compiles
  iOS/Android/desktop. **Still deferred:** photos/cover (file refs), weather/`DaySummary`, trip
  contents, and the per-device `TripSuggestion` detector.

---

### 6.1 As built — the web twin (`web-app-suite.md` Wave 2, Timeline run A, 2026-10-23)

The browser has its own reader of the same relations: `Timeline` + `TimelineBook.kt` in
`:tenshu-sdk-js`, bridged as `timeline.*` (`web/sdk/src/timeline.ts`, `apex/.../gateway/timeline.ts`).
`:timeline-shared-data` is **not** compiled to JS. The decisions later runs build on:

- **What is shared, what is copied.** Reconciliation (`MomentReconciler`), trip suggestion
  (`TripSuggester`) and merge roots (`PlaceMerge.resolveRoot`) are the same code on both sides. The
  joins are copied and pinned: merge folding, subject hydration, the confirmed-role rule (D9), day
  grouping, the suggestion's date range (`TimelineBookMappingTest`, `TimelineWebBridgeTest`), and the
  polyline decoder (⚠⚠ one golden vector in `MovementTracePolylineGoldenTest` *and*
  `TimelineBookMappingTest` — change the codec and both fail).
- ⚠⚠ **The web day list is data-level.** One row per stored moment, reconciled and merge-folded,
  but **without** `TimelineProjection.days`' row folds (consecutive-visit merge, in-ride station-stop
  collapse, endpoints). ⚠ Decided 2026-10-24 (`timeline-app.md` §Web scope 4a): that projection is
  extracted into a JS-compiled module in its own PR before Timeline run B; until then, unfolded rows.
- **Days are `Intl`, not `kotlinx-datetime`.** Kotlin/JS ships no zone database; the caller passes an
  IANA zone (`""` = the browser's own) and an unknown one is `error_timeline_invalid_zone`.
- **`PlacePlugin` / `HealthPlugin` run no `init*Service`.** Reads need only the generic relation
  maintainer; `initPlaceService` would start `PlaceReconciler.reconcileAll()` and `initHealthService`
  the rollup backfills — background work a tab does not do (`NoBackgroundWorkInATabTest`).
  ⚠ The feed re-reads its keyed workout lookups on `WorkoutChanges` (a save/delete counter):
  no relation it streams re-emits when a `Workout` lands, so without it an `Activity` moment that
  synced before its workout stayed unresolved in the push. With
  `inferPlaceRoles = false` this means a tab **syncs** place identity, merges and roles and never
  **computes** them.
- **Failures are failures** (the Contacts rule): a failed relation read fails the call, never an
  empty feed; a missing id is `null` inside `ok: true`. Accept / dismiss take the **suggestion id the
  UI showed** and refuse a stale one (`error_timeline_suggestion_gone`); dismissal is per tab.
- ⚠ **Suspected native defect, found writing the twin, not fixed and not verified by a test:**
  `TimelineProjectionRepository.mergesFlowFresh()` / `placeRolesFlowFresh()` start with
  `onStart { emit(empty) }`, so every one-shot `…Fresh().first()` sees **no merges and no roles**:
  `place()` never follows a folded id and shows no role, `mergedPlaceCount()` is always 0 (so
  *Unmerge* never shows), and `dismiss`/`acceptTripSuggestion` re-derive the suggestion without the
  confirmed home the feed used. The web twin reads every source to its first real emission instead.

## 7. Proposed phasing (each independently shippable)

1. **P1 — `:place-service` minimal.** ✅ **Scaffold shipped 2026-07-02** (5 modules, `Place` +
   `RecordPlace` + relation maintainer + green `RecordPlaceUnitTest`; see §3.2 as-built).
   Remaining P1: externalRefs storage, app-graph Koin wiring, geocoding (P4), threat-model location
   asset.
2. **P2 — `:health-service` minimal.** Neutral `Workout` metadata + `WorkoutLap`; route/streams
   on the content plane; **file-import `actual` first** (FIT/GPX on JVM — testable without a
   device). Threat-model: health asset.
3. **P3 — `:timeline-service`.** `Moment` + `Trip`; the real `TimelineRepository` projector
   (the loop-closer). At this point the app runs on real, synced, E2E data.
3b. **P3b — bridge: light up the projector over a seeded store.** The projector (§6, shipped)
   is *not runtime-exercised* — the app is still UI-only over the mock with **no `TenshuClient`**.
   This slice closes that: (a) a **`TimelineSeeder`** that writes a *service-shaped* fixture into
   the three services via the record ops (`recordPlace`/`recordWorkout`/`recordMoment`/
   `recordTrip`) — deliberately not the UI-model mock verbatim (converting `"09:14"` strings →
   epoch timestamps etc. is lossy); (b) an **in-process `TenshuClient`** for the Timeline app
   (host the 3 services' proxy in-process — no inter-app IPC/pairing, unlike Drive→Control — the
   pragmatic first client graph); (c) **flip the Koin binding** `MockTimelineRepository` →
   `TimelineProjectionRepository`, seed on first run. **Fidelity caveat:** the spine-only
   entities drop photos / place-names-on-moments / weather / trip contents, so the seeded+
   projected screens are *thinner* than today's hand-built mock until those deferred slices land
   (photos = file refs, subject hydration, place-visit history, `DaySummary`). So P3b proves the
   **pipe** end-to-end (seed → services → sync relation → projector → UI), not pixel parity.
   Verify with an integration test: seed → drive `TimelineProjectionRepository` over the
   in-process proxy → assert the projected `homeDays()`/`places()`/`activities()`.
   **As-built (P3b part 1 shipped 2026-07-02):** `TimelineSeeder` (service-shaped fixture via
   the record ops) + `TimelinePipelineIntegrationTest` — stands up all three services in one
   in-process `TestProxy` + a real `DefaultTenshuClient`, seeds, and drives
   `TimelineProjectionRepository` end-to-end (seed → services → relation maintainers → client
   `.flow()` → projector → UI model). **Green** — the runtime exercise the projector's unit tests
   could not cover; the client `.flow()` path works in-process.
   **As-built (P3b part 2 shipped 2026-07-02):** `InProcessTimelineProxy` — a production
   in-process proxy hosting the three services over an in-memory store (the production twin of
   `TestProxy`: `SchemaRegistry` → hand-wired `TContext` → `DatabaseImpl`/`InMemoryObjectStore`
   → `ClientRegistry` → `DefaultTenshuClient`), exposing a seeded `TimelineProjectionRepository`.
   The Koin binding flips in `timelineRealRepositoryModule()` (`MockTimelineRepository` kept for
   one-line revert). **`:timeline-desktop:run` boots clean** — DI graph resolves, the proxy
   constructs (init-order correct), the seeder runs, no crash — so it's *runtime-verified to
   boot*, not just compiled. ⚠ Residual: the seeded screens can't be *visually* confirmed
   headlessly (screenshots blocked) — a human/on-device pass confirms the rendered Chapters.
   No inter-app IPC / sync / pairing (a synced Timeline is later).
   **As-built (core-common helper extraction, 2026-07-05):** the generic proxy-layer assembly
   graduated out of the app data module into a reusable `InProcessProxy` (`:core-common`,
   `org.tenshu.core.common.proxy`) — the production twin of `TestProxy`, taking a
   `schemaRegistryFactory`, an `OperationImplementationFactory`, `serviceScope`, `clientId` and
   an `initialization` hook, and exposing `tContext`/`schemaRegistry`/`client`/`executionContext`.
   `InProcessTimelineProxy` now supplies only the Timeline-specific wiring (the three services'
   schema modules + proxy op factories + `initXService()`) and the `repository` seam + first-run
   seeding. Closes the "real duplication with `TestProxy`" residual; `TestProxy` itself keeps its
   test-only extras (gateway, `FileDatabase`, update listener) and is a candidate to re-home onto
   `InProcessProxy` separately.
4. **P4 — geocoding seam** (`jordond/compass` device tier + Ktor open endpoints; tiered
   privacy chain). Enriches `Place` labels/externalRefs. **Execution plan (2026-07-16):**
   [`place-geocoding.md`](place-geocoding.md) — phases G1–G5; deviations recorded there
   (direct platform impls instead of compass; app-side not proxy-side; single-result
   `resolve`; `externalRefs` still parked).
5. **P5 — live health import** (Health Connect + HealthKit `actual`s). **P5.1 shipped** (Fit
   `fit-app.md` §9.4, 2026-07-06): the portable `HealthImporter` seam (§4.2) + `ImportedHealth` +
   shared `HealthImportWriter` + in-process `ReferenceHealthImporter` (seeding now flows through
   the import contract) + threat-model asset **A34** (GDPR Art. 9). Bound via DI, not `expect`/`actual`.
   **P5.2 shipped** (Fit §9.5, 2026-07-06): the **Android `HealthConnectImporter`** (`connect-client`
   1.1.0) — the first real capture — bound behind the seam with the platform permission contract;
   reference fallback when HC is absent (compiles + APK assembles; on-device reads pending; `minSdk`
   26). **P5.3 shipped** (Fit §9.6): **desktop/JVM file import** — `ActivityFileParser` (GPX/TCX →
   `ImportedHealth` via `javax.xml`, no deps) + `FileHealthImporter` over the shared writer, fully
   unit-tested (the one importer verifiable without a device). iOS HealthKit (native Swift) + web
   (JS) FIT/GPX decoders + the desktop picker UI + binary FIT are the remaining pieces.
6. **P6 — detectors** (TripSuggestion clustering; Visit auto-capture from location).

P1–P3b make the app real over the existing UI. P4–P6 are enrichment.

---

## 8. Naming (locked 2026-07-02)

- **`:timeline-service`** for the spine. (The brief said "TripService"; broadened because
  **`Moment` is the atom** and `Trip`/`Day`/`Suggestion` are aggregations over it.)
- **`:health-service`** (not `:activity-service`) — the umbrella (HealthKit/Health Connect are
  both "health"), future-proof for the Tenshu Fit app + sleep/vitals. `ActivityRollup` /
  `MomentKind.Activity` stay as UI-layer *views*, not the service name.
- **`:place-service`** (singular, matching `:file-service`/`:notification-service`).

---

## 9. Reuse / non-goals

- **No new photo service** — Drive files + content plane.
- **No maps rendering in v1** — geocoding ≠ a map view; MapLibre Compose only if a map screen
  lands (mock's "See map of all places" is deferred, `timeline-app.md` §10).
- **No commercial-provider content storage** — ToS + privacy; open data only in the E2E store.

## 10. Open questions

**Decided in v0.2:** naming (§8, locked); TripSuggestion sync (§5.2, per-device + merge-time
refinement). `subjectRef` modelling (§5.1, Option A — polymorphic ref).

Still open:

- **`Day` envelope** — is `DaySummary` (weather/movement) worth a stored entity in v1, or
  derive-only until a weather source exists?
- **`TripMerger` purity** (§5.2) — **RESOLVED (I5a, `timeline-ingestion.md` §7).** The pure
  `TripDetector` (`:timeline-service-proxy` `commonMain`) takes **home as an explicit parameter**,
  so the distance-from-home heuristic's one external input is supplied by the *caller* rather than
  read inside the merger — the detector stays a pure function of `(moments, home, config)` and runs
  inside a `SemanticMerger.merge` with no IO beyond `resolveList`. The remaining I5b wiring question
  is narrow: the *merger's caller* must obtain home from **local state** (a locally-stored
  setting/entity resolvable at merge time), never a service/network read. Ties into
  `sync-merge-strategies.md` (trait-based merge config).
- **GERS extract logistics** — bundling / refreshing an Overture/Foursquare extract for the
  offline tier is an ops concern (dataset size, update cadence) not yet sized.
- **Health blob addressing** — reuse the exact File content-plane ops, or a health-specific
  variant? (Leaning reuse — the streaming-preview pull ops are generic-triple addressed.)

## 11. Version

Timeline-services v0.2 · sketch 2026-07-02 (v0.2 folds maintainer review: names locked,
per-device suggestions + merge refinement, subjectRef costed-but-deferred). Companion to
[`timeline-app.md`](timeline-app.md) (UI-v1). No code. Research provenance: Tenshu service
anatomy (`:notification-service`); Overture GERS / Foursquare OS Places / OSM licensing +
`jordond/compass` (POI); HealthKit / Health Connect / FIT-TCX-GPX (health).
