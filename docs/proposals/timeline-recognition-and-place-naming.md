# Timeline Phase 2 — sharper movement recognition & place naming

**Status:** v0.5 — **Phase 2 shipped (2026-07-22); §8 (#5 place identity) opened + P4a/P4b SHIPPED
(2026-07-30); §10.12 time-and-tag corroboration SHIPPED 2026-08-01 (as-built §10.16 — the §12
handover discharged, no schema/wire/format change); §9 (cross-device identity) designed, not built.**
A third recognition issue landed from the device: one venue becomes **several `Place` rows** (the office
listed twice after a rename). **§8** = the single-device half — P4a (F1 nearest-place snapping) + P4b (F2 venue-shaped
Wi-Fi fingerprint) shipped. **§9** = the cross-device half, opened by maintainer review: ⚠⚠ `Place`
is last-writer-wins and the name-protection guard is local-only, so a peer that captures before it
pulls a rename **erases the name**; ⚠⚠ `MomentMerger`/`TripMerger` have **never been registered in
production** although Timeline now syncs through Control. **§10** settles *where* identity is decided
— **in the proxy, not in each app's projector** — after the maintainer's steer; ⚠⚠ a **third**
deferral (reconcile-at-read, I6c) turns out to rest on the same expired "standalone-no-sync" premise.
Order: **P4d** (schema + merge plane) ✅ **SHIPPED** (fmt **v16**, `PlaceService` v2, as-built §10.8 —
⚠⚠ the merge pointer could **not** be a `Place` field: no type that rides as an operation parameter
can gain one until E2's nested reshapes land, so it became its own `PlaceMerge` row) → **P4e** (the
proxy reconciliation pass) → **P4f** ✅ (clients: reader, write-behind fingerprint store, merge/unmerge UX;
⚠ the read-time reconcile is **kept** by decision, §10.15) → **P4g** (desktop capture — **designed,
§11**; ⚠⚠ the "no location permission" premise is FALSE on macOS). P1 (#3a T0 tuning), P2
(#3b rail availability), P3b (4-A Photon venue ranking) and P3c (4-D opt-in
WiFi fingerprint) are on master; the trains→transit flip and the place picker were
on-device-verified. **P3a (4-C `labelSource`) built on `timeline/place-labelsource`** — see the
§4.4 as-built, which now also carries **4-B `accuracyM`** (§4.3) in the same v9 bump. Phase 1
(day-header layout + consecutive same-place visit merge) shipped
(`timeline/home-polish`). This doc designed Phase 2: the two *recognition* issues
that Phase 1 deferred — **trains misread as "Drive"** and **places named by street, not venue** —
plus a self-built, opt-in **WiFi place fingerprint**. Companion to
[`timeline-movement-capture.md`](timeline-movement-capture.md) (the capture + transit-tier
track, §13) and [`place-geocoding.md`](place-geocoding.md) (the P4 geocoding pipeline, now
partly superseded by that doc's §11.3 P3 — see §4.1). Docs-first, per the repo convention.

**Trigger (2026-07-20, real ColorOS device).** The overnight test surfaced four issues; the
two visual ones are fixed (Phase 1). The two here are recognition-quality:

- **#3 — a 116 km / 59 min train reads as "Drive · 116.3 km".** Some long trains *are* caught
  (two ~145 km rides classified `transit`), but a fast, near-non-stop intercity train slips
  through.
- **#4 — places show the street, not the venue.** One stay resolved to
  the street name and to the wrong neighbouring building instead of the
  office the user was actually in.

---

## 1. Scope

**In scope:**
- **#3a** — tune the T0 kinematic classifier so a fast, straight, long trip that never fully
  stops gets a `transit` hint it currently cannot earn.
- **#3b** — make the T1 rail-map-match sweep *actually run* on real trips: diagnosability,
  verifying live catalogue + `transit`-layer coverage, and a better trigger.
- **#4** — improve venue naming (Photon query refinement + confidence-aware coarsening + a
  `labelSource` marker so improvements reach already-mislabelled places) and add an **opt-in,
  device-local WiFi fingerprint** to re-recognise places the user has already named.

**Out of scope (noted, has a home elsewhere):**
- Auto-downloading a tile corridor around trips, and LRU eviction of offline regions — the
  user asked; neither is designed today (`maps-region-delivery.md`: streaming is the deliberate
  substitute for auto-download; the manage-offline-maps screen *is* built — Control → Settings
  → **Offline maps**). §3.4 explains why rail recognition does **not** need them.
- Fused/Play-Services location (breaks the de-Googled constraint) and any raise of the ±100 m
  NETWORK-provider precision floor by means other than WiFi (§4.5).
- Google Places / Foursquare imagery/naming (`place-imagery.md` Tier 2; licence-gated).

---

## 2. #3a — T0 kinematic tuning: the fast non-stop train

### 2.1 As-is — why the 116 km train stays `drive`

Movement classification runs at capture time: `MovementSegmenter.classifyMode` buckets the
**median leg speed** (`< 2.5 / 2.5–4 / 4–8 / ≥ 8` m/s → walk/run/cycle/**drive**), then hands a
`drive` baseline to `refineWithTransitKinematics` (T0, `TransitKinematics.kt`). T0 is
**promotion-only** (never demotes) and promotes `drive → transit` on exactly two paths:

- **`speedSaysRail`** — 85th-percentile cruise `> RAIL_ONLY_SPEED_MPS = 55.0` m/s (~198 km/h).
- **`shapeSaysRail`** — `turnDensity ≤ 25 °/km` **and** `wellSpacedStopCount ≥ 2` **and**
  `cruise ≥ SHAPE_RULE_MIN_CRUISE_MPS = 20` m/s.

The 116 km / 59 min ride averages ~32.8 m/s (~118 km/h). It fails **both**:
- cruise (~33 m/s) is well under 55 m/s → `speedSaysRail` false;
- `wellSpacedStopCount` needs **≥ 2 full stops** ≥ 1500 m apart, and `STOP_SPEED_MPS = 1.0` m/s
  requires an essentially complete halt. A "mid-trip speed reduction" to, say, 15 m/s is **not a
  stop**. A fast express with zero or one full halt has `wellSpacedStopCount < 2` →
  `shapeSaysRail` false.

So the decisive failing conjunct is the **station-stop requirement**: T0 can only see rail via
"very fast" (198 km/h) or "has ≥2 real stops." A straight, fast, *non-stop* intercity train sits
in the blind spot between them. (This is exactly the "Autobahn overlaps regional-rail speed"
tension §13.4 flags — which is why T0 leans on *straightness*, not speed.)

### 2.2 Design — a third path: "straight + fast + long," no stop required

Add a third promotion path to `refineWithTransitKinematics`, `straightFastLongSaysRail`, that
recognises the express's signature — extreme straightness sustained over a long distance at
rail speed — **without** requiring a stop:

```
straightFastLongSaysRail =
    turnDensity ≤ STRAIGHT_RAIL_DEG_PER_KM      // 12 °/km — much straighter than the 25 shape rule
 && cruise      ≥ FAST_RAIL_CRUISE_MPS          // 28 m/s ≈ 100 km/h
 && tripDistanceM ≥ LONG_RAIL_TRIP_M            // 150 km — the "definitely a train" distance
```

Strength **0.5** — deliberately *below* the shape rule's 0.6: this is a hint, not a verdict.

**Why 150 km, and why that keeps it safe** (as-built, P1). A straight fast run of
30–50 km is genuinely ambiguous — car, coach, or train — so T0 must **not** guess there; it stays
`drive` and is left to the T1 rail map-match (this is the maintainer's own steer: *"300 km is
definitely a high-speed train; 30–50 km could be anything → rail geometry"*, and it is exactly
what the existing `motorwayDrivingWithNoStopsStaysDrive` test — a 30 km straight fast run — pins).
No road runs near-arrow-straight (≤ 12 °/km) at rail speed for **150 km**: over that distance a
road accrues far more heading change following terrain and connecting towns. So:
1. **Straightness is tight (≤ 12 °/km)** *and* the distance gate is high (150 km) — together they
   admit essentially only rail. The 30 km motorway commute and the reported **116 km** case both
   stay `drive` at T0.
2. **T1 self-corrects any residual false positive.** The rail-map sweep (§3) *demotes* a `transit`
   trace back to `drive` when the path does **not** lie on rail (`RailRefinement.refine`, the
   `baseline == TRANSIT && !onRail → drive` branch): **be a little eager at T0, let T1 be the
   judge.**

Net effect: T0 gains a **coverage-independent fast-path for the unambiguous long-haul express**
(≥ 150 km, arrow-straight, fast → `transit` at capture, no T1 needed). The reported 116 km train
sits in the ambiguous band and is **#3b's job** (T1 rail map-match), consistent with the steer
above. The tuning wants T3-corpus calibration (§6).

### 2.3 Constants (defaults; tunable)

| New constant | Default | Rationale |
|---|---|---|
| `STRAIGHT_RAIL_DEG_PER_KM` | `12.0` | Below motorway turn density, above mainline rail |
| `FAST_RAIL_CRUISE_MPS` | `28.0` | ~100 km/h — excludes urban/short drives |
| `LONG_RAIL_TRIP_M` | `150_000` | The "definitely a train" distance; the ambiguous 30–50 km band is T1's job |

Pure `commonMain`, unit-testable on synthetic traces (as-built: a ≥ 150 km straight-fast run
promotes; an ambiguous-band 119 km one and a long-but-slow one stay `drive`; the 30 km motorway
fixture stays `drive`). **`MOVEMENT_CLASSIFIER_VERSION` is *not* bumped** (as-built correction):
the T1 sweep's `needsRefinement` only re-examines traces with `version < 4`
(`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED`), so bumping the base version to a new value would
*exclude* new T0-classified traces from the T1 sweep — the opposite of what we want. The rule
instead improves what version-3 T0 classification does in place; new captures still stamp 3 and
stay sweepable by T1. Mode is a plain string in `MovementTrace.mode` → no positional-composite /
format change (§13.5), so HR #9 does not fire (additive string values ride the current version).
Note this means the improvement applies to **new captures only**; an already-stored `drive` trace
is not re-run through T0 (T0 runs at capture, not in a sweep) — the T1 sweep (§3) is the path that
re-classifies existing traces.

---

## 3. #3b — T1 rail-map availability: make the sweep run

### 3.1 As-is — the sweep already streams; the gate is *coverage*, not *download*

The rail sweep (`RailRefinementSweep`, run once per launch via `RailRefinementTrigger`) reads
DRIVE/TRANSIT traces below v4, decodes each polyline, and calls
`client.mapRegionService.railLinesIn(bbox)`. The proxy impl (`RailLinesInImplementation`)
**deliberately does not consult installed state** — it resolves a covering region from the
*catalogue* and serves tiles via `StreamingRegionSource` (installed bundle if present, else CDN
range-fetch + cache). So **no pre-downloaded region is required**; a traveller's trip is
answerable by streaming. The result feeds `RailEvidence`:

- `Surveyed(lines)` → `RailMatcher.match`; `exclusiveCoverage ≥ 0.8` (heavy rail) → `transit`;
  `coverage ≥ 0.9` + corroboration → `transit` (tram); else confirm/​demote.
- **`Unavailable`** (op threw, failed, or returned empty) → **no-op, leave un-stamped, retry
  later.** This guard is load-bearing: a real train outside coverage is *never demoted* — but the
  corollary is it is also *never promoted*. It stays `drive` indefinitely.

Crucially, **T1 by pure coverage needs no stop** — `match` is geometry-only. So T1 is exactly the
tier that fixes the §2.1 false negative, *if* it can obtain rail geometry.

### 3.2 The real gap — coverage may be defined but not *live*, and the sweep is blind

The catalogue (`deploy/tiles/regions.json`) **does** define coverage for the test route:
a state-level region (`de-bayern`, whose bbox contains both ends of the route) **and** a `global`
region (`-180,-90,180,90`). `resolveRegion(<route start>)` should
therefore return `de-bayern`, not null. Yet on-device the train stayed `drive` and **no
`rail_sweep` breadcrumb appeared** at all. The candidate causes, none observable today:

1. **The live CDN may not carry `de-bayern`/`global` yet.** `regions.json` is the *source*
   catalogue; it carries `global`, and `deploy/tiles/` has a publish runbook, but publishing the planet (or
   the state-level archive with a `transit` layer at `RAIL_ZOOM = 14`) is a heavy op that may not have run
   against `tiles.service.tenshu.app`. If the deployed catalogue still lists only the Italian
   test regions, `resolveRegion(<route start>)` → null → `Unavailable`.
2. **The covering region's PMTiles may lack `transit` at z14** for that bbox (the demo/v4
   archive generations differ — the basemap memory notes two coexisting generations, one without
   `transit`). Then `railLinesIn` returns empty → `Unavailable`.
3. **The range-fetch may fail** (Control reaching the tile host), also → `Unavailable`.
4. **The sweep early-returns `SweepResult(0,0,0)` before its breadcrumb** when there are no
   candidates — so "no log" is ambiguous between "nothing to refine," "read failed," and "ran but
   everything Unavailable."

We cannot fix what we cannot see. So #3b is **diagnosability first, then coverage, then trigger.**

### 3.3 Design

**(a) Diagnosability (do first, cheap).** Emit a `Diagnostics` breadcrumb on *every* sweep run,
**before** any early return: candidate count, and per trace a one-line
`rail_sweep <traceId> evidence=<Unavailable|Surveyed(n)> coverage=<x> excl=<y> → <mode>`. Add a
matching proxy-side breadcrumb in `RailLinesInImplementation` distinguishing *no covering region*
(`resolveRegion==null`), *decompressor null*, *empty transit features*, and *fetch failure* —
these four all collapse to `Unavailable` app-side today, and the distinction is exactly what we
need to know. This turns the next on-device run into a decisive test of §3.2's four causes.

**(b) Coverage — verify + publish (ops, gated by (a)).** With the breadcrumbs, confirm on-device
whether `de-bayern`/`global` resolve and carry `transit` at z14. If not, publish them to the live
CDN per the `deploy/tiles/` publish runbook. This is an ops/infra action (`deploy/tiles/`), not app code —
`maps-region-delivery.md` owns it; this doc's contribution is the diagnosis that makes it
actionable. **This is likely the whole of the on-device fix** for the reported train, given the
streaming path already works.

**(c) Trigger — run more than once per launch.** Today the sweep fires once at app start, limited
to 25 traces, gated on capture enabled. Improvements:
- Also run the sweep **after a `Move` is written** (trip close) via the existing capture write
  path, so a just-completed train is refined promptly rather than next launch.
- Keep the `Unavailable`-leaves-un-stamped retry (already correct) so traces recorded before
  coverage went live get picked up once it does.
- Page beyond 25 when the backlog is larger (bounded, `bounded-store-cache.md` heap posture).

### 3.4 Why this needs neither auto-download nor LRU eviction

The user asked about auto-downloading regions and auto-evicting unused ones. Rail recognition
needs **neither**: `railLinesIn` streams uninstalled regions by range-fetch, so T1 works over a
network connection without any download. The offline-maps *management* surface the user wanted
(add/delete/sizes) already exists (Control → Settings → **Offline maps**). Auto-download-a-
corridor and LRU eviction remain genuinely unbuilt, but they are a **Maps** concern
(`maps-region-delivery.md` open items Q2 + the noted missing size-cap), independent of Timeline
recognition, and are best specced there rather than bundled here. Noted, not scoped.

---

## 4. #4 — Place naming: venue over street, honest when unsure, and WiFi

### 4.1 As-is — Photon POI naming shipped, but blunt

The venue-naming fix is **partly already built** (`timeline-movement-capture.md` §11.3 **P3**,
which supersedes `place-geocoding.md`'s "Android = device-tier-only" claim — both that doc and
the `TimelineAndroidModule` KDoc are stale on this point):
- A **proxy-mediated Photon geocoder** (`PhotonGeocoder` on Control, `ReverseGeocode` op) runs
  the network egress so Timeline keeps its stripped `INTERNET` (HR #1; threat-model A35).
- On Android, `ChainedReverseGeocoder` tries `ProxyReverseGeocoder` (Photon, POI-aware) **first**,
  then `AndroidReverseGeocoder` (address/street) as fallback.
- The enricher (`PlaceLabelEnricher`) writes a label **only when the stored one is blank** (D3),
  which is what makes manual rename permanent.

Two independent causes remain for "street, not venue":
1. **The Photon query is blunt.** It calls `/reverse?lat&lon&limit=1` — **no `radius`, no
   `osm_tag`/type filter, no ranking.** `labelOf()` takes `name → street → city` of the single
   nearest feature. When the nearest feature is the road, the road wins over the office 30 m away.
2. **The coordinate can be ~100 m off indoors.** The dwell centroid is accuracy-weighted (P2) but
   still floored by the NETWORK provider's ±100 m indoors (GPS can't see sky). Photon is then
   asked about the wrong spot and helpfully names the neighbouring building.

And a structural blocker: **D3 blank-only means improving the query does nothing for places
already mislabelled** — a street label is non-blank, so a better geocoder never revisits
it. Fixing #4 for *existing* places needs a way to distinguish a geocoded label from a
user-authored one.

### 4.2 4-A — Photon query refinement (prefer a venue within a small radius)

Localised to `PhotonGeocoder.kt` (proxy) and `PhotonReverseGeocoder.kt` (common/desktop):
- Request `limit = 5` (and pass Photon's `radius` where supported).
- Rank candidates in `labelOf()`: **prefer a feature carrying a POI `name` with an `osm_key` in
  {amenity, shop, office, tourism, leisure, healthcare, …} within `VENUE_PREFER_RADIUS_M` (≈ 60 m)
  over a bare `highway`/street feature**, even when the street is geometrically nearer. Fall back
  to today's nearest-feature behaviour when no POI qualifies.
- No downstream change — still returns one `String`/`GeocodeResult`.

This directly raises "which of the nearby features do we name" from "nearest" to "nearest
*venue*," which is the common indoor case.

### 4.3 4-B — confidence-aware coarsening (don't confidently name the wrong venue)

> **Built (P3a as-built in §4.4).** As-shipped differs from this design in one good way: the
> coarsening is done **consumer-side in the enricher** (pick `result.city` when
> `accuracyM > 80 m`), so **no** `resolve(…, accuracyM)` signature change was needed — the
> `accuracyM` is stored on `Place` and the dwell value is the *median* of the reported per-fix
> accuracies. The rest of this section is the original design rationale.

When the fix is poor (wide accuracy), a precise venue name is *more* wrong than a coarse one.
Thread the dwell's accuracy into naming:
- `geocodeResultOf` already degrades `name → street → locality`. Give `resolve(lat, lng)` an
  `accuracyM` input; when accuracy is worse than `COARSE_LABEL_ACCURACY_M` (≈ 80 m), skip the
  venue tier and return the **area/city** label instead of a confidently-wrong POI.
- The accuracy must reach the enricher. Cleanest: store the dwell's representative `accuracyM`
  on the `Place` row (a positional-composite append like G4's `city`/`countryCode` — **peer-
  breaking, needs an HR #9 lockstep bump**). If we'd rather avoid the schema bump now, pass
  accuracy inline on the capture-time enrich path and accept that backfill of old rows can't
  coarsen. **Recommendation:** append `accuracyM` to `Place` (it's a genuinely useful field and
  the pre-release window makes the bump cheap), and take the lockstep bump in the same PR.

### 4.4 4-C — a `labelSource` marker (so improvements reach mislabelled places)

Add `labelSource ∈ {geocoded, user}` to `Place` (`place-geocoding.md` §6 deferred this). Then:
- Rename sets `labelSource = user`; the enricher's D3 rule becomes **"write if blank *or*
  `labelSource == geocoded`"** — so a better query (4-A) or a better fix can *improve* an
  already-geocoded street label to a venue, while a **user** label is still never touched.
- This is what makes 4-A/4-B/4-D actually reach the street-labelled places already in the
  store, instead of only new captures. Same positional-composite append + lockstep bump as 4-B
  (do them together).

**As-built (P3a, `timeline/place-labelsource`).** Shipped `labelSource` as a plain string
(`LabelSource.{GEOCODED, USER}`, `geocoded`/empty = the improvable default) appended to `Place`
— `TENSHU_STORAGE_FORMAT_VERSION` **8 → 9**, HR #9 lockstep (⚠ this read "a deployed relay must
move with the bump — its 426 gate — so this PR is merge-gated on the coordinated relay redeploy"
until 2026-08-11. **No relay redeploy was or is owed**: E2e (2026-07-26) took payload versions off
the relay, and §11 of this same doc already says so — the two halves had disagreed since). Wiring:
- **Enricher (4-C):** a place is a candidate when `label.isBlank()` **or** `!isUser(labelSource)`;
  the re-read guard now skips only a `user` row (a geocoded non-blank label *is* improved). A
  successfully re-geocoded id is memoised **once per session** (a fresh app start is when a
  shipped 4-A refinement gets its one pass over old rows — no per-backfill re-query loop). Its
  writes are always `labelSource = geocoded`.
- **Rename / picker → `user`** (`renamePlace`, which the place picker routes through).

**⚠ Load-bearing find — the blank-clobber, and why 4-C also guards the *write* path.** `recordPlace`
does `saveData(place)`, which **replaces** the `placeId`-keyed row (that is why the enricher already
had a careful re-read guard). But `TimelineImportWriter.write` recorded every captured/imported
place *unconditionally* with its (blank) label — so a **repeat visit to a renamed place, and every
Wi-Fi-recognised re-visit (§4.5, which deliberately folds onto the *named* place), would erase the
name.** P3c would thus have *wiped* the very name it exists to reuse — a latent bug, un-field-tested
(the drift that causes mislabelling normally lands repeat captures on a slightly different grid id,
masking it; the Wi-Fi override removes that mask and makes the clobber deterministic). Fixed here:
`TimelineImportWriter` reads existing rows once and **never erases or downgrades a name** — it skips
the write when the incoming label is blank over a non-blank row, or when the stored row is `user`.
Covered by `aRepeatVisitNeverClobbersAUserRenamedPlace` (`TimelinePipelineIntegrationTest`) +
`improvesAnAlreadyGeocodedLabel` / `neverOverwritesOrRequeriesAUserLabel` (`PlaceLabelEnricherTest`).

**4-B `accuracyM` — built in the same v9 bump (§4.3 as-built).** Bundled here rather than deferred,
since P3a already takes the bump and a second field is free once a bump is happening at all. `Place` gains `accuracyM`; the dwell's representative accuracy (the **median of the reported
per-fix accuracies**, computed once in `toCentroidVisit`) threads `CapturedVisit` → `ImportedPlace`
→ `Place`. The coarsening itself is a **consumer-side choice in the enricher** — `GeocodeResult`
already carries the locality tier, so when `accuracyM > COARSE_LABEL_ACCURACY_M` (80 m) the enricher
writes `result.city` instead of the venue. **No geocoder or op signature changed** (the draft's
feared `resolve(…, accuracyM)` fan-out across three geocoders was avoided). `0` (no fix reported
accuracy) never coarsens — we only coarsen when we *know* the fix is poor. The Android source
already fills `CapturedFix.accuracyM` from `Location.accuracy`, so the chain is live end-to-end.
Covered by `coarsensToTheCityWhenTheDwellAccuracyIsPoor` / `keepsThePreciseVenueWhenTheDwellAccuracyIsGood`
(`PlaceLabelEnricherTest`) + `representativeAccuracyIsTheMedianOfReportedFixes` (`CentroidTest`).

### 4.5 4-D — opt-in, device-local WiFi place fingerprint

The ±100 m floor (§4.1 cause 2) is the deep root cause, and fused location is off the table
(de-Googled). A **self-built WiFi fingerprint** sidesteps it: remember which network the user was
on at a place they named, and next time that network is seen, reuse that place — *coordinates
never enter the decision*. This is standard positioning practice (SSID/BSSID), in its
privacy-preserving, on-device, per-user form.

- **Signal:** the **connected** AP's SSID/BSSID at Dwell-confirmation
  (`LocationCaptureEngine.run()` `Dwell(ongoing=true)` — the device is stationary there). Reading
  the *connected* AP rides existing `ACCESS_FINE_LOCATION`; it needs **no new permission and no
  `INTERNET`** (HR #1 intact). (Scanning *nearby* APs would need `NEARBY_WIFI_DEVICES` on 13+ —
  **out of scope**; connected-AP-only is enough for "am I at a place I know.")
- **Storage — device-local, not synced (v1).** Keep a `placeId → {bssid…}` fingerprint in an
  androidMain device-local store (precedent: `OngoingVisitState`, `CaptureHealthStore`,
  `AndroidPlaceGeocodingControl`). BSSIDs are **more sensitive than a city name** and identify
  location precisely; keeping them off the sync plane is the conservative posture and avoids a
  `Place` schema change. (Syncing them later, to share a fingerprint across a user's own devices,
  would be a deliberate follow-up with an HR #2 threat-model amendment.)
- **Match:** at Dwell-confirmation, if the current BSSID matches a stored fingerprint, **reuse
  that place's user-authored label** (strongest signal — a name the user already chose),
  overriding the geocoder. This makes a place the user renamed once "stick" on every later visit,
  regardless of indoor GPS drift.
- **Consent:** mirror `PlaceGeocodingControl` exactly — a `WifiFingerprintControl`, **off by
  default**, opt-in, whose Settings toggle only appears when the platform binding is present.

---

## 5. Privacy & threat model (HR #1 / HR #2)

- **#3a / #3b** — no egress or storage change. Rail geometry rides the existing tile-streaming
  path (Control does the fetch; app is INTERNET-free; relay/CDN see only tile ranges, HR #1).
  Breadcrumbs are device-local `Diagnostics` (PII-free trace ids + coverage numbers).
- **#4-A / #4-B / #4-C** — Photon egress is unchanged in *shape* (still `(lat,lng) → label` via
  Control, threat-model **A35**); a `radius`/`osm_tag` param and a coarse-label fallback do not
  widen it. The new `Place` fields (`accuracyM`, `labelSource`) sync within the user's E2E realm
  like `city`/`countryCode` — no relay exposure.
- **#4-D WiFi** — BSSIDs stay **device-local, never synced** in v1, matched on-device; no
  INTERNET, no relay. Opt-in, off by default. If a future slice syncs fingerprints across a
  user's devices, it takes an HR #2 amendment (a new asset: per-place network identifiers in the
  realm). No new Android permission (connected-AP read rides existing location grant).

---

## 6. Phasing (the agreed order)

Each slice is independently shippable and on-device-verifiable; land primitives first.

1. **P1 — #3a T0 tuning** (cheap, no deps). The `straightFastLongSaysRail` path + constants +
   synthetic tests. Ships the 116 km train as `transit` at capture; self-corrected by T1 later.
2. **P2 — #3b rail-map availability.**
   - **P2a** diagnosability breadcrumbs (app + proxy) — do first; makes P2b a decisive test.
   - **P2b** on-device diagnosis → verify/publish live `de-bayern`/`global` + `transit`@z14
     (ops, `deploy/tiles/`).
   - **P2c** trigger: sweep-after-trip-close + paging.
3. **P3 — #4 place naming.**
   - **P3a** 4-C `labelSource` **+ 4-B `accuracyM`** on `Place` (one schema PR, HR #9 v8→v9 bump;
     **merge-gated on the relay redeploy**). ✅ built (`timeline/place-labelsource`) + the
     blank-clobber write-path fix (§4.4 as-built).
   - **P3b** 4-A Photon venue-radius/type ranking. ✅ shipped.
   - **P3c** 4-D opt-in device-local WiFi fingerprint + `WifiFingerprintControl`. ✅ shipped.

**T3 corpus dependency (§13.3).** #3a's thresholds and #4-A's ranking both want the labelled
tuning corpus that imported Google `activityType` (T3) provides — flagged open in
`timeline-movement-capture.md` §9. Ship the defaults here; calibrate against the corpus when it
lands.

---

## 7. Open questions

- **Q1 — #3a strength & Autobahn false-positive rate.** Is `12 °/km` tight enough to exclude
  motorway driving in coverage-less areas (where T1 can't self-correct)? Needs the T3 corpus, or
  a conservative bump to the straightness gate. Default: ship at 12 °/km, strength 0.5.
- **Q2 — #3b: is coverage the whole story?** P2a's breadcrumbs answer it. If `de-bayern` resolves
  and carries `transit`@z14, the train is fixed by publish alone; if not, the missing piece is the
  publish, not the code.
- **Q3 — #4-D fingerprint match strength.** Is a single connected BSSID enough, or do we require a
  minimum dwell before trusting it (a coffee shop's guest AP vs a home router)? Default: trust a
  match only for a place that already has a **user** label (reuse), not to *create* names.
- **Q4 — #4 sync of WiFi fingerprints.** Device-local v1 is decided; is cross-device sync (own
  devices only) worth the A-asset amendment later? Deferred.

---

## 8. #5 — Place identity: one venue, several `Place` rows

**Trigger (2026-07-30, the same ColorOS device).** The user renamed a place to the office's name and the
Places tab then listed **two** rows with that name; Home kept the old name on the visits belonging to the
other row. This is a *third* recognition issue, distinct from #3/#4: the name is now right, the
**identity** is wrong.

### 8.1 As-is — the grid *is* the identity, and nothing merges

Place identity is minted once, at capture, by `VisitDetector.placeIdFor`: the dwell centroid snapped
to a `placeGridE7 = 10_000` grid (≈ 74 m of longitude × 111 m of latitude at 48°N) →
`"cap:$latE7,$lngE7"`. Three merge mechanisms exist around it, and **none of them merges places**:

| Mechanism | What it folds | Why it cannot fold this |
|---|---|---|
| Grid snap (`placeIdFor`) | Repeat visits landing in the *same* cell | A **fixed** grid: two fixes metres apart straddle a boundary and become two permanent identities |
| `mergeConsecutiveVisits` (Phase 1) | Visit fragments of one continuous stay (200 m / ≤ 15 min) | Read-time, within a day, within one stay — two visits a week apart never meet |
| `MomentReconciler` (I6, `timeline-ingestion.md` §7.1) | Near-duplicate **moments**: place cell (~55 m) **+ 2 h time bucket** | Cross-*source* same-real-world-moment dedup. Different days ⇒ different buckets ⇒ never folds |
| Wi-Fi fingerprint (§4.5, P3c) | Repeat visits on a *known* network | Learns per-BSSID; an *unknown* BSSID re-anchors to the drifted grid cell (§8.2) |

`TimelineProjectionRepository.places()` is a straight 1:1 map over stored `Place` rows, so every
fragment is its own row with its own visit count and its own label. Nothing in the codebase, and
nothing in any proposal, merges two `Place`s.

### 8.2 Why §4.5's Wi-Fi fingerprint does not close it — measured, not argued

The device's own `shared_prefs/tenshu_timeline_wifi_fingerprint.xml` (opt-in **on**, learning
correctly) is the whole diagnosis:

```
office AP 1 → cell (r, c)          ┐ the office:
office AP 2 → cell (r, c)          │ 3 APs →
office AP 3 → cell (r, c+1)        ┘ 2 places, 74 m apart

home AP 1   → cell (r', c')        ┐ home:
home AP 2   → cell (r', c'+1)      │ 3 APs →
home AP 3   → cell (r'-1, c')      ┘ 3 places
```

**12 BSSIDs → 8 placeIds for ~4 real venues**, and the Places tab agrees: the office appears twice (6 and 3
visits, one label city-suffixed), home twice (3 and 4).

The mechanism: `WifiPlaceFingerprint.resolvePlaceId` recognises only BSSIDs it has **already seen**.
An unknown BSSID falls through to `store.remember(bssid, candidate)` — anchoring it to whatever cell
the current (indoors: ±100 m NETWORK-provider) fix reported *at that moment*. So a venue mints up to
one place **per access point**. Home APs 1 and 2 are the two radios of a single router; office APs
1 and 3 are two APs of one office floor. (Cells are `0.001°` grid steps: one longitude step is 74 m
here, one latitude step 111 m.)

Two design assumptions failed together:
1. **§4.5's storage model is AP-shaped, but the thing being recognised is venue-shaped.** Every venue
   larger than a single-AP flat fragments.
2. **"No rename-time hook is needed — the name follows the placeId"** (the `WifiPlaceFingerprint`
   KDoc) is only true when one BSSID dominates a venue. Renaming binds *nothing*, so the next visit
   on a sibling AP misses the name entirely. This is Q3's "match strength" question arriving from an
   unexpected direction: the problem is not a *false* match, it is a **missing** one.

There are also **no Wi-Fi diagnostics breadcrumbs**, which is why "is this even working?" was
unanswerable from the device without reading `shared_prefs` by hand.

### 8.3 Why "Home does not update" needs no separate fix

Reassuringly, this is not a reactivity or staleness bug. `TimelineProjection.moment` renders
`title = m.title` (the literal `"Visit"` written at capture) and `place = subtitle(m)`, where
`subtitleResolver` joins the place label **live, by placeId**. A rename therefore *does* propagate
immediately — to the moments pointing at the renamed row. The other visits point at the sibling row
and keep its label. Fixing identity (§8.4) fixes the symptom; the read path needs no change.

### 8.4 Design

Three independently shippable pieces. F1 is the root-cause fix, F2 makes recognition venue-shaped,
F3 repairs history.

#### F1 — snap to the nearest existing place before minting a new one

Demote the grid from an *identity* to a **minting scheme for genuinely new places**, and make
identity "the place I already know, if I am at it":

> When a captured/imported place's id is **not already in the store**, and an existing place lies
> within the snap radius, adopt the existing id instead of the freshly-minted one.

**Where:** a pure `PlaceIdentityResolver.resolve(imported, existing)` over `ImportedTimeline`, run in
`TimelineImportWriter.write`. That seam is load-bearing and nearly free:
- the writer **already reads every stored place** (the §4.4 clobber guard) — it only needs `LAT_E7`/
  `LNG_E7` added to the projection it already issues;
- it is the **single write path every source converges on** (Android capture, iOS `CLVisit`, the
  Google-Timeline importer, the reference source), so one change covers all of them — including iOS,
  which has no Wi-Fi binding at all;
- it is pure `commonMain`, so it is unit-testable without a device.

The resolver remaps the place **and** the moments that reference it (`subjectId`, and the
`cap-visit:<placeId>:<arrival>` content-addressed `momentId`, so ids stay derivable and re-capture
stays idempotent).

**Snap radius — accuracy-scaled, not a bare constant.** Two dwells can only be told apart to within
the accuracy of the fixes that produced them, and `accuracyM` is already stored on `Place` and
threaded through `ImportedPlace` (4-B, §4.3). So:

```
radius = max(candidate.accuracyM, existing.accuracyM)
           .coerceIn(SAME_PLACE_MIN_RADIUS_M = 60, SAME_PLACE_MAX_RADIUS_M = 150)
```

with `100 m` when neither reports an accuracy. This is exactly the behaviour we want in both
directions: a poor indoor fix (the office case, ±100 m) snaps generously; two clean outdoor fixes
60 m apart stay distinct. Both observed duplicate pairs (74 m and 74–111 m) fold.

**Deliberately not remapped: an id that already exists in the store.** That rule preserves every
earlier decision — a Wi-Fi-recognised id (§4.5) is by construction already stored, so F1 never
overrides F2's verdict, and a repeat visit to a known place is untouched.

⚠ **Accepted cost:** two genuinely distinct venues within ~60 m *and* captured at poor accuracy will
merge. That is not a regression in information — at ±100 m indoors the coordinate never distinguished
them; it is precisely why F2 (network identity) and rename exist. The alternative — keeping the fixed
grid — merges nothing and splits *everything*, which is what the store looks like today.

#### F2 — make the fingerprint venue-shaped

`resolvePlaceId(candidatePlaceId, latE7, lngE7)` gains two lookups ahead of the learn:

1. **known BSSID** → its place (today's behaviour, unchanged);
2. **known SSID within `SSID_VENUE_RADIUS_M` (≈ 500 m)** → adopt that place, bind this BSSID to it.
   This is the large-office case: the network *name* is the venue, the BSSID is one radio in
   it. The radius keeps a chain or default SSID (a company network, a stock router name) in another city from matching;
3. **any fingerprinted place within `VENUE_RADIUS_M` (≈ 150 m)** → adopt it, bind this BSSID to it —
   the guest-AP / renamed-SSID case;
4. otherwise learn `candidate` as today.

Place coordinates come from parsing the `cap:<latE7>,<lngE7>` id — capture-minted ids are the only
ids the fingerprint ever stores, so this needs **no store-format change and no migration**: the 12
bindings already on the device work under the new rules immediately.

**Learn at rename, too.** `renamePlace` binds the currently-connected BSSID (and SSID) to the renamed
place, overriding a stale binding. Naming a place while standing in it is the single strongest signal
the user can give, and §4.5 currently ignores it.

**Breadcrumbs.** `wifi_recognised` / `wifi_learned` / `wifi_adopted` `Diagnostics` lines, so the next
on-device pass is readable without `run-as … cat shared_prefs`.

Privacy posture is unchanged: still connected-AP only, still device-local, still never synced, still
no new permission and no `INTERNET` (§5). SSID is read from the same `WifiInfo` as the BSSID.

#### F3 — merge the places already in the store (deferred to its own slice)

F1 + F2 stop new fragmentation; they do not repair the rows already written. The shape, mirroring
I6b's `supersededBy` precedent (reversible, id-stable, no moment rewriting):

- **`Place.mergedInto`** (scalar, `""` = not merged). The projector folds a merged row's visits into
  its survivor and hides it from Places; `place(id)` follows the pointer.
- **UX:** the flow that produced the report — renaming a place to a name an existing place within
  ~200 m already carries offers *"Merge with the existing <name>?"* — plus a manual *Merge into…* in
  place detail.
- **Cost:** an additive `Place` field ⇒ HR #9 `TENSHU_STORAGE_FORMAT_VERSION` bump + a `SchemaMigration`
  under schema-evolution E2. ⚠ Per E2e this **no longer owes a relay redeploy** (the 426 gate reads
  `RELAY_PROTOCOL_VERSION`, not the payload version), which is what makes F3 cheap enough to schedule
  on its own rather than bundling it here.

### 8.5 Phasing

- **P4a — F1** nearest-existing-place snapping (pure `commonMain` + the writer's existing read; no
  schema change, no format bump). The single highest-leverage fix; covers iOS and imports for free.
  ✅ **built** — as-built below.
- **P4b — F2** venue-shaped fingerprint + rename-time learning + breadcrumbs (Android-only surface;
  no schema change). ✅ **built** — as-built below.
- **P4c — F3** `Place.mergedInto` + merge UX (schema bump + migration; own slice). Not started.

**As-built (P4a + P4b, one PR).** No schema, wire, op or format change — so **no
`TENSHU_STORAGE_FORMAT_VERSION` bump and no relay redeploy** (HR #9 does not fire).

- **F1** = `PlaceIdentityResolver` (`:timeline-shared-data` `commonMain`, `internal`), called from
  `TimelineImportWriter.write` — which now reads `LAT_E7`/`LNG_E7` alongside the fields the §4.4
  clobber guard already projected, so identity costs **one extra field pair on a read that already
  happens**, not an extra round trip. `distanceMetersE7` was extracted out of the capture package's
  `capturedDistanceMeters` so the resolver shares the one haversine.
- ⚠ **The `momentId` has to be remapped too, not just `subjectId`.** A captured visit's id is
  `cap-visit:<placeId>:<arrivalMs>`; leaving it addressing the abandoned place would break content
  addressing — a re-capture of that visit (which now resolves to the target place) would mint a
  *second* moment for it. `remapMomentId` rewrites only that shape and leaves other ids alone.
- ⚠ **`radiusFor` clamps to `[60, 150] m`, defaulting to 100 m when neither side reports accuracy.**
  Both observed duplicate pairs (74 m at ±90 m accuracy) fold; two clean outdoor fixes 74 m apart
  (±10 m ⇒ the 60 m floor) stay distinct.
- **F2** = the four-step `resolvePlaceId(candidate, latE7, lngE7)`. ⚠ **The store needed no format
  change and no migration**: fingerprinted ids are always capture-minted `cap:<latE7>,<lngE7>`, so a
  place's coordinates parse straight out of the id, and the SSID index rides the same preferences
  file under an `s:` prefix beside the existing `b:` ones. The 12 bindings already on the device work
  under the new rules immediately.
- ⚠ `ConnectedWifi` stopped being a `fun interface` (it now carries `currentSsid()` as well), and
  Android's `WifiInfo.ssid` **arrives double-quoted** — `AndroidConnectedWifi` strips them. SSID keys
  are *not* lowercased (SSIDs are case-sensitive by spec) while BSSID keys still are.
- **Rename-time binding** rides `TimelineProjectionRepository.renamePlace` via an optional
  `WifiPlaceFingerprint` (`getOrNull` in `timelineRealRepositoryModule`, so every non-Android
  construction site is unchanged).
- Tests: `PlaceIdentityResolverTest` (8) + `WifiPlaceFingerprintTest` (9, extended with the F2 steps)
  + `aVisitAcrossTheGridBoundaryFoldsOntoTheRenamedPlaceItReallyIs` in
  `TimelinePipelineIntegrationTest` — the reported bug end-to-end through the real write path
  (rename, then a drifted second visit ⇒ **one** renamed place with 2 visits). Full
  `desktopTest jvmTest test` gate + the Android smoke gate green.
- ⚠ **Neither phase repairs history** — the two duplicate pairs already in the store stay until F3.

### 8.6 Open questions

- **Q5 — F1 radius calibration.** `60–150 m` accuracy-scaled is a first guess from two observed
  duplicate pairs. Wants the same T3-corpus calibration §6 flags for #3a/#4-A.
- **Q6 — should F1 also *split*?** A place that accumulates visits over a 300 m spread is evidence of
  an over-merge. Splitting is strictly harder (which visits go where?) and has no reported instance —
  deferred until one exists.
- **Q7 — F2 SSID radius vs. a mobile hotspot.** A phone hotspot's SSID travels with the user; the
  500 m radius bounds the damage per venue but does not eliminate it. Options if it bites: exclude
  SSIDs seen at ≥ N distinct locations (a cheap, device-local heuristic).

⚠ **The office symptom re-reported 2026-08-04 — and it is not a regression of F1/F2.** A full day at
one desk read as three consecutive rows for the office. F1/F2 fixed *place identity* (which row a fragment
attaches to); what produced this is the **fragments themselves**, upstream in capture:
`VisitDetectionConfig.radiusM` is a bare 100 m applied to ±100 m indoor fixes, so a stationary phone
leaves its own anchor by construction and mints a fresh dwell — and the discarded jitter, summed as
raw path length, writes a phantom `Walk` that then vetoes the read-time merge. ⚠⚠ **F1's own rule is
the one that was missing**: the accuracy-scaled radius reasoning in §8.4 applies verbatim to the
clustering that generates dwells, and was only ever applied to identity. Full analysis + the two
slices (accuracy-aware clustering; Wi-Fi observability) in
[`timeline-movement-capture.md`](timeline-movement-capture.md) §18.

⚠⚠ **§8.2's "is this even working?" is only half closed.** F2's `wifi_recognised`/`wifi_adopted`/
`wifi_learned` breadcrumbs cover the paths where the feature *works*; `resolvePlaceId`'s two early
returns — consent off, and an unusable/redacted BSSID — still log **nothing**, so "no wifi lines at
all" cannot be told from "the feature never ran". That is the half that fires when something is
wrong. See §18.5 / S7.

---

## 9. #5 continued — place identity **across devices**: sync the fingerprint, put identity on the merge plane

**Trigger (2026-07-30, maintainer, reviewing §8).** Two objections to the shipped P4a/P4b, both
correct, and chasing them down turned up a *third* problem neither of us had named:

1. *"`AndroidWifiFingerprintStore` being local-only is not great — change device and the user rebuilds
   the whole list."* Right, and it is worse than "rebuild": the new device does not just lack the
   fingerprints, it **re-fragments the places** while it relearns them, because F2's proximity/SSID
   steps only see what *this* device has learned.
2. *"Can we integrate this deeper in the `SemanticMerger` system?"* Yes — and §9.1 argues we **must**,
   independently of anything in §8.

### 9.1 Three findings that reframe the problem

**(a) ⚠⚠ `Place` is last-writer-wins, and the §4.4 clobber guard is *local only*.** `Place`'s schema
carries `Syncable(SyncStrategy.Eager)` with no `ConflictStrategy`, so `SyncProcessor` resolves a
conflict by taking the whole newer row (`strategy = ConflictStrategy.LastWriterWins`). The guard that
stops a blank capture erasing a user's name lives in `TimelineImportWriter` — it reads the **local**
store. So:

> Phone renames a place. Tablet, which has not yet pulled that block, captures a visit to
> the same placeId. Its local guard sees a blank row, so it writes a blank label — legitimately. That
> row is newer. On sync it **replaces** the renamed row and the name is gone on every device.

This is the §4.4 bug again, one layer out, and **F1 makes it more reachable**, not less: folding
visits onto known places is exactly what routes more writes at rows that may carry a user's name. It
is a live cross-device data-loss path today, and no amount of write-time care fixes it — the
authority that must arbitrate is the *merge*.

**(b) F1 consults only the local store, so it cannot converge.** The write-time snap asks "do I
already know a place here?" of the device doing the writing. Two devices capturing the same venue
before either has pulled the other's block — or one device offline for a day — each mint their own
id, and both ids are then perfectly valid rows that sync happily side by side. Write-time
deduplication is a good *cheap* filter and a bad *convergence* mechanism. §8 said F1 "prevents new
fragmentation"; precisely, it prevents new fragmentation **per device**.

**(c) ⚠⚠ `MomentMerger` and `TripMerger` are still not registered in production.** Both were built
(I5b/I6b) and left unregistered because "the timeline app is standalone-no-sync". **That premise
expired** — Timeline moved onto Control's proxy and its moments/places/traces land in
Control's durable, synced store. Production registers exactly `FolderMerger` + `DailyActivityMerger`
(`control-shared-proxy/.../PlatformProxyModule.kt`, all three platforms) plus `RealmSchemaEpochMerger`
(inside `initSyncService`). So every timeline type is silently on last-writer-wins today, and the
convergence those mergers were written to provide is not happening on any device. Anything we add
here inherits that trap unless we fix the seam, not just add a line.

### 9.2 F5 — a `PlaceMerger`, and register the timeline mergers where they cannot be forgotten

The direct answer to *"integrate deeper into the `SemanticMerger` system"*: **yes, and it is the
first thing to build, ahead of F3** — (a) is a data-loss bug, and F3's merge pointer needs a merge
plane underneath it to converge at all.

- `Place` flips to `Syncable(SyncStrategy.Eager, ConflictStrategy.Merge)`.
- `PlaceMerger : SemanticMerger` (`:place-service-proxy`, beside the mergers it mirrors) with
  field rules that are **order-insensitive**, because of the constraint below:
  - **label / labelSource** — a `user` label always beats a `geocoded` one, *whichever side it is
    on*; between two `user` labels, the newer state's (the incoming, by definition of when merge
    runs); a non-blank label always beats a blank one. This is the §4.4 guard, restated on the wire.
  - **city / countryCode** — never blank a non-blank (the enricher's rule).
  - **accuracyM** — keep the *better* (smallest non-zero) reading; `0` = unknown loses.
  - **capturedAt** — earliest (first time we ever saw the place) rather than newest, so the field
    means what its name says on every device.
  - **mergedInto** (F3, §9.4) — the deterministic rule; see there.
- ⚠⚠ **A merger only runs when the incoming block is *strictly newer*** (`isNewer` gates the
  `ConflictStrategy.Merge` branch in `SyncProcessor`; an older block is dropped whole). So merge is
  "newer state folds the older in", **not** a commutative CRDT join. Every rule above is therefore
  written to be side-independent — "the user label wins wherever it is" rather than "keep mine" — or
  the outcome would depend on which device pulled first.
- ⚠⚠ **Register at the one seam.** `RealmSchemaEpochMerger` is registered *inside*
  `SyncProxyInitialization`, with a comment saying in as many words that it is done there so the
  repo's "registered in tests only" gotcha — naming `MomentMerger`/`TripMerger` — cannot recur. Take
  that precedent: register `PlaceMerger`, `MomentMerger` and `TripMerger` at that same seam, so every
  wiring (Android/desktop/iOS `PlatformProxyModule`, the JS runtime, system-test) gets them by
  construction. Fixing (c) is a two-line change with a large blast radius; it belongs in this slice
  because F3 and F4 both assume convergence that is not currently switched on.

### 9.3 F4 — sync the place fingerprint (`:place-service`, schema-first)

The maintainer's objection stands: a device-local map means a new phone spends weeks relearning, and
§9.1(b) says it will *fragment places* while it does. Syncing it is also what makes the fingerprint
worth having on iOS, which has no Wi-Fi binding at all but would happily *consume* one.

**Where it lives.** Not on `Place` — the lookup runs network → place, and a growing list field on a
positional composite is exactly the shape the repo keeps paying for. A separate keyed row:

```
PlaceNetworkFingerprint            // :place-service, Syncable(Eager, ConflictStrategy.Merge)
  networkTag   : String            // the DataIdProvider key — see hashing below
  placeId      : String
  provenance   : String            // learned | user   (mirrors LabelSource exactly)
  observedAt   : timestamp
```

One row per network, content-addressed by `networkTag`, so a device that learns the same network
writes the *same* row and sync collides them instead of duplicating. Rows are tiny and needed
**before** a visit is written, so `Eager`.

**Its merger** is the smallest interesting one in the repo and answers the real cross-device
question — *two devices bound the same AP to different places*: a `user` binding beats a `learned`
one (the rename-time bind of §8.4 F2 is the user telling us directly); between two of equal
provenance, the newer. Same shape as `labelSource`, one field, no ambiguity.

**⚠⚠ Hash the network identifier, and be honest about what that buys.** Store
`networkTag = SHA-256(salt ‖ "bssid:" ‖ lowercase(bssid))` rather than the raw BSSID — the lookup is
exact-match, so a tag is functionally identical to the raw value. What it *does* buy: raw MACs of
your home, your employer's and your friends' routers never sit in plaintext in the realm, so they
cannot leak through a diagnostics export, an error report, a future data-export or a backup
inspection — the paths that actually leak things. What it does **not** buy: protection against an
attacker who has the realm plaintext. The salt must ride in the realm for other devices to compute
the same tag, and the BSSID space is small and OUI-constrained, so such an attacker can brute-force
the mapping. That is acceptable — and worth stating plainly in the amendment — because **the realm
already contains the exact coordinates of every place the user dwells and a polyline of every trip**
(`MovementTrace`). A network identifier adds essentially no location information the realm does not
already hold; what it adds is *linkability to third-party Wi-Fi geolocation databases*, which is
moot for an attacker who already has the coordinates. **The honest summary: syncing fingerprints
widens the blast radius from one device to N devices, and adds no new class of information.**

**⚠ The one real engineering constraint: `resolvePlaceId` is synchronous.** It is called inside the
capture collect loop, and service reads are suspend + cross-process. So the synced rows cannot be
read on the hot path. Shape: keep `WifiFingerprintStore` as the seam (unchanged interface), and add a
`SyncedWifiFingerprintStore` that (i) keeps an in-memory map hydrated from a reactive read of the
fingerprint rows, (ii) writes through to the service, and (iii) keeps the existing
`SharedPreferences` map as its **local durable cache** — so capture still works before the first
sync, offline, and while Control is unreachable (which on ColorOS is often, and which the client
resubscription gap makes worse). The prefs file stops being the source of truth and becomes a
write-behind cache; nothing in `WifiPlaceFingerprint` changes.

**⚠ Consent.** `WifiFingerprintControl` is off by default and stays so. Turning it on now means "and
share these across my devices" — the toggle's copy has to say that, and turning it *off* should stop
writing new rows (it does, by the existing gate) — it deliberately does **not** delete what synced,
same as every other row (deletion is R1 *Delete all* / a future per-type reset).

### 9.4 F3 — `Place.mergedInto`, with a **deterministic** survivor

The piece that repairs the rows already fragmented, now shaped to converge.

- **`Place.mergedInto`** (string, `""` = a root). Mirrors I6b's `supersededBy`: reversible, ids
  stable, no moment rewriting.
- **Read-time resolution.** The projector follows `mergedInto` to a fixed point (`resolveRoot`),
  folding a merged row's visits into its root and hiding non-roots from Places; `place(id)` follows
  the pointer so an old deep link still lands somewhere sensible.
- ⚠⚠ **The survivor must be deterministic, not "the row the user tapped".** Two devices merging the
  same pair independently, or a merge racing a rename, otherwise write *different* pointers and the
  merge plane can only pick the newer — a coin flip that can leave `A→B` on one device and `B→A` on
  another. Take `MomentReconciler`'s trick: the root is the **lexicographically smallest placeId in
  the merge set**, so every device computes the same pointer and sync collides identical writes
  instead of racing them. Chains (`A→B`, `B→C`) resolve by iterating to a fixed point with a hop cap;
  a cycle is broken by taking the minimum id in it. The *name* is not at the mercy of this: merging
  moves the user label onto the root, and §9.2's merger protects it from there.
- **Manual merge** (the reported flow): renaming a place to a name a place within ~200 m already
  carries offers *"Merge with the existing <name>?"*, plus a *Merge into…* action in place detail and
  an **Unmerge** (clear the pointer) so a mistake is recoverable — cheap precisely because nothing
  was rewritten.
- **Rejected: rekeying the rows** via the `DataIdMigrator` primitive (`file-content-addressing.md`
  Ph2). It is the wrong tool here: moments reference a place by a **string `subjectId`**, not a
  `DataId` reference, so the generic migrator would not rewrite them; and rekeying is irreversible,
  which a user-facing merge should not be.

### 9.5 F6 (optional) — a converging place reconciler

With F3's pointer + F5's merge plane in place, the automatic half of §9.1(b) becomes small: a
deterministic pass that clusters places by proximity and writes `mergedInto` to the min-id root, so
two devices agree without the user doing anything. It is exactly `MomentReconciler`, one level down.

⚠ **Gate it on corroboration.** Auto-merging places is riskier than auto-merging moments: a café and
the shop next door are 20 m apart and both real, and a wrong merge here is *visible* and *sticky*.
Rule: auto-merge only when at least one side is **unnamed**, or the two share a **network tag**, or
they carry the *same user label*. Two differently-named user-labelled places never auto-merge —
only the user may, via F3.

### 9.6 Threat model (HR #2)

F4 is the only piece with a threat-model delta: a **new asset** — per-place network identifiers
(hashed) inside the personal realm, syncing to every provisioned device. The amendment lands in the
same PR as F4 and should state §9.3's honest analysis: no new information class (the realm already
carries exact place coordinates and trip polylines), blast radius widens from one device to N,
hashing defends the export/diagnostic paths rather than a realm-plaintext attacker, personal realm
only (never a shared realm — a shared-folder peer must not learn the owner's networks), still no
`INTERNET` in Timeline and still ciphertext-only at the relay (HR #1). F3/F5/F6 touch no asset.

### 9.7 Phasing (recommended order — note it is *not* F3 first)

1. **P4d — F5 `PlaceMerger` + the three registrations.** ⚠ Fixes a live cross-device name-loss path
   (§9.1a) and switches on the convergence everything below assumes (§9.1c). `Place` gains the
   `Merge` conflict strategy; no new field, so this can ship *without* a schema field if we want it
   out fast — but see the bundling note.
2. **P4e — F3 `Place.mergedInto` + merge UX.** Bundle with P4d in one PR if we can take the format
   bump immediately: `mergedInto` is a field append (HR #9 bump + an E2 `SchemaMigration`), and
   doing it together means **one** bump and one migration rather than two. ⚠ Per E2e, no relay
   redeploy is owed either way.
3. **P4f — F4 synced fingerprints.** New composite + merger + the write-behind store + the consent
   copy + the threat-model amendment. A genuinely new type reshapes no existing row, so it rides a
   `:place-service` version bump without a row migration — verify against E2's chain-verify.
4. **P4g — F6 reconciler.** Only after F3/F5 are live on real devices; gated on corroboration.

### 9.8 Open questions

- **Q8 — is `provenance` on the fingerprint enough, or does it need a device id?** A `user` binding
  made on the phone and a `user` binding made on the tablet can still disagree. Newest-wins is
  probably right (the user changed their mind), but it is worth a sentence in the merger's KDoc.
- **Q9 — should F4 sync the SSID index at all?** The BSSID index is unambiguous; SSIDs collide by
  design across a chain, and the proximity gate that makes them safe is device-local anyway. Cheapest
  answer: sync BSSID tags only; keep the SSID index device-local. Recommended.
- **Q10 — do we retire the fingerprint's `learned` rows on an unmerge / place delete?** A dangling
  tag would silently re-fold visits onto a place that no longer exists. Cheap fix: resolve through
  `mergedInto` on read, and drop a tag whose place is gone during the same pass.
- **Q11 — how far does the "register the mergers" fix reach?** `MomentMerger` and `TripMerger` have
  never run in production; switching them on changes convergence behaviour for already-stored rows.
  Wanted: a system test over the real encrypt → `SyncBlock` → `processSyncBlock` path per merger
  (`MomentMergeTest`/`TripMergeTest` already ride that harness) before the flip.

---

## 10. #5 concluded — converge in **Control**, not in each app

**Trigger (2026-07-30, maintainer, reviewing §9).** *"Basically all merging should happen Control
side, also considering potential double visits of the Android/iOS versions of the app — and laptop
visits in future. Thinking about it, that would make the tracking even more reliable: when both phone
and desktop are on the same WLAN, that is kind of a 100% correct thing."* Three claims, all load-
bearing; §10.4 tightens the third.

### 10.1 The principle — and a third deferral resting on the same expired premise

Every identity/dedup pass in Timeline today runs **client-side**:

| Pass | Where it runs | Persisted? |
|---|---|---|
| `mergeConsecutiveVisits` (Phase 1) | app, read-time in `TimelineProjection.days` | no |
| `MomentReconciler` (I6c) | app, read-time in `TimelineProjectionRepository.reconcile` | no |
| `PlaceIdentityResolver` (F1, §8) | app, write path in `TimelineImportWriter` | id choice only |

I6c said *why* in as many words: **"the timeline app is standalone-no-sync, so there is no place for a
*persisted* canonical/supersession to converge."** ⚠⚠ **That is the same premise as §9.1(c), and it
expired at the same moment** — Timeline moved onto Control's proxy; Control already calls
`initTimelineService()` / `initPlaceService()` beside `initFileService()`. So **three** separate
deferrals — `MomentMerger` registration, `TripMerger` registration, and reconcile-at-read-not-at-write
— all rest on one sentence that stopped being true, and none was revisited when it did. The
maintainer's instinct is right, and the reason it is right is documented in our own tree.

⚠ **"Control-side" means *proxy*-side, precisely.** On iOS route-1 each first-party app hosts its own
in-process proxy (`FirstPartyLocalRuntime`), so there is no separate Control process to send work to.
Putting the pass in `:timeline-service-proxy` / `:place-service-proxy` gets both for free: it runs in
Control on Android/desktop and in-process on iOS, one implementation, no per-platform work. Framing
it as "Control-side" would quietly exclude iOS — the platform this is most about.

### 10.2 What multi-device capture does today

Phone and iPad at the same lunch each detect a dwell and write
`cap-visit:<placeId>:<arrivalMs>` — different arrival millisecond ⇒ **different content-addressed
ids** ⇒ two `Moment` rows, both perfectly valid, both synced. They are hidden today *only* by the
read-time reconciler, which means:

- **it is recomputed on every read, on every device** — and it is O(all moments), inside the
  `homeDays()`/`places()` combines. That is the same unbounded-read shape that blanked Home at ~10.5k
  workouts; a write-time pass is a performance fix as much as a correctness one;
- **any client that does not run it sees the duplicates** — the iOS surfaces, the JS SDK, a future
  desktop. The projector is not the place to encode truth;
- **nothing converges**: no device ever records *that* it decided these were one visit, so the
  decision cannot be corrected, corroborated, or reasoned about later.

⚠ The documented I6a limit stays either way — a visit straddling a 2 h bucket edge does not fold —
but persisting the verdict makes it fixable in **one** place instead of three.

### 10.3 The hook — a proxy-side reconciliation pass

One pass, two triggers, one code path. It must run on **local writes** (`recordMoment` /
`recordPlace`) *and* on **inbound sync applies** (a peer's block landing) — a duplicate is created by
the second device and discovered by the first. The uniform seam is a **proxy-side save observer**,
the same shape `DataIdMigrator` used (`file-content-addressing.md` Ph2 3a: "`ResolvedDataId` falls out
of ordinary save+delete observers").

⚠ **A `SemanticMerger` cannot do this job, and that is not a defect.** A merger merges two states **of
one key**. Two *different* momentIds that mean one real-world visit is a cross-key clustering problem.
The two are complements: **the pass produces the verdict** (`supersededBy` / `mergedInto`), **the
merger converges it** when two devices produce it independently. §9's mergers are the substrate this
pass writes onto — which is why they land first.

Constraints that shape it:
- ⚠ **Idempotent, or it loops.** The pass writes rows; those writes re-enter the observer. Write only
  on change, and derive the verdict deterministically so a re-run is a no-op. (`SyncObserver`'s
  no-op-on-delete surprise is the cautionary tale in this area.)
- ⚠ **Bounded, or it re-reads history per write.** Scope each run to the candidate's neighbourhood —
  place cell + time bucket for moments, proximity for places. `MomentReconciler`'s cluster key already
  *is* that index; use it as one, rather than reconciling the whole table.
- **Batched for bursts.** A Google-Timeline import writes thousands of rows; per-row reconciliation
  would be quadratic. The import path should reconcile once at the end (Q12).

### 10.4 Cross-device corroboration — the good idea, and its honest limit

Once fingerprints sync (§9.3 F4), two devices reporting the **same BSSID** are within one access
point's range — tens of metres — which is a far stronger colocation signal than either device's GPS,
indoors especially, and it costs nothing extra. Three uses:

1. **Fold two devices' visits** that share a network observation in the same window — a *better*
   clustering key than place-cell + time-bucket, and one that is immune to both coordinate drift and
   the bucket-boundary case.
2. **Corroborate a place merge** — the §9.5 F6 gate.
3. **Give a GPS-less device a place identity at all** — §10.5.

⚠ **BSSID, not SSID.** The maintainer's "same WLAN" is only ~100% correct at the *access point*: one
SSID can span a campus, a chain, or a mesh. This **flips Q9**: sync **BSSID tags only** — that is both
the more private choice (SSIDs are user-authored strings that frequently contain a person's name) and
the more useful one.

⚠⚠ **A device on a network is not a user at a place.** A laptop left at the office, a phone charging
at home while its owner is out — the network says "this *device* is here". So corroboration may
**strengthen or fold an existing dwell, and must never create one**. Written down as a rule here so
the desktop slice cannot quietly break it.

### 10.5 Sketch — desktop as a capture source (why F4 makes it nearly free)

Not scoped here, but it is what the maintainer's "laptop visits" points at, and F4's row shape should
not foreclose it (it does not — the row is keyed by *network*, not by device):

- A laptop has no GPS, and IP geolocation is useless indoors and privacy-hostile.
- With synced fingerprints it needs **no location permission whatsoever**: read the connected BSSID,
  look it up, and the place identity is exact. It can only ever recognise places a phone already
  taught it — self-limiting, and precisely the right failure mode (unknown network ⇒ nothing, never a
  guess).
- The **dwell** signal is user presence at the machine (session active / not idle), never the network
  alone — §10.4's rule.

### 10.6 Revised phasing

Three of the four pieces need schema changes, so bundle them into **one** format bump rather than
three:

1. **P4d — schema + merge plane** *(one PR, one HR #9 bump, one E2 `SchemaMigration`, ⚠ no relay
   redeploy per E2e)*: `Place.mergedInto`; `Place` → `ConflictStrategy.Merge`; the new
   `PlaceNetworkFingerprint` type; `PlaceMerger` + its fingerprint sibling; and the
   `PlaceMerger`/`MomentMerger`/`TripMerger` registrations at the `SyncProxyInitialization` seam
   (§9.2). Ships the §9.1(a) data-loss fix. ✅ **built** — as-built in §10.8.
2. **P4e — the proxy reconciliation pass** (§10.3): place identity + moment dedup, bounded and
   idempotent, writing `mergedInto` / `supersededBy`, consuming BSSID corroboration where present.
   ⚠ **Split in the build**: the *place* half shipped (as-built §10.9); the **moment** half is
   deferred to **P4e-2** for a reason worth reading — see §10.9's closing note.
3. **P4f — the clients** ✅ **COMPLETE**: the reader (§10.11), the write-behind fingerprint store
   (F4, §10.14), and merge/unmerge UX (§10.15). ⚠ The app-side read-time reconcile is **kept, by
   decision** — see §10.15.
4. **P4g — desktop capture** (§10.5), its own slice, gated on P4d–P4f.

⚠ **F1 (shipped) stays exactly where it is.** A cheap local pre-filter at the write path and a
convergent authority in the proxy are two layers, not a duplication: F1 stops the common case before
it ever becomes a row, the pass converges what F1 structurally cannot see (§9.1b). Do not "move F1 to
the proxy" — it would then need a cross-process round trip on every captured dwell.

### 10.7 Open questions

- **Q12 — inline or debounced? ✅ SETTLED 2026-07-30 (maintainer): inline for capture, one batched
  pass at the end of an import.** Reconciling inline in the save observer matches `DataIdMigrator`
  and keeps a captured dwell reconciled immediately; the importer already knows when it has finished,
  so a bulk import reconciles once rather than per row (which would be quadratic).
- **Q13 — how does a BSSID observation reach the pass?** Joining through the `PlaceNetworkFingerprint`
  row is free but only says "this place has this network", not "*this device* saw it at 12:04" — which
  is what folding two devices' visits actually wants. A per-visit observation would be a new field on
  `Moment` (positional append, another bump). Recommend: start with the join (no schema cost) and add
  the observation only if the bucket-boundary case is observed to bite.
- **Q14 — does `mergeConsecutiveVisits` graduate too? ✅ SETTLED 2026-07-30 (maintainer): no, it
  stays client-side.** It is a *presentation* fold ("one stay reads as one row"), not an identity
  claim — it asserts nothing about which rows are the same thing, and a client is free to render a
  run of visits however suits its surface. §10.1's table is therefore complete: the two *identity*
  passes graduate to the proxy, the one *presentation* pass does not.

### 10.8 As-built — P4d (schema + merge plane)

`TENSHU_STORAGE_FORMAT_VERSION` **15 → 16**; `PlaceService` → `version = 2` with a registered v1→v2
`SchemaMigration`. ⚠ **No relay redeploy** (payload-only; the 426 gate reads `RELAY_PROTOCOL_VERSION`,
E2e §7.8).

**⚠⚠ The design's `Place.mergedInto` field is not what shipped — E2 rejects it, and the reason
generalises.** The obvious shape, mirroring `Moment.supersededBy`, failed the chain verify at store
open:

```
Nested reshapes are not yet supported: migrated type 'tenshu/place/place' is embedded as a
composite field of 'tenshu/place/service/recordPlace'
```

`Place` rides as a parameter of the `recordPlace` **operation** — callstack params are
client-serialised composites (§2), so a migrated type's bytes inline into every embedding row, and
the overlay orchestration that would rewrite those is E2a's documented gap. The general rule, worth
knowing well beyond this slice: **no type that appears as an operation parameter can gain a field
until nested reshapes land** — which is very nearly every domain type in the tree. `Moment` and
`Trip` got away with `supersededBy`/`sources` only because they were appended *before* E2 existed;
the same edit today would be rejected.

So the pointer shipped as its own keyed row, **`PlaceMerge { placeId → mergedInto, mergedAt }`** —
additive, migration-free, and arguably the better model regardless: nearly every place is a root and
would otherwise have carried an empty field forever. Because both new types are genuinely new, the
v1→v2 step is **empty** (the `SyncService` v2 precedent) — it must still be registered, since the
floor is derived from the chain and `verify` requires the chain to reach the declared version.

The rest landed as designed:
- **`PlaceMerger`** — user label wins wherever it sits; non-blank beats blank; never blank a
  non-blank `city`/`countryCode`; keep the *better* (smallest non-zero) `accuracyM`; keep the
  **earliest** `capturedAt`. Every rule side-independent, per §9.2's ⚠⚠.
- **`PlaceNetworkFingerprintMerger`** — `user` binding beats `learned` on either side, then newest
  `observedAt` (a router really can move).
- **`PlaceMergeMerger`** — the smaller id wins, *not* the newer write, so a disagreement resolves
  identically on both devices.
- **Registration is a required parameter**, not an optional one: `initPlaceService(mergerRegistry)`
  and `initTimelineService(mergerRegistry)`. That is the whole fix for §9.1(c) — a default value
  would let "forgot to register" recur silently, a required parameter makes it a compile error in
  every wiring. `MomentMerger` and `TripMerger` now run in production for the first time. ⚠ This
  promoted `:sync-service-common` from `implementation` to `api` in both proxy modules, since
  `SemanticMergerRegistry` is now in a public signature.
- Tests: `PlaceMergeTest` (4) over the real encrypt → `SyncBlock` → `processSyncBlock` harness, the
  same one `MomentMergeTest`/`TripMergeTest` use — the only way to prove a merger *fires* rather than
  merely compiles. The first case is the §9.1(a) bug itself: a peer's blank capture arriving with a
  newer HLC must not erase a renamed place. Full `desktopTest jvmTest test` gate + Android smoke gate
  (v16 lockstep) green.

⚠ **Not yet wired to any consumer.** `PlaceMerge` and `PlaceNetworkFingerprint` have ops, mergers and
relations but no writer and no reader — that is P4e (the proxy reconciliation pass) and P4f (the
client store + merge UX). Nothing in the app behaves differently yet **except** the merge plane
itself, which is exactly the intended shape: the correctness fix ships first, alone, and provably.

### 10.9 As-built — P4e (the place half of the proxy reconciliation pass)

No schema, wire, op or format change: `PlaceMerge` and the merge plane already shipped with P4d, so
this is the writer that fills them. **No format bump, no relay redeploy.**

- **`PlaceClustering`** (`:place-service`, pure) decides; **`PlaceReconciler`** (`:place-service-proxy`,
  a `DataObserver`) does the store access. Splitting them keeps the decision unit-testable without a
  proxy and stops the observer growing logic.
- **`PlaceProximity`** was extracted to `:place-service` and the app-side F1 resolver now delegates to
  it. ⚠ Load-bearing: if the write-time snap and the proxy pass used different radii, the pass would
  spend its life undoing F1's decisions, or merging pairs F1 had deliberately kept apart.
- **Runs on local writes and inbound sync applies alike**, since a duplicate is created by the second
  device and discovered by the first — proven by `aPeersPlaceArrivingBySyncReconcilesToo`, which
  drives the `WriteOrigin.SYNC_INGEST` path the way `SyncProcessor` does.

**⚠ The corroboration gate is narrower than §9.5 designed it.** The draft required "at least one side
unnamed, **or** the same user label, **or** a shared network tag". As-built, the *only* veto is **both
sides carrying a user label that differs**. A geocoded label must not veto: geocoded names are machine
guesses, and the reported bug is precisely the geocoder naming two cells of one venue differently
(a street name beside the business's name). Requiring corroboration there would have preserved exactly
the fragmentation this pass exists to repair — and network tags cannot corroborate anything until F4
syncs them. The tag join is wired and consulted; it is simply not a *requirement*.

**⚠⚠ The startup sweep is not optional, and the reason is easy to miss.** The observer fires on a
`Place` **save** — but a repeat visit to an already-named place writes **no `Place` row at all**:
`TimelineImportWriter`'s §4.4 clobber guard skips the write precisely because the incoming blank label
would erase the name. So the rows that most need merging are exactly the ones that stop triggering
saves, and an observer alone would never reach a user's *existing* duplicates. `reconcileAll()` runs
once from `initPlaceService`, is idempotent (a settled store yields nothing), is O(N²) over *places*
— tens to low hundreds, not moments — and swallows failures so a locked or empty store at init cannot
take the service down.

⚠ A test note worth keeping: reproducing "the pre-P4e world" needs `ExecutionContext.saveData` with
the observer *dropped*, **not** a bare `database.saveData` — the latter bypasses the relation
maintainer, so the sweep reads an empty relation and the test passes for the wrong reason. That
mistake cost a debugging cycle here.

Tests: `PlaceClusteringTest` (9, pure) + `PlaceReconcilerTest` (8, over a real proxy). ⚠ Verified as a
real detector: disabling the registration fails three of the eight (the positive-side ones), rather
than passing regardless. Full `desktopTest jvmTest test` + Android smoke gate (v16) green.

**⚠⚠ Deferred to P4e-2: the moment half.** Not an oversight — a design problem that deserves its own
slice. Reconciling a `Moment` means reading the moments around it, and the proxy-side read available
here (`getArrayData`) returns the **whole relation**: that is precisely the unbounded read that
stopped completing at ~10.5k rows and blanked Timeline Home, and it would run on every captured
dwell. The place pass is safe at O(N) because a place is a *venue* (tens to hundreds); a moment is a
*visit* (tens of thousands). P4e-2 needs a bounded read first — the most plausible shape is the
shipped F1 ordered-window fusion (`sortBy(TIMESTAMP).take(n)`), which would cover the real case (two
devices capturing within hours of each other) while leaving a years-old import to the batched pass.
Meanwhile nothing regresses: the app's read-time `MomentReconciler` (I6c) still hides duplicate
moments, and P4f must not delete it until P4e-2 lands.

### 10.10 As-built — grid adjacency (the on-device pass found the rule's blind spot)

**On-device, 2026-07-30, the reporting ColorOS device.** All seven apps installed at v16, store wiped
by the format bump, ~70 MB re-pulled from the relay. The merges landed through the **observer** path
(the wipe left the startup sweep nothing to sweep), which is the `SYNC_INGEST` case — so P4e's
cross-device half was exercised on real data. Result:

- ✅ **The office collapsed from two rows to one**, carrying the user's name — the reported bug, fixed.
- ❌ **Home stayed doubled.**

⚠⚠ **Why, and it is a defect in the rule rather than the plumbing.** The identity grid is `0.001°` in
**both** axes, but a degree of longitude shortens with latitude: at 48°N one cell step is **~74 m
east-west and ~111 m north-south**. `PlaceProximity` floors at 60 m and defaults to 100 m, so the
74 m *longitude* split merged and the 111 m *latitude* split did not. **A rule whose purpose is
repairing grid fragmentation could not span one step of that grid** — and no metre radius can, in
both axes, at every latitude, because the grid is angular and the radius is metric.

**Fix: ask adjacency in the grid's own terms.** `PlaceGrid.areAdjacent(a, b)` parses the two
`cap:<latE7>,<lngE7>` ids back to cell indices and returns true when they touch (Chebyshev ≤ 1, so
diagonals count — a centroid drifts diagonally as easily as along an axis). `PlaceClustering` now
merges on **radius OR adjacency**.

Why this rather than widening the radius to ~135 m: adjacency is **self-limiting by construction** —
it reaches exactly one cell boundary, which is exactly the artefact — and it is latitude-independent.
Widening the radius would merge genuinely distinct venues that no grid ever split, in every axis,
everywhere. The user-label veto (§9.5) still applies on top, so two differently-named places never
auto-merge however close their cells.

- ⚠ **The same predicate went into the app-side F1** (`PlaceIdentityResolver`), not just the proxy
  pass. Otherwise F1 would keep minting rows that the pass immediately has to merge — a row plus a
  merge row where zero writes would do. One predicate, both layers, as §10.3 requires.
- ⚠ **`PLACE_GRID_E7` is now single-sourced** in `:place-service` and `VisitDetectionConfig.placeGridE7`
  defaults to it. A drift between the minting grid and the adjacency test would silently make
  "adjacent" mean the wrong thing.
- ⚠ **Chain handling changed with it.** With cells A–B–C where C touches B but not A, the old code
  *skipped* already-merged neighbours — so once B folded into A, anchoring on C saw only a settled B,
  clustered alone, and C stranded as a permanent duplicate. A merged neighbour is now **resolved to
  its root** instead, so the whole run collapses onto one row. Idempotence survives: a settled cluster
  resolves to a single id and yields no merges.
- ⚠ `floorDiv` is hand-rolled — `Math.floorDiv` is JVM-only, and truncation toward zero would make the
  cells straddling the equator and prime meridian look like one.

**Two tests were deliberately inverted** (`cleanFixesStayDistinctEvenNextDoor`,
`twoCleanFixesStayDistinct`): they asserted that two clean fixes 74 m apart stay distinct, which was
the pre-fix rule and is precisely the behaviour that left a venue split. Their replacements assert the
property that actually survives — adjacency reaches one boundary, never two. New: `PlaceGridTest` (6)
covering both axes, diagonals, two-cells-apart, non-capture ids and southern/western floor.

### 10.11 As-built — P4f's reader (the piece that made any of this visible)

⚠⚠ **Found by the on-device pass, and it invalidated the previous session's read of the result.**
After P4e's grid-adjacency merge the phone still showed home twice — and the decisive check was not in
the logs but in a grep: `PlaceMerge` / `resolveRoot` / `Relations.MERGES` appeared **nowhere** in
`timeline-shared-data`, `-logic` or `-ui`. P4d shipped the merge plane, P4e shipped the pass that
fills it, and **nothing read the result** — `places()` still mapped stored rows 1:1. The 8 merge rows
on the device were correct and inert.

That also corrected an earlier claim: "the office collapsed to one row" was **not** the fix working. Both
office ids were still in the store (the two cells one longitude step apart, 12 and 4 hits); the
single row was an artefact of an incomplete resync after the v16 wipe. A merge is not observable
until the reader exists — so no on-device verdict on P4d/P4e/#1367 was possible before this slice,
and the ones offered were wrong.

**Shape.** `PlaceMergeFolding` (pure, `:timeline-shared-data`) + two changes at the *flow boundary*
in `TimelineProjectionRepository`, so **every** consumer inherits the fold with no call-site change —
the feed, Places, place detail, trips, the suggester:
- `placesFlow()` drops rows that resolve to another root;
- `momentsFlow()` repoints each `Visit` subject to its surviving place, so the folded row's visits
  count towards — and are labelled by — the survivor. This is also what makes Home show the merged
  name on the *other* row's visits (§8.3's join, now through the root).
- `place(id)` resolves the pointer first, so a deep link to a folded id still lands somewhere.

Nothing is rewritten in the store: merging stays reversible precisely because the moments still point
where they always did (§9.4).

⚠⚠ **`mergesFlow()` starts by emitting empty and never propagates a failure.** It is `combine`d into
both hot reads, and `combine` emits nothing until every source has — a stalled or throwing merges read
would blank Home and Places outright. That is not hypothetical: one silent `combine` source is exactly
what blanked Timeline Home at ~10.5k workouts. An absent merge map degrades to "nothing is merged".

⚠ **A test that passed for the wrong reason, caught by inverting the implementation.** The first
end-to-end version wrote both visits through `TimelineImportWriter` — but F1's grid snapping
folds the second capture onto the first id, so only one row was ever created and the assertion held
*without a reader at all*. It now records both places through `recordPlace` directly, so two rows
genuinely exist and the merge path is what collapses them. Both the unit and the end-to-end tests were
then re-verified by stubbing `rootsOnly` to the identity: 4 tests fail, as they must.

Tests: `PlaceMergeFoldingTest` (6) + `twoRowsOfOneVenueReadAsOnePlaceOnceTheProxyMergesThem` in
`TimelinePipelineIntegrationTest`. Full gate + Android smoke gate (v16) green. No schema, wire or
format change — reader only.

### 10.12 Time as corroboration — designed (BUILT 2026-08-01; as-built §10.16)

> **Status:** the blocker below (a bounded proxy-side read) shipped as `readOrderedWindow`, and
> this is now built — `VisitTimeCorroboration` + a three-valued `PlaceClustering.mayMerge`. The design
> here stands as written; **§10.16 records what it became**, including the one narrowing the design did
> not name (a machine guess never contests a *user's* name). The §12 handover is discharged.

**Maintainer, 2026-07-30, on seeing the first over-merge:** a town-hall row folding into the row of
the square beside it was **correct** — it was a pause on the walk to an ice cream shop, not a
separate visit. Which raises the question: *would time-gating make the merge decision more reliable?*

**The physics checks out.** A square ringed by tall façades is an urban canyon; multipath off them
puts horizontal error at 20–50 m routinely and past 100 m in the worst case, and a NETWORK-provider
fallback is ±100 m by design. A dwell centroid is also an **average of fixes**, so a pause while
walking through lands between the real path points rather than on any of them. 100 m there is
unremarkable. (Separately: that a pass-through became a `Visit` at all is the dwell detector being
generous — movement capture was meant to render those as `Move`s. Its own thread.)

**⚠ But as a gate — an AND — it would break the primary case.** Repeat visits to one venue are spread
across days: the reporting device's two office rows carried 6 and 3 visits, and home has visits in both
cells every day. Requiring temporal contiguity *to merge* would re-fragment exactly the places this
track exists to repair.

**As corroboration for the ambiguous middle it is genuinely valuable, and needs no new data.** The
§9.5 gate as-built (§10.9) leaves one bucket doing the work on adjacency alone:

| both labels | verdict |
|---|---|
| blank, or equal | merge on adjacency — safe (the office: both rows carried the same name; only `city` differed) |
| **user**-authored and differing | never merge — the user is authoritative |
| **non-blank, differing, machine-guessed** | merges on adjacency alone — and this bucket holds *both* ice-cream-shop/town-hall (right) and café/bookshop-next-door (wrong) |

Time splits that last bucket:
- visits in the two rows **abut or overlap** (minutes apart, one stay) ⇒ drift ⇒ merge;
- each row has **its own visits on different days**, never abutting ⇒ two venues ⇒ leave alone.

⚠ It is a strong *positive* signal and a weak *negative* one — abutting proves drift, non-abutting
does not prove distinctness — so it may only ever arbitrate that bucket. It must never veto a merge
where the labels already agree. This is the corroboration §9.3 assumed had to wait for F4's synced
network tags; time gives a weaker version of it from data already on the device.

**Why it is not built.** The verdict needs the two places' *visit times*, i.e. a read of the moments
relation from inside `PlaceReconciler` — a proxy-side `DataObserver`, which reads
`database.getArrayData` and so gets the **whole** relation. That is the same unbounded read that
blocks P4e-2 (§10.9) and blanked Home at ~10.5k rows. `Place` cannot cache a first/last-visit either:
it is an operation parameter, so it cannot gain a field until E2's nested reshapes land (§10.8).

Demand-driven fetching (only when an ambiguous pair actually needs arbitrating — rare) would keep it
off the hot path, and remains the fallback if this is wanted before the read exists. **Preferred: wait
for the proper read.** Both this and P4e-2 are now recorded as named consumers of
`core-ordered-window-fusion.md` **F3**, together with the ⚠⚠ that F3 as currently specced serves the
*chain* evaluator and would need a **proxy-side bounded read entry point** to serve an observer at
all. One mechanism, built once, unblocks both — rather than two bespoke reads with two correctness
surfaces.

⚠ Until then the as-built gate stands, and its failure mode is known and acceptable: on the reporting
device it produced one over-merge in eleven places, and that one turned out to be **correct**.

### 10.13 As-built — P4e-2 (moment dedup in the proxy)

`MomentReconciliationPass` (`:timeline-service-proxy`), registered by `initTimelineService`. No
schema, wire, op or format change — `Moment.sources`/`supersededBy` and `MomentReconciler` all
shipped with I6a/I6b; **only the write pass was missing**, and only because "there is no place for a
persisted canonical to converge" (a premise that expired when Timeline moved onto Control's proxy). Until now the verdict was
recomputed per read, per device, unpersisted, and invisible to any client that did not run it.

**Bounded by the §6/F3 entry point.** The pass reconciles the most recent `windowSize` (200) moments
via `readOrderedWindow`, never the whole relation. That is sufficient for what it is *for*: the
duplicate it catches is created by a second device within hours of the first. ⚠ A years-old import
will not dedupe against equally old captures at write time — the documented cost of not scanning
history.

Two defects found while building it, both worth keeping:

- ⚠⚠ **Never CLEAR `supersededBy`, only set it.** The first version wrote the pass's full verdict,
  including `""` for anything it judged canonical — which **resurrects** a superseded moment whenever
  its counterpart is older than the window, or the verdict came from another device. A bounded window
  can prove *"these two are one moment"*; it can never prove *"this one is a duplicate of nothing"*.
  Re-exposing a moment (§7.1's reversibility) is a deliberate act elsewhere, never a side effect of
  what the window happened to contain. Caught by the existing `nearDuplicateMomentsCollapseAndSuperseded
  AreHidden`, which is exactly what that test was for.
- ⚠⚠ **A `Visit` whose place coordinates cannot be resolved is EXCLUDED, not clustered at (0, 0).**
  The cluster key is `placeCell + timeBucket`, so defaulting a missing coordinate to zero puts every
  unresolvable visit in ONE cell and merges unrelated places that share a time bucket. ⚠ The app-side
  pass this replaces did exactly that (`latE7 = p?.latE7 ?: 0`) — latent, and harmless only because
  its place join rarely missed. The place read is also `runCatching`-guarded: a timeline-only proxy
  has no places relation, and excluding every visit is the safe direction there too.

⚠ **Test-harness note.** `initTimelineService` installs the pass, so in a production-shaped test every
`saveData` already reconciles at the default window — which makes an explicit `reconcileRecent()` (and
any `windowSize` override) untestable. The suite therefore drives the pass on a proxy with the
observer dropped, plus **one** test that installs it and asserts the wiring fires. Verified as a real
detector: stubbing the write fails 2 of 8.

Tests: `MomentReconciliationPassTest` (8). Full gate + Android smoke gate green.

⚠ **The app-side read-time reconcile stays for now.** It is now redundant for anything the pass has
seen, but deleting it is a separate step — it still covers moments older than the window, and P4f's
tail should remove it only once that is accepted. `PlaceMergeFolding`-style flow-level removal is the
natural shape when it happens.

⚠ **Unrelated suite flakiness observed while gating this** (recorded so the next person does not
chase it): across repeated clean full-gate runs, `SqliteObjectStoreSoakTest`, `MultiRealmPushDrainTest`,
`MapRegionServiceTest.downloadProgressEndsWhenNoDownloadIsInFlight` and
`FilesScreenIntegrationTest.testFileUploadConflictShowsDialog` each failed **once**, in different runs,
and every one passes in isolation and in other full runs. Different tests each time, none touching
timeline or place ⇒ pre-existing parallel-load flakiness in the gate, not a regression from this work.

### 10.14 As-built — F4 (synced fingerprints)

`SyncedWifiFingerprintStore` + `NetworkTag` (`:timeline-shared-data`), wrapping the existing
`AndroidWifiFingerprintStore`. No schema or format change — the `PlaceNetworkFingerprint` type, its
op and its merger all shipped with P4d; this is the client half that fills them. Threat-model **A38**
lands in the same PR (HR #2).

- **Reads synchronous, writes not.** `resolvePlaceId` runs inside the capture collect loop and cannot
  suspend, so the synced rows live in an in-memory map; the service is never read on the hot path.
  Writes go through the local store *first* — durable immediately, and capture keeps working before
  the first sync, offline, and whenever Control is unreachable — then fire-and-forget to the service.
  A failed op costs the *sync* of a binding, never the binding.
- ⚠ **Hydration is a one-shot read, then a stream.** Following the stream alone leaves recognition
  blind until its first emission, and on a synchronous capture path "blind" means a venue mints a new
  place in the meantime — the exact failure F4 exists to stop. `refresh()` is also what makes the
  behaviour testable without racing a stream.
- ⚠ **No salt — a deliberate change from the §9.3 sketch.** A salt would have to ride in the realm for
  other devices to derive the same tag, so it would sit beside the tags it purports to protect and
  buy nothing against the only adversary that could use it, while adding a synced secret with its own
  genesis and rotation problems. A fixed domain separator
  (`SHA-256("tenshu:place-network:bssid:" ‖ lowercase(bssid))`) is exactly as strong against the
  threats hashing actually addresses (§9.3's honest analysis), with none of that machinery.
- ⚠ **BSSID tags only** (Q9 settled in §10.4). The SSID index stays device-local: one SSID spans a
  campus or a chain, and SSIDs are user-authored strings that often contain a person's name.
- **A rename-time binding syncs as `user` provenance**, which `PlaceNetworkFingerprintMerger` prefers
  over any peer's `learned` guess for the same network.

⚠⚠ **The consent copy was false the moment this shipped and had to change.** It read *"the network
names stay on this device and never sync"*. Half of that is still true (SSIDs never sync) and half
became a misstatement about what the toggle does. It now says recognised places are shared across the
user's own devices, that what travels is a scrambled id rather than the network name, and that the
relay can read none of it. A consent string that describes the old behaviour is worse than no string.

Tests: `NetworkTagTest` (4 — including "the raw BSSID never appears in the tag" and case-stability,
which is what makes two devices derive the same tag) + `SyncedWifiFingerprintStoreTest` (5, over a
real place-service proxy, including "a mirrored binding is recognised with no local record" and "the
SSID index is never synced"). Full gate + Android smoke gate green.

⚠ **A second test-harness lesson, same test as §10.11.** `twoRowsOfOneVenueReadAsOnePlace…` flaked
again: the poll predicate waited on `places().size == 1` while the assertions also covered
`visitsCount`. The poll returned the instant the merge landed but before the second visit was
counted, so the race simply moved one line down. **A partial predicate does not fix a race, it
relocates it** — poll on the whole expected state.

### 10.15 As-built — P4f's tail (manual merge, unmerge, and one decision reversed)

Manual merge and unmerge on place detail, plus the answer to "can the app-side read-time reconcile go
now?". No schema or format change.

**⚠ The read-time reconcile STAYS — the earlier plan to delete it was wrong, for a reason worth
recording.** §10.6 listed "delete the app-side read-time reconcile" as part of P4f, on the assumption
it was the expensive thing. It is not. The expensive read is `momentsFlow()` itself, which the feed
performs regardless; `reconcile()` runs over an **already-materialised list**, so removing it buys CPU,
not the unbounded read that blanked Home. Meanwhile it still covers what P4e-2's window cannot —
duplicates older than the recent 200, including everything written before that pass existed. Deleting
it would trade a real correctness property for a marginal saving. It stays until (and unless) a
progressive backfill stamps history, at which point the question can be asked again honestly.

**Manual merge** exists for the one pair the automatic pass deliberately refuses: two places the user
has **named differently** (§9.5's veto). ⚠ The survivor is *not* the row the user opened — it is the
deterministic root, `min(a, b)`, the same rule the automatic pass elects, so a hand-made merge and an
automatic one can never contradict each other. The loser's user-authored name travels to the survivor
**before** the merge row is written, so no read can catch the survivor unnamed while the other row is
already hidden. `merge()` hands the root back to the caller, because this screen's place may be the
one that disappears.

**⚠⚠ Unmerge forced a change to `PlaceMergeMerger`, and the first version was silently broken.**
Unmerge writes a **blank** target rather than deleting the row — the row is the carrier of the
decision. But the merger as shipped in P4d preferred a non-blank target unconditionally, so a peer
still holding the old merge would have **undone the user's undo**, forever, with no error anywhere.
The rule is now: *both* targets set and differing → the smaller id (unchanged, that is what makes two
devices agree); **one side blank → the incoming (newer) state**. Blank is not "no opinion", it is "the
user says these are different places", and the newer opinion is the one that counts. A stale peer
cannot resurrect the merge either — an older block never reaches the merger at all. `mergedAt` resets
on an unmerge for the same reason: "merged since" is meaningless once the merge is gone, and keeping
it would date a future re-merge to before the unmerge.

**Unmerge is offered only when something is actually folded into this row** (`mergedPlaceCount`),
because the folded rows are invisible by design — "am I a merge result?" cannot be answered by looking
at this place alone.

⚠ **Known UX wrinkle, not fixed here:** the read model is stream-backed, so a just-written merge is
visible *eventually*, not instantly. The detail screen's `load()` immediately after `merge()` can read
the pre-merge state; in practice the merge navigates to the survivor and the stream-backed Places list
updates itself, so it is not visible in the flow a user takes. It is visible in tests, which now await
the whole expected state.

Tests: three end-to-end cases in `TimelinePipelineIntegrationTest` (deterministic root + name carry,
unmerge restores both rows, a named survivor keeps its own name), plus `PlaceDetailMergeUiTest` (4) —
the click-through as a **Compose UI test** rather than a manual pass (HR #7), in a new
`:timeline-shared-ui` `desktopTest` source set mirroring `drive-shared-ui`'s harness.

**⚠ The click-through changed the design, which is the argument for doing it.** Rendering the screen
surfaced two problems no assertion would have caught:

1. **The merge affordance was a bare `Icons.Outlined.Merge` beside the rename pencil**, and at 24 dp it
   reads as a spindly glyph meaning nothing in particular. Rename earns an icon — it is frequent and
   the pencil is conventional; merge does not.
2. **"Separate merged places" sat between the HISTORY header and the visit list**, so a rare,
   corrective, structural action looked like a row of content.

Both now live **below** the content as grouped, *labelled* text actions ("Merge with another place" /
"Separate merged places"), aligned to the content's 16 dp margin (a `TextButton` carries 12 dp of its
own padding, so the enclosing column takes 4). The dialog itself needed no change.

### 10.16 As-built — time (and tags) as corroboration for the ambiguous middle (2026-08-01)

§10.12 built, over the §12 handover. **No schema, wire, op or format change** — every input was already
a synced row, which §12.8 said was the test of whether the slice was scoped right.

**Three parts.**

1. **`PlaceClustering.mayMerge` is three-valued** — `ALLOW` / `VETO` / `NEEDS_CORROBORATION`. The first
   two keep today's rules exactly; the third is the §10.12 bucket, *both* named, *both* machine-guessed,
   the guesses disagreeing.
2. **`PlaceCorroboration`** (`:place-service`) — a `Boolean?`-returning SAM consulted only for that
   bucket. ⚠⚠ **Three-valued on purpose:** `null` ("no evidence") is a different answer from `false`
   ("I looked; two venues"). Collapsing them would make an empty window read as proof of distinctness.
3. **`VisitTimeCorroboration`** (`:timeline-service-proxy`) — one `readOrderedWindow` over the recent
   500 moments per ambiguous pair; the two places' visit intervals abut or overlap ⇒ one stay ⇒ merge.

**Decisions, taken as §12.7 recommended.** D1: no evidence ⇒ merge, so the change is purely additive
(the `?: true` in `permits`). D2: abutting = overlap or ≤ 30 min gap. D3: **tags first** — a shared
`PlaceNetworkFingerprint` settles the pair without any read at all, and only then is time asked.

**⚠⚠ The narrowing §10.12 did not name: a machine guess never contests a user's name.** The obvious
reading of the table sends *any* differing pair of non-blank labels to corroboration, which would drag
in the mixed case (user-named "Home" beside its geocoded neighbouring cell). Those visits are spread
across days and would never abut ⇒ home would stop merging — the exact re-fragmentation §12.2 forbids,
arrived at by following §12.2's own table too literally. Mixed pairs stay `ALLOW`: the geocoder's guess
loses to the user's name, and `userLabelToCarry` already moves that name onto the surviving row.

**⚠ Geometry is evaluated before corroboration**, so only a spatially plausible pair can reach a store
read. Reversing the two conjuncts would put a moment window on every distant place pair — the shape of
read that blanked Home at ~10.5k rows.

**⚠ It lives on the timeline side, not in `PlaceReconciler`.** The verdict needs `Moment` rows, and
place is the *lower* layer (timeline depends on place, never the reverse). The wiring that hosts both
injects it: `initPlaceService(mergerRegistry, corroboration = VisitTimeCorroboration(this))` in the
three `PlatformProxyModule`s. The parameter is **optional**, unlike `mergerRegistry` — omitting it is
not a silent behaviour change (`PlaceCorroboration.Unknown` merges exactly what the pass merged before),
and Maps genuinely hosts place with no visit history. The JS runtime registers no place service at all.

**Tests.** The gate cases on the three-valued verdict; `VisitTimeCorroborationTest` per path (abutting /
overlapping / own-days / one side silent / outside the window / a non-`Visit` moment); a
`PlaceReconcilerTest` case per corroboration path. Two are the polarity rule as executable assertions:
*a source that refuses everything must not change a single verdict the labels already settle*, at both
the pure and the wired level. ⚠ Detector-checked both ways — a constant-`null` corroboration fails 4 of
7, an always-merge gate fails 3.

**⚠ Existing call sites broke on a trailing lambda, not on types.** `PlaceReconciler(ctx) { 42L }` bound
its lambda to the *new last* parameter the moment one was added. Harmless here (the compiler caught it),
but the same shape would be silent if the new parameter had also been a `() -> Long`.

**Remaining honestly.** Whether the time path ever fires in practice is unmeasured — §12.7 D3 asked for
that measurement before tuning D2, and post-F4 the tag may settle nearly every ambiguous pair. Nothing
here re-examines a merge already recorded: the pointer stays until removed, so this only shapes future
folds.

---

## 11. P4g — desktop capture (design pass, no code)

§10.5 sketched this in four lines off the back of F4. F4 has now shipped, so the sketch is worth
turning into something schedulable — and doing that immediately falsified one of its claims.

### 11.1 What F4 actually bought, and the claim that was wrong

The sketch said a laptop needs **no location permission whatsoever**: read the connected BSSID, look
it up in the synced fingerprints, and the place identity is exact. The lookup half is right and is now
real. The permission half is not, on the platform the maintainer actually uses:

⚠⚠ **macOS gates the connected BSSID behind Location Services.** Apple treats the AP identifier as
location data; since Sonoma, `CWInterface.bssid()` returns `nil` (and the `airport` CLI is gone)
unless the calling app holds a location authorisation. So on macOS the desktop needs the *same*
consent prompt a phone does — it just needs it for a much narrower purpose. On Linux (`nmcli` /
`iw dev … link`) and Windows (`netsh wlan show interfaces`) the read is unprivileged and the original
claim holds. ⏳ **Verify on the maintainer's machine before building** — this is from Apple's
documented behaviour, not something this repo has observed, and the whole slice's UX story depends on
it.

What F4 *did* buy is unchanged and is the real point: a desktop can recognise a place **it has no way
to sense**, because a phone taught it. It never derives a coordinate, never runs a geocoder, never
sees a map tile. And it is self-limiting by construction — an unknown network yields nothing.

### 11.2 ⚖️ Where capture runs — Control, not the Timeline app

Both hosts exist: `:timeline-desktop` is a real Compose app (viewer + Google-Timeline import), and
Control is the desktop proxy and tray process.

**Decision: Control.** The signal being captured is *"the user is present at this machine"*, and that
is true whether or not a Timeline window is open — indeed it is most true when the user is busy in
something else. Hosting capture in `:timeline-desktop` would mean the record depends on the user
having left a viewer app running, which is both a bad proxy for presence and a silent-data-loss shape
(the app is closed, so nothing is recorded, and nothing says so). Control already runs continuously,
already holds the store, and already owns the desktop tray.

⚠ This inverts the Android arrangement, where capture lives in Timeline's own foreground service — and
that asymmetry is *because* the platforms differ: Android gives an app a legitimate always-running
service with a user-visible indicator; desktop gives a viewer app no such thing.

**Consent stays the user's, and stays per-device.** The opt-in must not be a Control-only checkbox
buried away from the app whose data it is, and it must not be realm-wide either — "capture on my
phone, not on my work laptop" is an obvious and reasonable wish. ⇒ **a device-scoped `DeviceSetting`
via `:preference-service`**, surfaced in Timeline's Settings (where the existing capture and Wi-Fi
toggles live) and read by Control. That also retires a small existing wart: `AndroidWifiFingerprintControl`
and friends are hand-rolled `SharedPreferences` singletons predating the preference service.

### 11.3 ⚠⚠ The capture model is coordinate-first; desktop is place-first

This is the deepest thing the sketch missed. Every existing capture path starts from a **fix** and
derives a place: `CapturedVisit(latE7, lngE7, arrivalMs, departureMs, accuracyM)` → `VisitDetector`
grid-snaps a centroid → a `placeId`. A desktop has no fix at all. It starts with a **placeId** and has
nothing else.

Two ways out:

- **(a) Borrow the resolved place's own coordinates** and its `accuracyM`, then write an ordinary
  `CapturedVisit`. Cheap, reuses the whole existing pipeline unchanged, and semantically honest — the
  assertion is *"the user was at this place"*, and the place's coordinates are exactly what that
  means. It also keeps `MomentReconciliationPass` working, which needs coordinates for its cluster key
  (§10.13).
- **(b) Widen the write path to accept a place-identified visit with no coordinates.** Cleaner in
  principle, but it puts a null-coordinate shape through `VisitDetector`, `CapturedVisitWriter`,
  `ImportedPlace` and the reconciler — every one of which currently assumes a coordinate, and one of
  which (§10.13) treats a missing coordinate as *"exclude this from reconciliation"*.

**Recommendation: (a).** ⚠ With one hard rule carried over from §10.5: a desktop dwell may **never
create a place**. An unknown network records nothing at all. That is what stops coordinate-less
guesses polluting place identity, and it is why (a) is safe — the coordinates it borrows always come
from a place a *phone* established.

### 11.4 The presence signal — what counts as a dwell

"Connected to the office AP" is not "at the office" (§10.4's rule: a device on a network is not a user
at a place — a laptop left at work is the canonical counter-example). The dwell therefore needs
presence at the machine, not network attachment:

> **screen unlocked** ∧ **last input within `IDLE_GRACE`** ⇒ present. A dwell opens on the first
> present sample at a recognised network and closes when either conjunct fails for longer than the
> grace, or the network changes.

Coarse is fine: `minDwellMs` is already 5 minutes, so a sampling interval of ~60 s is plenty and cheap.
Per platform: macOS `CGEventSourceSecondsSinceLastEventType` (a `:macos-helpers` binary, the
`MacImageDecoder` / `MacPeerAttestation` precedent); Linux `logind` `IdleHint` or the X11/Wayland idle
protocol; Windows `GetLastInputInfo`. All behind one `PresenceProbe` seam so v1 can ship one platform.

⚠ Deliberately **no movement capture** on desktop. A laptop's "trip" is a bag being carried; there is
no fix stream to segment and nothing worth recording.

### 11.5 How it lands in the existing model

- Source tag **`desktop`**, added to `MomentSourceTag` and ranked **below** `CAPTURE` in `PRIORITY`.
  When a phone and a laptop record the same lunch, `MomentReconciliationPass` (§10.13) folds them and
  the phone's coordinate-derived moment wins the canonical election — the desktop's is corroboration,
  not evidence of *where*.
- Writes go through `CapturedVisitWriter` → `TimelineImportWriter` like every other source, so F1's
  identity snap, the §4.4 clobber guard and the place reconciler all apply unchanged.
- ⭐ The corroboration §10.4 wanted falls out for free: a desktop dwell and a phone dwell that share a
  **network tag** in the same window are colocation evidence stronger than either device's GPS — and
  it is exactly the input §10.12's time-corroboration wants for arbitrating an ambiguous place merge.

### 11.6 Threat model

No new **synced** asset: the fingerprints are A38 (F4) and the moments/places are A35's existing
capture assets. What is new is local: a periodic read of the connected AP and of idle time, both
device-local, neither stored. ⚠ The A35 amendment is a note, not a new asset — *"on desktop, capture
acquires no location at all; the place comes from a synced fingerprint a phone established, and the
dwell from presence at the machine"* — which is a genuine narrowing of what desktop capture can learn
compared with every other platform. ⚠ macOS's Location Services requirement (§11.1) is a *consent*
fact, not a data-flow one: no coordinate is read even when the permission is held.

### 11.7 Phasing

1. **P4g-1 — consent + seams.** The device-scoped capture preference (§11.2), `PresenceProbe` and
   `ConnectedWifi` on desktop as no-op-able seams, Timeline Settings surfacing the toggle. Ships
   nothing user-visible but makes the rest independently testable.
2. **P4g-2 — macOS reads.** The `:macos-helpers` binary for BSSID (CoreWLAN) + idle
   (`CGEventSource`). ⏳ Gated on §11.1's verification. Linux/Windows follow the same seam later.
3. **P4g-3 — the write path.** Place-first dwell → borrowed coordinates → `CapturedVisitWriter`, the
   never-create-a-place rule, the `desktop` source tag and its priority. Unit-testable end to end with
   fake probes; this is where the correctness lives.
4. **P4g-4 — other platforms**, if wanted.

### 11.8 Open questions

- **Q15 — is a laptop's presence even worth recording?** A desktop dwell adds no *place* the phone did
  not already know, and a phone in your pocket already records the office. The honest value is (a)
  corroboration for §10.12's ambiguous merges and (b) covering the case where the phone is dead or
  left elsewhere. ⚖️ Worth confirming that is enough before building P4g-2/3 — this is the one slice in
  the track whose *user-visible* benefit is indirect.
- **Q16 — does an always-on Control on desktop actually hold?** The tray process exists, but the
  desktop-tray memory notes it only works from the packaged `.app`. A `gradle :run` session would
  capture nothing, which is a confusing developer experience worth a named log line.
- **Q17 — sampling interval vs. battery.** 60 s of "read idle time + read BSSID" is trivial on mains
  but non-zero on a laptop. Suggest 60 s while present, backing off to 5 min after an idle transition.

---

## 12. Implementation notes for the next session — §10.12 time corroboration

> **DISCHARGED 2026-08-01 — built as §10.16.** Kept verbatim below: its decisions D1–D3 were taken as
> recommended and its trap list held (the `initPlaceService`-installs-the-pass trap and the
> `recordPlace`-not-`TimelineImportWriter` trap both applied again). ⚠ One thing it got *wrong by
> omission* — §12.5's "mayMerge becomes three-valued" reads, against §12.2's table, as sending every
> differing pair of non-blank labels to corroboration, including the mixed user-vs-guess case. That
> would have re-fragmented home. See §10.16's ⚠⚠.

The canonical handoff section, per the repo convention. §10.12 has the *design*; this has what a fresh
session needs to build it without re-deriving the context. Everything it depends on has now shipped.

### 12.1 The task in one paragraph

`PlaceClustering.mayMerge` currently vetoes exactly one thing: **both** labels user-authored **and**
different. Everything else merges on proximity or grid adjacency. That leaves one bucket doing real
work on thin evidence — **both labels non-blank, differing, machine-guessed** — and that bucket holds
both the ice-cream-shop/town-hall case (a pause on a walk; merging was *right*) and the
café/bookshop-next-door case (merging would be wrong). Time splits it: visits that **abut or overlap**
are one stay split by drift; visits that each recur **on their own days, never abutting** are two
venues.

### 12.2 ⚠⚠ The one rule not to get wrong

**Positive signal only.** Time may *permit* a merge in the ambiguous bucket. It must **never veto** a
merge where the labels already agree or one is blank. Repeat visits to one venue are spread across
days — the reporting device's two office rows carried 6 and 3 visits — so a "must abut to merge" rule
would re-fragment exactly what this whole track repaired. If the implementation ever reads as "no
temporal evidence ⇒ don't merge" *outside* the ambiguous bucket, it is wrong.

### 12.3 What is available now that was not when §10.12 was written

| Need | Now provided by |
|---|---|
| A bounded read reachable from a proxy observer | `ExecutionContext.readOrderedWindow(reference, orderByFieldId, limit, descending, keepFields)` — `core-common/.../OrderedWindowFusion.kt` |
| The pattern for a bounded moment read + place-coords join | `MomentReconciliationPass` — `timeline-service-proxy` |
| Network-tag corroboration (the §9.5 original gate) | F4 shipped: `PlaceNetworkFingerprint` rows now sync, so "do these two places share an access point" is answerable |

⚠ That last row matters: **the design assumed time was the only corroboration available. It no longer
is.** A shared network tag is stronger evidence than temporal proximity (same AP ⇒ tens of metres) and
needs no moment read at all. Consider doing tags *first* and time only as the fallback — see D3.

### 12.4 Where the code lives

- `place-service/.../PlaceClustering.kt` — the pure decision. `mayMerge` becomes three-valued.
- `place-service-proxy/.../PlaceReconciler.kt` — the store access; already reads places, merges and
  fingerprints, so the tag join is nearly free there.
- `timeline-service-proxy/.../MomentReconciliationPass.kt` — copy its shape for the moment read.
- `place-service/.../PlaceGrid.kt`, `PlaceProximity.kt` — the existing predicates, unchanged.

### 12.5 Suggested shape

1. `PlaceClustering.mayMerge` → returns `Allow` / `Veto` / `NeedsCorroboration` (only the third is new;
   the first two keep today's rules exactly).
2. `PlaceReconciler` resolves `NeedsCorroboration` **demand-driven** — the ambiguous pair is rare, so
   the extra read must not run on every captured dwell. Order: shared network tag (already in hand) →
   visit-time overlap (one `readOrderedWindow` over `MOMENTS`, filtered client-side to the two
   `subjectId`s) → unresolved.
3. Feed the verdict back as a plain boolean into the existing cluster construction; nothing else in
   the pass changes.

### 12.6 ⚠ Traps this track already paid for

- **The reconciler runs inside a save observer** — synchronous, cannot suspend. `readOrderedWindow` is
  synchronous, so this works; a service read would not.
- **Idempotence.** The verdict must be deterministic from the rows, or the pass writes, re-enters
  itself and flip-flops. Re-running over unchanged input must produce nothing.
- **⚠⚠ `initPlaceService` installs `PlaceReconciler`**, so in a production-shaped test *every*
  `saveData` already reconciles at the default window — an explicit call is untestable. Drop the
  observer (`observerRegistry.clear()` + `installRelationMaintainer()`), exactly as `PlaceReconcilerTest`
  does, and keep **one** test that proves the wiring fires.
- **⚠⚠ Do not seed test places via `TimelineImportWriter`** — F1's snap folds the second capture onto
  the first id, so the two rows you need never exist and the test passes for the wrong reason (this bit
  twice). Use `recordPlace` directly.
- **Poll the *whole* expected state** in integration tests. A partial predicate relocates a race rather
  than fixing it (bit twice, §10.11 and §10.14).
- **Verify the tests are detectors** by stubbing the new decision to a constant — every slice in this
  track did, and it caught a test that proved nothing.

### 12.7 Decisions to make first (do not let these get made silently)

- **D1 — no-evidence default.** When neither tags nor times can decide an ambiguous pair, merge or not?
  Today's behaviour merges. Choosing "don't" is a **behaviour change for existing data**, and would
  un-merge nothing already recorded (the pointer stays until removed) but would stop future ones.
  ⚖️ Recommend: keep today's behaviour (merge) so the change is purely additive, and revisit only if a
  wrong merge is actually reported.
- **D2 — what counts as abutting.** Suggest ≤ 30 min between the end of one visit and the start of the
  other, or any overlap. Wants a real example; the town-hall → ice-cream-shop walk is the one on file.
- **D3 — tags first, or time first, or both?** Tags are stronger and cheaper (§12.3). Time covers pairs
  with no shared network — which, post-F4, may be a small minority. ⚖️ Recommend tags first, time as
  fallback, and measure whether the time path ever fires before investing in tuning D2.

### 12.8 Definition of done

Unit tests on the three-valued gate; a `PlaceReconcilerTest` case per corroboration path; the detector
check; full `desktopTest jvmTest test` gate and `scripts/android-smoke.sh`. ⚠ No schema, wire or format
change is expected — if one appears, something has been mis-scoped, since every input already exists as
a synced row.

---

## 13. As-built — the Wi-Fi seams on iOS (2026-08-09)

§4.5 / F2 / F4 shipped Android-only: `TimelineAndroidModule` was the sole place binding
`WifiFingerprintControl` / `ConnectedWifi` / `WifiFingerprintStore`, so on iOS every consumer that
needs the radio was wired to `null` and silently did nothing. This slice binds them.

⚠ **Nothing in `commonMain` changed.** The shared `WifiPlaceFingerprint`, its state vocabulary and
the Android actuals are untouched; this is four new `iosMain` classes plus wiring.

### 13.1 What was actually missing, per consumer

Of the five things that read a fingerprint, only three were dead on iOS — worth stating precisely,
because "Wi-Fi doesn't work on iOS" was too coarse to schedule against:

| Consumer | Before | Now |
|---|---|---|
| Dwell placeId override (capture) | dead — `IosLocationCapture` had no hook at all | wired |
| `bindCurrentNetwork` on rename | dead — `TimelineProjectionRepository(wifi = null)` | wired |
| `sharesNetworkTag` merge arbitration (proxy) | **already worked** — reads the synced relation | unchanged |
| Cross-device seeding | dead — no store bound | wired (`SyncedWifiFingerprintStore`) |
| Settings status line | dead — no toggle | wired |

The third row is why an iPhone already benefited from tags an Android phone learned: that consumer
lives in `PlaceReconciler`, over synced rows, and never touches a radio.

### 13.2 ⚠⚠ Two iOS-only properties the Android design does not have

Both land on the same call, which is why `IosWifiRecognition` exists rather than the app wiring the
facade directly:

1. **The radio read is asynchronous, and its completion is delivered on the main queue.**
   `NEHotspotNetwork.fetchCurrent` is the modern API (`CNCopyCurrentNetworkInfo` is deprecated), and
   its own SDK header states the queue. `ConnectedWifi` is a *synchronous* interface — deliberately,
   because `resolvePlaceId` runs inside the capture path and cannot suspend — so a semaphore bridge
   would deadlock the moment it were called from the `CLVisit` delegate, which runs on main. The read
   is therefore split: `IosConnectedWifi.refresh()` awaits a real fetch, the accessors serve what it
   cached, and the capture path awaits the refresh immediately before resolving.
2. **The synced fingerprint mirror is empty on a cold wake.** A `CLVisit` relaunches the app into a
   fresh process and `SyncedWifiFingerprintStore` hydrates asynchronously — so without an awaited
   refresh, the very first dwell of the wake (the one the process was woken *for*) resolves against
   an empty map, misses a binding another device learned, and mints a duplicate place. That is
   exactly the fragmentation F4 exists to prevent, and iOS is the only platform where the race is the
   common case rather than a corner. `IosWifiRecognition.resolvePlaceId` awaits both refreshes.

⚠ The **rename** binding cannot await anything — it runs through shared code that has no suspend
seam — so it reads a cache the shell warms on every foreground (`TimelineSurface.refreshWifi()` in
`TimelineShippingRoot.connect()`). Realistic, because renaming happens with the app in front of you.

### 13.3 ⚠⚠ The entitlement gate — wired but inert on the current signing tier

`fetchCurrent` returns `nil` without **`com.apple.developer.networking.wifi-info`** ("Access WiFi
Information"), per its header, *and* without precise-location authorization. Capture already holds
the location grant, so the entitlement is the live gate — and enabling it requires the App ID
capability in the developer portal, which a **free personal team cannot do**. It is the same paid
rung as App Groups ([`ios-signing.md`](../how-to/ios-signing.md)), and the two behave differently
when you get it wrong: an ungrantable App Group falls back to an app-local container, while an
ungrantable entitlement declared in the profile makes the build **fail to sign**.

So it is declared in `Timeline.entitlements` (simulator, ad-hoc) and deliberately **left out of**
`Timeline.device.entitlements`, with the exact key to uncomment recorded there. On a paid team it is
a two-line change and no code moves.

⚠ Until then the subsystem is genuinely inert on device: `IosConnectedWifi` answers `null`,
`WifiPlaceFingerprint` publishes `NoNetwork`, and every dwell keeps its grid-snapped id — today's
behaviour exactly. ⚠ `fetchCurrent` also returns `nil` on the **simulator**, which has no Wi-Fi
interface of its own, so the sim cannot verify the working path either.

### 13.4 ⚠ The observability hole this opens, and the cheap mitigation

On Android the actionable failure is distinguishable: the platform hands back the redacted
`02:00:00:00:00:00`, which S7 surfaces as `IdentityWithheld` — "turn on Location Services". iOS has
no such sentinel; a missing entitlement and a genuinely absent Wi-Fi network are **both** `nil`, so
both land on `NoNetwork` and the status line says "no Wi-Fi network detected" while the real answer
may be "this build can never do this".

Runtime entitlement introspection would settle it, but `SecTask*` is not public on iOS. A
`NWPathMonitor` check (path is `.wifi` yet `fetchCurrent` is nil ⇒ conclusively the capability) would
also settle it and is the honest follow-up; it was judged not worth the C-interop surface while the
feature is gated anyway. The interim is a **one-shot diagnostic** (`wifi_fetch_unavailable`) logged
on the first nil fetch, naming the entitlement — so the answer is one log tail away rather than a
mystery. ⚠ Gated on never having seen a network, so an ordinary device on cellular does not log it.

### 13.5 As built

- `IosWifiFingerprintControl` — the opt-in over `NSUserDefaults` (off by default; survives the
  process teardown between location events).
- `IosWifiFingerprintStore` — the device-local two-index store. ⚠ **Per-key entries, not one stored
  dictionary**: `NSUserDefaults` is atomic per key but a read-modify-write of a dictionary is not,
  and there are two writers (the capture worker and the rename path) on different coroutines. The
  cost lands on `knownPlaceIds()`, which filters a prefix out of the flat app-wide domain rather than
  reading a small dedicated file — affordable because it runs only on an *unmatched* dwell, and on
  iOS a dwell is a `CLVisit`.
- `IosConnectedWifi` + the `RefreshableWifi` seam — the fetch-and-cache half. The seam exists so a
  test can supply a network without a radio; `FakeWifi` models the real property (no value until
  `refresh()` runs), which is what makes the ordering testable at all.
- `IosWifiRecognition` — the assembled stack: refresh-then-resolve, refresh-then-probe, `close()`.
- Wiring: `TimelineLocalRuntime.assemble` builds **one** stack and shares it with the repository
  (rename), `TimelineCaptureRuntime` (dwell) and `TimelineSurface` (toggle + status). ⚠ One instance
  deliberately — two facades would give Settings a different opinion of what capture just did.
- `SettingsView` gains the toggle plus the status line, behind the same live-runtime gate as capture.

⚠ **Route 2 (EPC, `DEV_HARNESS`) is not wired** — `EpcTimelineClient` builds its own repository and
still passes no fingerprint. First-party iOS is route-1; the harness tier is left alone deliberately.

### 13.6 Verification

`timeline-shared-data:iosSimulatorArm64Test` (10 new: store round-trip and case rules, the opt-in,
the refresh-before-lookup ordering **and its control** — the facade alone cannot resolve — consent-off
inertness, learning, and no-network degradation) plus `tenshu-sdk-ios:iosSimulatorArm64Test` (5 new:
the availability gate, the opt-in passthrough, and the four status keys Swift switches on).
`scripts/ios-smoke.sh --build-only timeline` passes.

⚠ **Not verified, and not verifiable here:** the actual radio read. It needs the paid entitlement and
real hardware — the simulator returns nil regardless. Everything above tests the contract *around*
the fetch, which is where the Android bugs were, but the fetch itself is on-device work.
⚠ Pre-existing and unrelated: `ContactsLocalRuntimeTest` has two failures on `master`.
