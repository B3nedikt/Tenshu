# Locale number formatting — figures in the user's language, in every app

**Status:** Shipped (2026-11-20). Extends Fit's `LocaleNumbers` (`fit-app.md` §7) to Control, Drive,
Home, Maps, Photos and Timeline. Open items in §5.

A German user saw `4,315 Schritte`, `3.2 km` and `2.5 MB`: every app built its figures by hand
(`"$whole.$tenth"`, `round(x * 10) / 10.0`, `String(format: "%.1f")`), which is English everywhere.
The digits now come from the platform's CLDR data and follow the app's language: `4.315`, `3,2 km`,
`2,5 MB` in German, `4 315` / `3,2` in French (narrow no-break space).

---

## 1. Where the formatter lives — `:core-common`, with a Compose facade in `:ui-core`

**Decision.** The formatter is `org.tenshu.core.common.format.LocaleNumbers` in **`:core-common`**:
`integer`, `decimal` (exactly *n* decimals) and `upTo` (at most *n*, trailing zeros dropped), over an
expect/actual on `java.text.NumberFormat` (Android + desktop, the shared `jvmMain`),
`NSNumberFormatter` (iOS) and `Intl.NumberFormat` (JS). `app.tenshu.ui.format.LocaleNumbers` in
`:ui-core` is a thin facade over it whose only job is to default the locale to Compose's
`Locale.current`, plus `bytes(…)` (§2).

**Why not "logic emits raw values, UI formats".** That is the ADR 0016 ideal, and Timeline's
`distanceParts` already does it. But the strings at issue are *built* in Compose-free logic modules,
and several are read by Swift as finished strings: `PhotoDetails` (the info sheet), Home's
`formatByteSize`/`formatDeciC` (called from `home-ios`), Control's `PhotosModelsCopy` (the iOS Photos
models screen), and `formatStorageBytes` itself (Control-iOS, every storage banner). Moving every one
to a raw-value shape would mean a Swift formatter per site, i.e. new copies of exactly what this work
removes. A Compose-free formatter fixes them in place.

**The locale.** Compose code passes `Locale.current` (the facade does it for you). Logic code takes
the default, `LocaleNumbers.defaultLanguageTag()`, which reads **what Compose's `Locale.current` reads
on that target**: `java.util.Locale.getDefault()` on Android and desktop, `NSLocale.preferredLanguages
.first` on iOS, `navigator.language` on JS. So a figure built in a view model and the label that
`stringResource` resolves agree. A function Swift calls keeps a **one-argument overload**, because a
Kotlin default argument does not cross into Objective-C. Add the `languageTag` form beside it and
don't replace the original.

**Rules that carry over from `fit-app.md` §7.** The platform actual takes a decimal **string**, never
a `Double`, so a `Long` past 2^53 keeps its digits. Rounding is half-up (ties away from zero) on every
target, so `72.45` reads `72.5` everywhere; JS uses `roundingMode: "halfExpand"`, the same rule.
Desktop needs the runtime to ship `jdk.localedata` (root `build.gradle.kts`), or `java.text` answers
every locale in English.

⚠ **Tests pin a language.** JVM test tasks force `en_US` (root `build.gradle.kts`), but `commonTest`
also runs on the iOS simulator and JS, whose locale is whatever the host has. A `commonTest` assertion
on formatted output passes an explicit tag (`formatStorageBytes(x, "en")`). A property with no tag
parameter gets its German case from a JVM test that moves `Locale.setDefault`
(`PhotoDetailsLocaleTest`, `FitNumberLocaleTest`).

⚠ `:core-common`'s own JS tests are disabled (`compileTestKotlinJs` is off in its build), so the JS
actual is covered only by compilation plus a by-hand `node` check of the `Intl` options. Nothing runs
it in the gate.

---

## 2. One byte formatter, and it is binary

**Decision.** `formatStorageBytes` (`:storage-service-common`) is the one byte formatter. It uses
binary steps with the conventional `KB/MB/GB` names, at most one decimal (dropped when it is `.0`),
half-up, and **locale digits**. It already served the storage card, every realm-storage banner and
Drive's file sizes. It now also serves Control's offline-maps and models screens, the Maps and
Timeline region-size hints, and Fit's import rows. Compose code reaches it as `LocaleNumbers.bytes(n)`.

**Why binary.** It is the only one of the copies that had a hard constraint behind it. The plans are
binary (Free = 5 GiB), and in decimal steps the plan the user bought as "5 GB" reads back as "5.37 GB".
Its own rule, one base per line ("8.3 MB of 5 GB used"), forbids mixing. The copies it replaced
disagreed with each other: Control, Maps and Timeline used binary with whole MB, and Fit used decimal,
truncated. A size that reads differently in two Tenshu apps looks like two files.

**Two deliberate decimal exceptions**, both left decimal with their digits localised:

| Site | Why decimal |
|------|-------------|
| `photosModelBytes` (`control-shared-logic`, the iOS Photos models screen) | The line sets the model's size beside the **device's free space**, which must read as the OS's own storage screen reads it, and iOS counts in 1000s. One base per line, so the whole line is decimal. |
| Home `formatByteSize` (the hub's disk) | A piece of hardware, read as its label reads. It is never set beside a plan figure. |

`PhotoDetails.size` (a photo's file size on the info sheet) is also still decimal, for the
storage-screen reason. It is listed in §5 because the same file reads binary in Drive.

---

## 3. Sites

| App | Site | Now |
|-----|------|-----|
| Control | `OfflineMapsScreen` / `ModelsScreen` `formatBytes` | deleted → `LocaleNumbers.bytes` |
| Control | `PhotosModelsCopy.photosModelBytes` | locale digits, stays decimal (§2) |
| Control-iOS | `ServicesModel.formatBytes` | unchanged code; already calls `formatStorageBytes`, so it is localised now |
| Drive | `FileMapper.formatUpToOneDecimal` | **dead code**, deleted (file sizes already went through `formatStorageBytes`) |
| Fit | `formatBytes` (Compose) + its Swift twin | → `LocaleNumbers.bytes`; Swift mirrors `formatStorageBytes` (Fit's framework does not export `:storage-service-common`) |
| Fit-iOS | weight editor pre-fill `String(format: "%.1f")` | locale decimal mark, no grouping (`save()` already reads `,` or `.`) |
| Home | `formatByteSize`, `formatDeciC` | locale digits; `formatDeciC(-5)` now reads `-0.5°`, where integer division used to drop the sign (`0.5°`) |
| Maps, Timeline | `formatRegionSize` (duplicate) | deleted → `LocaleNumbers.bytes` |
| Photos | `PhotoDetails` MP / size / shutter / aperture / focal length / EV | one `tenthsText` helper over `LocaleNumbers`: `12,2 MP`, `f/1,8`, `+0,3 EV` |
| Photos-iOS | editor dials `String(format: "%.1f°")` | `.formatted(.number…)`, half away from zero |
| Timeline-iOS | `MomentDetailView.distanceText` | `.formatted(.number…)`: `1,2 km` |
| every app | realm-storage banners | unchanged code; they call `formatStorageBytes`, so they are localised now |

Left alone on purpose: `FeedGridView`'s `[photos-probe]` timing print (a log line, not UI), Swift test
reports, `%d:%02d` pace and clock strings (integers, no separator), and the commerce service's
fake-Stripe page.

---

## 4. Timeline's movement title is not a stored-data problem

`movementTitle` (`timeline-projection`) and `GoogleTimelineParser.movementTitle` still build
`"Walk · 2.4 km"` into `Moment.title`. **No reshape is needed and none was done.** Compose never
shows that string: `momentTitleText` derives a movement's headline from its mode and route, and the
distance goes through `distanceText` → `TimelineFormat.distanceParts`, which emits whole and tenths
as separate integers for a per-language template. So the stored title is a neutral, English, ingest
fallback. Rewriting it would change no displayed text on Compose, and it would churn every stored
row, its sync traffic, and the byte-identity rule between capture and projection that its KDoc pins.

⚠ **iOS reads it raw.** `TimelineHomeView`, `TripDetailView` and `MomentDetailView` render
`moment.title`, so an iOS user sees the English `"Walk · 2.4 km"`. The fix is display-side: port
`momentTitleText`'s derivation to Swift, or expose it from the shared logic. It is §5's first item.

---

## 5. Open

1. **Timeline-iOS movement headline**: derive it as Compose does (§4), instead of printing the stored
   fallback.
2. **`PhotoDetails.size` vs Drive.** The same photo reads decimal on Photos' info sheet and binary in
   Drive. Decide whether the storage-screen argument still beats cross-app consistency. If it doesn't,
   delegate to `formatStorageBytes`.
3. **Control's Android/desktop models screen** says "Needs X free. This device has Y" in binary,
   while the iOS Photos copy of the same sentence is decimal (§2). Android's own storage screen has
   counted in 1000s since Android 8, so the decimal argument applies there too.
4. **Timeline-iOS `distanceText` ignores the realm's measurement system**: kilometres always, where
   Compose honours imperial (`LocalMeasurementSystem`). This predates the work here.
