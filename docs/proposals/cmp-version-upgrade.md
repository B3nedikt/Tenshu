# Compose Multiplatform 1.7.3 → 1.9.3+ upgrade — scoping

**Status:** v0.2 — **IMPLEMENTED 2026-07-08 (green; pending maintainer desktop-`run` + PR).**
Lifted the CMP **1.7.3** pin → **1.11.1** in one coordinated PR. Triggered by the Maps app's Ph7
(`docs/proposals/maps-app.md` §6), which needs a live **MapLibre** basemap binding (CMP ≥ 1.8).
Supersedes the tech-debt entry's conclusion (`docs/tech-debt.md` → *CMP Version Pin* now RESOLVED).

**As-built (§2/§5 confirmed — all mechanical, zero source drift):** bumped `jetbrains-compose`
`1.7.3 → 1.11.1`; deleted the **11** `force()`/`exclude()` resolutionStrategy blocks (+ the
hardcoded skiko `0.8.18` and the explicit `skiko-awt-runtime-macos-*` desktop deps) and the **6**
`fixComposeResourcesForAndroid` Sync-task workarounds. Empirical resolutions: skiko `0.144.6`
(not the §2-guessed `0.9.22.2` — 1.11.x uses a new scheme), JB `material3` `1.9.0` — **both
plugin-managed, no manual catalog pins needed** (Q2 = full delete confirmed). Android compose
resources now land via the plugin-native `copyAndroidMainComposeResourcesToAndroidAssets`.
Q3 = androidx compose BOM left pinned (`2026.05.00`, no split-brain). Q4 = Preview namespace flip
deferred (warnings-only follow-up; at 1.11.1 the compiler deprecates the *JB* namespace and prefers
`androidx` — the reverse of the 1.7.3 side-effect). **Kotlin stayed 2.3.21 → SKIE pin untouched.**
**Green:** `ui-core` + all five apps' `desktopTest` + Android `compileDebugKotlin`, full
`./gradlew test`, and `scripts/android-smoke.sh` (Control + Drive install/launch, no crash/ANR).
**Pending:** maintainer desktop-`run` click-through (sandbox can't screenshot) + squash-merge.

**Decisions (§11, locked 2026-07-08):**
- **Go** on the upgrade as its own track.
- **Target CMP 1.11.1** (Q1); fall back to **1.9.3** only if 1.11.1's source delta proves large
  during the `ui-core` step.
- **One coordinated PR** (§7) — `ui-core` + all five apps bumped + force-blocks stripped + fixed
  together; a half-bumped module graph will not resolve.
- **Maps Ph7 ships the schematic `MapSnippet` now** (Ph7a); live MapLibre swaps in
  after this track merges.
- Q2 (full delete vs minimal force) determined empirically in step 2. Q3/Q4 (androidx BOM, Preview
  namespace) settled inline during implementation.

**Owner's note:** this is a platform-wide toolchain change (every Compose UI module across
Drive / Control / Timeline / Fit / Maps), so it wants explicit sign-off on **target version**
and **rollout shape** before code. The empirical findings in §2 were produced by probing in a
throwaway worktree; no repo files changed.

---

## 1. Why the pin can move now (corrected finding)

The tech-debt entry pins CMP at **1.7.3** because newer CMP (e.g. 1.10.3) threw
`NoSuchFieldError` / `NoClassDefFoundError` in `desktopTest` under **AGP 9.1.x + Kotlin 2.3.x**.
Its **"revisit when AGP is bumped"** trigger has since fired — the repo is now on **AGP 9.2.1**.

Re-testing under the *current* toolchain (AGP 9.2.1, Kotlin 2.3.21, Gradle 9.4.1) found the
pin's cause is **resolved upstream**, but only above a version floor the earlier note didn't
capture:

- **AGP 9 support was *added* in CMP 1.9.3 / 1.10.0** ([KMP docs](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)).
  CMP **1.8.2 and 1.9.0 predate it** — they fail at *configuration time* with
  `KotlinMultiplatformAndroidComponentsExtension.onVariant(...)` (the AGP-9 KMP-library variant
  API). That is the wall an initial probe hit; it is **not** the pin's `desktopTest` issue and
  it is **gone at 1.9.3**.
- At **CMP 1.9.3** on this exact toolchain: the `onVariant` wall **clears**, dependencies
  **resolve**, and Compose-resource generation **runs**. The remaining failures are ordinary
  **source/coordinate drift** (§2), not a hard incompatibility.

**Net:** the pin is not stale-by-luck — it is liftable *because* AGP moved to 9.2.1 and CMP
1.9.3 added matching support. The blockers below are mechanical.

## 2. What actually breaks at 1.9.3 (all mechanical)

Three things, all fixable:

1. **The `force()` / `exclude()` blocks are the 1.7.3 workaround and now actively harm.**
   Each Compose module force-pins *every* compose artifact to one umbrella version and
   `exclude`s the `androidx.compose.*` groups. At 1.7.3 that avoided a JetBrains-vs-androidx
   class collision; at **1.9.x the classes live under `androidx.compose.*`**, so the excludes
   strip the real `Composable`/`runtime` classes → `Unresolved reference 'Composable'`. These
   blocks should be **removed** (hypothesis) or reduced to a minimal targeted force.
2. **`material3` is version-decoupled from the umbrella.** `org.jetbrains.compose.ui:ui`,
   `:foundation`, `:runtime` publish `1.9.3`, but **`org.jetbrains.compose.material3:material3`
   stops at `1.9.0`** (`material3:1.9.3` is a 404). Any force referencing `$composeVersion` for
   material3 must target its own line.
3. **skiko bumps `0.8.18 → 0.9.22.2`** (the skiko bundled by CMP 1.9.x).

**Not affected — the load-bearing de-risker:** **Kotlin stays 2.3.21.** Since Kotlin 2.0 the
Compose *compiler* ships with Kotlin (the `kotlin.compose` plugin), decoupled from the CMP
*runtime*. This upgrade moves only the **runtime + gradle plugin**, so it does **not** touch
the Kotlin compiler or the **SKIE ⨯ Kotlin pin** (`skie = 0.10.12`, zero headroom — see
tech-debt). No Kotlin/AGP/Gradle bump is required.

## 3. Target version

| Option | Notes |
|--------|-------|
| **CMP 1.9.3** | The empirically-probed floor for AGP 9 on this toolchain. Conservative; smallest source delta from 1.7.3. |
| **CMP 1.11.1** (latest stable) — **recommended** | Latest is always compatible with the latest Kotlin; min Kotlin 2.1.0 (we're on 2.3.21). More source drift than 1.9.3 but avoids a second upgrade soon, and 1.11.x is where MapLibre-Compose bindings are actively tested. skiko/material3 pins differ from 1.9.x — pin them from the chosen version's poms. |

Recommendation: **target 1.11.1**, but keep 1.9.3 as the documented fallback if 1.11.1's source
delta proves large. (1.12.x is beta — out.) Exact skiko + material3 versions are read from the
chosen CMP's `ui-desktop` / `material3` poms at implementation time, not guessed.

## 4. Blast radius (concrete)

- **11 build files** carry the `force()`/`exclude()` + hardcoded skiko `0.8.18`:
  `ui-core`, and `{drive,control,timeline,fit,maps}-{shared-ui|shared|desktop}` (per app: the
  shared-UI/​shared module + the desktop run module).
- **6 build files** carry the `fixComposeResourcesForAndroid` Sync-task workaround + the
  `androidComponents { onVariants { … addStaticSourceDirectory } }` asset wiring:
  `ui-core`, `{drive,control,fit,timeline,maps}-shared-ui`/`control-shared`. **Candidate for
  deletion** — CMP 1.9.3 adds first-class AGP-9 Compose-resource support; validate by removing
  and checking Android resource generation.
- **5 Android app modules** apply `platform(libs.compose.bom)` (androidx BOM `2026.05.00`):
  `control`, `drive-android`, `fit-android`, `timeline-android`, `maps-android`. Reconcile the
  androidx BOM with the CMP-managed versions (or drop the manual force where the plugin now
  manages it).
- **31 source files** import `org.jetbrains.compose.ui.tooling.preview.Preview` (a documented
  side-effect of the pin). Check whether the androidx `Preview` namespace is again usable at the
  target; if so this is an optional cleanup, not a blocker.
- **Version catalog** (`gradle/libs.versions.toml`): `jetbrains-compose`, plus new pins for the
  decoupled skiko + JetBrains-material3 lines.

## 5. Migration plan (ordered)

1. **Bump the catalog** `jetbrains-compose` → target; add `skiko` + `jetbrains-compose-material3`
   version entries (replace the 11 hardcoded `0.8.18` and the material3 `$composeVersion` forces
   with catalog refs — or delete per step 2).
2. **Delete the `force()`/`exclude()` blocks** in the 11 files (the hypothesis: unnecessary at
   1.9.x). Rebuild each module's `desktopTest`; if a genuine collision remains, re-add a
   **minimal, commented** targeted force rather than the whole apparatus.
3. **Delete the `fixComposeResourcesForAndroid` workaround** in the 6 files; verify Android
   Compose-resource generation still runs (`:<app>-android:compileDebugKotlin` + a resource-using
   screen).
4. **Reconcile the androidx compose BOM** in the 5 app modules.
5. **Source fixes** for CMP 1.7→target API drift (the `AppBottomSheet`/`AppDialog` etc.
   `Unresolved reference` class in `ui-core` first, since everything depends on it). Optionally
   flip the `Preview` import back to androidx.
6. **Update the pin docs**: rewrite the `docs/tech-debt.md` CMP entry (or retire it) and drop
   the CMP-1.7.3 caveats from `CLAUDE.md`.

Sequence within the module graph: **`ui-core` first** (all UI depends on it), then per app
(Drive / Control / Timeline / Fit / Maps), each gated on its `desktopTest` + Android compile +
a desktop `run` smoke.

## 6. Validation (House Rule #7)

Per module, green before moving on:

- `./gradlew :<module>:desktopTest` — the exact environment the pin cited.
- `./gradlew :<app>-android:compileDebugKotlin` — AGP-9 Compose-resource path.
- `./gradlew :<app>-desktop:run` — boots + renders (human/desktop smoke; screenshotting is
  sandbox-blocked, so this is the maintainer's click-through).
- Full `./gradlew test` at the end.
- Android on-device smoke for at least one app (resource + Preview path differs on device).

## 7. Rollout shape (decision needed)

- **One coordinated PR** — recommended. The force blocks are a *shared* apparatus; a half-bumped
  graph (some modules 1.7.3, some target) will not resolve, because a downstream app pulls both.
  A single PR that bumps + strips + fixes across `ui-core` + all five apps is the honest unit.
- **Staged (`ui-core` + one app first)** — only viable if the intermediate state compiles, which
  the shared force blocks make unlikely. Not recommended.

Either way this is **its own track**, landed **before** the live-MapLibre Maps Ph7 (which sits
on top of it). Until it lands, Maps Ph7 ships the **schematic `MapSnippet` render behind the
`expect`/`actual` seam** (`maps-app.md` §6 Ph7, Option A) so Maps is not blocked on this.

## 8. What it unblocks / pays for itself

- **Live MapLibre** for Maps Ph7 (the trigger) and any future map surface.
- **Deletes the `fixComposeResourcesForAndroid` hand-wiring** (6 files) — a maintenance tax that
  only exists because 1.7.3's resource path didn't work with the AGP-9 KMP plugin.
- **Removes 11 files of `force()`/`exclude()` cruft** — the pin's workaround.
- Two-plus years of Compose fixes/perf across Drive/Control/Timeline/Fit/Maps.

## 9. Alternatives considered

- **Isolate the AGP-specific Compose wiring into a `ui-core` Android wrapper** (raised in
  review) — does not help: the blocker was CMP-plugin ⨯ AGP-version *coordinate* coupling
  (fixed by CMP 1.9.3), not android-specific code that could be hidden behind a module boundary.
- **Bump AGP/Gradle further** — the toolchain is already *past* the AGP-9 floor; the fix is the
  newer **CMP**, not a newer AGP. A Kotlin bump is explicitly *not* wanted (SKIE pin).
- **Stay on 1.7.3, ship Maps with the schematic map only** — viable indefinitely for Maps slim
  (the schematic is faithful to the handoff's `MapSnippet`), but leaves the pin + workarounds +
  the live-map ceiling in place. This upgrade is worth doing on its own merits regardless of Maps.

## 10. Open questions

- **Q1 — target 1.11.1 or 1.9.3?** (§3). Recommend 1.11.1; fall back to 1.9.3 if source delta bites.
- **Q2 — can the `force()`/`exclude()` blocks be fully deleted, or is a minimal targeted force
  still needed** for a residual androidx-vs-JB collision on some module? (Determined empirically
  in step 2; hypothesis = full delete.)
- **Q3 — androidx compose BOM**: keep pinned (`2026.05.00`) or let the CMP plugin manage it? Pick
  one to avoid split-brain versions in the app modules.
- **Q4 — flip `Preview` back to the androidx namespace** (31 files) in this PR, or leave as a
  follow-up cleanup?

## 11. Decision needed

1. **Go / no-go** on the upgrade as its own track.
2. **Target version** (Q1) and **rollout shape** (§7).
3. Whether Maps Ph7 ships the **schematic render now** (unblocked) or **waits** for this track.

## 12. Version

CMP-upgrade scoping v0.1 · 2026-07-08. No code. Empirical basis: throwaway-worktree probe under
AGP 9.2.1 / Kotlin 2.3.21 / Gradle 9.4.1 — CMP 1.8.2/1.9.0 fail (`onVariant`, pre-AGP-9); CMP
1.9.3 clears config + resolution, leaving only force-block/​material3/​skiko/​source drift.

---

## 13. Follow-up bump — CMP 1.12.0 + BOM 2026.08.00 (2026-08-26)

Routine maintenance bump, one release behind on both halves. **The two move together**, and that
is the only non-obvious part of this track's steady state:

| Catalog key | From | To |
|-------------|------|----|
| `compose` (`androidx.compose:compose-bom`) | `2026.06.01` | `2026.08.00` |
| `jetbrains-compose` (`org.jetbrains.compose` plugin) | `1.11.1` | `1.12.0` |

BOM `2026.08.00` carries `androidx.compose.{ui,runtime,foundation,material}` **1.12.0**, and CMP
**1.12.0** wraps that same androidx release. Bumping either alone splits the Compose runtime the
Android app modules resolve from the one the KMP UI modules resolve — the exact split-brain Q3
(§10) was asking about. There is no `2026.07.x` BOM; Google skipped the month.

`material3` stays pinned at **1.4.0** by hand — the BOM maps it to 1.4.0 at both the old and new
version, so the narrow per-module `resolutionStrategy` (docs/agent.md *Material3 Alignment*) is
unchanged. `material-icons-extended` is still 1.7.8 in both BOMs.

**Kotlin:** none needed. CMP 1.12.0 publishes against `kotlin-stdlib` 2.2.20 and the repo is at
2.4.10 (newer compiler, older klib — the supported direction). The Compose *compiler* comes from
the `org.jetbrains.kotlin.plugin.compose` plugin at `version.ref = kotlin`, so it is not coupled to
the CMP version at all. SKIE's Kotlin pin is untouched.

**Source drift: zero.** No call-site changes, no new deprecations hit, no resolutionStrategy edits.

**Validation** (House Rule #7) — all four gates run on the bump alone:

- `./gradlew assemble` — green. (⚠ It also surfaced a **pre-existing, unrelated** failure:
  `:calendar-shared-logic:compileTestKotlinIos{Arm64,SimulatorArm64}` — four
  `MutableList.replaceAll` call sites in `CalendarManagementViewModelTest`, which binds to
  `java.util.List.replaceAll` on JVM but to an `@ExperimentalNativeApi` `actual` on K/N. Reproduced
  identically with the versions reverted; the module is Compose-free by construction. It is latent
  because neither `run-gate.sh` nor `iosKotlinCheck` compiles iOS **test** source sets. Since fixed.)
- `scripts/run-gate.sh` — all green, 112 test tasks, 1436 fresh test classes.
- `./gradlew iosKotlinCheck` + `scripts/ios-smoke.sh --build-only drive` — both pass (the Swift
  half matters here: CMP is what the iOS shells link).
- `scripts/android-smoke.sh` — PASSED, including the Fit/Contacts canary lockstep (`schema v20 ==
  source`). ⚠ Took three runs for environmental reasons already documented elsewhere: the
  `packageDebug`/`mergeLibDexDebug` AGP flake, then an LMK reap of Fit
  (`exit-info reason=3 LOW_MEMORY`, 135 MB free) that a cold boot cleared. Neither was the bump.

---

## 14. Follow-up bump — CMP 1.12.1 + BOM 2026.09.00 (2026-10-14)

A patch on both halves, taken in the 2026-10 dependency sweep alongside Gradle 9.8.1. The pair still
matches: BOM `2026.09.00` maps `androidx.compose.{ui,runtime}` to **1.12.1**, and CMP **1.12.1**'s
`ui` module requires `androidx.compose.runtime:runtime-retain` **1.12.1**. `material3` (1.4.0) and
`material-icons-extended` (1.7.8) are unchanged in the BOM, so the hand pin and the per-module
`resolutionStrategy` stay as they are.
