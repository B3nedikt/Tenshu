# Basemap labels — render the place names our tiles already carry

**Status:** v0.4 — **LB-1 + LB-2 + LB-3 SHIPPED (2026-07-21/22), on-device verified. Phase map COMPLETE.**
As-built in §10, including the thing the design did not predict: **two Protomaps archive generations
are in play at once** (the demo is `pmap:`-prefixed and carries `transit`; the deployed regions are
unprefixed and do not), so the first style rule ever to read a tile attribute silently drew nothing
on the fallback path — and the obvious fix breaks M9's rail tests. Adds text to `:ui-map`'s offline basemap:
place names, street names, and (later) POI names. Companion to [`maps-app.md`](maps-app.md) §6
(the basemap engine), [`maps-region-delivery.md`](maps-region-delivery.md) D10 (the streamed
sparse-file delivery this rides on, unchanged), and [`timeline-movement-capture.md`](timeline-movement-capture.md)
§11.3 (the Timeline map hero that surfaced the gap). Independent of — and shippable before — any
work on map *interactivity* (§9).

**Trigger (2026-07-21):** the Timeline place/route map renders live Munich tiles correctly, and the
maintainer's read was *"at this level of detail it's basically useless — the map is just gray."*
It is: our style has **no `glyphs` and no symbol layers**, so not one label is drawn. The question
was whether that is an SDK limit, a data-fidelity limit of the deployed tiles, or unspecified. It
is the third — and it is the cheapest large legibility win available to the map surface.

---

## 1. The finding: the labels are already on the device

The deployed regions are **Protomaps v4** extracts (`deploy/tiles/build/catalog.json`;
`de-bayern` = 1.8 GB, `tileSchemaVersion: 4`). Decoding a real z14 tile out of the checked-in
Firenze archive shows every label layer present and populated:

```
minzoom 0  maxzoom 15
  places   1–15    kind=locality|neighbourhood, kind_detail=city|town|…, name, name:<lang>×35,
                   min_zoom, population, population_rank, sort_key, capital, wikidata
  pois     5–15    kind=park|museum|attraction|…, kind_detail, name, name:<lang>, min_zoom
  roads    3–15    kind=major_road|minor_road|path|ferry, kind_detail=primary|…, name,
                   min_zoom, sort_rank, oneway, is_bridge, is_tunnel
```

One z14 Firenze tile carries 6 `places`, 177 `pois` and 335 `roads` features — named, multilingual,
and **already inside the byte ranges we fetch today**. Three consequences that shape the whole slice:

1. **This costs zero extra bytes.** The streamed sparse file (`buildStreamedPartial` →
   `PmtilesDirectory.coveringRanges`) fetches whole tiles. The label features are in those tiles,
   parsed and discarded. Labels change the *style*, not the fetch — no change to the D10 streaming
   budget, the range cache, or the region catalog.
2. **Per-feature cartography is pre-baked.** `min_zoom` (per feature) and `sort_key` / `sort_rank`
   are Protomaps' own curation — which label deserves to appear at which zoom, and who wins a
   collision. We filter and sort by them rather than inventing thresholds.
3. **Localised names ride along free.** `name:de`, `name:en`, `name:it`, … are in the tile. Offline
   label localisation is a filter expression, not a data problem (§3 D4).

## 2. What's actually missing: a glyph atlas

MapLibre renders `text-field` from **SDF glyph PBFs** fetched from the style's `glyphs` URL
template. `offlineBasemapStyle()` (`OfflineBasemap.android.kt:235`) deliberately emits no `glyphs`
key and no `symbol` layers, because *any* remote glyph URL is egress and breaks House Rule #1 —
recorded as load-bearing in `ui-map/ui-map-agent.md`.

That constraint is real but it is not a *ban on labels* — it is a ban on **fetching** them. Shipping
the atlas in the APK satisfies both. Two mechanisms confirmed:

- **`asset://glyphs/{fontstack}/{range}.pbf`** is a documented, supported scheme on MapLibre Native
  Android for glyphs and sprites ([style spec](https://maplibre.org/maplibre-style-spec/glyphs/),
  [flutter-maplibre-gl#338](https://github.com/maplibre/flutter-maplibre-gl/issues/338)). ⚠ We have
  scar tissue here — `pmtiles://asset://` **SIGABRTs** in android-sdk 13.0.2 — but that is the
  PMTiles reader plugin doing its own `open()`, a different code path from the core `FileSource`
  that resolves glyphs. Treat as *likely fine, spike first* (§8 R1).
- **`localIdeographFontFamily`** makes MapLibre render CJK Unified Ideographs + Hangul from **device
  system fonts**, no glyph PBFs at all
  ([API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map-options/local-ideograph-font-family.html)).
  This is what keeps the atlas small — it removes the single biggest glyph range from the budget,
  and it is HR#1-clean (a system font, not a fetch).

---

## 3. Decisions

**D1 — Ship the atlas as a checked-in APK asset, staged to `filesDir` like the demo archive.**
Not generated at Gradle build time (a node/rust font toolchain in the build graph for a static
artifact that never changes is a bad trade), and not downloaded. Same posture as the existing
checked-in `tenshu_basemap.pmtiles`: bytes in the repo, a documented regeneration script beside
them. If the `asset://` spike (R1) fails, the same `ensureBasemapFile` temp-then-rename staging we
already have serves them over `file://` — so the fallback is code we've written once already.

**D2 — Noto Sans, two weights, Latin ranges only for the current catalog.** OFL 1.1, redistributable
in an APK, and the family Protomaps' own styles assume. Range files are 256 codepoints each:

| Range | Covers | Needed for |
|---|---|---|
| `0-255` | Basic Latin + Latin-1 | de, it, fr, es — **ä ö ü ß é è à ù ç ñ** |
| `256-511` | Latin Extended-A | cs, pl, hr, hu, sk (future regions) |
| `768-1023` | Combining + Greek | *deferred until a region needs it* |
| `1024-1279` | Cyrillic | *deferred until a region needs it* |

v1 = **2 ranges × 2 weights (Regular + Medium)**. Estimated on the order of a few hundred KB total —
verify at generation, and if Medium is not worth its bytes, drop to one weight and carry hierarchy
on `text-size` + halo alone. CJK/Hangul → `localIdeographFontFamily`, never shipped.

**D3 — Filter and sort by the tile's own `min_zoom` / `sort_key`.** `["<=", ["get","min_zoom"], ["zoom"]]`
as the visibility gate, `["get","sort_key"]` (places) and `["get","sort_rank"]` (roads) as
`symbol-sort-key`. We inherit the cartographer's judgement instead of hand-tuning zoom stops per
layer, and it degrades sensibly when the schema gains features.

**D4 — Label with the endonym (`name`) in v1; locale coalesce is a later one-liner.** "München",
not "Munich". It matches the offline-first posture, keeps the glyph budget to Latin, and avoids the
trap where a device locale we have no glyphs for renders as tofu boxes. When we want localisation
(ADR 0016), it is `["coalesce", ["get", "name:de"], ["get", "name"]]` **gated on the locale being in
the shipped range set** — the gate is the load-bearing part, not the coalesce.

**D5 — POI labels off in v1.** 177 POI features in one z14 tile against a 200–220 dp snippet is
mush, not information. Places + roads first; POIs become a parameter when a full-screen map exists
to hold them. No speculative knob before then.

> **Superseded by LB-3 (§10c).** The premise was wrong twice over: density is collision-bound rather
> than feature-bound (LB-2), and a *landmark allowlist* cuts the same tile from 177 to 10–14. POI
> labels ship on, with no parameter — the allowlist is the knob.

**D6 — Move `offlineBasemapStyle()` to `commonMain` as part of the slice.** It is pure
`buildJsonObject` + `Color` with no Android API, currently stranded in `androidMain` where
`commonTest` cannot reach it. Moving it makes the whole style — including the HR#1 guard (§6) —
unit-testable per House Rule #7. ⚠ One snag: it uses `String.format`, which is JVM-only; needs a
small multiplatform hex formatter.

---

## 4. The style delta

Added to `offlineBasemapStyle()`, all **above** the existing fill/line layers:

| Layer id | Source-layer | Filter | Notes |
|---|---|---|---|
| `places-locality` | `places` | `kind == locality` ∧ `min_zoom <= zoom` | `text-size` interpolated on `population_rank`; Medium weight; `symbol-sort-key: sort_key` |
| `places-neighbourhood` | `places` | `kind == neighbourhood` ∧ `min_zoom <= zoom` | smaller, Regular, uppercase-tracked |
| `roads-labels` | `roads` | `kind in (major_road, minor_road)` ∧ `min_zoom <= zoom` | `symbol-placement: line`, `text-rotation-alignment: map`, minzoom ~14 |
| ~~`pois-labels`~~ | `pois` | — | **D5: not in v1** |

Plus, at the style root: `"glyphs": "asset://glyphs/{fontstack}/{range}.pbf"`.

`MapPalette` gains `label` + `labelHalo` (dark: light text / dark halo; light: near-black text /
white halo). The low-chroma rule holds — labels are neutral, the pin stays the only saturated mark.

Every symbol layer carries a halo (`text-halo-width` ≈ 1.2, `text-halo-color: labelHalo`) — without
it, text over our beige block fills is unreadable, which is half of what "gray and useless" is.

## 5. Testing

`offlineBasemapStyle()` returns a `JsonObject`, so once it is in `commonMain` (D6) the whole slice is
pure-unit-testable in the existing `OfflineBasemapTest`:

- the `glyphs` template is present and its fontstack matches the shipped asset directory names;
- each expected symbol layer exists, over the expected source-layer, with the `min_zoom` gate;
- **the HR#1 regression guard** — walk every string in the emitted style and assert every URL-shaped
  value starts with `pmtiles://file://`, `asset://` or `file://`. This is the test worth writing
  regardless of the rest: it makes "someone pastes a demotiles/fontstack URL into the style" a red
  test rather than a privacy incident found on device.

Glyph loading itself is framework behaviour → on-device. House Rule #8 applies (touches
`androidMain`): `scripts/android-smoke.sh` before merge.

**No threat-model amendment is owed.** Nothing leaves the device before or after; HR#1 is unchanged
and now assert-tested. Worth stating explicitly in the PR description since the change *looks*
network-shaped.

## 6. Phases

- **LB-1 — one label, end to end.** Generate + check in the atlas, stage it, add `glyphs` and the
  single `places-locality` layer. Proves the entire chain (asset resolution → fontstack naming →
  SDF render) on device with the smallest possible surface. **Everything risky is in this phase.**
- **LB-2 — the real label set.** `places-neighbourhood` + `roads-labels`, palette label colours,
  halos, sort keys, density tuning against the 220 dp snippet. This is the phase that changes the
  maintainer's read of the map.
- **LB-3 — optional.** `localIdeographFontFamily`, locale coalesce (D4), POI labels behind a
  parameter (D5), extra glyph ranges as the region catalog grows.

## 7. Risks

- **R1 — `asset://` glyphs abort like `pmtiles://asset://` did.** Different code path, but our
  history here is bad. *Mitigation:* LB-1 spikes it first; fallback is `file://` from `filesDir`
  via the existing staging helper. Cost of being wrong: one small function, not the design.
- **R2 — fontstack names with spaces.** `text-font: ["Noto Sans Regular"]` resolves to an asset path
  containing URL-encoded spaces. *Mitigation:* if it misbehaves, name the stack `NotoSans-Regular`
  and generate the directory to match — the name is ours to choose.
- **R3 — label density at snippet size.** A 220 dp box holds ~3–6 labels before it is mush. This is
  LB-2 tuning work (padding, `text-size`, the `min_zoom` gate), not a design risk, but it is the
  difference between "legible" and "cluttered" and should not be rushed.
- **R4 — symbol collision cost per frame.** Negligible at snippet size with gestures disabled;
  revisit only if an interactive map lands.

## 8. Non-goals

**Map interactivity is a separate slice.** Pinch/zoom/pan is disabled by explicit design
(`GestureOptions.AllDisabled` — *"a basemap snippet is a static frame, not an interactive map"*),
and enabling it is coupled to the D10 streaming model: `coveringRanges(marginTiles = 1)` populates a
3×3 tile block around one fixed centre, so a pannable camera would move into unpopulated holes.
That needs a demand-driven fetch loop (camera settles → compute ranges → `RegionRangeCache.ensure` →
force a source reload), or a fully-downloaded region. Labels ship independently of all of it, on
both the streamed and downloaded paths.

## 10. As-built — LB-1 (2026-07-21)

**Shipped.** `asset://glyphs/…` renders on device: the Firenze demo draws its `Firenze` locality
label over live tiles, halo and all. R1 and R2 both closed *negative* — no `asset://` abort (the
`pmtiles://asset://` SIGABRT really is confined to the PMTiles reader plugin, not the core
`FileSource`), and a fontstack name containing spaces resolves fine.

**What landed:**

- `src/androidMain/assets/glyphs/` — Noto Sans Regular + Medium, ranges `0-255` + `256-511`, plus
  `OFL.txt`. **416 KB**, inside the D2 estimate. Sourced from `protomaps/basemaps-assets`.
- **`commonMain/BasemapStyle.kt`** (D6) — `offlineBasemapStyle`, `Color.hex()` (hand-rolled; the old
  `String.format` is JVM-only) **and** `routeBasemapStyle` + its GeoJSON builders, all moved out of
  `androidMain`. The route style moved too because it was equally pure, and because its
  rebuild-the-root shape is precisely what drops a new root key — see below.
- **`places-locality`** symbol layer per §4, gated on `min_zoom` and ordered by `sort_key`, both
  `coalesce`d (a `get` on an absent attribute yields null, and a null inside `<=` fails the
  expression's type check — which drops the *whole layer*, not the feature).
- `MapPalette` gains `label` + `labelHalo` for both themes.
- **`BasemapStyleTest`** — 10 tests, including the §5 no-egress guard. `:ui-map:desktopTest` 43/43.

**Three findings the design missed:**

1. ⚠⚠ **Two Protomaps archive generations are in play at once.** The bundled demo carries
   `pmap:kind` / `pmap:min_zoom`; the deployed regions carry `kind` / `min_zoom` / `sort_key`. Nothing
   had noticed because **no style rule had ever read a `places` attribute** — every existing layer
   keys only on the source-layer name. So the first attribute-keyed rule rendered correctly against
   real regions and silently drew nothing on the demo, which is the fallback path every fresh install
   takes.

   The obvious fix — re-extract the demo from a current v4 source — **was tried and reverted**: the
   demo is the only local archive carrying the **`transit`** layer, which M9's rail matching reads
   directly, and dropping it failed 9 tests. So the style reads both namings via `tileAttr()` instead
   (one `coalesce`; the vocabulary is identical). ⚠ The v4 builds carrying no `transit` at all is a
   **separate open question for the tile pipeline**: M9 may have nothing to match against in a
   deployed region. Not investigated here.
2. ⚠⚠ **A changed bundled archive does not invalidate `:ui-map:desktopTest`.** The archive tests open
   it by relative path, not as a test resource, so Gradle reports a stale PASS. LB-1's first "43/43
   green" was exactly that — the 9 rail tests were already failing. Use `--rerun-tasks` after touching
   any bundled asset. Recorded in `ui-map-agent.md`.
3. ⚠ **`routeBasemapStyle` rebuilds the style root**, so `glyphs` had to be carried across explicitly
   or the route map would inherit symbol layers with no font. Now asserted, not just commented.

**Verification.** `:ui-map:desktopTest` **44/44 with `--rerun-tasks`** (11 new; the rail suites green
again after the asset revert); `:maps-android:assembleDebug` + `:timeline-android:assembleDebug`
green; APK inspection confirms the glyph assets merge from the KMP library AAR to `assets/glyphs/…`;
emulator screenshot shows the label rendered over the *original* demo archive via `tileAttr()`. The label render itself was reached with a throwaway
diagnostic patch forcing the camera onto the demo extent (reverted) — see *Nit* below.

**Nit for LB-2:** the city label and `BasemapChrome`'s centre pin both sit dead-centre and overlap.
A small pin offset or a `text-offset` fixes it; folded into LB-2's density/tuning pass.

**Not done in LB-1** (unchanged from the plan): `places-neighbourhood`, `roads-labels`, POI labels,
locale coalesce, extra glyph ranges, `localIdeographFontFamily`.

## 10b. As-built — LB-2 (2026-07-21)

**Shipped.** The real label set: neighbourhood names and street names alongside LB-1's localities.
On-device: "Firenze" + "OLTRARNO" (uppercase, letter-spaced) + "Via dei Pandolfini".

- **`places-neighbourhood`** — Regular, smaller, `text-transform: uppercase` + letter-spacing: the
  standard cartographic tell for *an area you are inside* rather than a point you can stand on.
- **`roads-labels-{major,minor,path}`** — `symbol-placement: line`, ordered by `sort_rank`.
- **Collision priority is layer order** (MapLibre places symbols layer by layer), so the style emits
  locality → neighbourhood → roads. A city name can never lose its spot to a side street.

**The finding that reshaped the layer (§7 R3 was the wrong worry):**

⚠⚠ **`["zoom"]` inside a `filter` is the *tile's* zoom, not the camera's** — and it is capped by the
archive's `maxzoom`. The first cut gated road class with filter branches (`minor_road` at `>= 15`,
`path` at `>= 16`); on the maxzoom-14 demo archive **neither can ever fire**, and on the deployed
regions (maxzoom 15) the path branch never fires either. Overzoom does not raise it. Symptom: no
street label ever renders, with no error — MapLibre parses and applies the filter perfectly, it just
never matches. Fixed by splitting road class across three layers so each gets a camera-based layer
`minzoom`. The per-feature `min_zoom` *attribute* gate is a different thing and stays as-is.

⚠ **Density was the wrong thing to fear.** §4's "294 named roads per tile → mush" conflated *tile*
content with *viewport* content. MapLibre's collision engine and available line length bound the
count long before the feature count does: at the 14.5 snippet camera, central Florence draws
**one** street name. So minor roads label from zoom 14 (not the planned 15), and `symbol-spacing` —
raised to 350 to thin repeats — made no observable difference and was dropped back to the default.
Tune these by measuring on device, not by counting features.

**Nit resolved:** the LB-1 pin/label overlap was an artifact of the diagnostic camera sitting exactly
on the city centroid. Real snippets centre on a *place*, so the city label lands elsewhere. No
offset hack added.

**Verification.** `:ui-map:desktopTest` 46/46 with `--rerun-tasks`; `:maps-android:assembleDebug` +
`:timeline-android:assembleDebug`; emulator screenshots at 14.5 and 13.8; smoke gate.

**Still not done** (LB-3): POI labels, locale coalesce, Greek/Cyrillic ranges,
`localIdeographFontFamily`.

## 10c. As-built — LB-3 (2026-07-22)

**Shipped.** Named landmarks and reader-language labels. On device (demo archive, forced `de`):
"**Florenz**", "**Galerien der Uffizien**", and "Via dei Pandolfini" — localised place, localised
landmark, endonym street, all in one frame.

- **`pois-landmark`** — the `pois` source-layer behind a **kind allowlist** (§ below), ranked by
  `min_zoom`, placed above roads in collision priority: "Ponte Vecchio" orients a reader better than
  a side street does.
- **Localised place + POI names** — `["coalesce", ["get","name:<lang>"], ["get","name"]]`, with the
  language cleared by `labelLanguageFor(Locale.current.language)`. Roads stay endonyms by design.

**⚠ An allowlist is required; `min_zoom` alone is not enough.** Protomaps calibrates `min_zoom` for
a full-screen map, and for some kinds it is far too eager: one z14 tile over central Florence puts
**38–61 `library` features** (convent and institute libraries) through the gate — noise on a
snippet. Restricting to landmark kinds cuts the same tile to **10–14**: Boboli, Ponte Vecchio, the
Uffizi, Santa Croce. Conversely `place_of_worship` needs no special handling — Protomaps already
holds churches to `min_zoom` 16, so they cost nothing at snippet zoom and arrive when earned.

**⚠ POIs carry no `sort_key`**, so collision order would be arbitrary and a random chapel could
out-rank the Duomo. `min_zoom` doubles as the prominence ranking (lower = more prominent = sorts
first), which is exactly `symbol-sort-key`'s semantics.

**⚠⚠ The locale *gate* is the load-bearing half of D4, not the coalesce.** The tiles carry ~20
`name:<lang>` variants including `el`, `he`, `ja`, `ko`. Asking for one whose script is outside the
shipped glyph ranges renders tofu boxes — strictly worse than the endonym. `GLYPH_COVERED_LANGUAGES`
is therefore an explicit list, **not** an "is it Latin-ish" heuristic: Vietnamese is Latin but needs
Latin Extended Additional (`0x1E00`), which we do not ship, so it is absent and correctly falls back.
This cannot protect against the *endonym itself* being unrenderable — a Greek region's `name` is
Greek regardless of the reader — but that is a glyph-range question for whenever the catalog leaves
Latin script.

**`localIdeographFontFamily` needs no code — verified in the SDK source, not assumed.**
`MapLibreMapOptions.localIdeographFontFamilyEnabled` defaults to `true`, and
`createFromAttributes(context)` — which maplibre-compose calls when it builds the `MapView` — sets
the family to `MapLibreConstants.DEFAULT_FONT` (`"sans-serif"`). So CJK and Hangul already render
from device system fonts. Worth knowing because maplibre-compose 0.13.0's `MapOptions` exposes
**no** hook for it (only render/gesture/ornament options), so had the default been off there would
have been no clean way to turn it on.

**Extra glyph ranges: still deferred**, and now with a reason rather than an omission — the locale
gate makes Greek/Cyrillic unnecessary for *correctness*; they become necessary only when the region
catalog itself leaves Latin script.

**Verification.** `:ui-map:desktopTest` 53/53 with `--rerun-tasks` (20 in `BasemapStyleTest`);
`:maps-android:assembleDebug` + `:timeline-android:assembleDebug`; emulator screenshot; smoke gate.

## 11. Version

v0.4 (2026-07-22) — LB-3 as-built (§10c): landmark POIs + reader-language labels. Supersedes D5.
Records that the POI allowlist (not `min_zoom`) is what makes POI labels viable, that the locale gate
is the load-bearing half of D4, and that CJK already works via the SDK default. **Phase map complete.**

v0.3 (2026-07-21) — LB-2 as-built (§10b): the full label set, plus the filter-zoom-vs-camera-zoom
trap and the correction that label density is collision-bound, not feature-bound.

v0.2 (2026-07-21) — LB-1 as-built (§10): shipped and on-device verified; records the demo-asset
schema drift and the route-style root-rebuild trap.

v0.1 (2026-07-21) — opened from the Timeline map legibility report. Establishes that the label data
is already resident on device and the only missing piece is a shipped glyph atlas; decides D1–D6 and
phases LB-1..LB-3. No code.
