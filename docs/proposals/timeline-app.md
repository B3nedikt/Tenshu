# Timeline — a first UI-only version over mock repositories

**Status:** v1.0 (2026-07-02). **Ph0–Ph6 shipped;
Ph8 close-out in flight (serif ADR 0021 + deferred catalogue).** The UI-only
Timeline app is complete end-to-end over the mock — desktop + Android, all 10
screens navigable. Remaining is on-device verification + the additive items in
§10. Distils the
`claude.ai/design` handoff for the Tenshu **Timeline** app into a committed,
executable plan. The handoff itself (`docs/design/timeline/`) is **gitignored**
(local-only, matching the `tenshu-icons/` convention) — so this proposal is the
**durable record** of what it said and what we decided. If the handoff drifts or
is lost, this doc plus `:ui-core` are enough to rebuild.

**Owner's note:** decision- and scope-oriented. The design handoff
(`docs/design/timeline/timeline-agent.md`, direction "Chapters" / Variation C) is
the source of truth for *layout, vocabulary, and data shape*; tokens come from
`:ui-core`, never from the mock. This proposal locks the module architecture,
records three decisions, and phases the UI-only build.

---

## 1. Goal & scope

Build a **runnable Timeline app on desktop + Android** that renders all 10 screens
from the mock, navigable, driven entirely by an **in-memory mock repository**
seeded from `docs/design/timeline/mockup/timeline-data.js`.

**In scope:** the domain model (§4 of the handoff), a `MockTimelineRepository`,
Compose-free ViewModels, the shared composables (`PhotoCluster`, `NamedMoment`,
`ChapterHeader`, `MomentRoute`), all 10 screens, custom KMP navigation, Koin DI,
desktop + Android shells, the `personalise()` tint, and dark-scheme wiring by role.

**Out of scope (later phases, all behind the repository seam or flagged
affordances):** real Timeline store / persistence / sync, real photo loading +
on-device dominant-color extraction, iOS shell, Months/Years zoom scopes, map of
all places, event notes, "see all" / "compare with last year".

## 2. The 10 screens (from the handoff §1)

Full-height PNGs live in `docs/design/timeline/screenshots/` (local-only).

1. Home · day-chapters feed  2. Home · Places tab  3. Home · Activities tab
4. Trip detail · Lisbon (multi-day, nested Sintra)  5. Trip detail · Sintra
(single-day)  6–9. Moment detail · activity / visit / event / quiet  10. Place
detail · visit history.

## 3. Decisions (locked 2026-07-01)

- **D1 — Standalone app modules**, not a feature inside Drive. Timeline is its own
  app; four modules mirror Drive:
  - `:timeline-shared-logic` — Compose-free domain model + `MockTimelineRepository`
    + ViewModels (android / `jvm("desktop")` / iOS targets, iOS-ready).
  - `:timeline-shared-ui` — Compose screens, shared composables, `App()`,
    navigation, DI (android / desktop).
  - `:timeline-desktop` — `jvm` `main()` run target.
  - `:timeline-android` — `ComponentActivity` shell.
  Reuses `:ui-core` unchanged. Package root `app.tenshu.timeline`.
- **D2 — Desktop + Android both** run targets in v1 (desktop = fast iteration,
  Android = 412 px frame parity). iOS deferred.
- **D3 — Bundle an editorial serif now.** The Chapters direction sets every
  display/title heading in an italic serif; that is the concept's whole voice. v1
  ships a bundled serif Compose font resource behind a single `TimelineType`
  accessor, so screens read as intended immediately. **This does not resolve the
  brand question** — `timeline-agent.md` §3 still owes an ADR before a serif enters
  the shared type system. `TimelineType` isolates the family so the ADR outcome
  (keep serif / fall back to M3 sans) is a one-line swap. Do **not** hardcode
  Georgia anywhere. Tracked: `// TODO(ADR): serif family — timeline-agent.md §3`.

## 4. Module wiring to copy from Drive (load-bearing details)

Verified against `:drive-shared-logic` / `:drive-shared-ui` / `:drive-android` /
`:drive-desktop`:

- `:*-shared-logic` uses a hand-created `jvmMain` source set re-parented by
  `androidMain` + `desktopMain` (the Android KMP plugin doesn't materialise that
  shared-JVM parent).
- Both Compose modules carry the per-target Compose `resolutionStrategy`
  force/exclude block (desktop forces `org.jetbrains.compose.*`, excludes
  `androidx.compose.*`) + the `fixComposeResourcesForAndroid` Sync workaround +
  `compose.resources { packageOfResClass = ... }`.
- `:*-shared-ui` depends on `:*-shared-logic` as **`api`** (VM/repo types flow
  transitively) and `:ui-core` as `implementation`.
- **Screens and their ViewModels share the same leaf package** so Kotlin resolves
  VM types across the module boundary with no import.
- Theme: reuse `TenshuTheme` (the only top-level wrapper), `MaterialTheme.spacing`
  (`LocalAppSpacing`), `MaterialTheme.shapes`, `MaterialTheme.colorScheme` — all
  from `:ui-core`. Amethyst Neutral; Slate is only the launcher icon tile.
- Navigation: custom `KmpNavHost` from `:ui-core` (not androidx Navigation). Back
  stack = `mutableStateListOf(Home)`; `sealed interface Destination` with
  `@Serializable` members; `kmpEntryProvider { entry<…> { … } }`; entries pull
  `koinViewModel(parameters = { parametersOf(id) })`. **But not
  `AdaptiveNavScaffold`** — see §4.1: Timeline is a single top-level surface with
  internal tabs, not a bottom-nav app.
- DI: Koin. `single<TimelineRepository> { MockTimelineRepository() }` +
  `viewModelsModule()`; the repository is an **interface** so the real store swaps
  in via one binding.
- Shells: desktop `Main.kt` provides `LocalViewModelStoreOwner` / SavedState /
  Lifecycle owner shims (copy Drive's `Main.kt`); Android `MainActivity`
  gets them free from `ComponentActivity`. Both call the same `App()`.

### 4.1 What reuses from `:ui-core` — and what's bespoke

`:ui-core` is a **foundation** (design tokens + a few generic M3 chrome wrappers +
nav/layout plumbing), **not** a rich component library — Drive/Control assemble
screens from their *own* composables on top of it. Timeline's "Chapters" design
diverges at the **component layer**, not the **token layer** (the handoff §2
mandates the same Amethyst tokens / M3 type scale / spacing; the serif is the one
additive divergence). So:

- **Reuse as-is (foundation, ~100%, effectively mandatory):** `theme/*`
  (`TenshuTheme`, `Colors`/Amethyst, `Shapes`, `Spacing`, `Motion`, `Typography` —
  `TimelineType` only *layers* the serif on top), `navigation/*` (`KmpNavHost` +
  `entry {}` DSL), `layout/WindowWidthSizeClass` + `ContentWidth`,
  `feedback/AppLoadingIndicator`.
- **Adapt / partial:** `chrome/AppTopAppBar` is a thin M3 `TopAppBar` wrapper with
  a *string title* — fine as a base for simple bars, but Timeline's Home bar
  (small-caps `TIMELINE` + search + settings gear) and the detail bars
  (**translucent-blur, collapsing over a hero**) exceed it and are largely custom.
  `AppListItem` / `AppEmptyState` / `AppDialog` / `AppBottomSheet` /
  `input/AppSearchBar` are usable scaffolding (empty tabs, search sheet, menus) —
  but the Places/Activities **rows** are too styled (72dp cover, italic-serif name,
  accent meta) to be `AppListItem`.
- **Bespoke, lives in `:timeline-shared-ui` (not in `:ui-core`, not yet):**
  `PhotoCluster` (the ~78% peek snap carousel + elongating dots), `NamedMoment`,
  `ChapterHeader`, `MomentRoute` (dotted rails), the Interlude / bound-trip /
  nested-trip cards, moment-detail stat grids + pace bars, the visit-history
  timeline, `PhotoFrame` placeholder, `personalise()`, and the Lucide glyph set
  (`:ui-core` ships brand *marks*, not app glyphs). Roughly **70–80% of the screen
  composables are new** — expected, since the design's identity *is* the component
  layer.
- **Single-surface + tabs, not bottom-nav.** Drive/Control are multi-destination
  apps → `AdaptiveNavScaffold` (nav bar / rail). Timeline's Home / Places /
  Activities are **M3 secondary tabs inside one screen** (the top bar stays
  "TIMELINE"), with detail screens pushed on the stack. So Timeline uses a plain
  `Scaffold` + `TabRow` under `KmpNavHost`, **not** `AdaptiveNavScaffold`.
- **Promotion policy:** build the Chapters vocabulary bespoke now; **don't**
  promote to `:ui-core` prematurely. `PhotoCluster` is the one piece plausibly
  reusable by a 2nd app (Drive galleries); graduate it only at the established
  "app #2 / ~a dozen shared components" trigger (mirrors `QrCode`/`QrScanner`,
  which moved to `:ui-core` only once shared).

## 5. Domain model (handoff §4, adapted to Kotlin)

`Photo(dominantColor, uri?, label?)`, `Movement`, `Moment(kind: MomentKind
{Activity,Visit,Event,Quiet})`, `Day`, recursive `Trip { contents:
List<TripEntry> }` with `sealed TripEntry { Day, NestedTrip, Collapsed,
MomentEntry }`, `TripSuggestion`, `Place`, `PlaceVisit`, `ActivityRollup`. Model
recursion for arbitrary nesting (mock nests one level). **Drop the presentational
`meta` strings** — compose them at render from real counts/durations (port
`durationFromMin()`). Prefer explicit `MomentKind` over title string-matching.
This is *structure*, not a wire format — the real persistence layer comes later. **When map
surfaces land (§10), `Place` and `Moment` gain a `Coord(latitude, longitude)`** mirroring the real
`:place-service` `Place` (SI lat/lng) — enough for place pins + a connect-the-dots trip route.

## 6. Personalisation (handoff §5)

`personalise(dominantHex, strength)` — tints only `soft` surfaces and `accent`
marks toward a photo's dominant color; primary text / outlines / structural color
stay Amethyst. **No-op at `strength <= 0`** (pure Amethyst). Default strength 0.7.
Reimplement `mix/soften/darken` via Compose `lerp(Color, Color, t)`.

## 7. Phases (each independently runnable)

- **Ph0 — Scaffolding.** ✅ Shipped. gitignored the handoff; this proposal;
  the four modules + build files by analogy to Drive; `settings.gradle.kts`
  includes; the serif accessor (`TimelineType`).
- **Ph1 — Domain model** (`:timeline-shared-logic`, §5). ✅ In this PR. `model/`
  package: `Photo` / `Movement` / `Moment` (+ `MomentKind`) / `Day` / recursive
  `Trip` + `TripEntry` / `TripSuggestion` / `Place` / `PlaceVisit` /
  `ActivityRollup`; presentational `meta` dropped. `TimelineFormat` is
  **string-free** — it owns only the locale-independent duration split
  (`durationParts` → `DurationParts` + `DurationForm`); see §8. Tests:
  `TimelineFormatTest` + `TimelineModelTest` (recursion walk).
- **Ph2 — `MockTimelineRepository`** seeded from `timeline-data.js`. ✅ In this PR.
  `data/` package: `TimelineRepository` (interface — the swappable seam),
  `TimelineMockData` (ported dataset), `MockTimelineRepository` (in-memory
  `StateFlow`s + recursive trip/moment lookup). Koin `single<TimelineRepository>`
  bound. Tests: `MockTimelineRepositoryTest` (seed + nested lookup + dismiss).
- **Ph3 — ViewModels.** ✅ In this PR. Compose-free VMs in `:timeline-shared-logic`
  under `ui/screens/*` (packages the Ph5 screens will share): `TimelineHomeViewModel`
  (owns all three tabs + suggestion + strength + interlude actions — Places/Activities
  are *tabs*, folded in per §4.1, not separate VMs) + `TripDetailViewModel` /
  `MomentDetailViewModel` / `PlaceDetailViewModel` (sealed `Loading/Loaded/NotFound`,
  recursive lookups). Koin `viewModel { … }` bound (param-taking details via
  `parametersOf(id)`). Tests: `TimelineViewModelsTest`.
- **Ph4 — Shared composables.** ✅ In this PR. `:timeline-shared-ui`
  `ui/components/*`: `PhotoCluster` (custom `PageSize` ~78% peek carousel +
  elongating dots — CMP 1.7.3 has no `PageSize.Fraction`), `NamedMoment` (rail +
  connector via `drawBehind`, not intrinsic height — it must compose over
  `PhotoCluster`'s `BoxWithConstraints`), `ChapterHeader` + `MomentRoute`,
  `PhotoFrame` (gradient from `dominantColor`; named `TtPhoto` until `timeline-photos.md`
  T-P1 gave it real images to draw), `TimelineIcons` (material-icons
  stand-in for Lucide), and `personalise()` (`ui/theme`, pure + composable
  wrapper). The localisation split lands here: `strings.xml` + `@Composable`
  `durationText`/`photoCountText`/`momentMetaText` render logic's `DurationForm`
  (§8). Tests: `PersonalisationTest`. App() shows a live preview of the composables
  (Ph5 replaces it). Screenshotting is sandbox-blocked; verified via clean run.
- **Ph5 — Screens** (10). ✅ In this PR (with Ph6 — inseparable for a runnable
  app). `ui/screens/*`: `TimelineHomeScreen` (top bar + `TabRow` + Home feed /
  Places / Activities tabs, incl. `InterludeCard` + bound-trip cover), the
  recursive `TripDetailScreen` (contents walker: day-chapter / nested-trip card /
  collapsed / flat moments), `MomentDetailScreen` (four anatomies on
  `MomentKind`), `PlaceDetailScreen` (visit-history timeline). New reusable
  cards in `ui/components` (`TimelineCards`, `DetailScaffold`).
- **Ph6 — Navigation + `App()` + DI.** ✅ In this PR. `App()` = `KmpNavHost` over
  `mutableStateListOf(Home)`; entries pull `koinViewModel(parameters = { parametersOf(id) })`;
  Home uses a plain `Scaffold`/`Column` + `TabRow` (not `AdaptiveNavScaffold`,
  §4.1). All four destinations verified to render on desktop.
- **Ph7 — Shells** (`:timeline-desktop` + `:timeline-android`). ✅ Landed in Ph0;
  both call the same `App()`.
- **Ph8 — Verify + close-out.** ✅ Mostly done. Unit tests over
  `MockTimelineRepository`, the duration formatter, `personalise()` (strength-0
  no-op), and the view models per House Rule #7 — green. Desktop `run` boots and
  every destination renders (back-stack-seeded smoke). **Serif ADR written**
  ([ADR 0021](../adr/0021-timeline-editorial-serif.md)) — Timeline keeps the
  italic serif, app-scoped, via the platform-generic face for now. **Still
  pending (needs a device/human, can't automate here):** on-device Android pass +
  desktop click-through + screenshots. Deferred affordances are catalogued in §10.

## 8. Localisation (decision)

Follows ADR 0016 and AGENTS.md ("every new user-facing string authored via
`stringResource(…)` / `t(…)`"). The load-bearing constraint:
`:timeline-shared-logic` is **Compose-free**, so it cannot use `stringResource`
(a `@Composable`, UI-only API) — and, as in Drive/Control, the logic layer
authors **no** user-facing strings. Therefore:

- **Logic emits data, not prose.** `TimelineFormat.durationParts()` returns a
  `DurationParts(hours, minutes, form)` — the only locale-independent decisions
  (the h/m split + which form). The model carries raw `Int` counts.
- **UI localises** (Ph4/Ph5). `:timeline-shared-ui` renders unit words,
  "photo/photos", "No photos", "now", and separators from
  `composeResources/values/strings.xml` via `stringResource`, choosing the
  template from `DurationForm` + the raw count — mirroring
  `drive-shared-ui`/`control-shared`.
- **Plurals** ride as two string keys (e.g. `timeline_photos_one` /
  `timeline_photos_other`) for now — the honest n=1 shape (the codebase has no
  `pluralStringResource`). They graduate to ICU plurals when ADR 0016's n=2
  catalogue lands.

Net: no localisable string is authored until the UI phase, where every one goes
through resources. Ph1 stays string-free.

## 9. Open items

- **Serif (D3)** — ✅ resolved by [ADR 0021](../adr/0021-timeline-editorial-serif.md):
  Timeline keeps the italic serif, **app-scoped** (not in `:ui-core`'s
  `AppTypography`), via the platform-generic face for v1; a curated bundled face is
  a one-line swap when the brand owner picks + licenses one.
- **Design-handoff process** — Timeline is the second local-only handoff after
  `tenshu-icons/`. A durable convention (submodule? committed-but-fenced dir?) is
  worth defining; this proposal is the interim "distil to a committed proposal"
  answer.

## 10. Deferred (built as affordance-only or not at all)

Tracked so the gaps aren't mistaken for bugs. None block v1; each is additive.

- **Zoom scopes** — the Moments/Days chip is present in spirit; **Months / Years**
  content ships later (handoff §7).
- **Search** — no Timeline search exists, and none is designed here. The Home top-bar search icon
  was a no-op placeholder; **removed 2026-07-30** — a dead top-bar affordance reads as a broken
  feature rather than as a coming one. The retrieval it implied is specified elsewhere, in
  [`mia-query-layer.md`](mia-query-layer.md) MQ-2 ("widen the sources: Timeline places/moments")
  with the `FindPlaces` / `RecallMoment` intents; re-add the button with that destination behind it.
  ⚠ Sequencing: search over the timeline is worth little until places are reliably *named* and
  place-identity fragmentation has settled (`timeline-recognition-and-place-naming.md` §8–§10) — until then it returns duplicate rows
  under "Unnamed place", which reads as a broken search rather than as thin data.
- **Settings** — the Home top-bar gear opens a real `Destination.Settings` screen. Content is the
  three capture opt-ins (Capture / Place names / Wi-Fi), each rendered only where a platform binds
  its control. **Trimmed 2026-07-30:** the Appearance note, the About card and its
  "End-to-end encrypted · Built on Tenshu" line were removed — the first restated the platform
  default, the last restated a product-wide promise, and neither is a *setting*. The Diagnostics
  section went with them: it works, but it is a developer surface; `DiagnosticsSection.kt` is kept
  and re-rendering it is a two-line change. Real toggles (personalisation strength, a synced
  account) arrive once a preference surface lands.
- **Map surfaces** — "See map of all places →" is affordance-only, and the trip "route" is a
  bespoke **icon-glyph** (`MomentRoute`), *not* a geographic map. When a real map lands it renders
  over **`:ui-map`** — the offline MapLibre basemap extracted from Maps Ph7b
  ([`maps-app.md`](maps-app.md) §6; routes-belong-to-Timeline is that doc's §2 D1). `:ui-map` is
  House-Rule-#1-clean (bundled offline PMTiles, no tile egress); a consuming Timeline shell must
  strip `INTERNET` + `ACCESS_FINE/COARSE_LOCATION` via `tools:node="remove"` as `maps-android` does.
  **Prerequisite: a data-model extension** — `Place` and `Moment` gain a `Coord(latitude, longitude)`
  (§5). The mock carries none today; the real `:place-service` / ingestion `Place` spine already
  stores SI lat/lng, so the mock extension mirrors it and the swap stays mechanical (same trick Maps
  used). Place + Moment coords are **enough for pins** ("map of all places") **and a connect-the-dots
  trip route**; a *true* GPS-track polyline (the path actually walked) needs recorded location samples
  from the ingestion track ([`timeline-ingestion.md`](timeline-ingestion.md)) — out of mock scope.
- **Event notes** — the dashed "Add a note…" box is a placeholder.
- **"See all N visits" / "Compare with last year"** — affordance-only.
- **Translucent-blur, scroll-aware detail top bars** — v1 uses a solid
  `AppTopAppBar` above the hero (handoff §1.2); the blur-over-hero is a refinement.
- **Rich moment stats** (distance / pace / energy grids, pace-per-km bars) — the
  mock model doesn't carry them; the real on-device store provides them later.
- **Lucide glyph set** — v1 maps to `material-icons-extended`; a curated Lucide
  set replaces `TimelineIcons` later (handoff §2).
- **Real photos** — `PhotoFrame` synthesises a gradient from `dominantColor`; real
  image loading + on-device dominant-colour extraction come with the real store.
- **iOS shell**, **persistence/sync**, and the **real Timeline store** (behind the
  `TimelineRepository` seam) — the whole point of the mock: none of it disturbs the
  UI built here.

## 10. As-built — Timeline runs on the Control proxy (the in-process store is gone)

**Landed 2026-07-13**, alongside the same migration for Fit (`fit-app.md` §10) and Maps
(`maps-app.md` §7). Timeline is now a Control client like Drive: `:timeline-service`,
`:place-service` and `:health-service` reads and writes go over IPC to Control's proxy. Timeline
hosts no proxy and no store of its own.

### 10.1 The bug

`InProcessTimelineProxy` built its `InProcessProxy` over an `InMemoryObjectStore` (hardcoded in
`:core-common` — not a constructor seam). **Timeline's entire store was RAM.** Every captured visit,
every imported moment, every trip lived only in the app's process and was gone at process death.
This was invisible because the app re-seeded a fixture (`ReferenceTimelineSource`) at every launch,
so the screens always had *something* in them — just never the user's own data.

Fit had the identical bug and it surfaced there first (recorded workouts vanishing from History
while Home kept re-importing steps). On-device confirmation: neither app's private directory held a
database at all, while Control's held an 8.7 MB `objects.db`.

### 10.2 Why the standalone proxy was obsolete

Bring-up scaffolding: it lit up the projector without wiring Timeline into Control, and §7 parked
the rest. Both halves of "the rest" had since landed independently — Control's proxy **already
hosts all three services** (schema modules, `initTimelineService()`/`initPlaceService()`/
`initHealthService()` relation maintainers, and the proxy op factories) over its durable, synced
store, and Timeline **already had a `TenshuClient` reaching Control** (`timelineClientModule`,
Binder/UDS), used only for offline map regions.

### 10.3 What changed

- `timelineClientModule` registers the timeline + place + health schema/client modules alongside the
  region ones. One client, four service surfaces.
- `timelineRealRepositoryModule` binds `TimelineProjectionRepository` over **that** client. The
  `TimelineRepository` seam is untouched — no VM or screen moved.
- **`InProcessTimelineProxy` is deleted.** `:place-service-proxy` and `:health-service-proxy` drop to
  test-only dependencies.
- `TimelineCaptureForegroundService` (live location capture) and the desktop Google-Timeline file
  import now write through the Control-backed client, so a captured visit is durable and synced.
- **The launch-time fixture seeding is gone** (§10.4).

**Timeline now requires Control** (Drive's contract) — a local-store fallback is what produced this
bug: data in two places, one of which silently evaporates.

### 10.4 The seeding decision (worth stating explicitly)

`ReferenceTimelineSource` used to be written into the store at every launch. Against a throwaway
in-memory store that was harmless and, in fact, the only way the screens had content. Against
Control's **durable, synced** store it would write fabricated moments into the user's real realm and
push them to every paired device — so it is gone. Real content arrives the way it is meant to: the
Google-Timeline import, or live location capture. The fixture survives for tests and
`timelineRepositoryModule()` (the pure-mock app). Threat-model **A35** carries this as an as-built
note; the same reasoning removed Maps' POI seeding and gated Fit's fixture import.

### 10.5 Known wart — RESOLVED 2026-07-13

`:timeline-service-proxy` was a *main* dependency of `:timeline-shared-data` — not for proxy op
implementations, but because the pure dedupe/suggestion algorithms (`MomentReconciler`,
`TripSuggester`, `TripDetector`) lived in that module and the client-side projector calls them. The
app therefore depended on a **proxy** module in a process that runs no proxy.

**Fixed:** those three files (and their tests) moved to `:timeline-service-common`, package
`org.tenshu.timeline.common`. They are pure functions over plain data — their only import is
`kotlin.math` — so nothing about them was ever proxy-side. The `SemanticMerger`s (`MomentMerger`,
`TripMerger`) stay in `-proxy`, where they belong: they bind to `ExecutionContext`.

The rule this leaves behind: **pure algorithm → `-common`; `ExecutionContext`-bound merger →
`-proxy`.** `:timeline-shared-data` no longer references `:timeline-service-proxy` in its main source
set at all — only in tests, which stand the service up in-process. Details in
`timeline-ingestion.md` §11.

### 10.1 Control-unreachable signal + shell banner (2026-07-18)

The §10 migration's known gap — Control unreachable ⇒ every read folds to empty ⇒ the app is
silently blank — is closed with the recipe Fit pioneered (fit-app.md §9.14), now the shared
**`:app-reachability`** engine: `TimelineRepository.controlReachable(): StateFlow<Boolean?>`
(default always-`true` for the mock), derived in `TimelineProjectionRepository` from the four
read-flow folds + stream `.catch` + a slow probe (one-row place select) that **owns detection**
(a `.flow()` read against a dead transport can hang silently rather than fail). On recovery the
engine re-subscribes all four read streams (`readEpoch`), so live screens repopulate without
re-entry. The shell (`App()`) stacks `:ui-core`'s `ErrorBanner` ("Can't reach Tenshu Control —
your timeline can't load…") above the nav host. Verified live on the emulator: `pm disable`
Control → banner renders; `pm enable` → same process, probe reconnects, banner clears. The
visit outbox keeps its existing app-start + per-write drains (drain-on-recovery not wired —
per-write already replays pending visits).

## 11. As-built — the feed headline is derived, not the stored title (2026-07-30)

The Home feed read as a column of identical **"Visit"** rows with the place name demoted to the
meta line, and every movement printed its mode and distance **twice**. Both came from the same
root: `Moment.title` is composed **at ingest** and then rendered verbatim —
`VisitDetector` hardcodes `title = "Visit"` for every captured visit, and `MovementMapper.moveTitle`
pre-composes `"Walk · 2.4 km"`, which `momentMetaText` then repeats as `"Walk · 2.4 km · 31 min"`.
⚠ Those ingest-side strings are also **unconditionally English** — they bypass the §8 split, which
otherwise holds everywhere.

**Fix: derive the headline at render** (`momentTitleText`, `:timeline-shared-ui`), leaving the
stored `title` untouched.

| kind | title | meta line |
|---|---|---|
| `Visit` | the place name (`Unnamed place` when blank) | `3 photos · 1 h 22 min` |
| `Movement` | `Home → Work`, else `Ride to Work` / `Ride from Home`, else `Ride` | `Ride · 12.4 km · 38 min` — the mode appears **only** when the title is the route |
| others | the stored title, unchanged | unchanged |

Deriving rather than rewriting rows is the load-bearing choice: it fixes **every already-stored
moment with no migration**, needs no schema or wire change, and makes these titles localisable for
the first time. Feed and detail share the helper — a row labelled "Blue Bottle" opening onto a hero
reading "Visit" would look like the wrong moment opened. A `Visit`'s zero-photo head is now dropped
too: with the place name gone from that line, `No photos · 1 h 22 min` would *lead* with a negative
(the same reasoning already documented for movements).

**Endpoints** (`TimelineProjection.movementEndpoints` → `Moment.originPlace` / `destinationPlace`,
UI-model-only fields): read off the visits that **immediately** bracket the movement in the merged
sequence. Decisions worth keeping:

- **Computed before the day grouping.** A night drive's destination visit lands on the *next*
  calendar day; bracketing within a day would silently drop exactly those.
- **Immediately adjacent only.** Scanning past an intervening movement would collapse an unrecorded
  stop — "Home → shop → Work" rendering as "Home → Work". Consecutive movements therefore resolve
  no endpoint on their inner sides and fall back to the mode. Honest, and rare in practice: the M1
  segmenter already folds sub-threshold pauses, so single moves between dwells are the common shape.
- **A 30-minute gap guard** (`ENDPOINT_MAX_GAP_MS`). Capture holes are routine (an OEM kills the
  collector, GPS drops indoors); the visit on the far side of a two-hour hole is not where the trip
  started, and naming it would be a confident wrong answer.
- **No stored endpoint reference, deliberately.** The bracketing visits already *are* the answer, so
  a stored copy could only drift from them.

Covered by six `TimelineProjectionTest` cases (both ends, day boundary, capture hole, consecutive
movements, un-named place, isolated movement). No schema, wire or `TENSHU_STORAGE_FORMAT_VERSION`
change — HR#9 untriggered.

## Web scope

**Status:** DECIDED 2026-10-24 (see "Decided" below) — was a question PR (`web-app-suite.md` §8.1, Wave 2 Timeline run A). The maintainer
answers here; run B (the `web/timeline` app) cites the answers. Run A, the SDK boundary and bridge,
builds only what no item below disputes: the day list, moment / trip / place detail, the place list,
movement route points as data, and the pure-data writes (rename, user-answered role, merge / unmerge,
accept / dismiss a trip suggestion).

What every item shares: a browser tab is a **foreground-only sync peer**. It runs while the tab is
open and visible (Page Visibility; a hidden tab's timers are throttled and a frozen tab runs
nothing), it has no OS sensors, and per `web-app-suite.md` §7 an app makes **no third-party fetch**.

### 1. Location capture — a one-shot "I'm here now" check-in?

- **Can:** Geolocation `getCurrentPosition` / `watchPosition`, only after a permission prompt
  (Permissions API `geolocation`), only on a secure origin, only while the page is open. On a laptop
  the fix is Wi-Fi / IP-derived: typically 20 m–several km, often with no altitude or speed.
- **Cannot:** anything in the background. `watchPosition` stops or is throttled when the tab is
  hidden or the screen locks; there is no background geolocation, no geofence, no Background Sync
  for location, no activity recognition, no pedometer, no Wi-Fi scan (no BSSID for §4.5 recognition).
- **What a check-in would cost:** it is not just "write a Visit". A visit names a `Place`, and place
  identity (the ~74 m identity grid, the snap to an existing place, the clobber guard) is decided by
  capture code in `:timeline-shared-data` and by `PlaceReconciler` in the proxy — the browser runs
  neither (run A deliberately does not start `PlaceReconciler`, see below). A web check-in would
  need a second copy of the place-minting rule, or would mint duplicate places for every check-in a
  phone later reconciles. And a coarse desktop fix would confidently name the wrong venue.
- **Recommendation: drop for v1.** Revisit only as *build reduced* — a manual "I was here" on an
  **existing** place the user picks from the list (no coordinate from the browser at all), if the
  maintainer wants a web write path for visits.

### 2. Geocoding of unnamed places

- **Native today:** an unnamed place is named on-device from offline tiles (`PlaceLabelEnricher`,
  the stop tiles for §32.14 transit legs, §33 line overlays), with the optional Photon fallback
  behind `PlaceGeocodingEgress` — a **device-local** opt-in, off by default
  (`AndroidPlaceGeocodingControl`, SharedPreferences), and every lookup passes through it.
- **Can:** nothing offline yet: the browser holds no tiles. After W-MAP it could read the basemap's
  PMTiles through the runtime's range stream (`RegionStreamHandle`) — same data, no new egress.
- **Cannot (by rule):** call Photon or any geocoder from the page — §7 forbids a third-party fetch,
  and the native opt-in is per device, so a browser has never been asked. Proxying through the relay
  would show the relay a coordinate (a threat-model entry, HR #1/#2).
- **Recommendation: build reduced.** Show whatever a native device already named (`label`, `city`,
  `countryCode` sync), else "Unnamed place" with the coordinate as a secondary line; the user's own
  **rename** is the naming route on web. No egress. Transit legs show their mode without stop or line
  names. Revisit tile-based naming after W-MAP. (Run A returns label/city/country/coordinates and no
  stop/line names, so this is a UI decision only.)

### 3. Photos on the timeline (`photoBytes`)

- **Can:** the file plane is on web already (Drive): `photosCapturedBetween` is a commonMain
  file-service op, and `getPreview` / `getLargePreview` / `ContentStream` serve the thumbnail rungs,
  which are `OnDemand` and pulled when absent. So "which photos belong to this moment" and a
  thumbnail strip are feasible in a foreground tab.
- **Cannot / costs:** the native hero reads the `XLARGE`/`XXLARGE` rung (~139 KB+) per opened
  moment; a browser feed that pulls rungs per row costs relay traffic and IndexedDB space (evictable,
  `drive-web-parity.md` §1.2d). HEIC originals do not render (rungs only). And the photo→moment
  assignment (`PhotoSelection`, `TimelineProjection.photosWithin`) lives in `:timeline-shared-data`
  — the same placement problem as item 4.
- **Recommendation: build reduced, in a later slice** — a count and small rungs on the detail
  screen, no heroes in the feed — and only after item 4 decides where the projection lives, so the
  row and the detail can never disagree about which photos they own. Not in run A.

### 4. Anything else

**4a. ⚠⚠ Where the feed projection lives — the one that matters.** Native Home is
`TimelineProjection.days` (`:timeline-shared-data`, ~1.5 k lines): reconcile → **consecutive-visit
merge** (§2b) → **in-ride station-stop collapse** (`timeline-movement-capture.md` §15, §32.8: *nine
rows become six*) → movement endpoints ("Home → Work", §11) → day grouping. Only the first and last
steps are portable today: `MomentReconciler`, `TripSuggester` and `PlaceMerge.resolveRoot` are in
JS-compiled modules; the merge, collapse and endpoint rules are not. Run A therefore ships a
**data-level** day list — reconciled, merge-folded, subjects resolved, grouped by local day — and
does **not** fold rows: a split stay is two rows and a train with a signal stop reads ride / stay /
ride on web. Options:
  1. *copy* the three rules into the JS facade — a ~700-line second reader of the most-tuned code in
     the app (`web-app-suite.md` §4.1's trap at its largest);
  2. *extract* the pure projection (it takes plain `*Input` data classes, no client) into a
     commonMain module that also compiles to JS, and call it from both;
  3. ship unfolded rows on web.

  **Recommendation: 2**, as its own refactor PR before run B, with `TimelineProjectionTest` moving
  with it so one suite pins both readers. Until then run B can render run A's rows as they are.

**4b. Live rows.** The "● now" current place and the in-progress ride come from device-local capture
state (`OngoingVisitState` / `OngoingMoveState`); a tab has none. **Drop** — web shows only stored
moments.

**4c. Trip-suggestion dismissal.** Natively "Not a trip" is per-device and in-memory (§5.2: a
suggestion never syncs), so it returns after an app restart. On web it is per-tab and returns on
reload, which a user will see far more often. **Recommendation:** accept for v1 (same rule as
native); a persisted dismissal would be a new synced or device-local row (HR #9) and is a decision
for both platforms at once. Run A also makes accept/dismiss name the suggestion id the UI showed,
so a suggestion that changed under the user is refused instead of accepting a different one.

**4d. Wi-Fi binding on rename** (§8.4 F2: naming a place binds the network you stand on). No
browser API exposes the network. **Drop** — a web rename is a rename only.

**4e. Place-name picker** (`placeSuggestions`, Photon). Same egress as item 2. **Drop** with it;
the rename field is free text.

**4f. Units.** The feed formats distances in the realm-wide `OwnerPreferences.Units`, readable on
web through the preference plane. **Build** in run B — undisputed, listed so it is not forgotten.

---

## Version

Timeline v1.2 · 2026-07-13 · **pure ingestion algorithms moved to `:timeline-service-common`**
(§10.5) — `MomentReconciler` / `TripSuggester` / `TripDetector` + tests; the app no longer depends on
a proxy module. Behaviour-neutral: no schema, wire, op or logic change.

Timeline v1.1 · 2026-07-13 · **moved onto the Control proxy; `InProcessTimelineProxy` deleted**
(§10) — the app's in-memory store was losing every captured visit and imported moment at process
death. Data now lives in Control's durable, synced store; launch-time fixture seeding removed (it
would have written invented history into a real, synced realm). Threat-model A35 amended.

Timeline UI-v1.0 · direction Chapters (C) · plan 2026-07-01, shipped 2026-07-02.
Sidecar handoff: `docs/design/timeline/` (gitignored). Tokens: `:ui-core` Amethyst
Neutral. Serif: [ADR 0021](../adr/0021-timeline-editorial-serif.md).

### Decided (maintainer, 2026-10-24)

- **4a: extract the feed projection into a shared module.** The pure projection (consecutive-visit
  merge, in-ride station-stop collapse, movement endpoints, day grouping) moves from
  `:timeline-shared-data` into a commonMain module that also compiles to JS, with
  `TimelineProjectionTest` moving with it so one suite pins both readers. This is its own refactor
  PR, landing **before** Timeline run B; until then the web renders run A's unfolded rows.
  **As built (2026-10-24):** `:timeline-projection` (android, jvm, js browser, iosX64/Arm64/
  SimulatorArm64) holds `TimelineProjection` + `PhotoSelection`, the presentation model
  (`app.tenshu.timeline.model`, moved out of `:timeline-shared-logic`, which now `api`s it), the five
  classifier-version stamps (`ClassifierVersions.kt`) and `movementTitle` (`MovementMapper.moveTitle`
  delegates) — all in their old packages, so no import changed. The eight projection test classes
  (100 cases) run in its commonTest on JVM **and** JS. The web feed (`TimelineBook.feedDays`) calls
  `TimelineProjection.days` with no ongoing state, no photos and no stop/line names; trip detail and
  a place's visits stay unfolded, as natively. ⚠ The web DTO grew, never reshaped:
  `TimelineDayMomentJs`' old fields still describe the row's **representative** stored moment, the
  new ones (`displayTitle`, `spanDurationMin`, `spanDistanceM`, `mergedStayCount`, `traceIds`,
  `containedMoves`, `origin*`/`destination*`) describe the folded row. ⚠ `tenshu-sdk-ios-timeline`
  must `export(":timeline-projection")` — the model's Swift names live there now.
- **1** No check-in on web. **2** No geocoding egress: show what a native device named, else
  "Unnamed place" plus the coordinate; rename is the web's naming route. **3** Photos on the timeline
  later (count and small rungs on detail only), after 4a. **4b** No live "now" rows. **4c** Per-tab
  trip dismissal, the same rule as native. **4d/4e** Dropped. **4f** Units: built in run B.

### Run B as built (`web/timeline`, 2026-10-24)

- Rows render **as delivered**: the app never folds, merges or re-sorts, so run A's unfolded rows
  and 4a's folded ones both render without a UI change. Titles are derived (place / mode /
  activity type), never the stored English `title`.
- ⚠ **4f is only half built.** Every distance formats through one `UnitsContext`
  (`web/timeline/src/lib/units.ts`), but the web SDK has **no read of `OwnerPreferences.Units`**
  (run A exposed none, and run B may not edit `tenshu-sdk-js`), so the root provides METRIC — native's
  no-proxy default. Wiring it is a `units` read on the SDK plus one line in `App.tsx`.
- The raw reads are **not** on the app's allowlist. (`timeline.movementRoutes` joined it with the
  maps, below.)

### Routes as built (`web/timeline`, web-app-suite Wave 3, 2026-10-28)

Maps over `@tenshu/map-kit` (W-MAP) on the three screens that have one natively — trip detail
(`TripRouteMap`), a movement moment (M5) and place detail (`PlaceMap`). Home has no map natively, so
the day list has none, and never loads MapLibre (`web/timeline/src/mapSplit.test.ts`).

- **Trip:** each leg's route points, coloured by mode, origin and destination pinned, the camera
  fitted to the whole route. ⚠⚠ Every leg is read in **one** `timeline.movementRoutes` call, which
  keeps the leg boundaries and narrows both relations to the requested trace ids (`traceId oneOf …`)
  before the polyline is projected. The first build called `movementRoute` once per leg, and each
  call loaded every polyline blob in the store. A collapsed ride is one leg (one
  mode, all its `traceIds`), as native's hero.
- ⚠ The cache holds **points per leg, keyed by trace ids**; the colour comes from the legs as they
  are now, so a reclassified leg is never drawn in its old mode's colour.
- ⚠ Longitudes are **unwrapped** across ±180° (`lib/route.ts` `unwrapLongitudes`), for the line, the
  pins and the fitted bounds alike, so a transpacific leg draws the short way. A region lookup is
  always handed a WGS84 longitude (`wrapLongitude`), however far the line runs past 180.
- ⚠ **Divergence from native:** native draws a trip as one line in the theme primary, with no pins,
  and from the visits' place coordinates rather than the traces. The web draws the traces, per mode
  (`lib/route.ts` `MODE_COLORS`; an unknown mode keeps native's primary) with both ends pinned.
- **Place:** one pin, centred at native's hero zoom (14.5). No coordinate (0, 0) → no map.
- ⚠ No route points → **no map**, never an empty one. A failed route read renders by `kind` in the
  map's place. The **first** region lookup failing (the camera's) renders by its `error_map_*` kind
  in the map's place (`AppMap`), and the legs list and the place's actions stay usable around it.
  ⚠⚠ A lookup after a pan never replaces the map: it stays with map-kit's own over-the-map alert.
  Tiles are live (`FetchTileTransport`); a runtime without a transport still reads
  "not available on the web", with no retry.
- Allowlist: `timeline.movementRoutes`, `mapRegion.regionAt`, `mapRegion.streamRange` (not
  `timeline.movementRoute`). CSP: Timeline's site block is the `hosted_map_app` snippet
  (`web/apex/Caddyfile`), the how-to §2b map policy; it and `hosted_app` are both one import of
  `hosted_app_site`, which takes the policy as its argument.
