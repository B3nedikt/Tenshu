# ADR 0026 — App icons: one grid, five outputs, and the checks that gate them

- **Status:** Accepted
- **Date:** 2026-09-07
- **Owner:** Benedikt
- **Supersedes:** — (see *Scope* — this is **not** about the brand cat mark)
- **Superseded by:** —

This ADR is the whole contract for Tenshu's **app icons**. It is written to be
self-contained: hand it to a design session with no repository access and it has
everything needed to regenerate the set correctly. Anything it does not say is not
a rule.

## Context

The icon knowledge was scattered and partly unreachable:

- The working notes lived in `docs/design/tenshu-icons/`, which is **gitignored**.
  Nine `*-desktop/build.gradle.kts` files cited `GENERATOR_NOTES` for the macOS
  recipe, so every clone of this repo pointed at a file it did not contain — and
  the section they named (*"macOS .icns"*) had never been written at all.
- Two ADRs (0003, 0004) are about the **brand cat mark**, a different artefact,
  and were routinely mistaken for icon-set governance.
- The cost was not theoretical. `calendar-android`, `contacts-android` and
  `photos-android` shipped **byte-identical** launcher drawables for months — all
  three drawing the Contacts glyph — on the wrong background. `mia-android`
  shipped a placeholder sparkle. Five of ten desktop apps had no icon. `home` and
  `fit` had no source at all, so nothing could regenerate or check them.

## Scope

**This ADR governs the app icon set** — the per-app marks under
`docs/design/tenshu-icons/icons/`, and their Android, macOS and web outputs.

**It does not govern the brand cat mark** — the Outline/Solid variants in
`docs/design/brand/marks/` used in product UI and web chrome. That is
[ADR 0004](0004-mark-set-v7-ears-reinstated.md), which remains in force. The two
overlap in exactly one place: `tenshu` and `control` use the cat as their app-icon
pictogram. A change to the cat's silhouette is an ADR 0004 decision; a change to
how it is packaged as an app icon is this one.

## Decision

### 1. The set

Fifteen marks: fourteen apps and the `tenshu` brand tile.

| ID | Group | Token | Ships on |
|----|-------|-------|----------|
| `control` | Platform | primary | Android, desktop |
| `drive` | Productivity | primary | Android, desktop |
| `photos` | Productivity | primary | Android, desktop |
| `calendar` | Productivity | primary | Android, desktop |
| `contacts` | Productivity | primary | Android, desktop |
| `alarm` | Productivity | primary | — |
| `timeline` | History & Places | slate | Android, desktop |
| `maps` | History & Places | slate | Android, desktop |
| `fit` | Health & Activity | slate | Android, desktop, iOS |
| `home` | Home & Devices | slate | Android, desktop |
| `mia` | Assistant & Ambient | accent | Android, desktop |
| `store` | Store | vault | — (no module yet) |
| `creator` | Creator & Creations | accent-soft | — |
| `creations` | Creator & Creations | accent-soft | — |
| `tenshu` | Brand | primary | — |

**The set must match the apps that exist, in both directions.** Reconcile against
the repository's module list, not against the previous manifest.

- **A module with no mark is a gap.** That is what left Home on the generic Java
  icon and two iOS apps with no asset catalogue.
- **A mark with no module is usually fine** — `alarm`, `store`, `creator` and
  `creations` are planned apps that simply have not been built.
- ⚠ **A mark for something that is not an app at all is a defect.** `hub` sat in
  this table for months. The Tenshu Hub is *hardware* — a small always-on home
  node running the `tenshu-hub` server — and its user-facing surface is the **Home**
  app (`milestones.md`, and `HubStatusSource` lives in `home-shared-logic`). It
  will never appear in a launcher, so an app tile was the wrong artefact, and its
  presence here produced a standing "redesign the hub icon" item for an icon
  nothing would ever show. It was removed on 2026-09-07 rather than redesigned.
  Before adding a mark, check that the thing is an app.

### 2. Colour tokens

No new hues. Five tokens, from Brand Foundations:

| Token | Background | Foreground |
|-------|------------|------------|
| primary | `#5A4A96` | `#FFFBFF` |
| slate | `#1F2937` | `#FAFAFA` |
| accent | `#7B5265` | `#FFFFFF` |
| vault | `#3E2F6F` | `#FFFBFF` |
| accent-soft | `#FFD8E7` | `#301120` |

⚠ Use the token's **own** foreground. Shipping `#FFFBFF` on a slate tile, or
`#FAFAFA` on a primary one, is a defect — both have happened.

⚠ `accent-soft`'s foreground is **`#301120`**, which is *not* one of the other
four tokens' hues — it is `onTertiaryContainer` from
[ADR 0001](0001-design-system-direction.md), the colour the design system already
specifies for the `#FFD8E7` container. The icon set originally paired the pink
with `#7B5265`, which is `tertiary` — a **sibling** token, not the **on-** token.
That is the mistake to avoid repeating: when a background is a Material container
colour, its foreground is the matching `on-` colour, not the container's relative.

### 3. Construction

- **Master grid 64 × 64.** Every pictogram is drawn here and nowhere else.
- **Stroke 3.0–3.5** for `tile/`, `foreground/`, `android/*`; **4.0–4.5** for
  `favicon/`. A 2.5 stroke renders under 2px at 48dp. The whole set is at this
  baseline as of 2026-09-07, with one deliberate exception: the **brand cat**
  (`tenshu`, `control`) keeps its 1.4/1.6 whiskers, because whisker weight is part
  of the mark and ADR 0004 governs it — see the decision log.
- **Rounded caps and joins**, always.
- **Filled accents are solid, never stroked.** Dots, suns, doors, needles. An
  outlined interior feature closes into a smudge at launcher size.
- **Radius-30.** Every rendered pixel stays within radius 30 of centre. This is a
  property of the *set*, re-checked every round, not of the marks being drawn.
  Where a finished mark is over, fit it by **scaling about the grid centre** rather
  than redrawing: wrap the pictogram in a nested group
  `translate(32(1-k) 32(1-k)) scale(k)` and author strokes at `target/k` so they
  still *render* at the baseline. Scaling preserves the silhouette exactly, which
  matters most for marks another ADR governs.

### 4. Output sets

| Path | Canvas | Purpose |
|------|--------|---------|
| `tile/<id>.svg` | 1024², `rx=225.28`, background | iOS, desktop, PWA, marketing |
| `foreground/<id>.svg` | 1024², transparent | general use; the source for platform ports |
| `favicon/<id>.svg` | 1024², background | `<link rel="icon">` — **simplified**, see below |
| `android/foreground/<id>.svg` | 432², transparent, inset 84 scale 4.125 | adaptive foreground layer |
| `android/themed/<id>.svg` | 432², `#FFFFFF` | Android 13+ monochrome layer |

**`favicon/` is a simplified variant, not a re-stroked tile.** Any interior
feature below **r = 4** on the 64-grid is **deleted**, not thickened. At 16px the
pictogram spans about 10px, so an `r=2.6` dot is 0.4px and renders as nothing.
Raising the stroke and dropping the detail are different operations; v0.4 did the
first and called it the second, reproducing the exact failure it set out to fix.
`favicon/tenshu.svg`, `favicon/drive.svg`, `favicon/home.svg` and
`favicon/fit.svg` are the convention done right: one solid silhouette.

### 5. Android

Two layers plus a background colour, wired by each app's `<adaptive-icon>`.

**The themed layer honours ALPHA ONLY.** Every opaque pixel becomes the user's
tint; every transparent pixel stays transparent. Consequences:

- A feature painted in a *contrasting colour* over the body vanishes. Eyes drawn
  black on a white head collapse to a solid silhouette.
- The only way a feature shows through is as a **transparency cutout** — a hole in
  the alpha channel. Build it as one compound path with `fill-rule="evenodd"`,
  the body followed by each feature as a sub-path.
- A feature that is *additive on empty ground* needs no cutout. The calendar's
  day dots, the compass needle's north half and Home's door all sit on the
  interior of a stroked outline, which is empty, so they simply add.
- A stroked feature cannot be a hole — strokes paint, they do not subtract.
  Convert it to a closed filled outline first: sample the centreline, offset by
  ±w/2 along the normal, walk out and back, semicircular caps at each end.

**Porting a mark to a VectorDrawable.** Reference implementations:
`timeline-android`, `calendar-android`, `home-android`.

- Wrap the pictogram in a `<group>` carrying the source's own
  `translateX/Y="84"`, `scaleX/Y="4.125"`, so path data is **copied verbatim**
  from the SVG. Do not bake the transform into coordinates.
- The group scale also scales the stroke, so `android:strokeWidth` stays the
  nominal 64-grid value.
- `<rect>` and `<circle>` have no VectorDrawable primitive. A rect with `rx`
  becomes `M … L … A …`; a circle becomes an arc-pair,
  `M cx-r cy a r r 0 1 0 2r 0 a r r 0 1 0 -2r 0 Z`.
- One `<path>` per group in the source: stroked paths get `strokeColor`, filled
  paths get `fillColor`.

⚠ **AAPT2 links malformed `pathData` without complaint.** It is Android's
`PathParser`, at inflate time, that rejects it. The only end-to-end check is
installing and looking at the launcher.

### 6. macOS

macOS does **not** take the full-bleed tile. Apple's icon grid insets the body
inside a transparent margin behind a continuous squircle; shipping tile artwork
directly makes the Dock icon read ~25% oversized against every other app.

| | Value |
|---|---|
| Canvas | 1024 × 1024 |
| Body | 824 × 824, inset **100 px** (0.805) |
| Body shape | continuous squircle — **not** the tile's `rx=225.28` |
| Pictogram | the 64-unit grid spans **580 px**, centred |
| Fill | the app's tile background |

⚠ The corner does not fit a circular-corner rectangle — a best-fit circle is
**15.5 px rms** out, because the profile has a superellipse's long tail. Do not
re-derive the curve. **Lift the alpha channel from a shipped `.icns`** and use it
as the body mask; every size in the iconset then gives a correctly antialiased
mask for free.

Recipe, per iconset entry (`icon_{16,32,128,256,512}x…` and their `@2x` twins):

1. `iconutil -c iconset drive-desktop/icons/drive.icns -o mask.iconset` once.
2. Take that entry's alpha as the body mask; fill it with the tile background.
3. Render `foreground/<id>.svg` through a wrapper scaling it to the 580-px grid —
   `<g transform="translate(58.9 58.9) scale(0.885)">` around the source's own
   contents, on a `0 0 1024 1024` viewBox — at the entry's pixel size. Let the
   renderer place it; do **not** render once and resample.
4. Composite over the fill, keeping the mask's alpha.
5. `iconutil -c icns build.iconset -o <app>.icns`.

No Linux `.png` is owed; its absence is not a gap. Windows is §6b.

### 6b. Windows

*Added 2026-11-10 with the Windows port (`docs/proposals/desktop-windows-port.md`). Until then this section
read "no `.ico` is owed", true while every `*-desktop` module built `Dmg` + `Pkg` only — and every Windows build
showed the JVM's icon and placeholder letter tiles.*

**The pictogram and colours are the macOS icon's (§6); the shape is Windows'.** Windows 11 does not mask app icons —
an app ships its own shape — and the first build reused the macOS master whole. Next to its taskbar neighbours the
Apple squircle in its ~10% transparent margin read as small and foreign, so Windows gets its own body:

| | Value |
|---|---|
| Canvas | 1024 × 1024 |
| Body | 944 × 944, inset **40 px** |
| Body shape | rounded square, corner radius **14%** of the body (132 px) — chosen over a softer 22% (2026-11-10) |
| Pictogram | its macOS share of the body: the 64-grid spans 580 × 944/824 ≈ **664 px**, centred |
| Fill | the app's tile background (sampled from the `.icns`) |

⚠ Keep the background. A backplate-free white pictogram vanishes on a light taskbar, and the theme-specific
`_altform-lightunplated` images cover only the taskbar and Start — not the `.exe`, title bar, Explorer or Settings.
A plate-free Windows icon means a full-colour Fluent redesign of the pictograms, not a recolour.

**Generated, never hand-made:** `scripts/windows-app-icons.py` (Pillow + [resvg](https://github.com/linebender/resvg)),
`--check` for drift. It works from what git holds, because the design source is gitignored: the pictogram is the
app's Android launcher foreground (§5's port of `foreground/<id>.svg`, turned back into SVG by `check-icons.py`'s
`vd_to_svg`); the fill is sampled from the shipped `.icns`. Every size is its own render, body included. Before
writing anything the script composes the same pictogram onto the `.icns`'s own body at 1024 and diffs it against the
`.icns` — 0.02–0.05 mean channel difference across the set, i.e. antialiasing — so a pictogram, colour or placement
that drifts from macOS fails rather than ships.

Per module, `<module>/icons/windows/`, all in git:

| Output | Sizes | Consumer |
|---|---|---|
| `<id>.ico` | 16 20 24 32 40 48 64 256 | `nativeDistributions.windows.iconFile` — the launcher `.exe` |
| `window/tenshu-app-icon/<N>.png` | 16 … 256 | `TenshuWindowIcon()` (`:ui-core`) — title bar, Alt+Tab |
| `msix/Square44x44Logo.targetsize-N[_altform-unplated].png` | 16 … 256 | Start, taskbar, Settings, search |
| `msix/Square44x44Logo.scale-N.png`, `Square150x150Logo.scale-N.png`, `StoreLogo.scale-N.png` | 100/150/200/400 % | Start tile, package logo |

- ⚠⚠ **The window icon is separate from the package's.** A packaged app's taskbar entry comes from its manifest
  logos, but the title bar and Alt+Tab show whatever AWT was told — the JVM's cup by default. macOS never needed
  `TenshuWindowIcon()` (the Dock reads the bundle), which is how ten apps shipped without it.
- ⚠ **The plated and unplated `targetsize` files are the same pixels.** Our icon carries its own body, so it needs
  no plate; both names exist only because Windows asks for each in different places.
- ⚠ **`resources.pri` is what makes the qualified files visible** — `packageMsix` builds it with `makepri`.
  Without it Windows looks only for the manifest's literal `Assets\Square44x44Logo.png`, finds nothing, and shows
  a blank tile.
- ⚠ Small sizes keep the full pictogram, like the `.icns` does at 16/32 — so the cat is a blob at 24 px on both
  desktops. A per-app `favicon/` simplification (§4) is the fix for both, and needs the design source.
- `check-icons.py` COVERAGE fails a desktop module missing any of: the `windows { iconFile }`, the window icons on
  the classpath, the `TenshuWindowIcon()` call, or the MSIX logos.

### 7. iOS

One image: a **1024 x 1024 full-bleed square**, `idiom: universal`,
`platform: ios`, in `<app>-ios/Resources/Assets.xcassets/AppIcon.appiconset/`.
Same artwork as the tile, at the same 10.24 scale.

⚠ **No baked corner radius, and no alpha channel.** iOS applies its own squircle
mask, so a pre-rounded image fringes where the two curves diverge, and its
transparent corners put an alpha channel in the file — which App Store Connect
rejects outright. The background colour runs to all four edges; every pixel is
opaque. This is the one place tile artwork must be re-rendered rather than
reused, because `tile/<id>.svg` bakes `rx=225.28`.

*This corrects superseded guidance.* The old generator notes said "Don't strip the
corner radius from `tile/<id>.svg` — keep it. The iOS mask sits on top of whatever
you ship." That is wrong. `maps-ios` followed it and shipped an icon with
transparent corners and an alpha channel; the other apps did not, and were right.

Generate by compositing `foreground/<id>.svg` over an un-rounded background rect
on a `0 0 1024 1024` viewBox, then writing PNG **colour type 2** (truecolour, no
alpha channel) — not merely setting every alpha byte to 255.

Each app also needs two lines of build wiring in its `project.yml`, without which
the catalogue is never compiled in at all:

```yaml
    sources:
      - path: Sources
      - path: Resources          # the asset catalogue lives here
    settings:
      base:
        ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon
```

`calendar-ios` and `contacts-ios` had neither, which is why they had no icon at
all rather than a wrong one.

**Verify with `actool`** — it compiles the catalogue exactly as the build does,
and its partial plist says whether an icon was actually emitted:

```sh
xcrun actool --app-icon AppIcon --output-partial-info-plist /tmp/p.plist \
  --target-device iphone --minimum-deployment-target 17.0 --platform iphoneos \
  --compile /tmp/out <app>-ios/Resources/Assets.xcassets
# then: plutil -p /tmp/p.plist   ->  CFBundleIconName = AppIcon
```

**Three appearances (iOS 18).** Each app ships `icon_1024.png`,
`icon_1024_dark.png` and `icon_1024_tinted.png`, declared in `Contents.json`:

```json
{ "appearances": [{"appearance": "luminosity", "value": "dark"}],
  "filename": "icon_1024_dark.png", "idiom": "universal",
  "platform": "ios", "size": "1024x1024" }
```

⚠ **The three have opposite alpha rules.** The default must be **opaque** — App
Store Connect rejects alpha. Dark and tinted must be **transparent**: iOS
composites them over a ground it supplies, so an opaque one covers it.

⚠⚠ **Both transparent variants derive from `android/themed/`, never from
`foreground/`.** This is the §5 trap in a new place. `foreground/` paints interior
detail in the *background* colour — a fake hole that only works because a
background sits behind it. Drop the background and that detail becomes a coloured
blob. `control`'s cat lost its whole face this way, on the first attempt, and it is
the only mark in the set built that way. `android/themed/` punches the same detail
as a real `evenodd` alpha hole, which is what transparency needs.

**Tinted must be greyscale.** Apple maps luminance to the user's tint, so the mark
is rendered pure white on transparency — full tint intensity, the same contract as
the Android themed layer.

**Verify with `assetutil`, not just `actool`.** A clean `actool` run only means no
errors; it does not prove the appearance keys were understood. Inspect the compiled
catalogue:

```sh
xcrun assetutil --info <compiled>/Assets.car | grep Appearance
#   "Appearance" : "UIAppearanceDark"
#   "Appearance" : "ISAppearanceTintable"
```

If those two renditions are absent, the variants were ignored.

### 8. Web

The web apps consume the **`tenshu` brand mark**, not a per-app icon — `apex` and
`drive` both show it in the browser tab. Six assets, all rasterised from
**`favicon/tenshu.svg`**, the simplified fill-based variant:

| File | From | Size |
|------|------|------|
| `web/apex/public/icons/tenshu.svg` | `favicon/tenshu.svg` verbatim | — |
| `web/drive/public/icons/tenshu.svg` | the same | — |
| `web/apex/public/icons/favicon-32.png` | `favicon/tenshu.svg` | 32 |
| `web/apex/public/icons/apple-touch-icon.png` | `favicon/tenshu.svg` | 180 |
| `web/apex/public/icons/pwa-192.png` | `favicon/tenshu.svg` | 192 |
| `web/apex/public/icons/pwa-512.png` | `favicon/tenshu.svg` | 512 |

⚠ **These are hand-copied, and nothing syncs or checks them.** They sat one round
behind for a full redesign cycle — still carrying the pre-radius-30 geometry —
and no gate would ever have said so. Until a build step exists, **re-run this
table whenever the `tenshu` mark changes**, and verify by rendering the source at
each size and diffing: a correct copy is 0 differing channels.

⚠ **Strip `<metadata>` on the way out.** Anything under `web/*/public/` goes to
the browser byte for byte, and the design source's C2PA provenance manifest can be
90%+ of an SVG's bytes — `tile/contacts.svg` is 8,292 bytes of which 556 are
geometry. `favicon/tenshu.svg` happens to have none today, so the strip is
insurance rather than a fix; the guard in §9 is what actually enforces it.

Note that §4 nominates the *tile* for `apple-touch-icon` and PWA sizes, while
these use the favicon variant throughout. That is deliberate here: `tenshu` is a
brand mark rather than an app, and the simplified silhouette is what should
represent it at every size. Do not "fix" it to the tile without deciding that.

**Per-app icons on the landing page (2026-10-16).** The apex landing page's Apps carousel shows
the real per-app icons, which is the one place the web uses them. They are **derived, not
hand-copied**: `scripts/web-app-icons.py` resizes each app's iOS `icon_1024.png` and
`icon_1024_dark.png` to 128 px WebPs in `web/apex/public/icons/apps/` (the dark one with a dark
backdrop baked in, since its source is transparent — #4A4A50 → #2C2C31, a step lighter than iOS's
own so the tile reads on the site's dark page), and `--check` reports drift. Re-run it
whenever an iOS icon changes.

### 9. The gates

**`scripts/check-icons.py` runs the mechanical ones.** ~13s, no arguments, exit
non-zero on drift. It splits by what it needs:

- **Coverage** — always runs, no design source required. Every module that ships
  an app declares and ships an icon; the adaptive-icon XML has all three layers;
  no two apps ship identical glyphs; every iOS project sources `Resources` and
  declares `ASSETCATALOG_COMPILER_APPICON_NAME`; the three iOS appearances
  obey their opposite alpha rules and the tinted one is greyscale; every `docs/…` path cited by a build file or drawable exists; every icon a web page
  references is present; and no SVG under `web/*/public/` carries `<metadata>`.
- **Fidelity** — needs `docs/design/tenshu-icons/`, which is gitignored, and
  `rsvg-convert`. Re-derives what each consumer *should* be and diffs it: Android
  drawables at 0 px, iOS PNGs at 0 channels, the six web assets at 0 channels,
  and every source inside radius 30.

⚠ **On a clone the fidelity half SKIPS**, and says so. It is designed to be loud
rather than green: a silent pass on a machine without the source would be worse
than no check. Treat "fidelity skipped" as "unverified", not "fine".

Each of its checks was proven against the real bug it exists for — identical
glyphs, a missing asset catalogue, a stale web asset, an alpha channel, a wrong
background, a dangling doc reference — by reintroducing the bug and confirming a
failure.

The five judgement gates below cannot be automated. Run them every round.

1. **Radius-30, measured from the rendered alpha** — rasterise
   `android/foreground/<id>.svg` at 432², take the greatest distance from centre
   of any pixel with alpha > 16, divide by 4.125. Reading path data instead misses
   stroke half-width and the true extreme of a rounded corner, which is where the
   clipping lives. Two circles matter and they differ: **32.0** units is the 66dp
   safe area (any mask), **34.9** is the 72dp visible circle (a plain circle mask).
2. **Stroke floor** — §3. Interior detail below roughly `r=2.5` must never be
   load-bearing.
3. **Silhouette test, executed** — flood-fill the themed layer from the border and
   treat unreached transparent pixels as solid. ⚠ **Read it as collision detection
   between marks, not as a score for one mark.** Filling an outline mark solid
   reduces it to its bounding shape, so in a stroke-based set every mark becomes a
   blob; a ring becoming a circle is expected, not a defect. Filled-silhouette IoU
   also over-reports: this set contains pairs at 0.87–0.89 that nobody considers a
   problem. Use it to find *semantic* twins, then judge by eye.

  Worked example, because this is easy to get wrong. `store` was a house and so
  was `home`. Filled-silhouette IoU put that pair at **0.823** — below three pairs
  in this set nobody objects to. Ink overlap (the strokes themselves, no fill) put
  it at **0.211**, lower than `drive`/`creations` at 0.411. *Neither metric found
  a problem*, and neither was wrong: the two houses are drawn at different scales
  and never actually looked alike. The collision was **semantic** — two marks that
  both mean "home" — and no pixel metric can see that. Replacing the house with a
  bag raised both numbers (0.901 filled, 0.488 ink) while unambiguously fixing the
  real defect. **Read these numbers as a prompt to look, never as a verdict.**
4. **Small-size false-positive test** — rasterise at a true 16 and 24px and ask
   *has this become a different known symbol?* This is not legibility; the failure
   is a successful read of the wrong sign. A ring with a centred bar reads as
   "prohibited". Watch anything reducing to a ring, bar, slash, X or chevron.
   The fix is to **break the symmetry**, geometrically — never with colour or
   opacity, which the themed layer discards (§5).
5. **Verify ports by pixel-diff, not by eye** — extract the port's geometry back
   out, re-render at the source's canvas size, and diff the alpha channels. A
   correct port is 0 differing pixels; a correct `.icns` regeneration of an
   already-shipping icon is ≤0.03% of channel samples, all on stroke edges.

### 10. Source of truth

- The **SVGs under `docs/design/tenshu-icons/icons/`** are the artwork source.
  That folder is gitignored and regenerated from a design session.
- **This ADR is the contract**, and it is in git. Build files and drawables cite
  *this*, never the gitignored notes.
- **Per-platform outputs are in git** and are produced from the SVGs by the
  recipes above: Android VectorDrawables (§5), macOS `.icns` (§6), Windows `.ico` +
  MSIX logos (§6b), iOS PNG sets (§7) and the web assets (§8).

## Decision log

| Date | Decision |
|------|----------|
| 2026-09-07 | **Timeline is a compass, with a two-tone needle.** The three-nodes-on-a-curve was measured worse small — at 16px it is three grey smudges. The v0.4 needle was a symmetric rhombus that read as a prohibition sign; only its north half is filled now. The halves are separated geometrically because the themed layer discards colour. |
| 2026-09-07 | **Home keeps the house**, drawn to §3 with a filled door. It is the smart-home app; the metaphor was already right. |
| 2026-09-07 | **Fit's shipping heart is adopted verbatim as its source**, 2.5 stroke included, so that giving it a source changes nothing on any device. |
| 2026-09-07 | **Mia is `accent` (`#7B5265`)**, not slate. |
| 2026-09-07 | Calendar/Contacts/Photos ported from source; they had shipped identical drawables. |
| 2026-09-07 | **`hub` removed from the set.** The Tenshu Hub is hardware whose surface is the Home app, so it will never have a launcher icon; the standing "redesign hub" item was asking for an artefact nothing would show. Its product shape is also explicitly unsettled in `milestones.md`, so a committed mark would be premature. §1 now states the rule in both directions. |
| 2026-09-07 | **`accent-soft`'s foreground is `onTertiaryContainer` `#301120`, 13.2:1.** ⚠ The recorded diagnosis — "fails contrast at any stroke weight" — was **wrong**: at `#7B5265` it measured **5.01:1**, comfortably past WCAG 1.4.11's 3.0 for graphics. It was never a failure, only the *lowest* in a set otherwise running 6.5–14.1:1, and what actually made `creator` and `creations` weak at 16px was a 2.5 stroke and clipped geometry — both already fixed by the stroke + radius pass. The remaining gap was that the icon set paired the pink container with `tertiary` instead of its `on-` colour. |
| 2026-09-07 | **iOS 18 dark and tinted variants** for all nine apps (§7). Both derive from `android/themed/`: deriving the tinted one from `foreground/` cost `control` its entire face, because that mark fakes its interior holes with the background colour. |
| 2026-09-07 | **`scripts/check-icons.py`** — the icon set kept growing consumers nobody updated, each found by accident. Coverage runs anywhere; fidelity needs the gitignored source and skips loudly. |
| 2026-09-07 | **Control carries the brand mark deliberately** — it is the platform's main app and gets no icon of its own. `tile/control.svg` and `tile/tenshu.svg` are the same artwork (they differ only by `aria-label`) and must stay that way. |
| 2026-09-07 | **Web is a real output with a stated source (§8).** Its six assets were hand-copied and had been one round behind for a full redesign cycle; nothing syncs or checks them. |
| 2026-09-07 | **The combined stroke + radius pass.** Ten marks raised to a 3.5 rendered stroke and six fitted inside radius 30 by centre-scaling (creator 0.805, creations 0.858, tenshu/control 0.878, drive/mia 0.880). `creator` was previously clipped by a plain circle mask at 35.1. ⚠ The brand cat is the exception: it was scaled but **not** re-stroked — its 1.4/1.6 whiskers are part of the mark and `brand-agent.md` forbids changing the silhouette without an ADR 0004 amendment. Scaling changes size, not silhouette. |
| 2026-09-07 | **`store` is a shopping bag with the simplified cat inside** — the same reduction the macOS tray icon uses (solid dome and ears, no whiskers, no face), scaled to 19 units. Frees the house for `home`. The favicon variant drops the cat: inside a bag it is a blob at 16px. |
| 2026-09-07 | **iOS icons are full-bleed squares with no alpha** (§7), correcting guidance that said to keep the baked radius. maps regenerated, timeline un-staled, home/mia filled, calendar/contacts created and wired. |

## Consequences

- Nine `*-desktop/build.gradle.kts` files now cite this ADR instead of a
  gitignored file.
- Every Android app with a mark renders it, verified by pixel-diff against source;
  9 of 10 desktop modules ship an `.icns`; all 9 iOS apps ship an icon.
- A design session is handed this file, not four separate documents.

## Open items

- ~~`store` is a house, and so is `home`.~~ **Closed 2026-09-07** — `store` is a
  shopping bag carrying the simplified brand cat. See the decision log.
- ~~Ten marks are still at 2.5 stroke, and six sit outside the 32-unit safe radius.~~
  **Closed 2026-09-07** — the combined pass ran. All 16 marks now render a 3.5
  stroke and sit inside radius 30; see the decision log.
- ~~`accent-soft` contrast.~~ **Closed 2026-09-07** — and the original diagnosis
  was wrong. See the decision log.
- ~~`control` is byte-identical to the `tenshu` brand tile.~~ **Closed
  2026-09-07 — deliberate, not a defect.** Control is the platform's main app and
  carries the brand mark on purpose; it gets no separate icon. ⚠ The consequence
  is that `tenshu` and `control` must stay in lockstep across all five output sets
  and the six web assets (§8) — a change to one is a change to both.
- ~~`hub` is a bare pill that reads as a toggle switch.~~ **Closed 2026-09-07 —
  removed from the set, not redesigned.** It is hardware, not an app.
- **7 of 15 tiles share `#5A4A96`** — **accepted 2026-09-07, revisit later.** Not a
  defect: it is arithmetic, five palette tokens across fifteen marks, and no new
  hues may be introduced for app icons (§2). It is tolerable because silhouette now
  carries the recognition it needs to — every mark is distinct filled solid and
  legible at a true 16px, which was not true before the stroke and radius pass.
  ⚠ That is a *conditional* acceptance: it holds only while the silhouettes stay
  distinct. A new mark on `primary` must clear §9's collision and small-size gates,
  or the colour has to do work it cannot.
- ~~The new SVGs carry inline C2PA metadata.~~ **Closed 2026-09-07 — guarded, then
  stripped.** The original entry overstated it: only `calendar`, `contacts` and
  `photos` still carried it, and it cost nothing — every consumer but web
  rasterises, and web serves only `tenshu.svg`, which never had any, so the strip
  in §8's copy step was a no-op. The live risk was latent: wiring
  `favicon/calendar.svg` into a page would have shipped 8,622 B where 886 B is
  artwork, silently. §9's check now fails on any `<metadata>` under
  `web/*/public/`. The source was then cleaned too — 125,955 B → 9,345 B across 15
  files, tree 172KB → 56KB, at **0 differing render channels**, the block being
  inert. ⚠ A design regeneration will reintroduce it, and that is fine: the source
  is deliberately **not** guarded, so a fresh drop does not fail the check for a
  cosmetic reason. The served copies are what is enforced.
