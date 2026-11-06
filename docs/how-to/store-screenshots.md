# Store screenshots — Control and Drive on iOS

The App Store needs screenshots at two sizes for every app that declares iPad support: the
**6.9" iPhone** and the **13" iPad**. This is the pipeline that makes them, phase S3 of
[`store-presence-and-support-pages.md`](../proposals/store-presence-and-support-pages.md) (§3.1).
One run feeds both the store listing and, later, the support pages.

```bash
scripts/store-screenshots.sh                    # control + drive, both devices, English
scripts/store-screenshots.sh drive --device ipad13
scripts/store-screenshots.sh --skip-build       # reuse the last build (after a test-only edit)
scripts/store-screenshots.sh --keep-sims        # leave the simulators booted to look around
scripts/store-screenshots.sh --offline          # local-only realm, for when the dev lab is down
scripts/store-screenshots.sh --device iphone69 --appearance dark   # the dark set (§1b)
```

⚠⚠ By default the run creates a **real, funded realm on the dev lab** (`tenshu.xyz`, §1a) —
never production. It needs the dev lab up and the `dev-mailpit-token` Keychain item
([`deploy-dev-lab-scaleway.md`](deploy-dev-lab-scaleway.md) §10); the script checks both first
and tells you to pass `--offline` if either is missing.

Output, gitignored — ⚠ **never committed**; publish it to R2 with `scripts/store-publish.sh` (§7):

```
store/<app>/screenshots/ios/<device>/<lang>/NN-<screen>.png        light (the default)
store/<app>/screenshots/ios/<device>/<lang>/dark/NN-<screen>.png   --appearance dark|both (§1b)
                             iphone69 → 1320 × 2868   (iPhone 17 Pro Max simulator)
                             ipad13   → 2064 × 2752   (iPad Pro 13-inch (M5) simulator)
```

The script fails if a capture is not one of the sizes App Store Connect accepts for its slot.
A full run takes 10–15 minutes on an M-series Mac; the build is about half of it.

| App | Screens |
|---|---|
| Control | `01-welcome`, `02-protected`, `03-recovery-phrase`, `04-home`, `05-devices`, `06-settings`, `07-storage`¹, `08-sign-in`, `09-add-device`¹ |
| Drive | `01-files`, `02-photos`, `03-preview`, `04-file-actions`, `05-add`, `06-share-link`¹, `07-empty` |
| Fit | `01-home`, `02-history`, `03-workout`, `05-browse`, `06-sleep`, `07-profile` (light only so far; §3a; the iPad's come from the iPhone's realm, §3e) |
| Calendar | `01-month`, `02-agenda`, `03-event`, `04-new-event`, `05-calendars`, `06-edit-calendar` (light only so far; §3b) |
| Contacts | `01-contacts`, `02-contact`, `03-search`, `04-duplicates`, `05-merge`, `06-you` (light only so far; §3c) |
| Photos | `01-library`, `02-collections`, `03-album`, `04-viewer`, `05-months` (iPhone and iPad, light; dev lab only, never with Control or Drive; §3d) |
| Timeline | `01-today`, `02-suggestion`, `03-trip`, `04-walk`, `05-places`, `06-place` (iPhone and iPad, light, en + de; after 14:00; §3f) |
| Mia | `01-welcome`, `02-files`, `03-photos`, `04-calendar`, `05-workouts` (iPhone only), `06-history` (light, en + de; dev lab only, on its own; §3g) |

¹ Dev-lab runs only: they need a funded realm (or, for `09-add-device`, a pairing session on the
relay), so `--offline` walks or skips them and captures nothing.

⚠ The list lives in **`store/<app>/screens.json`** (`captured`, with what each screen shows), next to
the names reserved for screens no pipeline captures yet (`planned`, e.g. Android's Services
screen). The support pages may name only those (§5, §7), and the script warns at the end of a run
when it captured a screen the file does not list, or missed one it lists.

## 1. What it does

1. Regenerates both Xcode projects and builds the
   `ControlIos-Screenshots` and `DriveIos-Screenshots` schemes once (`build-for-testing`).
2. Builds the Drive fixture into `build/store/fixture/drive` — fetching (sha256-verified) or drawing every file (§3).
3. Per device: **erases** the dedicated simulator `tenshu-shots-<device>` (created on first use,
   on the newest installed iOS runtime), renames it `iPhone` / `iPad` for the run (the realm
   records the device name, and Control's devices screen shows it), boots it, pins the status bar
   (9:41, full battery, full signal) and enrolls Face ID. The iPad is booted in **Full Screen
   Apps** mode (below). Simulators other sessions use are never
   touched; the name is restored when the script exits.
4. Runs, on that one simulator, in order:
   - `ControlOnboardingScreenshots` — a fresh device: *Welcome*, *Protected by your device*, the
     recovery phrase, and the *Turn on sync* sign-in screen before anything is typed. Tapping
     through it **is** the realm's genesis. On a dev-lab run it then signs a fresh account up
     (§1a) on that screen before finishing; `--offline` goes back without one.
   - `DriveIosScreenshots` — the empty listing a user meets right after setup, then seeds the
     realm (§2), then the listing, a photo folder as icons, a
     photo preview, a file's actions, the Share Link sheet after *Generate Link* (dev lab only),
     and the "+" menu. On a dev-lab run the listing is captured only once the *Not syncing*
     banner has gone.
   - `ControlMainScreenshots` — the same realm afterwards: Home (Drive's uploads as recent
     events), the devices screen, Settings, Settings → Storage (dev lab only: the Free plan
     and the relay-reported usage), and Settings → *Add a device* on *Show a code* (dev lab only:
     the QR is a real host pairing session, stopped right after the capture; not *Scan a code*,
     whose simulator camera fallback a phone never shows). Control's app lock is on by default, so this relaunch
     meets a Face ID prompt; the script answers it with a simulated match while the test runs.
5. Exports the attachments (`xcrun xcresulttool export attachments`) to the store layout. The
   attachment name is the file name.

A Drive-only run still runs Control's onboarding (Drive needs the realm) and does not export it.

## 1a. The dev-lab realm

Without an account a realm created on iOS is held local-only (`StorageSyncGate`), and Drive says
so in a banner on every screen. So the run gives each simulator a real account on the **dev lab**
— the shared test plane at `tenshu.xyz` ([`ops-dev-lab.md`](../proposals/ops-dev-lab.md) §10.10)
— and never on production.

**The four client settings, and the seam each one goes through.** All are Debug-only; the
defaults are production's.

| Setting | Value | Seam on iOS | Read |
|---|---|---|---|
| relay | `https://relay.tenshu.xyz` | `TENSHU_DEV_RELAY` — `DevRelayOverride.kt`; Control's runtime (`ControlSurface.installRealRuntime`) and every route-1 runtime (`relayBaseUrl = devRelayOverride()`, Drive's included) | runtime, launch env |
| commerce | `https://commerce.tenshu.xyz` | `TENSHU_COMMERCE_URL` — `iosCommerceUrl()` (`ProxyOriginSelection`) | runtime, launch env |
| billing mock | `false` | `TENSHU_BILLING_MOCK` — `iosBillingMock()` | runtime, launch env |
| extra issuer | `tenshu.commerce.dev:<pubkey>` | `TENSHU_EXTRA_ISSUER` — compiled into `BuildConfiguredIssuer` (identity-service), put in force by `trustBuildConfiguredIssuer(isRelease = !Platform.isDebugBinary)` | build time, the debug XCFramework |

The script passes the first three as `TEST_RUNNER_TENSHU_*`; each UI test forwards every
`TENSHU_*` variable of its runner to the app's `launchEnvironment` (Control's and both of
Drive's launches). The issuer is a public key, defaulted in the script; it goes into **every**
debug-framework build the script makes, `--offline` included — it is inert without dev-lab records,
and flipping it would recompile everything above identity-service. ⚠⚠ The script **exports** it
rather than prefixing the gradle line: each app's Xcode prebuild phase runs the same gradle task
again (`scripts/ios-build-sdk.sh`), and without the variable that second run rebuilds the
framework without the issuer — the Free record then fails as "unknown publisher" and the realm
never syncs, with nothing on screen to say why. All four can be overridden
from the environment (`TENSHU_SHOTS_RELAY`, `…_COMMERCE`, `…_MAIL`, `…_ISSUER`) if the plane is
rebuilt with a new seed. Check it took: Control's console prints
`Entitlement trust: publishers=[tenshu.dev, tenshu.commerce, tenshu.commerce.dev] (build-configured: tenshu.commerce.dev)`.

⚠⚠ **Why a Release build cannot read them.** Every one is gated on Kotlin/Native's
`Platform.isDebugBinary`, which is `false` in the release framework the archive links
(`scripts/ios-archive.sh` → `TENSHU_SDK_VARIANT=release`): `devRelayOverride()` returns `null`
before it reads the environment, `ProxyOriginSelection.resolve` drops the override when
`isRelease`, `EntitlementMinterSelection.useDevSeed` ignores the mock flag in a release build
(and `false` is the release behaviour anyway), and `issuerInForce` returns `null` for a release
binary whatever was compiled in (pinned by `EntitlementSignatureKeyTest`; the origin rule by
`ProxyOriginSelectionTest`). The archive's gradle step is a separate invocation that is not given
`TENSHU_EXTRA_ISSUER`, so its framework compiles no extra issuer in the first place. And a shipped
app has no launch environment to read.

**The account.** Per device and run the script makes a fresh address,
`alex.<base-36 timestamp>@example.com`. Dev commerce accepts any address with an `@`
(`normaliseEmail`), and the dev lab's mail is a sink that never delivers, so the reserved
`example.com` costs nothing and keeps the dev domain off the Storage screen, which shows the
address. Onboarding's *Turn on sync* opens the email-OTP screen; the test types the address and
taps *Email me a code*. Meanwhile the script polls `https://mail.tenshu.xyz/api/v1/search` for
that recipient — the bearer token goes from the Keychain straight into `curl -K -` on stdin, so it
is never an argument, an environment variable or a line of output — and writes the six digits
alone to `build/store-shots/<device>-<lang>/otp` (mode 600, deleted after the test). The test
reads the file, enters the code, and onboarding finishes on a Free plan (5 GB): the realm syncs,
and Drive's seeded files upload to the dev relay for real.

⚠ Creating an account through the public sign-up is the only write this pipeline makes to the dev
lab, which other sessions share. Each run leaves two accounts and two small realms behind; the
plane's own resets clear them. Commerce budgets OTP mail at 20 sends per IP per hour — about ten
runs.

**`--offline`** skips all of it: no `TENSHU_*` variables, no account, the local-only realm of
before, with the *Not syncing* banner on Drive and without `07-storage` / `06-share-link`.

## 1b. Dark mode captures

The support pages show dark screenshots in dark mode — the OS's, or the one picked in the page's
Light / Dark / Auto switch — so the pipeline can capture each screen in both system appearances:

```bash
scripts/store-screenshots.sh --appearance light   # the default; what every run did before
scripts/store-screenshots.sh --appearance dark    # dark only, into …/<lang>/dark/
scripts/store-screenshots.sh --appearance both    # light, then dark, per device
```

- The script sets the appearance with `xcrun simctl ui <udid> appearance dark|light` right after
  the boot, before any app launches. The apps follow the system appearance; nothing in them changes.
- `both` runs each device **twice**, each a fresh erase and a fresh onboarding (the onboarding is the
  realm's genesis, so a second pass cannot reuse the first realm). On the dev lab that is a second
  throwaway account per device. It roughly doubles the run.
- The dark folder becomes the manifest keys `<platform>/<device>/<lang>/dark/NN-screen` (§7). The light
  keys keep the form they always had — no appearance segment — so every light capture published
  before dark existed stays valid with no republish.
- A screen with no dark capture shows its light one in both themes on the support pages; there is
  never a placeholder or a broken image for a missing dark capture. So dark can be published one
  app, one device or even one folder at a time.
- `store/<app>/screens.json` names the appearances the pipeline covers (`appearances`); the same
  `captured` list applies to both, and the end-of-run warning checks each appearance's run.
- ⚠ The store listings take one set: App Store Connect gets the light captures, as before. Dark is
  for the support pages only.

## 2. Why the screens are genuine

⚠⚠ A screen produced by a path the release app cannot reach is a misleading listing (§3.1 of
the design). The rules this pipeline holds to, and which any new screen must keep:

- **The shipping screens.** Both schemes test the `Debug` configuration. Drive's
  `DebugHarness` (App / Pair tabs) is never built here. Control's DEBUG deep links
  (`-settingsHome`, `-shares`, `-notificationCenter`, `-pairingStep`, …) render over fakes and are
  never used.
- **A real realm.** Control's genesis runs through the onboarding by taps, over the real
  in-process runtime. Drive then runs its route-1 runtime over that realm, exactly as on a phone.
- **Data through the user's path.** The one dev hook is Drive's `-seedFixture <dir>`
  (DEBUG-only, `drive-ios/Sources/StoreFixtureSeed.swift`): a separate launch that walks the
  fixture tree and calls the same `createFolder` / `uploadFile` ops the "+" menu calls, so dates,
  sizes and thumbnails come from the upload itself. It renders a single line of text and is
  never captured; the test terminates it and every capture comes from the next launch, which has
  no arguments.
- **Every screen by taps.** Nothing is deep-linked. The app lock is passed with Face ID, as a
  user passes it, not switched off.

### What the screens honestly show — and what we left out

- The realm is funded by a real Free-plan account on the dev lab (§1a), so the screens show a
  syncing realm with no banner. ⚠ An `--offline` run shows Drive's *"Not syncing — this device
  has no storage plan"* banner again, because that is true of a local-only realm.
- The share link on `06-share-link` names the production viewer host (`tenshu.app`, the built-in
  default — `TENSHU_SHARE_HOST` is not set), but the share itself lives on the dev relay, so the
  link opens nothing. It is a picture of the screen, not a working link.
- Left out because it would advertise an empty screen: Control's **Apps** tab (it lists nothing
  on iOS yet). It is walked by the tests, only not captured.
- **The iPad runs in "Full Screen Apps" mode.** A fresh iPadOS 26+ simulator boots with
  Settings → Multitasking & Gestures on *Windowed Apps*: every app is a resizable window and
  SpringBoard draws the window-resize grabber at the bottom-right corner — system chrome, in every
  capture (Settings.app shows it too). An iPad in *Full Screen Apps* never draws it, and a store
  screenshot must not show it. Before booting the erased iPad simulator the script writes
  `SBMedusaMultitaskingEnabled = NO` into its `com.apple.springboard` preferences — one of the
  SpringBoard keys that Settings pane manages — and SpringBoard reads it at boot: apps then run
  full screen, with no grabber. This is the capture environment only: the apps' iPad windowing
  support is unchanged, so a user who prefers windows still gets them.
  ⚠ The key is private SpringBoard state, found in the strings of the runtime's
  `MultitaskingAndGesturesSettings.bundle` and verified by its effect on the iOS 27.0 runtime
  (grabber present without it, gone with it; `SBChamoisWindowingEnabled`, Stage Manager's key,
  made no difference). Not checked: which choice the Settings pane then shows selected. If a
  future runtime brings the grabber back, the key moved — check that bundle's strings again; do
  not crop or paint the corner out (the image must be what the device shows, at the exact size).
- The recovery phrase on `03-recovery-phrase` is a real phrase — of a throwaway simulator realm
  that is erased on the next run.

What still differs from an App Store build: the `Debug` configuration links the debug Kotlin
framework and is unoptimised. The screens are the same SwiftUI; capturing from the archive
spec's release framework (`project.archive.yml`) is a possible later step.

## 3. The fixture

The listing is content, not test data — it is what every future customer sees first.
`store/fixtures/drive/manifest.json` describes a plausible personal Drive: *Holiday 2026* with
photos and an itinerary, *Taxes* (with *Payslips*), *Recipes*, *Home*, and a few loose files. Each
entry carries its path, kind, `modified` time (which becomes the file's date in the app) and its
provenance: `source`, `license`, `author`, `url` (the page it came from).

⚠⚠ **No fixture file is in git** — not a photo, not a PDF ([design §3.3](../proposals/store-presence-and-support-pages.md)).
The manifest says how to *get* each one, in exactly one of two ways:

```jsonc
// drawn at run time by store/fixtures/generate.py; license must be "generated-placeholder"
{"path": "Garden.jpg", "kind": "photo", "label": "Garden", …, "generate": "placeholder"}
// downloaded at run time, refused unless its bytes hash to the pin
{"path": "Holiday 2026/IMG_4102.jpg", "kind": "photo", …, "license": "Unsplash",
 "author": "…", "url": "https://unsplash.com/photos/…",
 "fetch": {"url": "s3://tenshu-store-fixtures/fixtures/<sha256>.jpg", "sha256": "<sha256>"}}
```

`store/fixtures/generate.py <app> <out>` builds the tree; the screenshot script runs it before any
simulator is touched. A `fetch` entry is downloaded once into `build/store/fixture-cache/<sha256>`
(gitignored, reused by later runs, so a run with a warm cache needs no network) and **fails the run**
if the bytes do not match the pinned `sha256` — a changed file behind a URL is a different photo,
whose licence nobody has checked. `https://` URLs are fetched directly; `s3://` ones through the aws
CLI with the R2 key pair (`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`, else the Keychain's
`r2-assets-*`) and `FIXTURES_ENDPOINT` (the R2 S3 endpoint). `generate.py <app> --dry-run` validates
the manifest and says what would be fetched or drawn, without network or writes.

Today every entry is a **placeholder**: photos are labelled gradients marked *PLACEHOLDER IMAGE*,
PDFs a one-page document that says it is a placeholder, text files short texts written for this.

### Replacing a placeholder with a real photo

1. Choose a photo we may use **commercially** in a store listing (our own, CC0, or a licence such as
   Unsplash's or Pexels'). Record where it came from.
2. Upload it to the **private** fixtures bucket — `scripts/store-publish.sh --fixture <file>` puts it
   at `fixtures/<sha256>.<ext>` and prints the `fetch` object to paste.
   ⚠⚠ **Private, not the public `tenshu-assets` bucket.** Unsplash's and Pexels' terms allow
   commercial use of a photo but not redistributing the photos as a collection, and a public URL per
   photo listed in this public repo's manifest is exactly such a collection. R2 makes a whole bucket
   public through its custom domain, so "private" means the separate bucket with no domain
   (`deploy/cloudflare-assets`, `enable_fixtures_bucket = true`). A photo whose licence *does* allow
   redistribution (CC0, our own) may instead use its original public `https://` URL.
3. In its manifest entry: replace `"generate": "placeholder"` with that `fetch`, and set `source`,
   `license`, `author` and `url` to the real provenance (`"source": "own"` for a photo we took).
4. Run `python3 store/fixtures/generate.py drive --dry-run`, then the screenshot script.

The generator refuses the half-done states: a `fetch` whose licence still says
`generated-placeholder`, a `generate` with a real licence, and an entry with both or neither. ⚠ A
licence that requires attribution needs that attribution somewhere the listing points to. Keep real
photos at phone-photo sizes (a few MB); the listing shows file sizes.

To add a file, add an entry. A new `kind` needs a placeholder drawer in `generate.py` (there is
none for video yet).

### Nothing binary under `store/`

`scripts/check-store-assets.py` fails when the git index holds anything under `store/` other than
`.json`, `.py`, `.md` or `.txt` text (no NUL byte, ≤ 256 KB), or anything under
`store/<app>/screenshots/`. It checks the **index**, so a `git add -f` of a gitignored capture is
caught before the commit. `.githooks/pre-commit` runs it whenever a commit touches `store/`, and both
store scripts run it at the end. No Git LFS: everything binary is either generated or fetched.

## 3a. Fit's fixture — generated, and imported the way a user's is

`scripts/store-screenshots.sh fit` (dev lab). ⚠ iPhone, light only: the script skips Fit in dark,
which has never been checked, and on the iPad, where Apple Health's consent does not complete on
the simulator (§3e) — and refuses a run that leaves Fit nothing to capture. Fit's listing is a person's health data, so
nothing is downloaded: `store/fixtures/fit/generate_fit.py` writes it for the **day of the run**
from `store/fixtures/fit/manifest.json` — six weeks of one invented person's runs, rides and walks
in Florence, with the daily figures around them. Nothing it writes is committed (§ *Nothing binary*).

- **Workouts** are GPX files (route, elevation, heart rate), imported through
  `FitSurface.importPickedFile` — the call the Takeout screen's file picker makes — so the map,
  the stats and the heart-rate chart come out of the shipping parser.
- **Daily figures** (steps, distance, active energy, sleep stages, resting heart rate, weight,
  height, nutrition) go into the simulator's **Apple Health**. They reach the realm only on the
  capture launch, by taps: *Profile → Import from Apple Health →* the system consent sheet
  (*Select All N Topics*, *Continue*, then *All Recorded Data and Future Data* and *Allow*). ⚠ Not the first-run onboarding: the seed's file import
  already counts as a start, so Fit opens on Home — the path of a user who began with a file. (The
  test takes the onboarding if it ever shows.) ⚠ The sheet's wording moves between iOS releases
  (*Turn On All* up to iOS 26), and on iOS 27 the closing control is static text, not a button —
  *Allow* on the seed's write sheet, *Continue* on the read sheet; the test matches them by label.
  ⚠⚠ A read request on iOS 27 has a **second page** (*How much data would you like to share?*)
  whose *Allow* stays disabled until a history choice is made. Missing it leaves the request
  unanswered with no error: Fit's row stays *Not imported yet* and the page swallows the next tap.

Both halves are the seed launch (`-seedFixture <dir>`, `fit-ios/Sources/StoreFixtureSeed.swift`,
DEBUG-only), which renders one line of text and is never captured — the Drive rule (§2).
⚠ Writing to Apple Health asks for **write** consent on that launch, so the samples are Fit's own
in Apple Health. The importer skips only Fit's own *workouts* (R-iOS-6, so a recorded workout is
not imported twice), and the fixture's workouts never go to Apple Health — they are files.

⚠⚠ **The routes must stay inside the bundled basemap.** An iOS install draws only
`tenshu_basemap.pmtiles` (central Florence, z12–14) until a region is downloaded, so a route
anywhere else sits on an empty map. The generator routes every workout along **that archive's own
streets** (`store/fixtures/fit/streets.py` reads its `roads` layer): a route through named places
in `manifest.json`, shortest path between them, cyclists kept off footways and steps.
⚠ The tiles' lines are simplified, so crossings are rarely shared vertices; `streets.py` intersects
the segments itself (same level only — a bridge never meets the road under it). Without that, a
600 m walk routed 3.5 km round. Check a change by eye: `generate_fit.py <dir> --preview <png>` draws
every route over the network.

⚠ The fixture is dated relative to *now*, and today's run lasts 07:12–07:41: a capture before it
has ended fails at generation rather than show a run that has not finished yet. Each night ends at
least 20 minutes before a morning workout, and each day's figures, vitals and night draw from their
own seeded generators, so the hour of the run changes only what is cut off at *now*. What Fit cannot say on iOS
and is therefore absent: active minutes (Apple's exercise time is read-only to apps), and calories
for the file workouts (GPX carries none; Fit estimates them).

## 3b. Calendar's fixture — generated, and imported as `.ics` files

`scripts/store-screenshots.sh calendar` (dev lab). ⚠ Light only, on both devices, like Fit: the
script skips Calendar in dark (never checked), and refuses a run that leaves it nothing to capture
(iPad: §3e). Calendar runs after Control's main captures, over
the realm Control's onboarding made.

`store/fixtures/calendar/generate_calendar.py` writes the fixture for the **day of the run** from
`store/fixtures/calendar/manifest.json`: one invented person's five calendars (Personal, Work,
Family, Fitness, Travel) as one RFC 5545 `.ics` file each, plus `calendars.json` naming them and
their colours. Nothing it writes is committed. Every date is relative to today: `day` counts
calendar days, `workday` counts weekdays (so a weekend run never puts a design critique on a
Saturday), `weekday` + `week` names a day of a week (weeks start on Sunday, as the run's US region
does). Repeats start five weeks back, so the month grid has dots on both sides of today. Times are
**floating** (no `TZID`) — the importer stores civil times, so the simulator's time zone, which is
the Mac's, never shifts an event. `--today YYYY-MM-DD` generates another day's fixture to look at.

The seed launch (`-seedFixture <dir>`, `calendar-ios/Sources/StoreFixtureSeed.swift`, DEBUG-only)
renders one line of text and is never captured — the Drive rule (§2). It goes through the calls the
shipping sheets make:

- **Personal is the app's own.** Calendar seeds its first calendar itself on first launch
  (`ensureDefaultCalendar`, called from `CalendarLocalRuntime.install()`), and the fixture only
  fills it. ⚠ The seed awaits that call *before* it creates anything: a calendar created first makes
  `ensureDefaultCalendar` leave the realm with no default at all ("a calendar exists but no default
  is set — deliberately left alone").
- **The other four** are made with `CalendarRepository.saveCalendar` — what the *New calendar*
  sheet saves through.
- **Every event** goes in through `CalendarRepository.importIcs`, the call a calendar row's
  *Import or export…* sheet makes with the picked file's text. So recurrence, reminders (`VALARM`)
  and the stored source rows come out of the shipping parser. A file that imports zero events, or
  skips one, fails the seed by name.

The capture launch has no arguments and walks by taps: the month (today selected), the agenda, the
dentist's detail, a new event typed in through *+* and saved, the Calendars tab after *Turn on
reminders* → the system alert's *Allow*, and Work's *Edit* sheet. ⚠ The notification alert is
SpringBoard's, not the app's, so the test queries `com.apple.springboard` for *Allow*; once allowed
the reminders row disappears from the Calendars tab, which is why it is answered before
`05-calendars` rather than captured.

⚠ Three product bugs showed up only on these screens, and were fixed in the app rather than worked
around in the test — worth knowing, because a fixture that sidesteps them hides them again:
the first calendar was seeded with alpha 0 (`FIRST_CALENDAR_COLOR` was `0x4285F4`). ⚠ Compose
forces every calendar colour opaque, so only **iOS** — which honoured the byte — drew Personal with
no tick on Calendars and no dots on the month; iOS now ignores the alpha byte too
(`CalendarDayDot.alpha`, `CalendarPalette.sameColour`), which is what heals the realms already
seeded that way; iOS never called `ensureDefaultCalendar` at all (a
fresh realm had no calendar, and the editor's *Save* stayed disabled); and the editor preselected
whichever calendar sorts first (*Family*) rather than the one Calendars labels *Default for new
events*. ⚠ That is why the seed keeps Personal as the **app's** calendar instead of creating it:
a seed that made all five itself would have shown a blue Personal and never caught the first two.

What the screens honestly show — and what is absent: no week or day view (Calendar on iOS has month,
agenda and calendars tabs; a native time grid is a slice of its own, `calendar-ios/README.md`), and
no subscribed calendar (subscribing fetches a third party's feed on a schedule, which a fixture
should not do). A rule the editor cannot offer as a choice — the Mon/Wed/Fri stand-up — shows its
raw `RRULE` on the detail screen by design (a weekly one says *Weekly*); the detail capture is a
one-off, so it shows neither.

## 3c. Contacts' fixture — an invented address book, imported as vCards

`scripts/store-screenshots.sh contacts` (dev lab). ⚠ Light only, on both devices, like Fit
(`contacts_runs_on`): no dark set has been checked (iPad: §3e). The listing is
an address book, so it is **invented, never fetched**: `store/fixtures/contacts/manifest.json` lists
28 fictional people (several cultures, invented companies, a few notes, birthdays and categories) and
`generate_contacts.py` renders them as one vCard 3.0 file, in the shape Apple's and Google's exports
have (`TEL;TYPE=CELL;TYPE=VOICE`, `EMAIL;TYPE=INTERNET;TYPE=WORK`).

- **The seed** (`-seedFixture <dir>`, `contacts-ios/Sources/StoreFixtureSeed.swift`, DEBUG-only)
  hands the file to the shared repository's `importVcards` — the op *Import contacts* calls on
  Android and desktop. ⚠ The iOS app has **no** import or add screen (its contacts arrive through
  the realm from the user's other devices, and the listing says so), and it does not read the
  system address book (`claims.md`), so neither `CNContactStore` nor a picker is a user's path here
  and no permission sheet ever appears. The rows the seed writes are the ones an import on another
  device syncs in, raw vCard included. Renders one line, never captured — the Drive rule (§2).
- ⚠⚠ **The generator refuses a real-looking person.** Every phone number must sit in a range
  reserved for fiction (US `555-0100…0199`, UK `07700 900xxx`, Germany `030 23125xxx`, France
  `01 99 00xx xx`, Australia `0491 570 156…159`) and every email under `example.com/.org/.net`
  (subdomains included). Add a person only inside those.
- ⚠ **The duplicate count is part of the picture.** The app pairs contacts that share a phone (on its
  last nine digits), an email (case-insensitively) or a name. The fixture has exactly two deliberate
  pairs — *Priya Raman* / *Priya R.* (the same mobile, written `+1 646 555 0113` and
  `+1 (646) 555-0113`) and *Tomás* / *Tom Oliveira* (the same email, different case) — and the
  generator fails if a new entry adds or removes one.
- **No avatars.** A photo would be binary under `store/`; the app draws monograms itself.
- **What it cannot show:** a *verified* contact, a *key changed* warning, or an accepted invitation.
  Each needs a second realm and a contact handshake (Control on iOS hosts it); every fixture
  contact is import-only, so the list carries no badges and each contact's *Keys* section says it
  has no Tenshu key. Nor the identity code on *You* — the app says it is "not available on this
  device yet", and `06-you` shows that sentence because it is true.
- `06-you` types the name (*Alex Rivera*) through *Edit*, as a user does; the name is the platform's
  (`OwnerPreferences.Name`), shared with the other Tenshu apps on that realm.
- `03-search` types *Lumen* (an organisation three people share) and closes the keyboard with its own
  Search key, so the capture shows results rather than half a keyboard. ⚠ On iOS 26+ a search with no
  keyboard has **no Cancel** — only the field's clear (x) and a round *Close* button — so the walk
  clears the field and taps *Close*.
- ⚠ `05-merge`'s *Which one should be kept?* is a `confirmationDialog`, which iOS 26+ draws as a
  **popover from the tapped row with no Cancel button**; the walk dismisses it by tapping outside
  (`PopoverDismissRegion`, else a blank spot), as a user does. It never merges.
- ⚠ The walk taps each row **at its centre**, as XCUITest does — which is where a contact row is
  empty space. Under `.buttonStyle(.plain)` that took no tap until the rows got a full-width
  `contentShape`; if a run stops at *the contact's detail* with the list still on screen, that is back.
- ⚠ After a failed test, `xcodebuild test-without-building` can hang in tear-down (as §9 records);
  the run sits still until that xcodebuild is killed, and the result bundle has no `Info.plist`
  before then. The failure itself is already in `contacts.xcresult.log`, with the screen's element
  tree in the message.

Defects this walk found and fixed (2026-10-22): the iOS search filtered nothing (the list rendered
`contacts`, not the view model's `searchResults`), and its field read its text back from the view
model a turn late; a tap on a row's empty middle opened nothing; the detail left addresses out; *1 possible duplicates*; and every imported email was labelled *internet*
(`primaryLabel` took vCard's `INTERNET` kind type for a label — `contact-service-common`).

## 3d. Photos' support set — the landing photos, six to a day, seeded through Drive

`scripts/store-screenshots.sh photos` (dev lab; iPhone and iPad, light). Photos has no data of its
own: it shows every image in the realm, and on iOS only Drive puts images there (Photos backs
nothing up, PIO-D5). So the library comes in through **Drive's** shipping path, as in §9: Drive's
`-seedFixture` launch, in `DriveLandingScreenshots/testSeedLandingRealm` — the landing walk's own
seed, waiting until the uploads are done (the capsule gone), and capturing nothing. Then
`PhotosScreenshots` walks the realm by taps.

- **The fixture, `store/fixtures/photos/`, is the landing set re-dated** — `{"from": "landing",
  "modified": {path: date}}` (`generate.py` `redated`): the same 48 photos, pins and licences, which
  stay in the landing manifest (⚠ re-pin there, never here), six to a day instead of three. ⚠ Why six:
  the 13" iPad's library is six columns (`gridWidthScale`), where the mosaic's feature takes six
  photos and a day of three fills **half a row**; on the phone a day of six is two full blocks. The
  library order is the landing set's, so the walk's newest-first photo lists hold for both. The
  generator refuses a date for a path the landing set lacks, and a landing photo with no date here.
  ⚠ A day holds one folder only, so three days are partial (Kitchen and Dolomites 6 + 5, Coast trip
  6 + 2), all below the library's first screen.
- ⚠ Light only: the script refuses `--appearance dark` (no dark set checked). `--lang de` runs (§4).
- ⚠ **It never shares a run with `control` or `drive`**, and the script refuses one: their captures
  show the synthetic Drive fixture (§3), and Photos would show its labelled gradients. It does run
  with `fit`, `calendar` and `contacts`, whose fixtures hold no images.
- ⚠ `DriveLandingScreenshots` holds two tests, so the script names the **method**
  (`…/testLandingScreenshots`, `…/testSeedLandingRealm`); naming the class would run both.
- **What the walk makes, as a user would**: three albums through Collections → *New album* and a
  library selection → *Add to Album* — *Coast trip* and *Dolomites* (a folder each) and *Outdoors*
  (all three folders, 31 photos) — and one favourite through the viewer's heart. ⚠ `03-album` opens
  *Outdoors* because it must fill the iPad's six-column grid: a 12-photo album was two rows on a
  white page. ⚠ Made in that order: Collections lists the newest album first, and the phone's row
  shows only two cards beside *New album*, so the album `03-album` opens is made last.
- `05-months` is the library's grouping button tapped from *Mosaic* through *Day* to *Month*; the
  button's label names the current grouping (`FeedDensity.description`), which is how the walk
  knows where it is. That label comes from Kotlin and is English in every language, so the walk reads
  it in English (§4).
- **Not captured**: the viewer's *About* sheet (at its medium detent, half of it is empty glass);
  Search, whose iOS screen matches file names and synced tags only (description search needs an
  on-device index, PIO-7) and says so — a store picture of a search for `IMG_74` sells nothing;
  Places and People, which need GPS in the EXIF and face grouping (PIO-8).
- The tabs go through the shared `app.shellTab(i)` / `app.waitForShell` (`ios-shell/UITestSupport`,
  §3e) — on the iPad's floating bar too, which XCUITest lists as no tab bar at all. ⚠ The viewer
  hides the bar until *Back*'s pop ends, so `PhotosWalk.openTab` waits for the shell before each tap.
- Every capture waits for Photos' own sync capsule (*Syncing…*) to stay gone for 3 s, as the Drive
  walks do (§9).
- ⚠ **A selection tap can be lost**, and a selection one short only shows at *Add to Album*
  ("11 Selected", 2026-10-23, iPhone and iPad alike). A tile whose centre sits under the floating
  tab bar or the selection's toolbar reports `isHittable`, and the tap lands on the bar. So
  `PhotosWalk.reveal` scrolls a tile clear of the bottom 200 pt where the grid can still move (the
  library's last row cannot), and `addToAlbum` checks the count after **every** tap and taps once
  more only if it did not rise — never blind, since a second tap that took would deselect.

## 3e. The 13" iPad — the same walks, the shell's other tab bar

Every app here builds with `TARGETED_DEVICE_FAMILY "1,2"`, so App Store Connect wants a 13" iPad
set (2064 × 2752 portrait) beside the iPhone's, and `ipad13` runs Control's, Drive's, Calendar's
and Contacts' walks, light only — and Fit's, over the iPhone's realm (below). They are the iPhone walks, unchanged in what they capture: the apps have no iPad-specific
composition (no `NavigationSplitView`; every screen is the phone's, wider — lists span the width,
onboarding and pairing cap their column at 480 pt, sheets are form sheets), so the iPad shows the
same screens by the same taps.

⚠⚠ **What differs is how a walk finds a tab.** On a 13" iPad in portrait (1032 pt, under the
shell's 1100 pt sidebar threshold) `AdaptiveAppShell` draws iPadOS's **floating tab bar at the
top** — and XCUITest sees **no `TabBar` element there at all** (iOS 27 simulator, 2026-10-23):
the pill is a plain `Other` holding the sidebar toggle (`ToggleSideBar`, *Toggle sidebar*) and one
`Button` per destination, each with a duplicate `Button` nested inside and its SF symbol as the
identifier. So `app.tabBars.buttons.element(boundBy:)` — what every walk used — finds nothing on
the iPad, and a bare positional lookup over all buttons would land on the toggle. The walks
share one lookup instead, `app.shellTab(index)` in **`ios-shell/UITestSupport/ShellTabs.swift`**
(compiled into each app's `…IosScreenshots` target by its `project.yml`): the `TabBar` on an
iPhone; on the iPad the direct button children of the toggle's **own parent**, minus the toggle
(⚠ not of any `Other` that merely *contains* the toggle — that matches every ancestor, and a
direct button of an outer one would shift the indices). The **sidebar** placement (a window
≥ 1100 pt, i.e. landscape) is not supported, because no walk has run there: `shellTab` fails the
test, saying so, when the toggle's sibling buttons are not one level row. `app.waitForShell(timeout:)`
waits for either bar. ⚠ Resolve a tab when you tap it — an element taken before launch is bound to whichever
placement was on screen then. Control's walk used to fall back to `app.buttons["Settings"]` on
the iPad, which only worked in English; it uses the shared lookup now too.

What the first iPad captures showed (2026-10-23):
- ⚠⚠ **Calendar's month grid drew three months at once** — `UICalendarView` at 1032 pt centres one
  month and fills the spare width with the neighbouring months' day columns, under one month's
  title and weekday row. Its month page is ~390 pt wide at any width; an iPhone leaves the rest
  blank, an iPad draws the neighbours there. A product bug, fixed in the app: on an **iPad**
  (keyed on the device idiom, not the size class — a compact-width trait override changed
  nothing, so it is the idiom that draws them, in any iPad window) `CalendarMonthScreen` caps
  the grid at 440 pt, centred, and masks its outer 12 pt, where the neighbours' cut digits still
  showed at 440. ⚠ Checked full-screen only; a narrow Slide Over window is not. If `01-month`
  ever shows a stray digit beside the grid, that is back.
- ⚠⚠ **The month lost its last week — on every device.** The grid sat above the day's list capped
  at 380 pt, and `UICalendarView` keeps its row height and drops rows rather than shrink, so a
  five- or six-week month showed four. Fixed: the grid is now the day list's **first row**, at its
  natural height, and the day's events scroll up beneath it. Checked on the smallest iPhone the
  apps support (iPhone SE 3rd gen, 375 × 667, by a one-off local run): the whole month fills the
  screen and the events start under the tab bar, a scroll away — raising the cap instead would
  have left the SE no room for them. (The walk itself is not for the SE: its agenda step looks for
  an event four days out, which a 667 pt screen has not loaded.)
  - **Six-week months (2026-10-24, iOS 27).** `UICalendarView` does **not** change height between
    a five- and a six-week month here: at a given width it keeps one height (383 pt at 402 pt wide)
    and re-spaces its rows. The row is still sized by asking it (`CalendarMonthView.sizeThatFits`,
    re-asked on every page because the month is one of the representable's inputs), not by a
    weeks × row-height guess. `calendar-ios/UITests/MonthGridUITests` pins it: pages to the next
    six-week month (January 2027 with a Sunday start), checks its last week is hittable and above
    the row beneath, pages back and checks no week-sized gap is left — green on iPhone SE (3rd gen),
    iPhone 17, iPhone 17 Pro Max and the 13" iPad. It needs a provisioned simulator (its doc says how).
  - **The grid follows the model's month.** A rebuilt `UICalendarView` opened on today's month
    under the paged month's title, and *Today* moved the title but not the grid. `makeUIView` now
    opens on the model's month, and an existing grid moves only on an explicit `MonthJump` (Today),
    never because the model's month differs from it: that month is mostly the grid's own paging
    coming back, and a bookkeeping guess at which emissions were echoes left a stale entry after a
    quick there-and-back that later swallowed Today. A jump is animated for a neighbouring month only
    — animating across several walks through them, and each reported month would page the model.
    The UI test's *Today* steps (from five months out, and after a there-and-back) pin both.
- ⚠ **Calendar's walk waits by name and for sync to settle.** It waited for "the second cell", which
  the loading `ProgressView` row also is, and the iPhone `01-month` captured "Syncing…": the
  shell's sync-activity capsule, up while the shipping launch's first sync of the freshly seeded
  realm with the dev lab runs. It now waits for a fixture event on today (by weekday) and, before
  every capture, for the capsule to stay gone 3 s — Drive's `waitForSyncIdle`.
- ⚠⚠ **The dev relay is behind: every walk fails at its first sync wait.** Drive's walk sitting
  300 s behind *Uploading…* (2026-10-28 twice, 2026-10-29) was not the fixture and not a quota: the
  dev lab's relay (image `579024ce`, protocol range 4..4) answered the realm registration
  `426` — the release cut restarted `RELAY_PROTOCOL_VERSION` at 1 — so the realm never
  existed there and every later call of the device was `401`, the background session's content
  PUTs included (~140 a second, each re-enqueued at once: `tech-debt.md`, *"A content PUT's 401
  re-enqueues with no backoff"*). The relay's 426 text says *"this client must be updated"*, which
  after a version reset is backwards. Both scripts now probe the relay's range before building
  (`scripts/lib/devlab-relay-protocol.sh`, a `POST /sync/realm` that the version gate refuses or
  the key decoding rejects — nothing is written) and stop with the remedy: redeploy the dev relay.
- ⚠⚠ **Fit's iPad walk cannot seed, so it shows the iPhone's data, synced.** Its seed launch writes
  to Apple Health, and on the 13" iPad simulator (iOS 27, 2026-10-24) the write consent
  (*Select All*, *Allow*) is followed by **"iCloud Health Data Sync is Off"** (*Open Settings* /
  *Not Now*). Left up, the seed waits behind it for good; *Not Now* ends the request with HealthKit
  error 5, *"Authorization is not determined"* (`dismissHealthSyncNotice` answers it, so that is a
  20 s failure rather than a 300 s hang). Not tried: an iCloud account on the simulator, or *Open
  Settings*. Instead (2026-10-30) the iPad does what a user's second device does: the iPhone's
  onboarding writes the realm's recovery phrase to `build/store-shots/fit-realm-phrase`
  (`SHOTS_PHRASE_FILE`, deleted when the run ends), the iPad does **no genesis** and recovers that
  realm by typing the phrase (`ControlRecoverScreenshots`), and Fit's
  `testStoreScreenshotsFromSyncedRealm` waits for the iPhone's newest workout to arrive in History,
  then captures the same screens. No seed and no import on the iPad, so nothing there is staged
  beyond the recovery a user does. ⚠ Consequences: a run with Fit on the iPad has **Fit alone**
  (the other apps' iPad captures need a genesis there) and **the iPhone first** — the script
  refuses anything else — and it needs the dev lab.
- Accepted as the system's, not ours: on Contacts' `03-search` iPadOS hides the top tab bar
  and puts the large title flush against the screen edge — in **every** searching state (typing,
  and still 10 s after the Search key; checked 2026-10-24), so it is iPadOS's searching layout, not
  a capture taken mid-transition, and no other moment of a search avoids it; Control's
  `09-add-device` sheet scrolls, with *Stop* at its bottom edge; Contacts' merge question is a
  popover over the navigation bar.

## 3f. Timeline's fixture — a generated Google Timeline export, through the shared importer

`scripts/store-screenshots.sh timeline` (dev lab; iPhone and iPad, light — no dark set checked,
`timeline_runs_on`). Timeline's listing is a person's movements, so nothing is downloaded:
`store/fixtures/timeline/generate_timeline.py` writes it for the **day of the run** from
`store/fixtures/timeline/manifest.json` — two weeks of one invented person's ordinary days at home in
**Milan** (Home, Office, a lunch bar, a climbing gym, Parco Sempione, the Darsena, cycled, walked or
run between), then a train on the third morning back and **three days in Florence** ending today (the
Duomo, Santa Croce, Piazzale Michelangelo, Boboli, the Uffizi, the Accademia, Mercato Centrale…).
Nothing it writes is committed. The Milan places are generic names on arbitrary street points, never
an address; the Florence ones are public squares, galleries and gardens.

- **Why Milan and Florence.** An iOS install draws only the bundled basemap (central Florence) until
  a region is downloaded, so every screen with a map is a Florence one. And Timeline suggests a trip
  only for moments **more than 100 km** from home (`TripDetectionConfig.awayRadiusM`), home being the
  most-visited ~110 m cell (`TripSuggester.deriveHome`) — so home has to be elsewhere, and Florence is
  the trip. The Milan days are what makes Home the home; no capture shows a Milan map.
- **The trip's title is a place's label.** A suggestion is titled by the label most visited among
  its members (`TripSuggester.dominantLabel`), so the rented flat is labelled *Florence*, visited each
  night: the card and the trip read *Florence*. A user names places the same way (*"You name your
  places yourself"*, the listing).
- **The shape.** The legacy Semantic Location History JSON (`timelineObjects`: `placeVisit` with a
  `location.name`, `activitySegment` with a timestamped `simplifiedRawPath`), one file per month. The
  2024+ on-device export names no place, and iOS looks nothing up, so it would make every row
  *Unnamed place*. No Google `placeId` is invented: places are keyed by their coordinates.
- **The legs.** Each visit ends at its manifest time; the leg after it lasts as long as its distance
  takes at the mode's speed, and the next visit starts on arrival. Florence walks are routed along the
  basemap's own streets (Fit's `streets.py`, §3a); Milan legs and the train are bent straight lines.
  `generate_timeline.py <dir> --preview <png>` draws every Florence leg over the network.
- ⚠ **Run it after 14:00 local time** (`notBefore`): today's chapter is the Florence morning, and the
  generator cuts everything after *now* (the stay in progress ends at *now*), so a morning run would
  capture an empty today. The walk opens today's 09:25 walk (Piazza Santo Spirito → the Accademia,
  across the river) by its start time, which the manifest fixes.

**The seed** (`-seedFixture <dir>`, `timeline-ios/Sources/StoreFixtureSeed.swift`, DEBUG-only) hands
the files to the shared `FileTimelineImporter.importFiles` — the call Timeline's *Import from Google
Takeout* makes on Android and desktop — through `TimelineLocalRuntime.importGoogleTimelineForStoreFixture`.
⚠⚠ **The iOS app has no import**, and its listing says so (`store/timeline/claims.md`): its rows come
from its own capture, or through the realm from the user's other devices. So that Kotlin entry point
refuses a release binary (`Platform.isDebugBinary`, the dev-relay seam's gate), and no iOS screen
calls it. What the seed writes is exactly what such an import on another device syncs onto the phone:
places, visit moments, and movement moments with their traces — the same pair live capture writes
(M7). Capture itself cannot be driven: the simulator delivers no `CLVisit`, and two weeks cannot be
lived in a run. Renders one line, never captured — the Drive rule (§2).

**The walk** (`timeline-ios/Screenshots/TimelineScreenshots.swift`, scheme `TimelineIos-Screenshots`)
has no tabs to find: Timeline is one stack with a segmented *Home / Places / Activities* control, the
same on the iPad, so it never calls `shellTab`. By taps: `01-today` (the feed's top), `02-suggestion`
(scrolled to the *interlude* card under today's chapter), `03-trip` (*Read as a trip*, then the new
chapter under *Bound chapters* at the feed's end, opened — the route of its visits over the map),
`04-walk` (today's walk on the map), `05-places`, `06-place` (*Piazza Santo Spirito*, three visits).
Every capture waits for the sync capsule to stay gone 3 s, as the other walks do.

**Defects this walk found (2026-10-30).**
- ⚠⚠ **"Can't reach Tenshu Control" over a working runtime — OPEN.** On five of ten launches tried by
  hand (iOS 27.2 simulator, just after the seed or long after it) the first read of an empty place-service collection
  — `placeService.merges`, then `roles` — comes back as a message-less `InternalErrorDto` from the
  in-process runtime. `TimelineProjectionRepository.foldTracked` takes any failure for Control being
  gone (`markUnreachable`), and `TimelineRootView` reads `controlReachable()` only when it mounts, so
  the banner stays up for the whole session even after the probe recovers. The first run captured
  all six screens under it. The walk now relaunches while the banner is up (printing
  `TIMELINE-SHOTS launch N opened under the Control-unreachable banner`) and `shot` refuses a capture
  with it — a user's relaunch, not a fix. Not fixed here: the internal error is in the core/store
  layer, and the shell's snapshot read is its own change.
- **Fixed:** a movement's row opened its second line with a bare "·" (` · 22 min` — a movement has no
  place line); it now shows the route between the bracketing places, as Compose's `movementRouteText`
  does (*Piazza Santo Spirito → Galleria dell'Accademia · 22 min*). And Places said *1 visits*.
- **Fixed, iPad:** a place row (and a feed row) took a tap only on its text — under
  `.buttonStyle(.plain)` the row's empty middle is not hit-testable, and on the 13" iPad that middle
  is where a tap at the row's centre lands, so *Piazza Santo Spirito* never opened. Both rows now
  carry a full-row `contentShape`, as Contacts' rows do (§3c).
- ⚠ **Open:** after *Read as a trip* and a relaunch, the interlude offers the same trip again — the
  accepted and dismissed suggestions are held in memory only (`resolvedSuggestions`), and nothing
  checks the suggestion against the trips already recorded.
- Fixture lesson: two places under 200 m apart with a short walk between them are one stay
  (`SAME_PLACE_RADIUS_M`, `ABSORB_MOVE_MAX_M`) — the flat was first put next to Piazza Santo Spirito,
  and the morning coffee vanished into the night's stay.

**German** (`--lang de`, 2026-10-30): the app's own chrome is German (*Orte*, *Aktivitäten*, *Als Reise
ansehen*, *Keine Reise*, the basemap's *Florenz*); the day headings, *Walking*, the trip's stats and
*visits* are built in Kotlin or passed as plain `String`s and stay English — the shipping app's German,
as §4 says of the others.

**What it cannot show.** The *Activities* tab: it counts Fit's workouts (`WorkoutActivityRollup`), and
a Timeline import writes none, so it is empty and not captured. Photos on a moment (the fixture has
none). A recorded day — the `● now` row of a stay in progress is device-local capture state, which an
import does not set.
## 3g. Mia — questions over the other apps' data

`scripts/store-screenshots.sh mia` (dev lab, light, iPhone and iPad). Mia keeps no data of its own:
it answers typed questions from the rows the other apps keep in the realm, so a capture is honest only
if those rows exist. The run therefore fills the realm first through **the other apps' own seed
launches** — the same `-seedFixture` paths their walks use, nothing new — and captures none of
their screens:

1. Control's onboarding (the genesis and the dev-lab account, §1a), not exported.
2. `DriveScreenshots/testSeedRealm` — Drive's fixture (§3), the shipping launch before and after it,
   ending once the uploads are done.
3. `CalendarScreenshots/testSeedRealm` — the five calendars and their `.ics` files (§3b). No new event,
   no reminders prompt.
4. `FitScreenshots/testSeedRealm` — **iPhone only**: the GPX workouts through Fit's file import (§3a).
   The daily figures stay in Apple Health (they reach the realm only through Profile's import, which
   no question here needs). The iPad simulator cannot run this seed (§3e).
5. `MiaScreenshots` — one launch with no arguments. Mia has **no hook at all**: the intro is passed
   by taps, every question is typed into *Ask Mia…* and sent with the keyboard's return.

⚠ Each of those classes also holds its app's own walk, so the script names the **method**; Drive's and
Calendar's own runs now name `…/testStoreScreenshots` for the same reason.

**The questions**, and the arm of `QueryPlanner` each takes (`mia-service/.../plan/QueryPlanner.kt`):

| Capture | Question | Planner arm → read | What it cites |
|---|---|---|---|
| `02-files` | *Show me my tax files* | catch-all name search, `name contains "tax"` | the *Taxes* folder, *Tax return 2025.pdf* |
| `03-photos` | *Photos from July* | `FindPhotos` → `photosCapturedBetween` | the *Holiday 2026* photos (their `File.CreatedAt` is the fixture's July `modified`) |
| `04-calendar` | *What's on my calendar next week?* | `QueryCalendar` → `queryOccurrences` | next Monday–Sunday of the Calendar fixture |
| `05-workouts` | *Show my workouts from last week* | `FindWorkouts` (fitness words win over the rest) | last week's runs, rides and walks |

The walk captures an answer only when it is a citation list: an answer with no rows is the same
caption with *"— nothing matched."*, and the walk fails on it instead. `02-files` and `03-photos` also
check for a named row.

- ⚠ **Pick a question by its arm, not by how it reads.** *"How far did I run this week"* is routed to
  `FindWorkouts`, which filters by **time only**, so it would list rides too — under a question about
  runs. *"Find my payslips"* searches names for `payslips`, which matches the folder and neither PDF
  (*Payslip 2026-08.pdf*). *"This week"* for the calendar starts **today** (`AgendaTimeGrammar`), so
  on a Friday it is a weekend; *next week* is always seven full days.
- ⚠ **One launch for the whole walk.** Mia holds the conversation in memory (MQ-D8): History lists
  the current session only, as one row titled by its first question, and is empty in any other launch.
- **Never captured: Settings.** Every row there is *Coming soon*; a listing must not show one.
- **The iPad has no `05-workouts`.** `screens.json` marks it `"devices": ["iphone69"]`, which
  `check_screens` honours, and `listing.json` lists it anyway: `store-listing.py screenshots` warns
  that the iPad set has one screen fewer and pushes the rest. Recovering the iPhone's realm on the
  iPad (Fit's way, §3e) would add it, at the cost of an iPad run that seeds nothing of its own.
- **German.** Mia's grammar is English (`TimeGrammar`, MQ-D4) and its answers are Kotlin strings, so a
  `--lang de` run types the same English questions into Mia's German chrome — what a German user gets.
  Both sets were captured and published on 2026-10-30.
- ⚠ `mia` runs alone: beside Drive's or Calendar's own walk, the seed and the walk would capture
  each other's data.

A product defect this walk found and fixed (2026-10-30): **Mia's question field dropped letters when
typed into fast.** `ChatModel` copied the view model's `input` back into the field on every emission
that differed, so the echo of one keystroke could land after the next and erase it — the iPhone walk
sent *"Sho me my tax fles"* and got *"— nothing matched."* (the iPad's, typing at the same speed, got
lucky). The field now takes back only the view model's clear on send. The walk also checks the
field holds the question before it presses return, so a dropped key fails as itself rather than as
a wrong answer.

## 4. Another language

Every run passes a region with its language — `-testRegion US` for `en`, `DE` for `de` — because
without one the simulator inherits the Mac's region, and on a Mac set to English with a German
region an English listing shows German dates (*6. Oct 2026* rather than *Oct 6, 2026*). Those two
flags reach the app only, so the script also writes `AppleLocale` / `AppleLanguages` into the
erased simulator's global preferences before it boots — otherwise the iPad status bar, which
SpringBoard draws, still reads *Wed 14. Oct*.

`--lang de` passes `-testLanguage de -testRegion DE` to the test run and writes to `…/de/`. Every
iOS app here carries German strings (`Resources/Localizable.xcstrings`), though not for every screen:
Control's onboarding, sign-in and pairing, the "Not syncing" banner (Kotlin), Fit's category titles,
Photos' grouping button and selection title are English in a German run, because the app draws them
in English. The fixtures stay as they are (*Holiday 2026*, the invented person) — one data set for
every language.

**How a walk finds a label in another language.** The tabs go by **position** (`tab(index)` →
`shellTab`, §3e). Every other lookup is written with the **English** label through
`ios-shell/UITestSupport/ShotStrings.swift` — `app.buttons[l: "New event"]`,
`ShotStrings.labelBegins("Storage")`, `app.buttons[any: ShotStrings.format("Actions for %@", name)]` —
and matches the English label **or** its translation. A `--lang` other than `en` makes
`scripts/lib/ios_shot_strings.py` write the table for each run (`build/store-shots/strings-<app>-ios-<lang>.json`:
the app's own catalogue, plus the words of Apple's sheets the walks tap — Apple Health's consent,
alerts, the keyboard's keys) and hands it to the runner as `TEST_RUNNER_SHOTS_STRINGS`. An English run
has no table, so its lookups are the English ones they always were.
- ⚠⚠ Write a new lookup with `[l: "…"]` (or `ShotStrings.label…`), never `["…"]`: a bare English
  lookup passes every English run and fails the first German one.
- ⚠ It matches **both** languages on purpose: a string can be in the catalogue and still reach the
  screen in English (a `String` handed to `Text` — Drive's `model.copied ? "Copied" : "Copy Link"`),
  and a label nothing translates is the English one, which is what such a screen shows.
- ⚠ The system words in `ios_shot_strings.py` (`SYSTEM`) are partly unverified: a run that fails at
  one prints the labels it found — correct the entry from that.
- A label whose number sits mid-sentence goes through `ShotStrings.labelLike` (`*` wildcards): Apple
  Health's iOS 27 *Select All 11 Topics* is *Alle 11 Themen auswählen* in German.
- **What a German set shows (2026-10-29).** Only what the app translates: Control's iOS onboarding
  (01–03, 08, 09) is plain `String` constants with no German and stays English; Drive and Fit keep
  Kotlin-built labels (*Search files*, *10 files*, *steps*, *Today*) and Android's bottom bars in
  English. The captures are the shipping app's German, not a translation pass.
- **Android, German (`store-screenshots-android.sh --lang de`; the Android walk itself is §8).** Run it on a private AVD copy
  and adb server port (`TENSHU_SHOTS_AVD`, `TENSHU_SHOTS_PORT`, `ANDROID_ADB_SERVER_PORT`,
  `ADB_LOCAL_TRANSPORT_MAX_PORT=5553` so the private server scans no other emulator) when another
  session's emulator is up. Two traps the German run found:
  - Gboard comes up bilingual (DE · EN) and ignores ESCAPE, so the next tap landed on a key
    (*…example.comy*). `type_text` now closes an open keyboard with BACK (`dismiss_keyboard`).
  - The emulator's Wi-Fi fails its DNS validation now and then, the default route moves to the
    metered cellular network, and Drive's 01-files showed *Waiting for Wi-Fi* and then *Uploading
    71 of 108*. The walk (`settle_uploads`) answers with the banner's *Upload over cellular* and
    waits until neither banner has been up for 3 s, re-checking both, since the one banner slot
    can swap between them; it refuses the capture otherwise. ⚠ Turning mobile data off is no cure: Wi-Fi alone then
    resolves nothing.

## 5. Adding a screen or an app

- A screen: one more `shot("NN-name")` in the app's `Screenshots/*.swift`, reached by taps, **and
  the same name in `store/<app>/screens.json`** (`captured`). Keep names lower-case
  `NN-kebab-case`; the export ignores anything else. Take the next free number — never renumber,
  a published name is what the support pages link. `web/apex/support-pages/screens.test.ts` fails
  when the file and the UI tests disagree, or when a page names a screen the file does not list.
- An app: a `<App>IosScreenshots` UI-test target and `<App>Ios-Screenshots` scheme in its
  `project.yml` (copy Drive's — `test: config: Debug`), and a block in the script's device loop.
  Route-1 apps need Control's onboarding first, as Drive does.

## 6. When it fails

- Logs and result bundles: `build/store-shots/<device>-<lang>[-dark]/*.xcresult{,.log}`; open a bundle in
  Xcode to see the failing step and its screenshot. The build logs are
  `build/store-shots/build-*.log`.
- *"Welcome never appeared"* — the simulator was not fresh, or Control crashed at launch.
- *"the dev lab is not answering"* / *"refuses the Keychain token"* — the pre-flight; pass
  `--offline`, or see `deploy-dev-lab-scaleway.md` §10.
- *"no OTP arrived"* — the mail never reached the mailbox, or the per-IP OTP budget is spent
  (wait an hour). *"commerce never asked for a code"* names the screen's error line.
- Drive waits up to two minutes for the *Not syncing* banner to go; if it does not, the
  entitlement never reached Drive's runtime — look for the `Entitlement trust:` line (§1a).
- *"seeding failed"* / *"NOT PROVISIONED"* — Control's genesis did not hand Drive its key
  material; run Control's onboarding test alone and look at the realm.
- A wrong size — the device type in the script changed; App Store Connect's accepted sizes are in
  the script's `accepted_sizes`.

## 7. Publishing — R2 and the manifest

The captures are published to the R2 bucket `tenshu-assets` behind `https://assets.tenshu.app`
([`deploy/cloudflare-assets`](../../deploy/cloudflare-assets/)), **content-addressed**:

```
https://assets.tenshu.app/sha256/<sha256 of the PNG>.png      Cache-Control: public, max-age=31536000, immutable
```

and recorded in a committed manifest per app, one line per screen, so a PR diff reads as
"this page now shows this screenshot":

```jsonc
// store/control/screenshots.manifest.json — written by store-publish.sh, never by hand
{
  "about": "…", "version": 1,
  "base": "https://assets.tenshu.app/sha256/",
  "screens": {
    "ios/iphone69/en/01-welcome": {"sha256": "9596…0104", "width": 1320, "height": 2868, "bytes": 812345},
    "ios/iphone69/en/dark/01-welcome": {"sha256": "1c2e…77ab", "width": 1320, "height": 2868, "bytes": 790112},
    …
  }
}
```

```bash
scripts/store-publish.sh --dry-run          # hash, list the objects, diff the manifests — no network, no writes
scripts/store-publish.sh                    # upload the missing objects, then rewrite the manifests
scripts/store-publish.sh --verify           # HEAD every manifest URL: 200, image/png, size, immutable
```

- **Objects first, manifest last.** A failed upload leaves the old manifest in place. An object
  already in the bucket is skipped (same key ⇒ same bytes).
- **Only what you captured is replaced.** Each `<platform>/<device>/<lang>` folder present locally
  replaces its entries; the rest of the manifest stays. So publishing an iPhone-only run keeps the
  iPad entries, and a screen that a captured folder no longer has drops out. A `…/<lang>/dark`
  folder is its own group: publishing light leaves the dark entries alone, and the other way round.
- **Framed renders** (`scripts/store-frame`, [`store-listings.md`](store-listings.md) §6) are a device of
  their own, `<platform>/<device>framed/<lang>/NN-screen` — the same key form, so nothing here changes
  for them and the support pages, which read `iphone69` only, never show them.
- **Two key forms, nothing else:** `<platform>/<device>/<lang>/NN-screen` (light) and
  `<platform>/<device>/<lang>/dark/NN-screen` (dark). `scripts/check-store-assets.py` (pre-commit, and
  both store scripts) fails on any other key, and `store-publish.sh` refuses a capture folder of any
  other shape.
- **It refuses when the bucket does not exist**, saying so, and needs the R2 key pair scoped to
  `tenshu-assets` (`r2-assets-access-key-id` / `r2-assets-secret-access-key` in the Keychain, or
  `R2_*`) and the endpoint (`ASSETS_ENDPOINT`, else the module's Terraform output).
  ⚠ The Terraform state lives in the `../Drive-ops` worktree (master, detached), where the module was
  applied on 2026-10-15; anywhere else the output is empty. From another worktree:
  `ASSETS_ENDPOINT=$(cd ../Drive-ops/deploy/cloudflare-assets && ./tf.sh output -raw s3_endpoint) scripts/store-publish.sh …`
- ⚠⚠ **Run `--verify` before merging a manifest change.** A support page links every screen its
  manifest lists, so a listed image R2 does not serve would be a broken picture on a public page.

**The support pages** (`web/apex/support-pages/generate.ts`) resolve each `shot:NN-screen` image slot — or
`shot:<app>/NN-screen` for another app's screen — through the manifest to the R2 URL with `width` and
`height`; an unlisted screen stays a placeholder. They read the manifest from disk and never fetch, so
the apex build needs no network (its image copies `store/` for this). The iOS pages show the
**iPhone 6.9"** capture (`SUPPORT_DEVICE`): the pages' iOS steps describe the iPhone, and a portrait
phone capture fits the narrow screenshot column beside each step's text (15 rem wide, ~520 px tall on
wide screens), where the iPad's near-square one would shrink to a thumbnail. The Android pages show
the `phone` capture of §8. Other platforms keep placeholders until they have a pipeline. With a light **and** a dark capture published for a slot
the page carries both `<img>`s and CSS shows the one matching the effective theme (the switch's
choice, else the OS); with one of them, that one in both themes (§1b).
⚠ A slot may name only a screen in `store/<app>/screens.json` (§5): on iOS one the pipeline
captures, on Android one in its `android.captured` list (§8), elsewhere also a `planned` one for that
platform — so the macOS and web blocks use the iOS names wherever the step shows the same screen, and
a later pipeline there fills them in.

**Cloud sessions** reading these images need `assets.tenshu.app` on their environment's network
allowlist — a maintainer setting.

### 7.1 Web derivatives — WebP for the website

The PNGs are full size (1080 × 2400, 1320 × 2868, up to ~3 MB) because App Store Connect and
fastlane need them. The website never shows them when it can help it: `store-publish.sh` also makes
**WebP copies** of every PNG the manifests name (`scripts/lib/store_webp.py`) —

- **widths 540 and 960** (never an upscale): 540 covers the 75mm carousel phone and the 15rem step
  frame at 1× *and* 2×, 960 the phones' 3× and the narrow support column;
- `cwebp -q 78 -m 6 -sharp_yuv -metadata none`: UI text stays crisp at 2×; 540s run ~7–100 KB
  (median 18 KB), 960s ~13–250 KB (median 34 KB), photo grids the largest;
- uploaded to `sha256/<hex of the WebP>.webp` (content-addressed by their own bytes, `image/webp`,
  immutable) and recorded in **`store/<app>/screenshots.webp.json`**, keyed by the source PNG's
  sha256, objects first and the sidecar last, like the manifest:

```jsonc
{ "about": "…", "version": 1, "base": "https://assets.tenshu.app/sha256/",
  "derivatives": { "<png sha256>": [{"width": 540, "height": 1200, "sha256": "…", "bytes": 18011},
                                    {"width": 960, "height": 2134, "sha256": "…", "bytes": 33777}] } }
```

- **Every run backfills.** Whatever a manifest PNG lacks is made — from the local capture, else from
  the PNG downloaded back from R2 (hash-checked). A run with no local captures therefore only
  backfills; `--dry-run` lists what it would make (`local capture` / `download from R2`) without
  network. Entries for PNGs no manifest names any more are pruned.
- ⚠ **A sidecar, not a manifest field, on purpose:** the manifest is rewritten whole by every
  publish, including from branches whose `store-publish.sh` predates this, which would drop a field.
  A PNG with no copies yet is simply shown as itself; `AppCarousel.test.tsx` goes red for a landing
  capture without copies — run `store-publish.sh` from a current checkout and it fills them in.
- **Needs `cwebp`** for the real run (`brew install webp`; the Python is stdlib only). `--verify`
  also HEADs every derivative: 200, `image/webp`, the recorded size, immutable.
- The pages put them in `srcset` with `sizes`, keep the PNG as `src` (the fallback, not a candidate)
  and keep `width`/`height` (`web-about-tenshu-public.md` §9). Changing `WIDTHS` or the encoder
  settings leaves existing copies alone; a new width is filled in on the next run.


## 8. Android

```bash
scripts/store-screenshots-android.sh                     # control + drive, light, English
scripts/store-screenshots-android.sh --appearance both   # light, then dark (a fresh install each)
scripts/store-screenshots-android.sh --skip-build        # reuse the last APKs
scripts/store-screenshots-android.sh --avd <name>        # another 1080 × 2400 AVD with rounded corners (default Pixel_8_Shots)
scripts/store-screenshots-android.sh fit calendar contacts   # the three apps (light, dev lab only; §8.1)
scripts/store-screenshots-android.sh timeline maps home mia  # the Play-only four (light, dev lab only; §8.2)
scripts/store-screenshots-android.sh control drive contacts calendar fit --lang de   # the German set, all five
scripts/store-screenshots-android.sh --set landing --lang de                         # German landing set (§9)
```

```
store/<app>/screenshots/android/phone/<lang>/NN-<screen>.png        light   (<lang> = en | de)
store/<app>/screenshots/android/phone/<lang>/dark/NN-<screen>.png   dark
```

The same pipeline in spirit as iOS — a real dev-lab realm (§1a), every screen by taps, the same
screen names, published with `store-publish.sh` (§7) — with these differences:

- **The emulator.** It boots the AVD `Pixel_8_Shots` (1080 × 2400, refused otherwise) with
  `-read-only` on its own port (5590; `TENSHU_SHOTS_PORT`). Every write of the session goes to a
  temporary overlay that the emulator discards on exit, so the AVD is never changed, and the run's realm, session and recovery phrase go
  with the overlay. `tenshu_dev_lab` and port 5580 are refused (deploy-dev-lab-scaleway.md §10). The
  emulator is stopped at the end (`--keep-emulator` leaves it up). Each appearance uninstalls every
  `app.tenshu.*` / `com.tenshu.*` package in the session and installs Control and Drive fresh.
- ⚠⚠ **A display with rounded corners and a cutout.** Until 2026-10-17 the captures came from the
  smoke gate's `Medium_Phone`, a generic profile with square display corners — so Android drew the
  status-bar clock ~2 % from the left edge (x ≈ 22 px), and the landing carousel's rounded phone frame
  clipped it. The Pixel 8 profile reports rounded corners (radius 104 px) and a punch-hole cutout, and
  Android insets the status bar itself, as on the phone: the clock starts at x ≈ 42 px and the icons
  end as far from the right. Same 1080 × 2400 at 420 dpi, so every key and layout stayed. The script
  creates the AVD on first use **from the system image the smoke gate already has** (no download;
  `TENSHU_SHOTS_IMAGE` picks another) with the SDK's `pixel_8` skin, which carries the cutout and the
  corner mask the emulator hands the guest, and boots it once to make its userdata. After each boot
  it refuses a display whose `dumpsys display` reports no rounded corners — so `--avd Medium_Phone`
  now fails on purpose.
- **The dev-lab settings are compiled in.** Android has no runtime origin seam, so the four settings
  go to Gradle as `-P` flags, which outrank `local.properties` — nothing is written there.
  `--offline` builds production-pointed debug APKs and captures only the screens marked without
  `devLabOnly`.
- ⚠⚠ **Build from the commit the dev lab runs, or newer.** Clients and relay ship together — deriving
  the device id from the A4 key and length-prefixing signed messages both changed what the
  relay accepts without a protocol bump — so an older checkout's client is refused: onboarding and
  sign-up look fine, the first push after sign-up gets `401 — Invalid token`, and *Show a code*
  turns into the new-device offer, because the host poll 401s and the re-register repair is refused
  too. Seen 2026-10-30 from a checkout 44 commits behind master. The script warns when the checkout
  is behind `origin/master`, and the walk names this cause when it lands on the offer.
- **The status bar** is Android's demo mode: 9:41, battery full, Wi-Fi full, no mobile icon. ⚠ On
  the Android 17 image, `notifications -e visible false` hides nothing and `mobile show` keeps the
  real *3G* icon and weak bars, so the mobile icon is hidden outright, the walk snoozes every posted
  notification (Safety Center's *no screen lock* one) before each capture, and the demo state is
  re-sent before each capture too — sent only once at boot, a later capture showed a second Wi-Fi
  glyph. ⚠ Re-sending alone does not clear that glyph once it is there (2026-10-17: every dark capture
  of one run had it), so each re-send leaves demo mode and enters it again (`exit`, then the rest). Notifications are not granted to either app, so their own icons never appear.
- **The walk** is `scripts/lib/android_shots.py`: it finds each control by its text in a
  `uiautomator dump` and taps it. No deep link (Control has `app.tenshu.control.deeplink`; it is not
  used). The one permission it grants by hand is Control's camera, as a user allows it when *Add a
  device* opens on *Scan a code*.
- ⚠ **Control's part runs in one foreground session.** Its app lock is on by default, re-arms when
  the app goes to the background, and cannot be passed on an emulator with no screen lock — so
  every Control screen is captured before Drive is launched.
- **Two languages, `--lang en|de`** (default `en`; the support pages show the English capture where a
  language has none, `SHOT_FALLBACK_LANG`). `de` sets each installed Tenshu app's **own** locale to
  `de-DE` after the install and before any launch (`cmd locale set-app-locales`, checked back with
  `get-app-locales` — a silent no-op would file an English set under `de/`), so the apps draw their
  German strings and German dates and numbers, and writes to `…/phone/de/` (`…/phonelanding/de/`).
  The **system** locale stays English on purpose: the system picker (DocumentsUI) and Health
  Connect's sheets, which the walk taps but never captures, keep the English labels it names. Same
  realm recipe, same fixtures (the invented data keeps its English names), same screen names.
  - **The walk is still written in English.** `find()` (and `tap_lowest`) accept a label's German as
    well, from a table `android_shots.py` reads out of the repository's own string resources —
    `values/strings.xml` against `values-de/strings.xml` by resource name, Android `res/` and Compose
    `composeResources/` alike, the app's own modules first (`APP_MODULES`), every module second. A
    format string is matched as a pattern with its arguments carried over (*Imported 28 contacts.*
    → *28 Kontakte importiert.*), a prefix lookup (`startswith`) against the German of every string
    the English prefix begins, an all-caps label as its resource in caps (*PERSONAL DATA* →
    *PERSÖNLICHE DATEN*). The English label always matches too. `TRANSLATION_OVERRIDES` holds the
    few labels no Tenshu resource carries (Compose's own *Close sheet* — AOSP's German is *Tabelle
    schließen*) and the fragments `wait_text` tests with `in` / `endswith`. A new walk step needs
    no German of its own unless its label is built in code.
  - ⚠ Only Android `res/` strips a string's enclosing quotes; Compose resources keep them
    (Contacts' `"%1$s" and "%2$s"` renders with its quotes), and the table reads them the same way.
  - ⚠ **What stays English on a German capture is the app's, not the walk's**: text with no
    `values-de` entry. Seen 2026-10-28 — Control's bottom bar (*Home, Apps, Data, Services, Settings*
    are literals in `Destination.kt`) and some Settings rows (*Errors*, *Offline maps*, *Models*);
    the plan's name (*Plan: Free*) comes from the commerce plane. Calendar and Photos carry
    ~15 German strings each, so most of their screens stay English.

| App | Screens (Android) |
|---|---|
| Control | `01-welcome`, `02-protected`, `03-recovery-phrase`, `06-settings`, `07-storage`¹, `08-sign-in`¹, `09-add-device`¹, `10-services`¹ |
| Drive | `01-files`¹, `02-photos`¹, `03-preview`¹, `04-file-actions`¹, `05-add`, `06-share-link`¹, `07-empty`, `09-shared`¹ |
| Fit | `01-home`, `02-history`, `03-workout`, `05-browse`, `06-sleep`, `07-profile` (light, dev lab; §8.1) |
| Calendar | `01-month`, `02-agenda`, `03-event`, `04-new-event`, `05-calendars`, `06-edit-calendar`, `07-week`, `08-day` (light, dev lab; §8.1) |
| Contacts | `01-contacts`, `02-contact`, `03-search`, `04-duplicates`, `05-merge`, `06-you`, `07-invite` (light, dev lab; §8.1) |
| Timeline | `01-today`, `02-suggestion`, `03-trip`, `04-walk`, `05-places`, `06-place` (light, dev lab; §8.2) |
| Maps | `01-places`, `02-place` (light, dev lab; §8.2) |
| Home | `01-devices`, `02-automations`, `03-automation` (light, dev lab; §8.2) |
| Mia | `01-welcome`, `02-files`, `03-photos`, `04-calendar`, `06-history` (light, dev lab; §8.2) |

¹ Dev-lab runs only. On Android `10-services` is *Services → Sign in or create an account → Pick a
plan* (Free, Basic and Premium; a Play build sells the paid two through Google Play — a
capture from before 2026-10-23 shows Free alone), and `07-storage` is the Services card
after sign-up, which on iOS lives under Settings → Storage.

The list is `android.captured` in `store/<app>/screens.json`: its names must be ones the file already
gives that number (`captured` or `planned`), so a number never means two screens, and
`screens.test.ts` checks it against the walk's `shot("…")` calls and the Android blocks' slots. Not
captured on Android: Control's devices screen (the emulator names itself *Google
sdk_gphone16k_arm64*, which a model property sets and nothing short of root changes).

**Drive's fixture on Android.** The same tree iOS seeds (§3, `store/fixtures/generate.py drive`),
built by the script before the emulator boots, but uploaded the way a user uploads — Android has no
`-seedFixture` hook and gets none. The walk `adb push`es the tree into the emulator's
`/sdcard/Documents` (keeping each file's mtime, which the picker reports as the document's
last-modified time and Drive shows as its date), then uploads each top-level folder with
**+ → Upload → Folder upload** and each loose file with **+ → Upload → File**, picking it in the
system picker. Everything goes through `uploadFolder` / `uploadFile` to the dev relay, and the push
lands on the discarded overlay. Dev-lab runs only: a realm with no plan refuses a folder upload at
its capacity pre-flight, so `--offline` stops after `05-add`.
- `02-photos` is the Holiday folder **as a grid** (the folder's *Switch to grid view* toggle), the
  Android twin of the iOS icons; the walk switches back to the list before it opens the photo.
  A Binder unbind owes a stream replay; without it the root listing stays on *No files here yet*
  after a folder upload until the Files tab is entered again, and the grid toggle does nothing. The
  walk has no workaround for that, so if a run waits forever for a seeded folder's row, that defect is back.
- `09-shared` (Shared → My shares, listing the link `06-share-link` made) is Android's own: the iOS
  walk does not capture it, so `screens.json` reserves the number under `planned` for Android.

### 8.1 Fit, Calendar and Contacts

`scripts/store-screenshots-android.sh fit calendar contacts` (any subset). ⚠ **Light only, dev lab
only**, as on iOS (§3a–§3c): no dark set has been looked at, and each app's data goes into the synced
realm Control's walk makes, so `--offline` and `--appearance dark` are refused. Each app is a
separate APK (`app.tenshu.<app>.android`) that reaches Control over Binder; the script builds and
installs it beside Control and Drive and walks it after Control's walk, in the order Contacts →
Calendar → Fit — Contacts first because the name its *You* screen types is the realm owner's
(`OwnerPreferences.Name`), which Fit's Profile shows too. The fixtures are the iOS run's own
generators (`store/fixtures/<app>/generate_<app>.py`, dated for the day of the run), so both
platforms show the same invented person, calendars and address book. Every file goes into the
emulator's `/sdcard/Download` by `adb push` and is picked in the system picker (DocumentsUI →
*Downloads*) by taps; nothing uses a deep link or an intent the walk crafts.

**Contacts** — *+ → Import contacts → Import a .vcf file*, then `01-contacts` … `06-you` as on iOS
(Amara Okafor, *Lumen*, the Priya pair, *Alex Rivera*), plus `07-invite`: *You → Invite someone*,
a real one-time invitation on the dev relay (no second device needed), **revoked** straight after
the capture. ⚠ Android's merge dialog has *Cancel*, which the walk taps — it never merges.
⚠ The pair row's text contains double quotes, and `uiautomator dump` then writes the attribute in
**single** quotes (`text='"Priya R." and "Priya Raman"'`); the walk's parser reads both forms — a
parser that reads only `"…"` sees no such row. The app orders the pair itself (*Priya R.* first),
so the walk matches it by both names, not by a spelled-out string.
⚠ What it honestly shows: the *You* screen's *"Your identity code and invitation link are not
available on this device yet"* — true of the identity code (no client-readable own-identity op),
**no longer true of the link** on Android, which `07-invite` shows working. A wording defect, left
for a copy pass rather than fixed in a screenshot branch.

**Calendar** — Personal is the app's own (`ensureDefaultCalendar`); the walk makes the other four
through *Calendars → New calendar* (name typed, colour tapped — the swatches carry no label or
description, so it picks by **palette index**, `CALENDAR_PALETTE` mirrors `CalendarPalette.COLORS`),
then fills each of the five from its own file through its row's *More for <name> → Import or
export… → Import from a file*. The captures: month (today selected), agenda, the dentist opened,
a new event typed in and saved, the calendars list, Work's *Edit calendar* sheet, and Android's own
`07-week` / `08-day` time grids (reserved for Android under `planned`; iOS has no time grid).
- ⚠ `03-event` is the **editor** (*Edit event*): Android has no read-only event detail; tapping an
  event opens it for editing. Same number as iOS's detail, because it is the same step — the event
  opened from the agenda — and the support pages' slot then fills on both.
- ⚠ No reminders step: Android's Calendar asks for no notification permission (Control owns the
  alarms), so there is no *Turn on reminders* row to answer, unlike iOS.
- ⚠ Close the keyboard before tapping *Location*: the field sits under it, and a tap on its label
  once landed on the keyboard's comma key and typed the location into the title.
- ⚠ Weeks start on **Monday** on the emulator; the fixture's `weekday`/`week` dates are computed
  Sunday-first (§3b), which moves nothing visible.

**Fit** — two halves, as on iOS:
- **Workouts**: the GPX files zipped into *Fit workouts.zip* (the shape of a Takeout or watch export)
  and imported through *Profile → Google Takeout → Import again*, which takes a zip or one activity
  file. Routes, elevation and heart rate come from the shipping parser; the map is the bundled
  Florence basemap Control serves.
- **Daily figures** (steps, distance, energy, sleep stages, resting heart rate, weight, height,
  nutrition, water): **Health Connect**, read through Fit's shipping import — the read-consent sheet
  Fit opens on launch (*Allow all*, *Allow*), then *Profile → Health Connect → Import your full
  history* and Health Connect's *Allow Fit to access past data?* (the fixture is six weeks; Health
  Connect shares 30 days without that grant, and the one height sample is the oldest record).
- ⚠ That full-history import is the walk's standing check of the out-of-band stream path: after it
  (~3,650 rows) one of Fit's list streams is a **1.35 MB** initial emission, over one Binder
  transaction, which Control now sends through `SharedMemory` (Control's log:
  `AndroidClientConnection: callback(app.tenshu.fit.android): 1350918 bytes over the 131072-byte inline
  limit`). Before that fix (`core-client-resubscription.md` §4.8, 2026-10-23) it failed with
  `TransactionTooLargeException`, took Control's whole callback channel to Fit down with it, and every
  workout's detail said *Couldn't load this workout* — so the walk had dropped this step for a day.
- ⚠⚠ **The one hook, and why there is one.** Health Connect has no way in from adb — its shell
  (`cmd healthconnect`) inserts only a canned test step record — and Fit never writes to it. So the
  **debug** build carries `StoreFixtureSeedActivity` (`fit-android/src/debug/`, absent from release),
  the twin of fit-ios's `-seedFixture`: the walk writes `health.json` into the app's own files with
  `run-as`, starts the activity, answers Health Connect's **write** sheet, and waits for its one line
  (`seed: OK N records`), which is never captured; then it stops Fit and launches it normally. The
  workouts never pass through it. The debug manifest declares the WRITE permissions it needs.
- ⚠⚠ **Write access through Health Connect's sheet, never `pm grant`.** Granted with `pm grant`, the
  records were written and Fit's Health Connect import still read `steps=0 distance=0` for every
  day: Health Connect's aggregates count only the apps on its per-type data-source priority list,
  and the consent sheet is what puts a writer there.
- ⚠ `run-as`, not the app's external files directory: a folder `adb push` makes under
  `Android/data/<package>` belongs to the shell, and the app cannot read it.
- ⚠ **Run it after 07:45 local time, and never across midnight.** The generator refuses a fixture
  before today's run has ended (§3a), and a run that starts before midnight walks Fit after it with
  yesterday's fixture: Home's day reads 0 and the walk times out waiting for the morning's run (seen
  2026-10-22, the first full run). Fit goes last in a run, so start a three-app run early enough.
- ⚠ The walk waits for the Takeout import's **history row** (the *No imports yet* line going), not
  for *Importing…* to disappear — that holds before the import has started. And it taps *Import
  again* only once no import is running: the button is disabled during the full-history one.
- ⚠ Fit's Profile shows *Name* only because the Contacts walk ran first (a `fit`-only run reads
  *Not set*).
- `01-home` is Home at its top (the ring, the day's figures and the first week card) — Home is
  one scrolling page with four week cards, so the morning's run sits far below, and
  History shows it; `07-profile` is scrolled so *Data sources* and *Batch import* both
  show. Energy on a file workout is "—": a GPX carries none, and nothing estimates it.

### 8.2 Timeline, Maps, Home and Mia — the Play listings' captures

`scripts/store-screenshots-android.sh timeline maps home mia` (any subset; light, dev lab only, as §8.1).
These four have no support pages; their captures exist for their Google Play listings
(`store-listings.md` §8) — before 2026-11-05 those were mostly empty screens under a *Not syncing*
banner, from a hand run nobody could repeat. Their `screens.json` carry an `android` list like the
others; Home and Maps have no iOS walk, so theirs say `"platform": "android"` with an empty iOS
`captured`, and `screens.test.ts` reads every `screens.json` (not only the support pages' apps) when it
checks that the walk shoots nothing unlisted.

**Data an app reads from another.** Maps shows Timeline's places and Mia answers from Drive's and
Calendar's rows, so `maps` also walks Timeline and `mia` also walks Drive and Calendar
(`needs` in the script). A walk run only for its data is walked in full and its captures are not
copied out, so `mia` alone never replaces Drive's or Calendar's published set.

**Timeline** — the iOS run's fixture (§3f, `generate_timeline.py`: two weeks in Milan, then three
days in Florence ending today), zipped as a Takeout archive (`Takeout/Location History
(Timeline)/Semantic Location History/<year>/…`) and imported the way a user does: *Settings → Import
from Google Takeout → Choose file…* and the system picker. No hook — Android has the import iOS lacks.
The result is a line under the button (*Imported 270 entries from 2 timeline file(s)…*) and *Done*;
place-name lookups stay off (every fixture place is named). ⚠ Same rule as iOS: **after 14:00 local
time** (`notBefore`), or the generator refuses. Order matters: `04-walk` (today's 09:25 walk) is opened
from the top of the feed **before** the trip — the same walk recurs on other days, so it is never
searched for further down; the accepted trip is the *BOUND CHAPTER* card after the last day (*Open
chapter →*); Places lists the oldest last visit first, so `05-places` is the list scrolled to its
Florence end.
- **Fixed (2026-11-05):** the Places line was a hard-coded *"N visits · last …"* — *1 visits*, and
  English on a German capture. It is now `timeline_place_visits_one/other`, with German.
- ⚠ **Open:** the trip detail offers *Keep Firenze (test) offline* — production's region catalog
  (`deploy/tiles/regions.json`) names its Florence region *Firenze (test)*. And a movement's detail
  ends with *CAPTURED AUTOMATICALLY · tenshu://timeline/g-move:…*, a raw id, and wrong for an import.
  Both are on the captures because they are on the app; the listing's set leaves out `03-trip`.

**Maps** — ⚠ Maps cannot make a place: search, *Add a place*, *Save* and the detail's *Edit /
Directions / Share* do nothing yet, and the listing claims none of them (`store/maps/claims.md`).
Its places are Timeline's (one `placeService`), so its walk runs after Timeline's import and captures
*Your places* and one Florence place's detail — the only map in the app, drawn from the basemap
bundled with Maps (central Florence). No map of every place exists to capture.
- ⚠⚠ **Walked, not published (2026-11-05).** What it shows is not listing material: the top bar
  draws under the status bar (no inset), every place reads *No visits yet* / *0 visits* with a
  *Store* icon although Timeline counts its visits — a false claim on a listing — and the detail
  carries a developer note (*"This is the canonical place detail…"*) and *Keep Firenze (test)
  offline*. Maps' `listing.json` keeps its `local` set until those are fixed.

**Home** — Home's devices are rows a **Tenshu Hub** writes into the realm; with none, Home shows *No
devices yet* and an automation cannot be saved (a schedule or sun trigger still needs a device action).
So the run starts the desktop Hub (`:hub:installDist`) on the dev lab with its simulated devices
(`--simulated-devices`, `hub-pi-deploy.md`):
1. `tenshu-hub provision` into a data dir under the run's results, then `tenshu-hub run`, which arms
   the printed-label pairing and waits.
2. Control's walk, still in its one foreground session, pairs it as a user pairs a Hub from its label:
   *Add a device → Scan a code → Enter code instead*, the printed `tenshu://hub?…` URI typed into
   *Pairing Code*, *Continue*, *Approve*. (The emulator has no camera to scan the label with.)
3. Home's walk: the devices (the lamp switched on and dimmed by Home's own controls — the command goes
   to the Hub and its new state comes back), then two automations made in Home's editor (*Lights
   out* at 23:30; *Evening lights* 15 minutes before sunset, captured in the editor before *Save*).
The Hub is stopped when the walks end.
- ⚠ **The devices are simulated, under ordinary names.** *"Simulated light"* on a store listing reads
  as a fake, so the run passes `--simulated-device-name light=Living room lamp` (and `sensor=Bedroom
  climate`, `bridge=Device bridge`) — a Hub launch option that changes only the name a row is first
  announced with: same ids, kinds and capabilities, no schema or wire change, and the default names
  stay. The lamp really switches and dims and the sensor's figures really drift; nothing on screen
  is drawn by a fake.
- *Hub storage* is the Mac's own disk as the desktop Hub reports it (*N GB free of 494 GB*).
- ⚠ Home's *Add automation* button exposes no text to `uiautomator dump` (its *Add device* twin does),
  so the walk taps the clickable node right above the bottom bar (`tap_fab`).
- ⚠ The editor's time field starts filled (*20:00*): typed into, it reads *20:0019:30*. The walk
  empties it first (`replace_field`).
- The Hub runs as a release-style JVM build, so it logs the dev lab's entitlement as *UNVERIFIED*; it
  keeps syncing (the relay is what checks the plan), and nothing on Home's screens depends on it.

**Mia** — the iOS walk's questions (§3g) into the realm Drive's and Calendar's walks filled: *Show me
my tax files*, *Photos from July*, *What's on my calendar next week?*, then History. No `05-workouts`
(it would need Fit's walk too; the iPad has none either). Each answer must be a citation list — a
*"— nothing matched."* fails the walk.
- ⚠ **Enter does not send**: the field is multi-line, so the walk taps *Send*, after checking the
  field holds the whole question.
- ⚠ A long answer pushes its question off the top, so the walk scrolls back just far enough to show
  the question's bubble (`mia_reveal`, the iOS `revealQuestion`).

⚠⚠ **The issuer check.** A Control that does not trust the dev lab's issuer (`tenshu.commerce.dev`,
compiled in with `-Ptenshu.extraIssuer`) reads the realm's Free plan as *unverified*, and every
client then shows *"Not syncing — this device has no storage plan"* while Control's own Services card
says *Plan: Free* — the banner sits on every capture and nothing fails. So after Control's walk the
script reads Control's start-up line (*"Entitlement trust: publishers=[…] (build-configured: …)"*,
from `initEntitlementStorage`) and refuses the run without it. It reads it from a **16 MB** log ring
it sets at boot: the image's UWB and NFC HALs crash-loop and roll the default ring over in minutes.
Diagnosed 2026-10-22 from Control's store (`tenshu/result/error/entitlementUnverified` error events,
read with `run-as`).

⚠⚠ **`--keep-emulator` leaves an emulator other sessions can take.** With its own emulator gone,
another session's `android-smoke.sh` installed its own eight apps — including a Control built
without the dev issuer — onto a kept `emulator-5590` (2026-10-22). A scripted run is safe (it holds
the gate lock the smoke gate also takes); iterating by hand on a kept emulator is not. If Maps, Home
or Timeline appear on it, it is no longer your state.

Product defects these walks found and fixed (2026-10-22/23): Fit dropped a picked file whenever it
raced the Health Connect refresh that coming back from the picker starts (`importFiles` stepped
aside on a held import lock; it now waits) — on the emulator that was nearly every pick; Fit's Browse kept *"Connect a data
source"* up for good on Android (Medical, which no Android source fills, counted as a gap — iOS had
fixed it for iOS only); Calendar's event editor crushed the fifth calendar chip to nothing on a
phone, so that calendar could not be chosen, and wrapped *Yearly* onto two lines (both rows now
wrap); and the Calendars list had a status-bar-high blank band under its top bar (a nested
Scaffold took the insets twice).

## 9. The landing page's content set — stock photos

The landing page's Apps carousel ([`web-about-tenshu-public.md`](../proposals/web-about-tenshu-public.md)
§9) shows richer screens than the support pages: a realm full of real photographs, not labelled
gradients. They are a **separate content set** — a separate realm, separate captures, separate keys
— so the support pages keep their plain synthetic captures, which match their step-by-step text.

```bash
scripts/store-screenshots-android.sh --set landing --appearance both   # Drive + Photos, light then dark
scripts/store-publish.sh drive photos --dry-run && scripts/store-publish.sh drive photos   # then --verify
```

```
store/<app>/screenshots/android/phonelanding/en/NN-<screen>.png        light
store/<app>/screenshots/android/phonelanding/en/dark/NN-<screen>.png   dark
```

- **The keys.** `phonelanding` is a *device* segment, so the keys are the ordinary
  `<platform>/<device>/<lang>/[dark/]NN-screen` form (§7) — `MANIFEST_KEY_RE`,
  `check-store-assets.py` and `store-publish.sh` needed no change — and the support pages, which read
  `phone`, never show one. A landing set's numbers are its own: `store/<app>/screens.json` lists them
  under `landing.captured`, and the walk captures them with `landing_shot("NN-…")`, which
  `screens.test.ts` checks against those lists. The carousel names them as
  `{ screen, landing: true }` in `LANDING_SCREENS` (`web/apex/src/landing/carousel/landingApps.ts`).
- **What it captures.** Drive: `01-files` (the root of a plausible Drive), `02-photos` (*Dolomites
  2026* as a grid of real thumbnails), `03-preview` (one photo). Photos: `01-library` (the default
  mosaic, grouped by day), `02-album` (*Autumn walks*, made through Collections → *New album* and
  filled with its twelve photos from the viewer's More → *Add to album*), `03-viewer` (one photo full screen). Photos reads
  every image in the realm, so the Drive walk's uploads are its library — it is walked after Drive,
  on the same realm, with nothing seeded of its own.
- **iOS** (2026-10-17): `scripts/store-screenshots.sh --set landing --appearance both` — the same recipe
  seeded through Drive's `-seedFixture` launch (§2), then `DriveLandingScreenshots` (`01-files`,
  `02-photos` as Icons, `03-preview` of `IMG_5102.jpg`) and `PhotosLandingScreenshots` (`01-library`,
  `02-album`, `03-viewer` of the newest photo, `IMG_7424.jpg`) on the same simulator, keyed
  `ios/iphone69landing/<lang>/[dark/]NN-screen` (1320 × 2868). Photos' tests are their own target and
  scheme (`PhotosIos-Screenshots`, `Debug` — never the `-photosFixture` mock). The album is made as on
  Android, through Collections → *New album*, but filled from a library selection (long press, taps,
  *Add to Album*) rather than twelve trips through the viewer's menu: both are a user's path. The
  names are `landing.ios.captured` in `store/<app>/screens.json`, checked against the tests'
  `landingShot("…")` calls and against the Android landing list by `screens.test.ts`. iPhone only, dev
  lab only; Control is onboarded for the realm and not exported. ⚠ The 55-file seed has stalled mid-tree and never
  resumed (twice on 2026-10-17/18, on a heavily loaded machine; once it took two minutes), so the
  test gives each `-seedFixture` launch ten minutes and relaunches a stalled one — the seed skips
  every path already in the realm. ⚠ After a failed test `xcodebuild test-without-building` has
  hung in tear-down for hours; if a run sits still with a `failed (` line at the end of its
  `.xcresult.log`, kill that xcodebuild and the script carries on. ⚠ Every Drive capture (both
  walks) first waits up to five minutes for the sync-activity capsule (*Uploading…*) to be gone for
  3 s, and fails rather than capture it: the seed's bytes were still uploading in every dark Drive
  capture on 2026-10-23.
- **Maps is not captured** (2026-10-17): the Android Maps app has no map of saved places to show —
  its only map is the place detail's snippet, nothing in it creates a place (search, *Add a place*
  and *Save* are not wired), places come only from Timeline's capture or a Takeout import, and its
  tiles are streamed from production's tiles host, which a debug build cannot repoint
  (`ProxyModule` `tilesHostUrl`). A Maps landing capture waits for a place-creating flow.

### The recipe — `store/fixtures/landing/manifest.json`

The same generator and the same entry shape as §3, so it is **platform-neutral**: `generate.py
landing <out>` builds the tree for the Android walk here and for the iOS one (`-seedFixture`) alike.
48 photos from **Unsplash**, each entry carrying the photographer (name and handle), the licence
(*Unsplash License*), the photo's page, and a `fetch` of **Unsplash's own image URL** with a fixed
size (`?w=2400&q=85&fm=jpg&fit=max`) and the sha256 of those bytes; the documents beside them are
generated placeholders. The photos are dated three to a day: the Photos mosaic gives a day of three
one large tile and two small ones, a full block, where a day of one or two gets no large tile and a
day of four leaves a lonely tile. ⚠ No top-level folder may be called *Documents*: the walk pushes
the tree into the emulator's Documents, and picking a folder of that name picks the whole tree (seen
2026-10-17: every photo twice).

- ⚠⚠ **No photo is in git, and none is hosted by us.** The recipe points at the licensor's host,
  never at a copy: a list of Unsplash URLs is not a redistributed collection, which is what §3.3 of
  the design forbids (a public copy per photo). That is why these entries need neither the private
  bucket `tenshu-store-fixtures` — which does not exist yet (`enable_fixtures_bucket = false`) — nor
  R2 keys: any machine with network can build the set. A photo whose source is *not* stable or
  public still goes to the private bucket (§3).
- **A changed file fails the run.** If Unsplash ever re-encodes a size, the hash no longer matches and
  `generate.py` refuses it. Check the new bytes are the same photo, then re-pin. It has happened:
  on 2026-10-23, 41 of the 48 URLs served new bytes (stable across fetches) six days after
  pinning. The re-pin was checked by content, not by trusting the URL: imgix's `?fm=json` on the
  same photo id gives the original's dimensions, which the new rendition matched; every new image
  was looked at against the published landing captures (the 21 shown there) or its page slug and
  author (the rest); and the Android Drive capture's file sizes for *Dolomites 2026* agree with the
  new bytes to ~0.1 KB, a re-encode, not a new photo. Again on 2026-10-31: 18 of the 48, checked the
  same way (each against its page slug on a contact sheet; the four *Dolomites 2026* ones also against
  the sizes the published grid capture shows). ⚠ So expect it: a landing run months after the last pin
  will usually stop here first. Unsplash's photo pages sit behind a bot wall,
  so the licence check is the image still being on `images.unsplash.com`, not `plus.unsplash.com`.
- **Choosing a photo.** Unsplash or Pexels only, under their own licence (not Unsplash+). **No
  recognisable person, no logo or brand, no legible sign, no artwork** — and no famous building in a
  country without freedom of panorama. Look at it full size before adding it, and record author and
  page even though neither licence requires attribution. Never sell or redistribute the set.
- **Adding one.** Append an entry (download the sized URL once, `shasum -a 256` it), keep the day
  groups at three or more, run `generate.py landing --dry-run`, then the walk.

