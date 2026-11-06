# Apple Maps as a user-elected renderer on iOS

**Status:** v1.5 — **ACCEPTED 2026-08-13.** AM-1 + AM-2 + AM-3 + AM-4
+ **AM-4b** shipped: the election, the renderer with the A35 amendment, the parity pass
(§8.2), the fallback offer, and the coverage predicate that makes that offer reachable (§8.4) —
which also fixed the same empty-tile bug on **Android**, where it was equally live and equally
unnoticed. §8's phase map was re-derived from the code, and **D9** corrects D2's "per device" to per
*app*. **AM-5a** (§8.1) answered the macOS entitlement question — *none is needed*, and claiming one
without a profile is what would break a build — and **AM-5** (§8.5) then shipped macOS itself:
`MKMapSnapshotter` → PNG → Compose `Image`, no window-embedding bridge. **Every phase this doc
planned has shipped.** ⚠ Two gaps are named rather than closed: only Timeline's settings screen
hosts the desktop toggle (Fit and Maps have no settings screen), and the desktop UI is
compile-verified only — the renderer was proven by invoking the helper directly.
Narrows the platform's standing
*"No MapKit. Ever."* rule from an **absolute** to a **default**: MapLibre-over-PMTiles stays what
every user gets, and MapKit becomes an explicit, revocable, per-device election for users who
prefer it. §5 **D1–D10 are decided**; §8 carries the phase map.

⚠⚠ **What acceptance does and does not license.** MapKit may be written **only** as the elected
renderer of the three heroes (§5 **D6** — place detail, trip/movement route, workout route) on
iOS/iPadOS — and, since AM-5, on **macOS** behind the same shape (D4's deferral is spent; §8.5). It
is still forbidden as *the* renderer (**D1**), as the region picker, as an Explore/Navigate surface,
and on Android at all (§9 **Q4**). ⚠ A hero that renders MapKit with no consent gate is
the absolute rule broken, not the default one narrowed — **§8 v1.1 makes that unrepresentable** by
landing the election (AM-1) *before* the renderer (AM-2), which is why the earlier
"AM-1 and AM-2 must land together" rule no longer appears.

✅ **The threat-model amendment landed with AM-2** (D8 / House Rule #2), as §7 specified: A35 gains
sub-clause **(f)** (rendering is a second egress surface on the asset), §8.18 gains **T-MR-05** with
the residual stated plainly, the surface list gains **#18**, and the non-change — sync plane,
custody, sealing, House Rule #1 — is recorded there rather than left to be inferred.

The four sites that used to read "never" — `ios-fit-timeline-maps-parity.md` §4 / §8 D1,
`maps-app.md` §3, `timeline-movement-capture.md` §7 (M5's iOS sibling) and `docs/README.md` — were restated on
acceptance (2026-08-13). ⚠ Four **code** comments still say MapKit is not an option
(`ios-basemap/Sources/TenshuMapView.swift`, `maps-ios/Sources/MapsMapHero.swift`,
`timeline-ios/Sources/TimelineMapHero.swift`, `timeline-ios/Sources/RouteSketch.swift`) — correct
for the default path they annotate, and AM-1 restates them where it lands beside them.

Companions: [`maps-region-delivery.md`](maps-region-delivery.md) (the tile plane this would sit
beside — D6 egress, D10 streaming), [`ios-fit-timeline-maps-parity.md`](ios-fit-timeline-maps-parity.md)
(§3.3.1 the iOS map as built, §4 the rule this narrows), [`place-geocoding.md`](place-geocoding.md)
(D5 — the consent shape this copies), [`maps-app.md`](maps-app.md) §3 (the renderer decision),
threat model **A35**.

**Trigger (2026-08-12):** *"while it offers less privacy than our current one, it is free on iOS
and Mac basically, frequent iOS users are used to its look, and Apple does at least a lot for
privacy. So for some users being able to choose between our maps and Apple Maps would be a
feature."* The question is fair and was never actually asked — see §1.

---

## 1. What was decided, and what wasn't

The rule appeared in four places and was consistent in all of them. ⚠ **All four were restated on
acceptance (2026-08-13)** — the quotations below are what they said *before*, kept because §1's
argument is about what that wording did and did not decide. Line numbers are pre-edit:

- `ios-fit-timeline-maps-parity.md:422` — *"⚠⚠ **No MapKit. Ever.** Apple-served tiles would
  disclose a route's region to a third party on every detail open — the A35 posture… the iOS map
  answer is MapLibre over the same offline PMTiles or nothing."* (recorded as decision **D1**).
- `maps-app.md:77` — MapLibre Native everywhere, *"**not** Apple MapKit as renderer (third-party
  leakage from inside the privacy-flagship app; forks styling)"*.
- `timeline-movement-capture.md:393` — the same reasoning for the movement hero.
- `docs/README.md:512` — the index restatement.

**Every one of those rejects MapKit *as the renderer* — the thing every user gets, with no say.**
None of them considers a user-elected alternative that is off by default. That distinction is not a
loophole: "we will not ship a product that leaks your map views to Apple" and "we will not let you
choose to" are different claims, and only the first was argued.

One thing is already permitted and unchanged by this doc: **Apple/Google Maps as a *Navigate*
hand-off** (`maps-app.md` §3) — launching their app with a destination. That is deferred with the
Navigate tab and is orthogonal.

---

## 2. Two facts that make this worth reopening

### 2.1 Since D10, our own tile plane egresses per view too

The rule was written against the **whole-region-bundle** model, where the privacy property was
categorical: download Bayern once, then render forever with **zero further egress**. Under that
model, "Apple sees every detail open" versus "our CDN saw one download, once" is a difference in
kind.

**D10 changed that** (`maps-region-delivery.md` §1, v0.4). Streaming is now the *default*: the
proxy range-fetches tiles on demand and caches them; whole-region prefetch became the **opt-in**.
The doc names the cost itself: *"streaming lets the tile host observe a coarse viewport pattern by
IP… which whole-region-only avoided."*

So the honest current comparison is not first-party-vs-third-party. It is:

|  | **MapLibre + our tiles (today, streamed default)** | **MapLibre + downloaded region (opt-in)** | **MapKit** |
|---|---|---|---|
| Who observes a view | our tile host (static object storage + CDN) | nobody | Apple |
| What they observe | coarse viewport pattern + IP, at view time | — | viewport + IP, at view time |
| Can it be reduced to zero? | ✅ by downloading the region | already zero | ❌ never |
| Coverage | catalog regions only | downloaded regions only | global, no setup |

**The difference that survives is counterparty and reducibility**, not "leaks vs doesn't leak."
That is still a real difference and it is the one this doc must defend — but it is much narrower
than the sentence "No MapKit. Ever." implies, and the sentence has not been revisited since D10.

### 2.2 Today, no region means no geographic map at all

`ios-basemap/Sources/BasemapHeroes.swift:47-55` gates the map on **both** halves holding: a route of
≥ 2 points **and** `:basemap-core` resolving an archive. Otherwise the caller's schematic fallback
runs — `RouteSketch.swift`, a normalized sketch on a blank ground. That is a deliberate, correct
degradation (better than a blank rectangle), but the user-visible consequence is:

⚠⚠ **This section was true of the design and false of the code until AM-4b (§8.3, §8.4).** "Resolving
an archive" asked only whether one *existed*, and every shell bundles the Firenze demo — so a user
outside it got that archive rendered with no tiles for their area, i.e. a route on an **empty
background**, not the sketch described here. On Android too. AM-4b made the sentence below true by
making the resolver ask whether the archive *covers* the target.

> A user who opens Timeline in a city we have not built an extract for sees a coloured squiggle
> where a map should be.

The catalog is a fixed set of pre-built signed extracts (`how-to/tiles-pipeline.md`); the planet is
the ambition, not today's state. **Coverage — not familiarity — is the strongest argument for the
option**, and it is one the trigger message didn't even make.

---

## 3. What Apple actually costs, stated precisely

Not *"Apple sees your location"* — they may already, via Location Services, and A35 already records
that `CLGeocoder` is server-backed ("Native ≠ private"). The specific new disclosure is:

> **Which place, trip, or workout you chose to look at, and when** — the tile requests for a
> place-detail hero are a viewing-behaviour trace over exactly the A35 asset, re-derived from the
> UI rather than from capture.

Mitigating facts, stated fairly because the proposal fails without them:

- MapKit on Apple platforms needs **no API key and has no per-request billing** (MapKit **JS** is
  the metered one — irrelevant here). The user's "free" is correct.
- Apple's stated MapKit posture — fuzzed/truncated location for map service calls, rotating
  identifiers, no Apple-ID association — is genuine engineering, not just marketing. It is not
  verifiable by us, which is the whole point of the zero-trust posture, but it is not nothing.
- The disclosure is **per-device and per-election**: a user who never touches the setting is
  unaffected, and the sync plane is untouched in every case (House Rule #1 is not in question —
  no coordinate reaches the relay either way, and `Place`/`Moment` custody is unchanged).

---

## 4. Why not an onboarding decision

The trigger suggested *"ideally this would be a simple onboarding decision."* I'd argue against
that specific placement, for three reasons:

1. **It asks cold.** At onboarding the user has no places, no trips, no workouts — nothing whose
   disclosure they can evaluate. A privacy trade offered before the data exists is a coin flip
   dressed as consent.
2. **The platform already has a precedent for this exact shape, and it isn't onboarding.**
   `PlaceGeocodingControl` (`place-geocoding.md` D5) is the established pattern for *"a third party
   learns a coordinate"*: a persisted flag, **off by default**, platform-bound via Koin `getOrNull`
   so the toggle renders **only where an implementation is bound**, **revocable**, and **re-checked
   before every lookup** so disabling stops a run mid-way. iOS already has its instance —
   `IosPlaceGeocodingControl` over `NSUserDefaults`, surfaced in Settings and installed by
   `TimelineSurface`. Copying it is cheaper than inventing a second consent idiom.
3. **Onboarding is a fork; this should be a setting.** A fork implies symmetry between two equal
   choices. These are not equal: one is the platform's posture, the other is a considered
   concession. The default must be MapLibre for anyone who never opens Settings.

**Where it *should* appear:** in Settings beside the geocoding toggle, and — the one placement worth
arguing for — as an inline affordance **on the schematic fallback itself**, exactly where the user
is looking at a squiggle and wondering why there is no map. That is the moment the trade is legible:
*"No offline map for this area. Download the region, or use Apple Maps here (Apple will see which
places you view)."* Two honest options at the point of failure beat one abstract question at first
launch.

---

## 5. Decisions — **accepted 2026-08-13**

- **D1 — MapLibre stays the default, on every platform, forever.** This doc narrows an absolute to
  a default; it does not demote the offline basemap. A user who never opens Settings must be
  unaffected in look and in egress.
- **D2 — The election is per device, off by default, revocable.** Mirrors `PlaceGeocodingControl`
  in shape and in copy discipline (disclose what the other party learns *before* the toggle, the
  `CalendarsScreen.kt:137` framing). ⚠⚠ **"Per device" is corrected by D9 — it is per *app*.**
- **D3 — Device-local storage (`NSUserDefaults`), not a synced `DeviceSetting`.** Both are viable;
  device-local wins because a synced setting is **remotely flippable** — another device (or a
  Control surface) could enable a third-party egress channel on a phone that isn't in the user's
  hand. An egress consent should not be actuatable from elsewhere. Cost: it is not visible in
  Control's device list, and does not survive a reinstall. Accepted.
- **D4 — iOS/iPadOS first; macOS is *possible* and deliberately deferred.** ⚠ An earlier draft of
  this line claimed the desktop apps "cannot reach MapKit without a native window-embedding
  bridge." **That is wrong, and the repo already contains the disproof** — see §6.5. macOS is
  scoped out here because it is a second consent surface, a second renderer parity pass and an
  entitlement question (§6.5), not because it is infeasible.
- **D5 — Election does not disable the region machinery.** A device on MapKit still downloads,
  holds and manages regions normally; the Control *Offline maps* surface stays meaningful. Rationale:
  the election is revocable, and a user who flips back must not discover their regions were GC'd.
  It also keeps the two paths independently testable.
- **D6 — MapKit renders the same three heroes, and nothing more.** Place detail, trip/movement
  route, workout route. It does **not** become the region picker (which is a list on both platforms
  by design — parity §3.3.1 item 2), does not enable pan/zoom where MapLibre has it disabled, and
  does not gain an Explore/Navigate surface (`milestones.md` → Out of Scope → *Maps full*).
- **D7 — No mixed rendering within a screen.** One renderer per device, chosen once, applied
  everywhere. A hero that silently switched engines by data availability would make the disclosure
  unpredictable.
- **D8 — The threat-model amendment lands with the first code, not with this doc** (House Rule #2).
  §7 is the sketch it must become. ⚠ Per §8 that is **AM-2**, the phase that creates the egress —
  not AM-1, which adds a flag nothing reads, and not a tail phase.

- **D10 — The desktop election is per app too, keyed by the running launcher.** `Preferences`
  scoped to `app/tenshu/<launcher>/basemap` rather than a shared node: `:ui-map` compiles into every
  desktop shell, so a constant key would silently make one election serve them all — which is the
  "per device" claim D9 rejects. Same scope, same copy, a different store.

- **D9 — ⚠⚠ The election is per *app*, and the copy must say so. Added 2026-08-13; corrects D2.**
  D2 said "per device" and D3 chose `NSUserDefaults` to get it. Under route 1 those two are not the
  same thing: Fit, Timeline and Maps are **separate apps with separate sandboxes**, so
  `NSUserDefaults.standard` is per-app storage. The entitlements make it explicit —
  `Timeline.entitlements` carries `group.app.tenshu.shared` for the **simulator**, and
  `Timeline.device.entitlements` deliberately carries **no App Group at all** ("paid-tier — the
  on-device shared-replica gate"). So on every real device today, electing MapKit in Timeline
  leaves Fit and Maps on MapLibre.

  This is `ios-control-offline-maps.md` §2 one plane over: **the thing is per-app, so a surface
  claiming device scope is a lie.** For a *consent* control that is worse than for a region store —
  a grant whose stated scope exceeds its real one is defective consent, not a cosmetic slip.

  Therefore: store per-app, and say "in this app", never "on this device". ⚠ Do **not** reach for
  `UserDefaults(suiteName: "group.app.tenshu.shared")` to fake device scope — without the
  entitlement it degrades *silently*, which would make the consent scope vary by build with nothing
  on screen changing. If the App Group ever becomes universal, widening app→device is a
  deliberate migration with new copy, and it needs its own decision here.

---

## 6. Architecture — the seam is already the right shape

The iOS map surface is unusually small and already funnels through one module:

| File | Lines | Role |
|---|---|---|
| `ios-basemap/Sources/TenshuMapView.swift` | 121 | the `UIViewRepresentable` over MapLibre |
| `ios-basemap/Sources/BasemapHeroes.swift` | 148 | `TenshuRouteMap` + the point hero; the **election point** |
| `ios-basemap/Sources/BasemapStyle.swift` | 75 | style-JSON assembly (route line, palette, region path) |
| `ios-basemap/Sources/BasemapAssets.swift` | 71 | bundled demo archive + attribution chip |
| `timeline-ios/Sources/TimelineMapHero.swift` | 84 | consumer |
| `maps-ios/Sources/MapsMapHero.swift` | 47 | consumer |
| `fit-ios/Sources/WorkoutRouteMapView.swift` | 40 | consumer |

`TenshuRouteMap`'s signature — `route: [BasemapCoord]`, `colorHex`, `regionBundlePath`,
`interactive`, `fallback` — is renderer-agnostic already, except for `regionBundlePath`. **The
election belongs inside `BasemapHeroes.swift`**: the three consumers keep calling one type and never
learn which engine drew the pixels.

Four things do not port and are the real implementation content:

1. ⚠ **There is no style JSON in MapKit.** `BasemapStyle.route(...)` builds a MapLibre style
   document; MapKit wants `MKPolyline` + `MKPolylineRenderer` (or SwiftUI `MapPolyline`) and an
   annotation for the point hero. The route-drawing logic is re-expressed, not shared.
2. ⚠ **Deployment target is iOS 16.0** (`timeline-ios/project.yml:21`, `maps-ios/project.yml:17`).
   SwiftUI's `Map` with `MapPolyline`/`MapCameraPosition` is iOS 17+. So either raise the floor —
   a product decision with its own blast radius — or use `MKMapView` behind a
   `UIViewRepresentable`, which is what `TenshuMapView` already is. **Recommend `MKMapView`**: same
   shape as the existing file, no floor change.
3. **Camera framing must match.** `routeCameraFrame` fits both axes against real points, and its
   KDoc records an earlier version that framed every route *"about a third of a zoom too tight."*
   MapKit's `MKMapRect` + edge padding must be tuned to visually match, or the two engines will
   disagree about what "the same route" looks like — a screenshot-diff task, not a logic one.
4. **Attribution differs.** MapKit draws Apple's logo and legal link itself and they may not be
   obscured; our `BasemapAttributionChip` must be **suppressed**, not stacked.

### 6.5 macOS — why "impossible" is the wrong word (correcting D4's first draft)

The reflexive answer is *"Compose Desktop is JVM, so no MapKit."* The repo says otherwise, twice:

1. **⭐ We already drive AppKit from the JVM, in-process.** `macos-helpers/.../ffi/ObjC.kt` is a
   working Objective-C runtime bridge over `java.lang.foreign` — per-signature `objc_msgSend`
   binding (never variadic — the KDoc explains the arm64 AAPCS trap), `reinterpret` for returned
   pointers, and **runtime class synthesis with FFM upcall stubs** for delegates
   (`objc_allocateClassPair` + `class_addMethod`). It exists because a *spawned helper* made the
   JVM the responsible TCC subject and got it SIGKILLed on first camera grant
   (`desktop-qr-scanning.md` §9). Constructing an `MKMapView` or `MKMapSnapshotter` is the same
   kind of call this file already makes.
2. **⭐⭐ And the established pattern avoids view embedding entirely.** `MacCameraSession` does not
   embed an `AVCaptureVideoPreviewLayer` in the window — it reads frames
   (`CoreVideo.readBgra(pixelBuffer)`) and hands them to Compose to draw. **Pull the pixels, draw
   them ourselves.**

That second point is the one that matters here, because **our map heroes are static frames, not
interactive maps**: gestures are disabled by design (`milestones.md` → Out of Scope → *Maps full*;
`TenshuRouteMap.interactive` defaults to `false` precisely so a pannable map cannot fight the page
scroll). A static frame does not need a live view — it needs **an image of a map region**, which is
what `MKMapSnapshotter` returns. That reduces macOS MapKit to: snapshotter → bitmap → the same
`Image` composable, with the route drawn as an overlay in Compose. **No window embedding, no JAWT,
no `CALayer` attach, no z-order fight with the Skia canvas.**

For completeness, the embedding path we would be avoiding is real but genuinely unpleasant: an
`NSView` in a JVM window means JAWT's macOS surface-layers protocol (attach a `CALayer`), and the
native layer then composites *outside* Compose's Skia canvas — it does not clip to rounded corners
and floats over scrolling content. JCEF proves it is possible; nothing here needs it.

⚠ **The real macOS gate is a signing question, not a rendering one.** MapKit on macOS is understood
to require the **Maps capability / `com.apple.developer.maps` entitlement** in the provisioning
profile — **unverified, and it must be verified before any macOS phase is scheduled**, because
`gradle/desktop-app-signing.gradle.kts` already documents that this bundle's signing is delicate
(the bundled JDK's `libjli.dylib`, Team-ID mismatch on `dlopen`). An entitlement the desktop bundle
cannot carry would end the macOS variant on its own — which is a much better reason to defer than
the one D4 first gave.

---

## 7. Threat-model amendment (sketch — §8 D8 says this lands with the code)

**A35** currently reads as absolute on this point. The amendment must:

1. Add to A35 a named sub-clause: *"Map **rendering** is a second egress surface on this asset,
   distinct from capture and from geocoding. The default renderer is MapLibre over Tenshu-served
   tiles (`maps-region-delivery.md` D6/D10 — coarse viewport by IP, reducible to zero by holding a
   region offline). On iOS a user MAY elect MapKit, off by default and revocable, which discloses
   viewport and IP to Apple at view time and is **not** reducible to zero. No coordinate reaches
   the relay under either renderer; House Rule #1 is unaffected."*
2. Extend **§8.18** (offline map-region delivery) with the elected-renderer variant, or add a
   sibling section — the tile-plane analysis there assumes our own host.
3. Add a surface row for the elected MapKit egress, with the residual stated plainly: **an elected
   device's map-viewing behaviour is observable by Apple, and we cannot verify their handling of
   it.** That sentence is the cost of the feature and should be in the doc, not softened.
4. Record the non-change explicitly: sync plane, custody, key handling, `Place`/`Moment` sealing —
   all untouched. This is a render-time decision with no wire or schema component.

---

## 8. Phases

⚠ **Re-derived 2026-08-13 (v1.1), after reading the seam rather than reasoning about it.** The
original four phases are kept at the bottom of this section with what each got wrong; three things
moved.

| Phase | Work | Size |
|---|---|---|
| **AM-1** | ✅ **DONE 2026-08-13.** `MapRendererControl` + the `BasemapRenderer` it resolves to — Swift, per-app `UserDefaults`, **off by default**, in `ios-basemap/Sources`. No consumer, no toggle, no MapKit: the gate exists before anything can egress through it | **XS** |
| **AM-2** | `MapKitRouteMap` + `MapKitPointMap` (`MKMapView` behind `UIViewRepresentable` — §6.2), attribution suppression (§6.4), the switch inside `BasemapHeroes`, the shared `MapRendererSection` toggle in all three shells, **and the A35 amendment (§7)** — this is the phase that creates the egress | **M** |
| **AM-3** | ✅ **DONE 2026-08-13.** Camera-framing + dark-mode parity, both engines × all three heroes — and it found **two real defects that compiled clean** (§8.2) | **S** |
| **AM-4** | ⚠ **BUILT + INERT 2026-08-13 (§8.3).** The fallback affordance: the schematic sketch gains *"use Apple Maps here"* when no archive resolves. It renders correctly and **cannot currently be reached in a shipped build** — the trigger is not what §2.2 assumed | **S** |
| **AM-4b** | ✅ **DONE 2026-08-13 (§8.4).** The coverage predicate in `:basemap-core` — an archive that cannot cover the target now resolves to `Schematic`, which makes AM-4's offer reachable and fixes the same bug on **Android**, where it was equally live | **M** |
| **AM-5a** | ✅ **DONE 2026-08-13 (§8.1).** The entitlement probe: **no entitlement is needed** — `MKMapSnapshotter` renders from a bundle carrying none. ⚠ Claiming `com.apple.developer.maps` without a profile is an instant SIGKILL, so the hazard is real and entirely self-inflicted | **XS** |
| **AM-5** | ✅ **DONE 2026-08-13 (§8.5).** macOS: `map-snapshot-helper` (swiftc, beside `image-decode-helper`) → PNG → an ordinary Compose `Image`, behind a per-app `Preferences` election. ⚠ Only Timeline's settings screen hosts the toggle — Fit and Maps have none | **M** |

### 8.2 AM-3 as built — what a screenshot found that a compiler could not (2026-08-13)

Both engines photographed against the **same** three heroes (trip route, movement route, place
point) in light and dark, on an iPhone 16 simulator, through the shipping views. The harness is two
new DEV_HARNESS arguments layered on Timeline's existing `-mapHero`: **`-mapRenderer
<maplibre|mapkit>`** and **`-appearance <light|dark>`**, so one combination is one launch and no
human flips a switch between shots. ⚠ `-mapRenderer` **writes the real election** (it is
`MapRendererControl.select`), so a probe run leaves the harness app elected until the next run
names the other engine.

**F1 — ⚠⚠ The point hero was exactly one zoom level too far out, and it looked like taste.**
`metersPerPoint` used **156543.03392**, the equatorial metres-per-pixel for a **256 px** tile.
`:basemap-core` states its own convention one line from where anyone would look —
`MAPLIBRE_TILE_SIZE_DP = 512.0`, *"MapLibre's world is 512 × 2^zoom density-independent pixels
across"* — so its zoom N covers **half** the ground of the 256-tile zoom N. Side by side the bug was
unmissable (MapLibre framed the Vasari Corridor; MapKit framed half of Florence) and alone it was
invisible: a plausible map of the right place. Fixed to **78271.51696**. ⭐ The lesson generalises —
when two renderers share a *number* rather than a code path, the number needs the shared unit
written next to it.

**F2 — ⚠⚠ `updateUIView` is not a layout callback, and the fix for that broke the hero outright.**
`makeUIView` runs at `frame: .zero`, so the first camera fit is computed against a zero surface;
SwiftUI does not promise another `updateUIView` once a real size exists, and the guard against
re-fitting an unchanged route suppressed the one that did arrive. The first attempt — *bail out of
fitting at zero bounds*, which is correct in isolation — then rendered **the default world view with
no route on it, in both appearances**. The fix is an `MKMapView` subclass that reports its own
`layoutSubviews`; the camera is fitted from there, which is the only moment the surface is known to
exist. ⚠ The pre-AM-3 behaviour was *accidentally* passable: a zero-bounds `setVisibleMapRect` lands
in roughly the right place and MapKit adapts as the view grows — so the padding was never the 0.15
fraction it claimed, and no screenshot would have flagged it until one was placed beside MapLibre's.

**F3 — dark mode needed no work.** MapKit follows the trait collection, and the route colour is the
caller's own theme colour crossing as `#RRGGBB` (D8), so it adapts with the app. This is the one
place the elected path is *cheaper* than the MapLibre one, which has to be handed `dark:` explicitly.

**F4 — attribution behaves.** Apple's logo + *Legal* link render bottom-left, unobscured, in all
three heroes; our "Offline · MapLibre" chip is correctly absent (§6.4). No change needed.

**Residual, accepted:** MapKit frames a route slightly *looser* than MapLibre — bounding-box +
proportional inset versus `routeCameraFrame`'s zoom arithmetic. On the trip hero the route occupies
~73% of the surface height against MapLibre's ~80%. Both look framed; neither clips. Chasing the
last 7% would mean re-implementing the Mercator fit against `MKMapRect`, which buys pixels and costs
a second copy of the one piece of cartography this doc has kept single-sourced.

⚠ **Still unproven:** the `interactive` flag on **either** engine (parity §3.3.1 item 2 — unchanged
by this phase), and every hero in Maps, whose shell was compiled but not photographed.

**F5 — ⚠⚠ the first on-device pass found what the simulator could not (2026-08-14).** On an
iPhone 17e (iOS 26.6) a real workout rendered **a bare green field with the route on it**, while
Apple's own Maps app drew fine. The tell was in the corner: Apple's logo, so MapKit *was* the
renderer.

Instrumenting `MKMapViewDelegate` and pulling the log off the device settled it in one read
(`devicectl device copy from --domain-type appDataContainer` — the device console is unreachable
from this toolchain, `log --device` having been removed):

```
route points=7  first=(48.15, 11.58)  last=(48.15, 11.58)
region center=(48.15, 11.58)  span=(8.9e-05, 2.3e-04)  bounds=(350, 200)
willStartLoadingMap → didFinishLoadingMap  ×4,  no error
```

**MapKit never failed.** The workout was recorded **standing still** — seven fixes spread across
about 4 cm — so `setVisibleMapRect(line.boundingMapRect)` honoured it and framed a **10 m × 17 m**
window, at which Apple's basemap has no roads or labels and draws bare land. We asked for the wrong
thing.

⭐ **MapLibre rendered the same workout correctly**, because `routeCameraFrame` clamps to
`BASEMAP_MAX_ZOOM` and its KDoc names the degenerate case. The MapKit fit had no equivalent, and
**AM-3's parity pass could not have caught it**: its mock trip was a real multi-kilometre route, and
a degenerate one was never photographed. ⚠ "Both engines, same route" is not the same test as "both
engines, same *degenerate* route".

**Fixed** by clamping the MapKit fit to the same constant — which is now **public in
`:basemap-core` and used as `routeCameraFrame`'s own default**, rather than a 15.0 spelled twice.
That is F1's lesson applied before it bit a second time: when two renderers share a number, they
must share the *definition*. `RouteCameraTest.theExportedMaxZoomIsWhatADegenerateRouteClampsTo`
pins the link rather than the value. Verified on the device.

### 8.3 AM-4 as built — ⚠⚠ §2.2's premise is wrong, and the affordance is inert because of it

The offer is built, photographed on both hero types, and **unreachable in production**. Recorded at
this length because the wrong half is easy to fix and the right half is a phase.

**What §2.2 says:** *"no downloaded region means no geographic map at all — the schematic sketch,
not a basemap."* That was the trigger AM-4 was designed against.

**What the code does:** `BasemapStyle.tileUrl` returns nil — the only route to the schematic
fallback — when `resolveBasemapSource` answers `.schematic`, and it answers that **only when no
bundled demo archive exists**. Every map-bearing shell mirrors `tenshu_basemap.pmtiles` in through
`ios-basemap/basemap.yml`, so `bundledDemoAvailable` is **always true** and the fallback is
unreachable. ⚠ A `regionBundlePath` whose file is missing does not help either: `BasemapStyle`'s own
KDoc records that it falls *through* to the demo rather than blanking.

**So what does a user outside Firenze actually see?** Not a sketch. The demo archive resolves,
MapLibre renders it, and the camera flies to geometry the archive has no tiles for — **a route
floating on an empty background**. `TimelineMapHero.swift` has said so all along, one comment above
the call: *"geometry outside it frames onto empty tiles."* The coverage failure is real; the
*symptom* §2.2 named is not the symptom the code produces.

**Consequence for the offer.** Attaching it to the schematic branch attaches it to a branch users
never see, so AM-4 alone changes nothing for anyone. It is kept rather than reverted because the
branch is the *correct* home once the trigger is right, and because it is now verified: a
DEV_HARNESS `-forceSchematic` argument reaches it, and both hero types were photographed showing the
disclosure above the tap.

**AM-4b is the fix, and it is not a UI phase.** The hero must be able to ask *"does the archive I
resolved actually cover this point?"* — which belongs in `:basemap-core` beside `resolveBasemapSource`,
where both platforms and the no-egress guard already live. ⚠ The bundled demo needs a declared bbox
for that (it has none today), and `RegionEntry.coverage` already carries one for catalog regions, so
the shape exists. Sized **M** rather than S because it is a Kotlin change with a Compose consumer,
not a Swift one.

⚠⚠ **Until AM-4b lands, §4's "disclosed at the point of failure" is unfulfilled** — the Settings
toggle (AM-2) is the only way to elect. That matters more than it looks: §4 argues the *point of
failure* is the placement that makes the trade legible, and the whole acceptance in §10 was
conditional on that framing.

**Also deliberately not offered: "download the region".** §4's sketch pairs the two, and the
download half is omitted — in Maps because `MapsPlaceDetailView` already ships a *"Keep <region>
offline"* row with real size, progress and failure states on the same screen, and in Timeline and
Fit because neither has a region picker **and** each app has its own region store under route 1
(D9), so a download made in Maps would not serve Timeline's heroes on a real device. Pointing a user
at another app to fix this one would cost them a 200 MB download and not work.

### 8.5 AM-5 as built — macOS, without a window-embedding bridge (2026-08-13)

§6.5 said the desktop answer is *pull the pixels, draw them ourselves*, and that is what shipped.
No JAWT, no `CALayer` attach, no `MKMapView` in a Skia window — and, per **AM-5a**, no entitlement.

**The shape, which is a copy of one this repo already runs.** `macos-helpers/src/main/swift/map-snapshot-helper.swift`
is a swiftc-built binary that reads one JSON request on stdin and writes one PNG on stdout, wired
into the build exactly as its sibling `image-decode-helper` is, and found at runtime by the same
`HelperBinary` (signed bundle copy first, extracted jar resource for dev runs). `MacMapSnapshotter`
is its Kotlin face and mirrors `MacImageDecoder` line for line: availability is a **runtime**
property, every failure is a `null` rather than a throw, and the call is blocking so it belongs off
the UI thread.

**The consent gate comes first, again.** Both desktop actuals check
`DesktopMapRendererControl.usesAppleMaps && MacMapSnapshotter.isAvailable()` before anything is
fetched; `AppleMapsSnapshot` re-checks nothing, which is stated on it. The election is
`Preferences`-backed — **device-local** (D3: an egress consent must not be remotely flippable) and
**per app** (D9), for which see **D10** below.

**The toggle renders only where there is something to elect.** `MapRendererSetting()` is an
`expect`/`actual`: empty on Android (§9 **Q4** — Google Maps is a different and worse trade),
empty on iOS (its settings are SwiftUI and carry `MapRendererSection`), empty on desktop off macOS
**and** on a macOS build whose helper was never produced. So Timeline's settings screen calls it
unconditionally and the platform decides — one rule in one place, rather than a gate at each call
site that could disagree with the actual.

**Two things the snapshot path gets right for free**, both of which cost the live iOS path work:
Apple's logo and legal link are *burned into the image* by MapKit, so §6.4's "must not be obscured"
is satisfied by construction; and the route is stroked **inside** the snapshot using
`snapshot.point(for:)`, MapKit's own projection, so nothing has to reproject and disagree at the
edges.

⚠⚠ **What is NOT done, precisely.** Only **Timeline's** settings screen hosts the toggle, because it
is the only shell with a Compose settings screen at all — `fit-shared-ui` and `maps-shared-ui` have
none, the same finding AM-2 hit on iOS from the other side. So a Fit or Maps *desktop* user cannot
elect, and their heroes stay schematic no matter what Timeline is set to (D9 again: the elections do
not travel).

⚠⚠ **And what was not exercised.** The renderer is proven by invoking the helper directly — a route
hero in light and a point hero in dark, both real Apple basemaps, the route stroked in the caller's
colour. **The desktop UI was not run**: no app launch, no screenshot of the toggle or of a hero
in-app, so the Compose half is compile-verified only. That is the AM-3-shaped pass this phase still
owes.

⚠ **No test fetches from Apple, deliberately.** A `desktopTest` round-tripping the helper would be
the obvious proof and would put a third-party network call in `./gradlew test` on every run — slow,
flaky, and an egress nobody asked for. The evidence is the images instead.

### 8.4 AM-4b as built — the archive answers the coverage question itself (2026-08-13)

`resolveBasemapSource` now takes `demoBounds` and `target`, and a bundled demo that cannot cover the
target resolves to `BasemapSource.Schematic` instead of being handed to MapLibre to render as empty
tiles. That is the whole change; everything else follows from where it sits.

**⭐ The bbox is read, not declared.** `basemapBoundsFromPmtilesHeader` parses the four signed int32s
at bytes 102–117 of the PMTiles v3 header, so the demo extract states its own coverage and a re-cut
extract cannot silently invalidate a constant. `DemoArchiveBoundsTest` (`jvmTest`) opens the **real**
archive at `../ui-map/src/androidMain/assets/tenshu_basemap.pmtiles` — the single-source-of-truth
tree, per `:ui-map`'s own real-archive tests — and asserts both that a bbox exists (a missing one
would make this whole phase inert) and that Ponte Vecchio resolves to `BundledDemo` while Belém
resolves to `Schematic`. ⚠ That test is the phase's real gate: the rule is trivial, the *input* is
what can quietly stop being true.

**⚠⚠ The bug was never iOS-only.** `RouteBasemap.android.kt` carried the same behaviour and said so
in a comment two lines above the call — *"a region whose coverage doesn't contain the route just
frames onto empty tiles"* — so Android users have been getting the empty background too, on every
hero outside Firenze, since the demo extract shipped. Both Android composables now pass a target:
`RouteBasemap` the whole route's bounding box (it already frames the corridor), `OfflineBasemap` its
camera point.

**No defaults on the new parameters.** They could have defaulted to null and left every un-updated
call site compiling and silently pre-AM-4b; instead the signature change names all four callers.
⚠ One of them — `:ui-map`'s `OfflineBasemap.ios.kt` — passes nulls *on purpose*: it is the
superseded Compose-on-iOS renderer (D7) that nothing reaches, and wiring a second header reader into
a file scheduled for deletion would be the wrong kind of thorough.

**Not covered, deliberately:** a **downloaded region** is never coverage-checked. Its bbox is known
to the catalog but is not threaded to a renderer, which receives a path; and a user who downloaded a
region did so *for* somewhere. A region held for one city and viewed in another still renders empty
tiles — the same bug, one case narrower, and its own piece of work.

**Also unfixed here:** the point hero on Android judges coverage by its camera *point*, so a route
straddling the extract edge is judged by its middle. Imprecise in the same direction the camera
already is, and never worse than the previous answer of "always the demo".

### 8.1 AM-5a — the entitlement probe — ✅ **RUN 2026-08-13. ANSWER: no entitlement is needed.**

**Result first, because it reverses the phase's own premise.**

| Case (same binary, same identity, launched directly) | Outcome |
|---|---|
| Ad-hoc signed, no entitlements | ✅ rendered |
| **Signed as `signDesktopApp` signs** — real identity, no hardened runtime, no profile | ✅ rendered |
| Hardened runtime + `disable-library-validation` (the `-Ptenshu.hardenedRuntime` path) | ✅ rendered |
| **`com.apple.developer.maps` claimed, no provisioning profile** | ⛔ **SIGKILL (exit 137)** before the first line of output |

`MKMapSnapshotter` renders a real Apple basemap — Florence, labelled, 400×300 PNG — from a plain
`.app` bundle carrying **no entitlements at all**. `codesign -d --entitlements -` on the working
build prints nothing; `TeamIdentifier=FAYT82GWD7`.

⚠⚠ **The AMFI hazard is real but self-inflicted.** The last row is a clean A/B: same binary, same
identity, the entitlement file is the only variable, and the result flips 0 ↔ 137 every time. So
claiming the Maps capability without a profile *is* instantly fatal, exactly as the keychain-helper
precedent predicted — **and the fix is to not claim it.** Adding "Maps" in Xcode would be the thing
that breaks a macOS build, not the thing that enables it. ⚠ No crash report was written and no
`amfid` log line was capturable, so the *mechanism* is inference from the precedent; the
**observable** is a reproducible instant SIGKILL.

**Consequences:**

- **Q1 is answered "no", so Q2 and Q3 never arise.** Whether a jpackage bundle can carry a
  restricted entitlement, and whether Developer ID profiles support Maps, are both moot — nothing
  needs to carry it.
- **AM-5 is unblocked on the entitlement question.** What remains for macOS is what §6.5 always
  said: a second consent surface, a renderer parity pass, and the `MKMapSnapshotter` → bitmap →
  Compose plumbing over `macos-helpers`' existing Obj-C bridge. None of it is a signing problem.
- ⚠ The probe proves **snapshotting**, which is what §6.5's design uses. It says nothing about
  embedding a live `MKMapView` in a JVM window — the path §6.5 deliberately avoids.

⚠ **Probe hygiene, recorded because it nearly produced a false negative:** the first run "timed out
with no callback" and looked like MapKit refusing to work. It was the harness — bare
`MKMapSnapshotter.start {}` delivers its completion on the **main** queue, which the tool was
blocking on a semaphore. `start(with: DispatchQueue.global())` fixed it. A probe that fails for its
own reasons is worse than no probe, because its answer is the one that gets written down.

The probe itself lives outside the repo, as this section always said it should — ~40 lines of Swift,
`swiftc` + a hand-written `Info.plist` + `codesign`.

---

*Original framing, kept because the reasoning is why the phase existed:*

§6.5 leaves one thing unverified, and it is load-bearing enough to be its own phase: **MapKit on
macOS is understood to require the *Maps* capability**, which puts `com.apple.developer.maps` in the
entitlements. Everything in `com.apple.developer.*` is a **restricted** entitlement — honored only
when the signature carries a provisioning profile from a team whose App ID enables it. Claimed
without one, **AMFI kills the process at launch**: a crash on start, not a degraded map.

⭐ **This repo has already paid that toll once**, which is why the risk is concrete rather than
theoretical. `control-desktop/build.gradle.kts:407-413`: the keychain helper needs
`data-protection-keychain`, *"restricted, else AMFI kills it"* — so it could not be a plain `swiftc`
binary. It is built by `xcodebuild` as a **separate signed .app carrying its own embedded profile**,
embedded whole into Control.app and never re-signed (`--deep` would strip the entitlement), and on a
free team the profile must be minted once in the Xcode GUI before `xcodebuild` works offline.

Three questions, in the order that makes the cheapest one decisive:

1. **Is it required for what we would actually do?** §6.5's design embeds no live map — it is
   `MKMapSnapshotter` → bitmap → drawn in Compose (the `MacCameraSession` pattern). Whether the Maps
   capability gates *snapshotting* specifically is exactly what is unverified. If it does not, the
   other two questions never arise.
2. **Can a jpackage bundle carry it?** ⚠ `gradle/desktop-app-signing.gradle.kts:22-33` signs
   **without** hardened runtime on purpose (the bundled Adoptium JDK's `libjli.dylib` fails Library
   Validation otherwise), and the precedent above solved its own version of this by *not* entitling
   the main bundle at all.
3. **Does it survive the distribution paths?** Free team (GUI-minted profile) and production
   Developer ID + notarisation carry narrower capability sets than App Store profiles; whether Maps
   is in the Developer ID set is the specific unknown.

**The probe itself does not belong in this repo.** Write a throwaway macOS app that calls
`MKMapSnapshotter`, sign it the way `signDesktopApp` signs (real identity, no hardened runtime, no
profile), and launch it. Renders ⇒ Q1 is answered *"no entitlement needed"* and AM-5 unblocks. AMFI
kills it ⇒ the answer arrives with a crash log, and the fallback shape is already known from the
keychain helper. ⚠ Record the result **here** either way: an unanswered question that someone
re-derives is how this doc's own D4 shipped a wrong first draft (§6.5).

⚠ **AM-5a gates nothing on iOS.** MapKit on iOS needs no entitlement at all — iOS's "Maps"
capability is for *routing* apps declaring `MKDirectionsApplicationSupportedModes`, a different
feature this doc never proposes using. AM-1…AM-4 are unaffected.

**What moved, and why:**

1. ⚠⚠ **The renderer and the election swapped places, and that dissolves the "must land together"
   rule** in this doc's own header. That rule is only true renderer-first: a MapKit hero with no
   control to read is the absolute rule broken. Election-**first** cannot be un-gated by
   construction, so the two become independently shippable — and AM-1 is reviewable without a
   300-line `UIViewRepresentable` competing for attention.
2. ⚠ **The Settings toggle ships in AM-2, not with the control.** A switch labelled *"use Apple
   Maps"* that renders no Apple map is a fabricated success — the failure mode
   `ios-control-notifications-and-shares.md` and the `ControlSurface` sweep (defaults were fabrication, not absence) both exist to
   stop. `SettingsView.swift:67`'s `if geocodingAvailable` is the established shape: the affordance
   appears only where something is bound behind it.
3. ⚠ **`MapRendererControl` is Swift, not Kotlin — deliberately diverging from D2's "copy
   `PlaceGeocodingControl`".** That one is Kotlin because Kotlin's own enricher re-checks it before
   every lookup. Here the sole reader is `BasemapHeroes.swift`; no Kotlin ever asks. Going through
   Kotlin would mean a new `iosMain` source set in `:basemap-core` (it has **commonMain/commonTest
   only** today), an umbrella export and a SKIE crossing, to carry one `Bool` that never leaves
   Swift. D2's *shape* — persisted, off by default, revocable, re-read at use — is copied exactly;
   only the language differs. ⚠ If AM-5 ever happens, macOS needs its own; D4 already makes that a
   separate consent surface.

**Scope note that survived contact with the entitlements:** D9 below — the election is per **app**,
not per device, and the copy in AM-2 must say so.

**The original phase map, and what each entry got wrong:**

| Was | Now | Why |
|---|---|---|
| AM-1 renderer + framing parity | AM-2 + AM-3 | Framing parity is a verification pass with its own failure mode; bundling it hid a screenshot-diff task inside a rendering task |
| AM-2 control + toggle | AM-1 (control) + AM-2 (toggle) | The toggle is the user-visible half and belongs with the thing it switches |
| AM-3 fallback affordance | AM-4 | Unchanged in content, renumbered |
| AM-4 docs + threat model, **M** | ✅ docs half **shipped**; amendment → AM-2 | The doc restatement landed on acceptance. The amendment is not a tail phase at all: **D8** attaches it to the first code that creates the egress, which is AM-2 |

Sizes are honest rather than encouraging: the seam is ~415 Swift lines and the three consumers
(`TimelineMapHero` 84, `MapsMapHero` 47, `WorkoutRouteMapView` 40) do not change at all — the
election lives entirely inside `BasemapHeroes.swift`'s 148. ⚠ D6 says "three heroes" but that is
**two view types**: `TenshuRouteMap` serves both the trip/movement and workout heroes,
`TenshuPointMap` the place one.

⚠ **Verification has no Kotlin gate.** Nothing in `./gradlew desktopTest jvmTest test` compiles a
line of this, and there is no Swift unit-test target outside `drive-ios`'s UITests. The gate is
`scripts/ios-smoke.sh --build-only` (every app, no device), plus a `DEV_HARNESS` launch-argument
probe for AM-3's screenshots — `simctl` has no tap verb and the heroes are two taps deep
(parity §3.1.1 item 7).

⚠ **Ongoing cost not captured by any phase:** two renderers forever — two visual languages, two
screenshot sets, two dark-mode passes, and the `interactive` flag (still unproven on *either*
path — parity §3.3.1 item 2) now needing proof twice. Every future map feature pays this.

---

## 9. Open questions

- **Q1.** Does an elected device still prefetch regions in the background (bandwidth + storage the
  user isn't rendering from), or only on explicit download? D5 keeps the machinery; it doesn't
  settle the automatic half.
- **Q2.** Does the election belong in **Control** (the platform's settings home, and where
  *Offline maps* lives) rather than each app's Settings? Control-on-iOS has no offline-maps surface
  at all today, so this couples to that gap.
- **Q3.** Is there a defensible middle: MapKit **only** where no region resolves (coverage
  fallback), MapLibre everywhere else? It maximises coverage and minimises disclosure — but it
  violates D7's predictability, and the user could not answer "who saw this view?" without knowing
  the archive state. Recorded, not recommended.
- **Q4.** Android's analogue (Google Maps SDK) — explicitly **out of scope** here and I would not
  extend it there. The Play-services dependency, the account linkage, and the `INTERNET`-stripped
  manifest posture (`maps-region-delivery.md` R1) make it a different and worse trade.
- **Q5.** macOS (§6.5): does the desktop bundle's signing carry the Maps entitlement, and is a
  snapshot-based hero visually acceptable beside a live MapLibre one? Both are answerable in an
  afternoon, and neither blocks the iOS phases.

---

## 10. The honest case for saying no

⚠ **Kept after acceptance, deliberately.** The decision went the other way, but every cost below is
still real and none of it was refuted — item 3 in particular is the thing acceptance must not be
allowed to relieve: **growing the catalog is still the fix for coverage**, and MapKit must not
become the reason it stops. Read this before AM-1, not as history.

1. **It is the flagship app's flagship property.** Timeline is where the platform makes its
   strongest privacy claim (A35: *"the single biggest sensitivity escalation the platform has taken
   on"*). An option to weaken it, inside that app, is a strange thing to ship — and "the user chose
   it" is the argument every product uses to shed responsibility for a default that was hard.
2. **Options are a tax on the promise.** "Your location history never leaves your devices" is a
   sentence we can say today. Afterwards it becomes "…unless you turned on Apple Maps," which is
   worse marketing *and* worse security communication.
3. **The coverage argument has a better answer.** Users see squiggles because the catalog is small.
   Building more extracts fixes that for everyone, without disclosure, and the pipeline already
   exists (`how-to/tiles-pipeline.md`). MapKit would relieve the pressure to do the thing we should
   do anyway.
4. **Two renderers forever** (§8) on a team this size, for a feature whose benefit is aesthetic
   familiarity plus a coverage gap we intend to close.

**The recommendation was:** worth building **if and only if** it is framed as §4 describes — off by
default, revocable, disclosed at the point of failure rather than at onboarding — and worth
declining if the appetite is for an onboarding fork between two equal-looking choices. The middle
ground the trigger reached for (an easy first-launch pick) is the one shape I'd argue against in
either direction.

**Accepted 2026-08-13 on exactly that condition** — the conditional half, not the fork. §4 is
therefore not advisory: an onboarding pick, a symmetric two-choice screen, or a default that ships
MapKit to anyone who never opens Settings would be a *different* decision from the one accepted,
and needs its own amendment here.
