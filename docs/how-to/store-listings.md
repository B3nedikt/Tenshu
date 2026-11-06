# Store listings — the App Store text, pushed from the repo

The App Store listing of each app is files in `store/`, reviewed like code and pushed to App Store
Connect with fastlane's `deliver`. App Store Connect is an output: change the files, not the console.
This is decision D7 and phase S2 of
[`store-presence-and-support-pages.md`](../proposals/store-presence-and-support-pages.md) (§2), for
Control, Drive, Fit, Calendar, Contacts and Photos on iOS, in English and German (`de-DE`, 2026-10-28). The framed screenshots (§6 below) are the groundwork of S7.
Taking an app from a TestFlight build to a submission, with everything that is not a file here,
is [`app-store-release.md`](app-store-release.md).

```bash
scripts/store-push.sh drive --dry-run      # check, assemble, fetch screenshots, render the preview — writes nothing
open build/store/deliver/drive/ios/Preview.html
scripts/store-push.sh drive                # push exactly what the preview showed (clean tree only)
```

## 1. Setup, once per Mac

- **Ruby ≥ 3.2.** fastlane 2.241 needs it, and macOS's own Ruby is 2.6: `brew install ruby`, then put
  Homebrew's Ruby first on `PATH` (`/opt/homebrew/opt/ruby/bin`).
- **`bundle install`** in the repo root. `Gemfile.lock` pins fastlane and every dependency; it lists
  `arm64-darwin`, `x86_64-darwin` and `x86_64-linux`. ⚠ It was written by Bundler 4.0; an older
  Bundler offers to install the matching one — let it. To move fastlane: `bundle update fastlane`.
- **The App Store Connect API key** — the same one `ios-archive.sh --upload` uses
  ([`ios-testflight.md`](ios-testflight.md) §2.1): `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`.
  fastlane reads them through its `app_store_connect_api_key` action. The preview needs none of them.
- **The apps must exist in App Store Connect** (`app.tenshu.<app>.ios`). The record is made by hand in
  App Store Connect's web UI ([`app-store-release.md`](app-store-release.md) §3) — neither `deliver` nor
  `ios-archive.sh` can create one ([`ios-testflight.md`](ios-testflight.md) §2). ⚠ The pushed apps'
  records all exist (2026-10-24: Fit, Calendar and Contacts pushed, Photos' record made; see
  [`app-store-release.md`](app-store-release.md) §2). A push fails for an app whose record does not
  exist yet. The dry run needs no record.
- Optional: `export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8`, or fastlane warns about the locale.

## 2. The files

```
store/
  shared/copyright.txt                    "2026 Cajetan UG (haftungsbeschränkt)", every app
  shared/en-US/privacy_url.txt            every app — the page is web/apex/support-pages/privacy.ts (§5)
  shared/en-US/marketing_url.txt          https://tenshu.app
  <app>/en-US/name.txt                    "Tenshu Drive"                     30 characters
          subtitle.txt                    "Encrypted file storage"           30 characters
          description.txt                 the long text                      4000 characters
          keywords.txt                    one comma-separated line           100 BYTES (UTF-8)
          promotional_text.txt            changeable without a release       170 characters
          release_notes.txt               What's New                         4000 characters
          support_url.txt                 https://tenshu.app/apps/<app>/support/ios/en
          captions.txt                    one line per screen, for framed screenshots (§6)
  <app>/primary_category.txt              App Store category id (UTILITIES, PRODUCTIVITY, HEALTH_AND_FITNESS, PHOTO_AND_VIDEO)
  <app>/secondary_category.txt            optional, as in App Store Connect; never the primary again
  <app>/age_rating.json                   the age rating questionnaire, deliver's app_rating_config_path
  <app>/review/notes.txt                  App Review notes — how to reach the screens; Drive needs Control
  <app>/claims.md                         the source of every privacy sentence in the description
  <app>/listing.json                      bundle id, store locale → manifest language, the listed screenshots
  <app>/privacy-label.md                  ⚖️ the App Privacy answers, typed into the console by hand (§5)
```

**Pushed and copy-only apps.** Control, Drive, Fit, Calendar, Contacts, Photos, Timeline and Mia are *pushed*
(`PUSHED_APPS` in the script): every file above is required but the secondary category. Maps is
*copy-only* for now (`COPY_ONLY_APPS`): it has the `en-US/` copy, `claims.md`,
`privacy-label.md` and review notes, but not every pipeline file (`listing.json`, `age_rating.json`, the
primary category, published App Store screenshots). `check` holds their copy to every rule below and
checks any pipeline file that does exist; `store-listing.py pushable`, `assemble` and `screenshots`
refuse them, naming what is missing, and `store-push.sh` asks `pushable` before anything else.
⚠ Timeline's and Mia's text, age rating, categories and copyright were written to App Store Connect
through the API from these files (2026-10-30), ahead of their screenshots — a later `store-push.sh` of
the same files replaces them with the same values. Mia moved to `PUSHED_APPS` with its screenshots
([`store-screenshots.md`](store-screenshots.md) §3g); its iPad set has no `05-workouts`. An app
moves to `PUSHED_APPS` when its pipeline files are in. ⚠ **Photos'** `listing.json` names its App Store
set (`ios/iphone69`, `ios/ipad13`, [`store-screenshots.md`](store-screenshots.md) §3d); its landing
page's captures (`ios/iphone69landing`, the same doc's §9) are **not** App Store screenshots and are never used as a
fallback. Photos, Maps and Timeline's `claims.md` also carries a hand-reviewed table of feature claims and what
was left out because the iOS app does not do it — re-read it when the app gains a feature. ⚠ Their
support URLs (`tenshu.app/apps/<app>/support/ios/en`) resolve for Photos once the apex image carrying its pages is deployed; Maps' and Timeline's do not exist yet.

An app's own file wins over `store/shared/`'s of the same name. The file names are deliver's own, so
`scripts/store-listing.py assemble` only copies them into one folder: deliver refuses to read
`store/<app>/` directly, because it holds folders that are not languages.

**`scripts/store-listing.py check`** enforces it, and runs in the pre-commit hook (`.githooks/pre-commit`,
whenever `store/` is staged), at the start of every `store-push.sh`, and in web CI:

- every limit above, counted as App Store Connect counts (keywords in bytes; deliver strips each value,
  so a trailing newline is free);
- the name is "Tenshu <App>" (§6.1), the support URL is the D2 one, the URLs are `https://`;
- **the copy rules (D6):** no superlative from a list (*best*, *most secure*, *military-grade*,
  *unbreakable*, …) and no competitor (*iCloud*, *Dropbox*, …), in any text or caption. A word list
  catches the claims that are wrong on their face, not tone — that is still review's job;
- **every privacy sentence has a source.** Each sentence under the description's `HOW … PRIVATE`
  heading must be quoted verbatim in `store/<app>/claims.md` with a `- source:` that exists (design
  §4.4: a claim with no source is not published). Editing a privacy sentence therefore means editing
  its claim, which is where a reviewer sees the source next to it. ⚠ A threat-model amendment that
  weakens a claim has to change the listing too (House Rule 2);
- categories, the age rating answers' names and types, and `listing.json`'s screens against
  `screens.json`;
- the review folder holds `notes.txt` only. The review **contact** (name, phone, email) is personal
  data: it comes from the Keychain and is pushed with `store-push.sh --review-info` only (§5).

Tests: `python3 scripts/test_store_listing.py`.

**Copy voice.** Plain, short sentences, second person, no superlatives. The privacy wording follows
the reviewed support pages (`web/apex/content/apps/<app>/support/en.md`), so the listing and the page
make the same promise. Control's description says first that the other apps need it; Drive's says it
needs Control, and its review notes tell App Review to install Control first.

⚠ **Release notes on 1.0.** App Store Connect has no What's New for an app's first version, and deliver
drops `release_notes.txt` while only one version exists. The file is ready for the second.

## 3. The preview — `--dry-run`

`scripts/store-push.sh <app> --dry-run`:

1. runs `check-store-assets.py` and `store-listing.py check`;
2. assembles `build/store/deliver/<app>/ios/` (gitignored): `metadata/`, `age_rating.json`, `bundle_id`;
3. fetches the listed screenshots (§4) into `screenshots/en-US/`;
4. renders **deliver's own HTML preview** (`Deliver::HtmlGenerator`, the page `deliver` shows before an
   upload) from those files, as `Preview.html`, and writes `preview.stamp`, a sha256 over every file.

It needs no credentials and never contacts App Store Connect: the name and languages come from the
files. The only network is the public screenshot URLs. `--no-screenshots` skips them.

**What fastlane offers, and why this one.** `deliver --verify_only` validates a *binary*, not metadata.
`precheck` reads the listing *already in* App Store Connect, so it needs the key and checks the old
text, not ours; deliver runs it after a push anyway (below). The HTML preview is the one check of the
local files, so the dry run renders it without logging in.

## 4. Screenshots: R2 → a local folder, by sha256

deliver uploads local files; the images live in R2 ([`store-screenshots.md`](store-screenshots.md) §7).
`store-listing.py screenshots` takes `listing.json`'s screens, **in its order**, for each device in
`devices` (`iphone69`, `ipad13`), looks each up in `store/<app>/screenshots.manifest.json`
(`ios/<device>/en/NN-screen`; `ios/<device>framed/…` when `framed` is true), downloads
`https://assets.tenshu.app/sha256/<sha256>.png` into `build/store/screenshot-cache/`, **fails unless the
bytes hash to the manifest**, checks the size is one App Store Connect accepts for the slot, and copies
it to `screenshots/en-US/<device>-NN-<screen>.png`. deliver sorts each slot by file name, so `NN` is the
listing order. It then prints, per device, how many of the listed screens it found, and:

- **no `iphone69` screenshot at all: it stops.** App Store Connect requires the 6.9" slot, and no other
  set (landing, framed when `framed` is false) stands in for it. `--no-screenshots` pushes the text alone;
- **no `ipad13` screenshot at all: one warning**, not one per screen — every app builds with
  `TARGETED_DEVICE_FAMILY` "1,2", so a submission needs the 13" iPad slot, and a push
  (`overwrite_screenshots`) removes any iPad set uploaded by hand. Today that is every app;
- a gap *inside* a set (some screens published, some not) is a warning per missing screen.

deliver files 1320 × 2868 and 1290 × 2796 under `APP_IPHONE_67`, and 2064 × 2752 and 2048 × 2732 under
`APP_IPAD_PRO_3GEN_129`: those are App Store Connect's display types for the 6.9" iPhone and 13" iPad
slots. It decides by pixel size alone (`Deliver::AppScreenshot.calculate_display_type`; all four checked
on fastlane 2.241.1, 2026-10-23), so an `ipad13` capture reaches the iPad slot with no extra setting.
The preview labels the iPhone slot "iPhone 14 Pro Max", deliver's name for `APP_IPHONE_67`.
⚠ The preview does not show the categories or the age rating; check those in
`build/store/deliver/<app>/ios/metadata/*_category.txt` and `age_rating.json`.

The listing shows **light** captures only; dark ones are for the support pages
([`store-screenshots.md`](store-screenshots.md) §1b).

## 5. The push

`scripts/store-push.sh <app>`:

- **refuses a dirty tree** (as `ios-archive.sh --upload` does), so App Store Connect always holds a
  commit;
- needs `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`; takes the version from `MARKETING_VERSION` in
  `ios-release/release.yml`;
- re-assembles and re-fetches, then compares the sha256 with `preview.stamp`. **Only when they match
  does it pass `force: true`.** Otherwise deliver renders its preview itself and asks before it writes;
- runs `deliver` with `skip_binary_upload: true`, `submit_for_review: false`,
  `automatic_release: false`, the age rating file, and `overwrite_screenshots: true`.
  ⚠ **Overwrite replaces every screenshot set of the pushed language**, iPad included. If the iPad
  screenshots were uploaded by hand and are not in the manifest yet, push with `--no-screenshots`;
- deliver then runs `precheck` on what is now in App Store Connect (read-only, warnings only;
  `precheck_include_in_app_purchases: false`, because it cannot read in-app purchases with an API key).

It never uploads a binary (that is `ios-archive.sh`), never submits for review and never releases.

### What stays manual in App Store Connect

| | Why | Source of the answer |
|---|---|---|
| **App Privacy** (the privacy label) | no API with an API key (fastlane's action logs in with an Apple ID) | `store/<app>/privacy-label.md` ⚖️ |
| **Pricing and availability** | deliver's `price_tier` is deprecated by Apple's new pricing; free, set once | — |
| **App Review contact** (name, phone, email) | **pushed with `--review-info` only, never in this repo**: once per app (and again after a notes edit), `store-push.sh <app> --review-info` reads `ASC_REVIEW_FIRST_NAME`/`_LAST_NAME`/`_EMAIL`/`_PHONE` (phone as `+<country> …`) or the Keychain (account `tenshu`, services `asc-review-first-name`/`-last-name`/`-email`/`-phone`) and refuses without all four — App Store Connect rejected review notes with no contact (observed 2026-10-27, Calendar). Apple-only, never shown on the product page. ⚠ deliver prints them in its option summary, so keep push logs local. ⚠⚠ deliver sets **Sign-in required OFF** whenever it writes these details without a demo account, so a plain push (no `--review-info`) leaves App Review Information untouched, and after any `--review-info` push the **demo account** (username `commerce-review-address-apple`, password `commerce-review-signin-code-apple`, both Keychain) has to be entered by hand again. App Store Connect is expected to carry it over to each new version (not yet observed) | Keychain; [`app-store-release.md`](app-store-release.md) §5 for the notes and the demo account |
| **Content rights**, **export compliance** | console questions; export compliance is ⚖️ undecided ([`ios-testflight.md`](ios-testflight.md) §6) | — |
| **The privacy policy page** | built: `https://tenshu.app/legal/privacy` (the stores' URL; a 302 by Accept-Language to `/en`, `/de` — binding — or `/fr`) from `web/apex/support-pages/privacy.ts`, published 2026-10-23 ⚖️ **not yet counsel-reviewed**; live once the apex image carrying it is deployed (design §1.4) | `pre-release-checklist.md` §1 |
| **Age rating** | file-driven (`age_rating.json`), not manual — but its answers are a judgement; see below | `store/<app>/age_rating.json` |
| **Category, copyright** | file-driven | `store/<app>/*_category.txt`, `store/shared/copyright.txt` |
| **Header and Search Results** (the iOS 27 creative asset) | fastlane deliver 2.241 has no field for it; upload by hand until fastlane supports it (§6a) | `scripts/store-creative-assets.sh` → `build/store/creative/<app>/header-16x9.png` |

⚠ **Age rating judgement calls.** Every app answers every question *none* / *no*, except Fit's
`healthOrWellnessTopics`. The ones to reread:

- `userGeneratedContent` is `false` for Drive. A share link publishes a file to anyone holding the link,
  but it opens in a web page, not in the app, and inside the app a user only sees their own realm's
  files. If App Review reads share links as user-generated content, the answer flips and guideline 1.2
  (reporting and blocking) applies.
- `userGeneratedContent` is `false` for **Photos** too, for a stronger reason: Photos shows only the
  user's own realm's photos and has no share action at all on iOS (`photos-ios/Sources/FeedSelection.swift`).
  Apple's definition is "broad distribution of content created by users"; a private library is not that.
  Likewise its `sexualContent…` answers rate what the app *provides*, not what a user stores.
- **Fit: `healthOrWellnessTopics` is `true`** — check the rating App Store Connect computes from it;
  how Apple's 2025 tiers map this answer is not verified here. Apple defines it as "self-care or
  lifestyle recommendations", naming *calorie tracking* and *exercise recommendations*. The app shows
  food energy (Browse → Nutrition, kcal per day, from Apple Health) and preset daily goals (30 active
  minutes, 8,000 steps). The rating describes the app, not the screenshots, so it stays `true` although
  the listing shows neither. A fitness tracker answering *no* is the likelier rejection.
- **Fit: `medicalOrTreatmentInformation` is `NONE`.** Apple's definition is "diagnoses or guidance
  around the management of medical conditions"; Fit shows the user's own measurements and gives no
  diagnosis, score or advice (the description says it is not a medical device, `store/fit/claims.md`).
  ⚠ Browse shows a **Medical** card (medications, immunizations, allergies, conditions), on iOS always
  "No entries yet", since nothing can fill it. Like the calorie figures, `claims.md` leaves it out of
  the copy because it invites a 2.3 / 5.1.3 reading, so the listing leaves Browse (`05-browse`) out of
  its screenshots too. It would only ever display imported records, not guidance; if App Review reads it
  otherwise, the answer becomes `INFREQUENT` (the 2025 questionnaire's "Infrequent"; the API also takes
  the older `INFREQUENT_OR_MILD`, and `check` accepts both), or the card is hidden on iOS.
- `socialMedia` is `false` for every app (no feed, no profiles others browse). `socialMediaAgeRestricted`
  is asked only when it is `true`, so the files leave it out and `check` refuses it otherwise; deliver
  sends the file's keys as they are. The optional fields (`kidsAgeBand`, the overrides,
  `developerAgeRatingInfoUrl`) are not set.
- Contacts' invitation is an identity exchange, not messaging: `messagingAndChat` is `false`.

## 6. Framed screenshots — `scripts/store-frame`

Design §3.2: Apple's own product bezels, composited by our renderer, not fastlane `frameit`. The
renderer is `web/store-frame` (TypeScript, Chromium through playwright-core): an HTML template with a
caption from `store/<app>/<locale>/captions.txt`, the capture placed into the bezel's transparent
screen area, the background and text colour from apex's tokens (`web/shared/tokens.css`, light).

```bash
pnpm -C web install
pnpm -C web --filter @tenshu/store-frame exec playwright-core install chromium   # once per machine
scripts/store-frame check                                  # which frames are ready
scripts/store-frame render drive --device iphone69 --lang en
scripts/store-publish.sh drive                             # publishes them as ios/iphone69framed/en/…
```

**The bezels are never in the repo.** Apple's licence lets us show our app in them, not redistribute
them, and this repo is public. `store/frames/manifest.json` names each: Apple's download, its file
name, the licence note, its `sha256` and `fetch` URL, its screen area (`x`, `y`, `width`, `height`,
`cornerRadius`, in the bezel's own pixels) and the output size. Until the maintainer downloads them,
`sha256`, `fetch` and the screen area are `null`, and `render` stops with what to do.

Setting a frame up, once:

1. ⚖️ Read Apple's current Design Resources terms. Download the product bezel (e.g. *iPhone 17 Pro Max*,
   portrait) from [developer.apple.com/design/resources](https://developer.apple.com/design/resources/).
2. Put the PNG in `build/store/frames/` (gitignored; `STORE_FRAMES_DIR` overrides it).
3. `scripts/store-frame inspect "build/store/frames/<file>.png"` prints its sha256 and the transparent
   screen area: a flood fill from the centre, and the corner radius estimated on the diagonal. Check the
   radius against the device's published screen radius, and fix the `file` name in the manifest.
4. `scripts/store-publish.sh --fixture "build/store/frames/<file>.png"` puts it in the **private**
   bucket `tenshu-store-fixtures` and prints its `s3://` URL. Copy sha256, `fetch` and `screen` into the
   manifest. Another machine then gets it with `scripts/store-frame fetch` (R2 key pair and
   `FIXTURES_ENDPOINT`, as for the fixture photos — [`store-screenshots.md`](store-screenshots.md) §3).
   ⚠ `web/store-frame/src/frames.test.ts` asserts that no frame is pinned yet; that commit updates it.

`render` reads the bezel by sha256 and **fails on a mismatch** — a different file has a geometry nobody
measured. It refuses a capture whose aspect ratio is not the screen area's (a stretched app is a
misleading listing), writes exactly the frame's `output` size, and takes each plain capture from
`store/<app>/screenshots/` or, failing that, from R2 by its manifest sha256.

**Where framed renders go.** `store/<app>/screenshots/ios/<device>framed/<lang>/NN-screen.png`, so
`store-publish.sh` publishes them unchanged as manifest keys `ios/iphone69framed/en/NN-screen`. That is
the existing key form with a device id of its own: the 16 published plain keys stay valid,
`check-store-assets.py` accepts it, and the support pages, which read `SUPPORT_DEVICE` (`iphone69`)
only, keep showing the unframed captures. The store listing takes the framed set when
`listing.json` says `"framed": true`.

Tests: `pnpm -C web -F @tenshu/store-frame test`. The end-to-end test draws a synthetic bezel and
capture in Chromium at test time (nothing committed), frames them, and reads the output back pixel by
pixel: the app inside the screen area, the bezel around it, the token background, the caption.

## 6a. Header and Search Results — the creative asset

Since 2026-10-05 an iOS/iPadOS 27 product page can open with a **header** image and show a **search
results** image in place of the first screenshots
([specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications/),
[guidance](https://developer.apple.com/app-store/asset-best-practices/)). Both are optional. Apple takes a
21:9 header (3840 × 1646, JPEG/PNG), a 3:2 search image (1920 × 1280 up to 3840 × 2560), or **one 16:9
universal PNG at 5244 × 2950 that serves both** — opaque, no alpha. We make the universal one, per app,
from repo data: the app's icon colour, the Tenshu palette and two of its published iPhone screenshots in
plain CSS-drawn frames (never Apple's bezels). It is a first version; hand-made art can replace it.

```bash
scripts/store-creative-assets.sh                    # all six: control drive calendar contacts photos fit
scripts/store-creative-assets.sh drive              # one app
open build/store/creative/drive/                    # the asset and its three previews
```

Inputs: `scripts/store-creative-assets.html` (the layout), `store/creative/apps.json` (which two screens of
`ios/iphone69/en/` each app shows — fetched by their `screenshots.manifest.json` URL, sha256-checked, into
`build/store/screenshot-cache/`), the app's `<app>-ios/…/AppIcon.appiconset/icon_1024.png` (its tile
colour is sampled) and `store/<app>/en-US/name.txt` + `subtitle.txt` (previews only). Outputs, all in
`build/store/creative/<app>/` and never committed:

| File | What |
|---|---|
| `header-16x9.png` | **the upload**: 5244 × 2950, PNG, RGB, sRGB ICC profile; the script fails otherwise |
| `preview-crops.png` | the whole asset with the 21:9 crop, the 3:2 crop, their intersection and Apple's art safe area |
| `preview-header-21x9.png`, `preview-search-3x2.png` | each crop, with the App Store's icon, name and Get mocked translucently over its bottom |

**Geometry.** Apple's template for the universal asset (the Sketch file linked from the guidance page)
marks one **art safe area: x 1921–3323, y 660–1622** — centred, and *above* the vertical centre, because
the App Store lays the app's icon and name over the bottom of the art. Key content (the top of both
screens) sits inside it; the lower half is calm. ⚠ The two crops are our model, not Apple's geometry:
Apple publishes the safe area, not each device's crop. We take 21:9 as the full width centred on the safe
area (y 17–2264) and 3:2 as the full height centred (x 409–4834). App Store Connect's **Preview** shows the
real crops on iPhone and iPad in both orientations — check it before submitting. The phones' size follows from the
same model: tops level just above the safe area, bottoms 90 px inside the 21:9 crop's lower edge. Any
taller and the header cuts them, and in 3:2 they run under the overlaid app name. The script also
checks that each phone's screen in the render matches its screenshot, so a screenshot that fails to load stops the run. Apple's rules: no text
that repeats the name, no prices, URLs, awards or other platforms, 4+ content.

**Uploading — manual.** fastlane deliver 2.241 cannot push creative assets (the App Store Connect API
can, through Asset Library, but nothing here calls it), so it stays by hand until fastlane supports it:

1. App Store Connect → Apps → the app → **App Store** tab → the iOS version in the sidebar.
2. **Product Page Information** → **Header and Search Results** → **Header** → **Upload**, and drop
   `build/store/creative/<app>/header-16x9.png` in.
3. Tick **Use header asset in search results**, so the same file serves both placements.
4. **Preview** — iPhone and iPad, both orientations — then save. It is reviewed with the version, or
   on its own if submitted from Asset Library.

## 7. Not built, and not verified

- **Never pushed.** No run of the push lane against App Store Connect has happened. The first one is
  the maintainer's, from a Mac with the key: dry run, look at the preview, push.
- **German on the App Store** (`de-DE`, 2026-10-28): `store/<app>/de-DE/` for all eight apps with copy,
  `store/shared/de-DE/` (the privacy URL is the binding German page, `/legal/privacy/de`), and
  `"de-DE": "de"` in each pushed app's `languages`. The copy follows §8's German rules (du-form, the iOS
  apps' own German labels from `Localizable.xcstrings`); its privacy sentences are `claims.md`'s German
  claims, and `check` holds them to the English App Store privacy section one for one and in order, as
  `play-check` does for Play. ⚠ Control's iOS onboarding, pairing and sign-in are English in the app,
  so the German copy names those labels in English with the German in brackets, as the German support
  page does. The screenshots are German captures, `ios/<device>/de/…` (`store-screenshots.md` §4), where
  `languages` says `"de-DE": "de"`; an app whose German set is not published yet says `"de-DE": "en"`
  and lists the English set under German, deliberately and visibly, because `store-listing.py
  screenshots` stops on an empty iPhone slot rather than fall back on its own. ⚠ 2026-10-28: Control
  is `de`; Drive, Fit, Calendar, Contacts and Photos are `en` until their German walks
  (`store-screenshots.sh <app> --lang de`) are captured and published — then flip the line.
- **Play through fastlane** (`supply`, S5): `store-push.sh --android` still refuses. The Play listing
  is pushed by our own script instead (§8), into a folder in supply's layout, so a `supply` lane can
  take it over unchanged.
- **Android and browser frames** (§3.2's other rows) are new entries in `store/frames/manifest.json`
  and, for the browser, a template; the renderer has no platform assumption beyond the bezel image.

## 8. Google Play — the ten Android listings

```bash
python3 scripts/store-listing.py play-check                       # Play's limits + copy rules + claims (also part of `check`)
python3 scripts/play-listing-assets.py drive maps …                # assemble build/store/play/<app>/ (text, icon, feature graphic, screenshots)
python3 scripts/lib/play_publish.py listing build/store/play/drive --dry-run   # what would be sent
python3 scripts/lib/play_publish.py listing build/store/play/drive            # push it (one edit, committed)
python3 scripts/lib/play_publish.py show-listing app.tenshu.drive.android     # read the live listing back
```

All ten apps have a Play listing — Home and Mia too, which have no App Store copy yet — in English
(`en-US`) and German (`de-DE`, 2026-10-28). It is an **initial, tester-ready** listing (2026-10-22), to
be refined; nothing here is final copy.

**The files.**

```
store/<app>/play/en-US/title.txt                 "Tenshu Drive"            30 characters
                       short_description.txt     one line                  80 characters
                       full_description.txt      the long text             4000 characters
store/<app>/play/de-DE/…                         the same three files, in German
store/<app>/play/listing.json                    package, the feature graphic's tagline (+ `taglines`), the screenshots
store/<app>/claims.md                            shared with the App Store copy; Play-only claims in their own section
```

The names are fastlane supply's. **The Play copy is its own file, not the App Store description** — a
departure from design §2.1's "one description file for both stores": the platform facts differ in
almost every app (Google Play vs the App Store, fingerprint vs Face ID, Health Connect vs Apple Health,
Android's background photo backup and Timeline capture, the Android version), and several Android apps
do more (Calendar's week view and widget, Contacts' editing and import, Timeline's Takeout import).
Each was checked against the Android code, not the iOS copy. Shared sentences are kept word for word,
so they share a claim.

**`play-check`** (also run by `check`, so by the pre-commit hook and web CI): Play's limits; the D6
word lists; no iOS fact (*App Store*, *Face ID*, *iPhone*, *Apple Health*…); the title is "Tenshu
<App>"; every app but Control names **Tenshu Control** in its description; `listing.json`'s package is
`app.tenshu.<app>.android` and its screenshots resolve; and **every sentence under the `HOW … PRIVATE`
heading is quoted in `store/<app>/claims.md` with a source that exists** — as for the App Store. A claim
stays live while either store's description quotes it.

**Locales.** A folder under `play/` named like a locale (`en-US`, `de-DE`) *is* that locale: `play-check`
checks each, `play-listing-assets.py` assembles each, and `play_publish.py listing` sends each folder's
name as the Play language code — so a new language is a new folder, nothing else. Play's code for German
is `de-DE`. Each non-English locale also needs its line in `listing.json`'s `taglines` (below), or
`play-check` fails rather than draw an English feature graphic.

**German copy** (`de-DE`): du-form, the labels exactly as the German UI spells them
(`values-de/strings.xml` — *Freigegeben*, *Meine Freigaben*, *Speicherplan*, *Mediathek*), product names
as in English, and the privacy wording of the German support pages (`web/apex/content/apps/<app>/support/de.md`)
where one exists; help links point at `…/support/android/de` for the six apps that have German pages.
Section headings are ALL CAPS and never contain ß (`"ß".upper()` is `SS`, so the line would not read as
a heading). The privacy heading is `WIE … PRIVAT …` (e.g. *WIE DRIVE DEINE DATEIEN PRIVAT HÄLT*). The
copy rules (D6) carry German superlatives too, as regex **stems** — German inflects and compounds, so a
word list always misses a form (*Allerbeste*, *Unknackbare*, *Branchenführende*, *100 %* with a no-break
space). Matched as whole words, umlauts counting as letters. ⚠ Never a bare `best\w*` (*bestätigen*,
*Bestellung*) or `\w*führend\w*` (*anführende*, *durchführende*): the claim-making compounds are named.

**German claims.** Every German privacy sentence is quoted in `claims.md`'s `## Deutsch (de-DE)`
section like an English one, plus a `- translates: "<the English claim>"` line naming the English claim
it translates, and that claim's sources. `play-check` checks the mechanics, not the meaning:

- every German claim has exactly one `translates:`, naming a claim that is in `claims.md` in English —
  so changing an English claim flags its translation (edit both together);
- the German privacy section's `translates:` targets are **the English Play listing's privacy sentences,
  one for one and in order** — a dropped caveat or an extra claim fails.

Whether a German sentence says what its English claim says is review's job; nothing here can tell.

**Help links** are checked too: every `tenshu.app/apps/<app>/support/<platform>/<lang>` link must be a
page that exists (`web/apex/content/apps/<app>/support/<lang>.md`) declaring `android`, in the listing's
own language; the hub `tenshu.app/apps/support/<lang>` is only for an app without a page of its own.

**Screenshots per locale.** `screenshots` is en-US's set. Every other locale is named in
`localeScreenshots` — its own set, in the same shape and its own language (`{"manifest":
["android/phone/de/01-welcome", …]}`), or `"en-US"` for the English one — and `play-check` fails on a
locale that names neither, or on an own set whose keys are another language's (that is what `"en-US"`
is for). `play-listing-assets.py` assembles a `phoneScreenshots` folder for every locale, the English
captures included where a locale maps to them, so `play_publish.py listing` replaces whatever set that
locale had — a hand-uploaded one too. Only a locale with no captures at all (a `local` folder a fresh
clone lacks) gets no folder, and the push then leaves its live set alone.
Which apps map de-DE to English, and why (2026-10-28):
- **Calendar, Photos** — the Android apps carry ~20 German strings each and their German listings say
  they are English; a German capture would be the English screen with German dates.
- **Maps** — its English set is still a hand capture (`local`): the Android walk exists
  (`store-screenshots.md` §8.2) but what Maps shows is not listing material yet (false *No visits
  yet* counts, a top bar under the status bar), so nothing is published for it.
**Timeline, Home, Mia** (2026-11-05) have English and German captures from the walk's §8.2 run, each
`localeScreenshots` naming its own `/de/` set — Mia's German set is Mia's German chrome around the same
English questions and answers, which is what its German listing says it does.
The rest (Control, Drive, Fit, Contacts) have German captures from the Android walk's `--lang de` run
(`store-screenshots.md` §8, 2026-10-29), and each `localeScreenshots` names its own
`{"manifest": ["android/phone/de/…"]}` set: Control and Drive the same screens as their en-US set, Fit and
Contacts five of their walk's screens (their en-US set is a `local` folder). The feature graphic is per
locale too (`taglines`).

**The graphics** (`scripts/play-listing-assets.py`, Pillow + macOS's Helvetica Neue; output gitignored):

- **Icon, 512 × 512** — the app's iOS tile `<app>-ios/Resources/Assets.xcassets/AppIcon.appiconset/icon_1024.png`,
  resized. It is the full-bleed, opaque tile ADR 0026 produces for every platform (its colour is the
  Android adaptive icon's background); Play applies its own mask. The Android launcher rasters are not
  used: they are per-density and carry the adaptive inset.
- **Feature graphic, 1024 × 500** — drawn: the tagline, large, beside one or two of the app's own
  screens in the website's Android phone (the second behind and lower, both running off the bottom
  edge), on a gradient of the tile's colour, with the tile and the name as a small line above the
  tagline. The tagline is `listing.json`'s `tagline` (for a non-English locale, its line in `taglines`,
  e.g. `{"de-DE": "Verschlüsselter Dateispeicher"}`). No Play badge (design §3.2.1).
  - **Why screens, not the icon (2026-11-04):** the graphic is the large card on Tenshu's Play
    developer page and in Play's app collections, and the card's row already shows the icon and the
    name — the first design (tile + name + tagline) repeated both and showed nothing of the app.
  - **Which screens:** `featureScreens` in `listing.json` — one or two raw English Android captures
    (`android/phone…/en/…`, never a framed render, which has its own caption and canvas; `play-check`
    enforces it). A locale with captures of its own takes each key's `/de/` twin where the manifest has
    one. Without `featureScreens`, the first two unframed captures of the locale's set.
  - ⚠ **No screen, no graphic:** an app whose captures are a `local` folder this clone lacks (Mia, Home,
    Maps, Timeline today — no Android captures in R2) gets a warning and no `featureGraphic.png`, and
    the push then leaves the live graphic alone. Moving those captures into the Android walk and R2
    gives them the new graphic.
- **Phone screenshots** — each capture centred on a 1242 × 2208 (9:16) canvas of the tile colour.
  ⚠ The captures are 1080 × 2400, and **Play refuses a screenshot whose long side is more than twice
  its short side** (2400/1080 = 2.22), so a raw capture cannot be uploaded; padding keeps every pixel
  the app drew, where cropping would cut its chrome.
  - `"manifest": [keys]` — published Android captures (§8 of `store-screenshots.md`), fetched from R2
    and verified by sha256, as the App Store's are. Control and Drive use the dev-lab walk's
    `android/phone/en/…`; Photos the landing set's `android/phonelanding/en/…` (stock photos).
  - `"local": "build/store/play-captures/<app>"` — the other seven, captured by hand on the emulator
    for the first listing (a fresh realm, no plan, data typed in where it was cheap). **They are not in
    R2 and not reproducible from a clone**: a fresh checkout has no such folder, and assembling then
    warns and sends no screenshots — and the push then leaves the live ones alone, because only image
    types that have files are replaced. Moving them into the Android walk and R2 is the follow-up.
  - **Framed (Control since 2026-10-30):** a key under `android/phoneframed/<lang>/…` is a finished
    1242 × 2208 picture — the capture in the website's CSS-drawn Android phone, a caption above it, on
    the website's background (`--surface`) — and is uploaded as drawn, not padded again. The maintainer
    chose this over the tile-colour canvas ("the purple background doesn't look that good"), white for
    consistency with tenshu.app. Play has **one** set per locale, shown alike in its light and dark
    themes, so there is no dark variant and no transparency (Play takes no alpha).

    ```bash
    scripts/store-frame render control --device phone --lang en   # store/control/screenshots/android/phoneframed/en/
    scripts/store-frame render control --device phone --lang de
    scripts/store-publish.sh                                       # then name the keys in play/listing.json
    ```

    The captions are `store/<app>/play/<locale>/captions.txt` — Play's own, not the App Store's
    (`store/<app>/<locale>/captions.txt`), and the file decides which screens are framed. Plain words a
    non-technical user knows (no *keys*, no *realm*); a line taken from the website's copy says so. ⚠
    Keep each to exactly two lines: a third shrinks the phone to fit rather than crop it, and a single
    line lifts it ~90 px — either way the set stops matching. The face is **Roboto** from Google Fonts, the website's heading style (semibold, tight):
    the site sets no web font, and its system stack is Roboto on Android. ⚠ Never the system face — on
    a Mac that is San Francisco, licensed for Apple-platform mockups only; the render fails if Roboto
    did not load. The phone is a copy of `appCarousel.css`'s Android frame (`web/store-frame/src/android.ts`);
    change the two together.

**The push** (`play_publish.py listing`): one edit per app — the listing text (`edits.listings.update`),
then each image type the folder has, **replaced, not appended** (`deleteall`, then one upload each:
Play adds to a set, so a re-run would double it), then commit; on any failure the edit is discarded and
nothing is visible. It never touches tracks, releases or bundles, so it is safe for
`app.tenshu.home.android` (whose first release must be made by hand, `android-release.md`). Credentials
are the same service account as the bundle upload (`android-release.md`). Text-only changes need no
review gate of ours, but Play reviews every listing change before it is public.

**What stays manual in the Play Console:** the app category, contact details, the privacy policy URL,
the content rating questionnaire and the permission declarations
([`play-store-declarations.md`](play-store-declarations.md)) — none has a listing-API field, or they
are declarations, not listing text. The click-through answers for all of them are
[`play-console-answers.md`](play-console-answers.md). **Data safety** is the exception: it has an API
(`applications.dataSafety`), and `play_publish.py data-safety` uploads `store/<app>/play/data-safety.csv`
(`play-console-answers.md` §12).

⚠ **Found while writing the copy (2026-10-22), not fixed here:**

- **Mia's merged manifest had INTERNET** — the only client app that never strips it — pulled in by
  ML Kit's `datatransport` through `:ui-core`'s QR scanner. The scanner now lives in
  `:ui-qr-scanner`, and Mia's merged debug manifest on master (2026-10-22) has no INTERNET any more.
  The listing still does not claim "no internet access": add a `tools:node="remove"` to Mia's manifest
  first, as every other client has, so the claim cannot come back false. (Drive's merged manifest
  had the same INTERNET from the same source until the scanner moved to ZXing; Drive now strips it.)
- **Photos declares media-read permissions** (`READ_MEDIA_IMAGES/VIDEO`) for its *Back up a folder*
  list, but never requests them at runtime, so the list is probably empty; the listing does not
  advertise it.
- **Maps shows stub controls** (search, Edit / Directions / Share, the Saved tab) that do nothing; the
  listing says it has no search or directions yet.
- **Fit's onboarding hints overstate Android**: "FIT · TCX · GPX" (no FIT decoder on Android) and
  "medical records" from Health Connect (not imported).
