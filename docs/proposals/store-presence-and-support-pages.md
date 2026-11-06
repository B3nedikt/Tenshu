# Store presence and support pages — one source for listings, screenshots and help

**Status:** Draft v0.2, 2026-10-14 — D2, D5, the iPad question, store names and the support address decided by the maintainer (§0, §6). S1 and S3 built (en); S2 (App Store, en) and S7's renderer built 2026-10-15 (§2.2 as built, §5). The support hub and Getting started (§4.7) decided and built 2026-10-16. Depends on the TestFlight pipeline
([`how-to/ios-testflight.md`](../how-to/ios-testflight.md)). ⚖️ The privacy policy and the
imprint (§1.4) are counsel-gated and sit on the critical path to a public listing.

**Scope:** everything a store listing needs besides the binary, for the App Store and Google Play;
where that content lives; which parts are generated; and a new public page per app and platform —
`/apps/<app>/support/<platform>/<lang>` — that serves as the store's *support URL* and as the
first help a user finds.

---

## 0. The decisions this asks for

| # | Decision | Recommendation |
|---|---|---|
| D1 | Where listing text lives | In the repo, one folder per app and language (§2), pushed by script. App Store Connect and Play Console are outputs, never edited by hand except where no API exists (§2.3). |
| D2 | The URL of a support page | ✅ **Decided:** `tenshu.app/apps/<app>/support/<platform>/<lang>`, one instance of the general pattern `/apps/<app>/<page>/<platform>/<lang>` (§4.1). |
| D3 | How support pages are built | Static HTML generated at build time from content files, served by apex's Caddy. Not routes in the apex SPA (§4.2). |
| D4 | Where screenshots come from | UI tests driving each app over fixture data, on simulators and emulators (§3.1). One run feeds the store and the support pages. |
| D5 | Languages at launch | ✅ **Decided:** English. German translations of every app are prepared in a separate PR; the maintainer reviews them and they ship with the first release if good enough, else the next. The store and support pages follow the apps' languages. |
| D6 | The privacy comparison | Name no competitor. Compare against *how cloud services usually work*, and link every claim to the document that makes it true (§4.4). ⚖️ Counsel reads it before it is public. |
| D7 | Tooling | ✅ **Decided 2026-10-15:** fastlane's `deliver` (App Store) and `supply` (Play) for metadata and screenshots, reading our folder layout. It is the only maintained tool that covers both stores; our own scripts stay for the binary (§2.2). Device frames are **not** fastlane's `frameit`: Apple's own product bezels, composited by our renderer (§3.2), the bezel files kept out of the repo (§3.3). |

## 1. What a listing needs — the full list

"Have" means it exists today. **Write** is copy someone writes once and maintains. **Generate**
is produced by a pipeline. **Form** is answered in the console, with no API. ⚠ Limits and
requirements change; check each against the live console when submitting, as
[`play-store-declarations.md`](../how-to/play-store-declarations.md) does for Play.

### 1.1 App Store, per app

| Item | Required | Limit | Source | State |
|---|---|---|---|---|
| Name | yes | 30 chars | write | "Tenshu Drive", "Tenshu Control", … (§6.1); the bundles keep "Drive", "Control" |
| Subtitle | no | 30 chars | write | — |
| Primary category (+ secondary) | yes | — | write | — |
| Description | yes | 4000 chars | write | — |
| Keywords | yes | 100 bytes, comma-separated | write | — |
| Promotional text | no | 170 chars, changeable without a release | write | — |
| What's New | yes from the second version | 4000 chars | write per release | — |
| Support URL | **yes** | — | **generate** (§4) | none exists |
| Marketing URL | no | — | link to `/apps/<app>` | none exists |
| Privacy policy URL | **yes, every app** | — | ⚖️ write | `/legal/privacy` (302 by Accept-Language to `/<lang>`), published 2026-10-23, not counsel-reviewed (§1.4) |
| Copyright | yes | — | write | "2026 Cajetan UG (haftungsbeschränkt)" |
| App icon (1024 px) | yes | no alpha | have | in the asset catalogues |
| iPhone screenshots, 6.9" | **yes** | 1–10 per language | **generate** (§3) | none |
| iPad screenshots, 13" | **yes**, because the apps declare iPad support | 1–10 per language | **generate** | none |
| App preview videos | no | 15–30 s | generate, later | — |
| Age rating questionnaire | yes | — | API | — |
| App privacy (the "nutrition label") | **yes** | — | **form**, no public API | must agree with each app's `PrivacyInfo.xcprivacy` |
| Content rights | yes | — | form | — |
| Export compliance | yes | — | plist key or per-build question | ⚖️ undecided ([`ios-testflight.md`](../how-to/ios-testflight.md) §6) |
| Review contact, notes, demo account | yes | — | write | the notes must explain that Drive needs Control installed first |
| Price and availability | yes | — | API | free |
| TestFlight: beta description, feedback email, test notes | yes for external testers | — | write | — |

### 1.2 Google Play, per app

| Item | Required | Limit | Source |
|---|---|---|---|
| Title | yes | 30 chars | write (shared with the App Store name) |
| Short description | yes | 80 chars | write |
| Full description | yes | 4000 chars | write (shared with the App Store description) |
| Icon | yes | 512 × 512 | generate from the existing icon source |
| **Feature graphic** | **yes** | 1024 × 500 | **generate** (§3.2) — the one required marketing graphic |
| Phone screenshots | yes, 2–8 | — | generate |
| Tablet screenshots | for tablet listing | — | generate |
| Privacy policy URL | yes | — | ⚖️ shared with the App Store |
| Data safety form | yes | — | form; must agree with the iOS privacy label |
| Content rating (IARC) | yes | — | form |
| Permission declarations | yes, several | — | already inventoried in [`play-store-declarations.md`](../how-to/play-store-declarations.md) |
| App access instructions | yes if anything is gated | — | write (same text as the App Store review notes) |

### 1.3 What can be shared

- **Across stores:** the name, the long description, the screenshots (cropped per device), the
  privacy policy and the support URL.
- **Across apps:** the privacy policy, the imprint, the copyright line, and the
  "how Tenshu keeps it private" framing that each support page specialises (§4.4).
- **Never shared:** each app's privacy label and data-safety answers. Fit collects health data and
  Timeline location; Control and Drive do neither.

### 1.4 The legal prerequisites — the actual critical path

A privacy policy URL is mandatory for every app on both stores, and as a German UG the website owes an
imprint. **Both now exist** as static pages beside the support pages: the imprint at
`/legal/imprint/<lang>` (`web/apex/support-pages/imprint.ts`) and the privacy policy at
`/legal/privacy/<lang>` (`web/apex/support-pages/privacy.ts`, 2026-10-23), German binding, English and
French translations marked unreviewed, both linked from every footer. The language-neutral
`/legal/privacy` and `/legal/imprint` — the former is the stores' URL — are a 302 in Caddy to
`/<lang>` by the first language of Accept-Language (de → de, fr → fr, else and no header → en; q-values
not weighed), with `Vary: Accept-Language` and `no-store`; the build also writes a static language
list at those paths for hosts without the rule (`web/apex/Caddyfile`, generate.ts `legalLanguageList`). ⚖️ **Neither is
counsel-reviewed yet:** the maintainer chose to publish now and have counsel read them once the
product has traction ([`pre-release-checklist.md`](pre-release-checklist.md) §1). ⚠ Internal
TestFlight needs none of this; external TestFlight and both public listings need the privacy policy
to be *served*, i.e. an apex image carrying it on production.

**What the privacy policy commits us to that no code does yet** — operator duties until the code
catches up, and each one a reason to re-read the policy when it changes:

- **Account closure and erasure are by e-mail, by hand** (`privacy@tenshu.app`, fallback
  `contact@cajetan.company`). No UI closes an account; commerce has no route that deletes the
  `account` row or the e-mail (only `/admin/accounts/close`). The policy promises: close on request,
  delete account + e-mail + `account_owner` link 30 days later, keep only invoices and the
  acceptance records. Closure-driven content deletion (`ACCOUNT_CLOSURE_RECLAIM`,
  `retention-quota-model.md` P3) is described as **built, not in operation**, and full server-side
  erasure on request as not automated.
- **Unverified accounts** (code requested, never entered) are deleted on request; nothing purges
  them (`docs/tech-debt.md`). The same for expired codes and sessions: the policy states validity
  and retention separately.
- **Acceptance records with IP are kept up to 10 years**, as the design says (append-only, survive
  erasure: `commerce-accounts-and-sessions.md` §5.2 / D10, `acceptable-use-and-limits.md` §4.1). No
  code deletes them at 10 years either; and a row is written on **every** `/register`, i.e. every
  sign-in — the policy says so.
- **The weekly blob mirror never prunes**, so the policy states indefinite retention for that copy
  and that we are working on a period. Pruning on `tenshu-relay-blobs-mirror` closes it; then amend
  the policy's retention list.
- **Server logs** (the server-logging fix): web-server access and error logs with the IP
  truncated (IPv4 /24, IPv6 /48), no User-Agent or query string, kept at most about a week; application
  logs without IPs, e-mail at most as its domain, within 50m × 5 per service. The policy states this
  "with our standard configuration" — it is true only on boxes **redeployed with that fix**, so the
  apex, relay and commerce redeploys are owed before the text matches production.

**Production facts the maintainer should confirm on the boxes** (the policy hedges each one "with
our standard configuration"): `CONTENT_SWEEP_MODE=armed` on the relay; the 30-day lifecycle on both
backup buckets (applied out of band by `set-lifecycle.py`); the server-logging settings (deployed on all
three boxes); blob-mirror pruning still OFF, as the policy says, until you enable it — then
amend the policy; that
`privacy@`/`support@` forward to the inbox you expect, and which provider hosts it.

**Open for counsel (and decisions for the maintainer)** — the deletion items first, because there
the policy has to describe a gap rather than a guarantee:

⚖️ **Since 2026-10-28 the maintainer acts as counsel** for privacy-notice and stored-data changes, which pass [`policy/privacy-review-checklist.md`](../policy/privacy-review-checklist.md); the items below wait for a one-off lawyer review before the paid launch.

1. **Server-side erasure on request is not fully automated.** We can delete the account record and
   e-mail (by hand); content removal depends on the user deleting files, then on account-level
   reclamation (P3, on since 2026-10-24). No operator path deletes one account's content today
   (`reclaimOwner` is reachable only through the closure route, i.e. by closing the account; the B0 operator reclaim is for undescribed
   ciphertext, not per account). The policy says so and promises only to tell the user what can be
   deleted. Whether that meets Art. 17, with the published 30-day grace, is the first question.
2. **Per-file deletion is phrased "normally, about 15–20 minutes, with our standard
   configuration"**. Disclosed exceptions: an internal pause; shared-folder content; undescribed
   uploads (indefinitely, until an operator clean-up or account-level deletion); share-link copies
   (until revoked or expired, plus 30 days; users are told to revoke); folder-delete change records
   and path-bearing deletion notices (encrypted, kept indefinitely).
3. **The weekly blob mirror is not pruned**, disclosed as indefinite retention. Pruning is being
   added (off until the operator enables it); amend the policy's retention list when it runs.
4. **Acceptance records with IP for up to 10 years** (Art. 6(1)(c)/(f)) — proportionate, and is the
   IP needed in them at all? They are also written for **unverified** sign-ups, i.e. possibly for an
   address someone else typed in.
5. **iOS device backups** (rewritten 2026-11-05, policy version `2026-11-05`): the data store and
   file contents are now excluded from device backup (`ios-shared-store-sqlite.md` D3/D3b), so
   HealthKit-imported data no longer reaches iCloud Backup (guideline 5.1.3). The policy lists what
   is still backed up: settings, Timeline's Wi-Fi network→place map, Calendar's check values, and
   a Fit workout not yet saved (recorded, entered or promoted from Timeline; route, steps, strap heart
   rate — not HealthKit data; held while Control has no realm) and Timeline's journey in progress. Open: whether those few per-app files should be excluded too
   (`docs/tech-debt.md` "iOS device backups: what the app sandboxes still carry").
6. **Art. 9 for Fit's encrypted health data**: the policy treats ciphertext we cannot decrypt as
   possibly health data and relies on explicit consent given by granting Fit access with sync on.
   Whether that consent shape holds, or whether ciphertext is outside Art. 9 for us at all.
7. **QR scanning, MediaPipe and Play Billing on Android** (verified against the bytecode):
   the scanner decodes with ZXing on the device (Drive's merged manifest no longer carries
   `INTERNET`); a scanned code is still used for the pairing or invitation it carries. MediaPipe
   (Photos' face grouping, in Control) and the Play Billing Library would log through Google's
   datatransport, but Control's manifest removes its upload backend, so the events are rejected on
   the device — a build check enforces it — and Control makes no Play-services API calls (R8 strips
   GoogleApi/TelemetryLogging). ⚠ The APK still contains datatransport and the MediaPipe/Billing
   loggers — only the upload backend is gone — so the policy says our apps **send** no analytics or
   telemetry and contain no advertising or analytics libraries (never "contain no telemetry
   libraries"). A Play purchase is handled by Google Play under Google's policy with no usage or
   diagnostic data from the billing library itself, and a signed-in user opening the plans screen
   makes Control ask Google Play for existing purchases (`ServicesViewModel.onResumed` →
   `restoreStorePurchases`); the policy says both. ⚠ Keep it true: a new Google
   SDK, or dropping the manifest removal, reopens the disclosure. Counsel: the matching Play
   Data-safety answer (`docs/how-to/play-store-declarations.md`).
8. **Terms**: published 2026-10-28 at `/legal/terms/<lang>` (`support-pages/terms.ts`) and accepted
   at sign-up; `TOS_VERSION` = its date. The policy still describes only what is recorded (the
   versions sent at sign-in), which now names a readable page — no `PRIVACY_VERSION` change. The
   terms' own review list: `docs/policy/acceptable-use-and-limits.md` "Terms of use".
9. **Stripe's dual role** (processor for payment and invoicing, controller for AML/fraud) as stated.
10. **Minimum age 16**: stated as the intended audience, with deletion by hand when we learn of a
    younger child without parental consent. The terms state it too, and every sign-up screen asks the
    user to confirm 16+ (2026-10-28); no age is collected; the store ratings are 4+.
    Confirmed by the maintainer on 2026-10-24 and recorded in `docs/policy/acceptable-use-and-limits.md`
    §7 (why 16 not 13, and the under-18 contract-capacity question), with Play's target audience
    16–17 and 18+.
11. **The Data Privacy Framework** status of Cloudflare, Stripe, Google and Apple is stated "to our
    knowledge": check the DPF list before review, and that Cloudflare's and Stripe's DPAs are in place.
12. **Our e-mail provider** — the inbox Cloudflare Email Routing forwards to — is named only
    generically as a processor; confirm the provider and its DPA.

## 2. Listing text as files

### 2.1 Layout

```
store/
  shared/<lang>/            copyright.txt, privacy_url.txt, …
  <app>/
    <lang>/                 name.txt, subtitle.txt, description.txt, keywords.txt,
                            promotional_text.txt, release_notes.txt, short_description.txt
    review/                 notes.txt, contact.txt
    screenshots/            generated, gitignored (§3)
```

The per-language file names follow fastlane's own layout, so `deliver` and `supply` read the
folder without translation. One description file feeds both stores. Play's short description is
the only Play-only text.

### 2.2 Pushing it

`scripts/store-push.sh <app> [--ios] [--android]` runs `deliver` and `supply` against the folder,
using an App Store Connect API key and a Play service-account key. Neither key lives in the repo.
Text changes go through review like code, and a listing can be rebuilt from git.

**Why fastlane here, and not for the binary:** for the binary we already have
`scripts/ios-archive.sh`, which knows our two-spec project setup and checks the product. For
metadata and screenshots, `deliver`/`supply` already implement both stores' upload rules, and our
own script would re-implement them.

**As built (2026-10-15), App Store only** — runbook [`how-to/store-listings.md`](../how-to/store-listings.md):

- `scripts/store-push.sh <app> [--ios] [--dry-run] [--no-screenshots]` over the `ios preview` / `ios
  push` lanes in `fastlane/Fastfile` (fastlane 2.241.1, pinned by `Gemfile.lock`; Ruby ≥ 3.2). deliver
  cannot read `store/<app>/` as it stands — its loader refuses folders that are not languages — so
  `scripts/store-listing.py assemble` copies the files into `build/store/deliver/<app>/ios/` first,
  shared files (`store/shared/`) included. ⚠ The §2.1 layout gained `store/<app>/{claims.md,
  listing.json, age_rating.json, primary_category.txt, secondary_category.txt}` and dropped
  `review/contact.txt` (personal data, kept in the console).
- **The dry run never contacts App Store Connect:** it renders deliver's own HTML preview
  (`Deliver::HtmlGenerator`) from the files and stamps their sha256. A push passes `force: true` only
  when the files still match that stamp; otherwise deliver previews and asks. `--verify_only` validates
  binaries only and `precheck` reads what App Store Connect already has, so neither previews our files.
- **Screenshots reach deliver by sha256:** the listed screens (`listing.json`, in order) are fetched from
  R2 by their manifest hash into a gitignored folder and verified. `overwrite_screenshots` makes App
  Store Connect's set equal to ours.
- **Copy is gated:** `scripts/store-listing.py check` (pre-commit, web CI, every push) enforces §1.1's
  limits, D6's no-superlatives / no-competitor rule as a word list, and §4.4's "a claim with no source
  is not published": every privacy sentence of a description is quoted in `store/<app>/claims.md` with
  a source that exists.
- Not built: `supply` (S5 — `--android` refuses), German. ⚠ The privacy policy URL is
  `https://tenshu.app/legal/privacy` (§1.4); a submission needs it served on production.

### 2.3 What stays manual

The App Store privacy label and Play's data-safety form have no public API (fastlane's privacy
action logs in with an Apple ID, not the API key). They change rarely. Keep the answers as a
checked-in file per app (`store/<app>/privacy-label.md`) next to the `PrivacyInfo.xcprivacy`, so
a change to one shows up in review beside the other.

## 3. Generated content

### 3.1 Screenshots

The same screens, captured by UI tests:

- **iOS:** an XCUITest target per app (Drive already has `DriveIosUITests`) runs a
  `Screenshots` scheme on the 6.9" iPhone and 13" iPad simulators, once per language, over fixture
  data. Photos already has `-photosFixture`, and Drive has `DRIVE_DEV_PROVISION_CONTROL`.
- **Android:** the same screen names, on one 1080 × 2400 emulator phone. As built (2026-10-16) it is
  an adb walk over `uiautomator` dumps rather than Compose UI tests — it has to cross from Control to
  Drive — and it feeds the support pages only ([`how-to/store-screenshots.md`](../how-to/store-screenshots.md) §8).
- **Desktop:** later, for the support pages only.

⚠ **The fixture is content, not test data.** It is what every future customer sees first: real
looking file names, photos we hold the rights to, plausible dates. It needs designing once; its
manifest lives in the repo, its files do not (§3.3).

⚠ Screenshots must come from the release build of the app they advertise. A screen rendered by a
test-only path that the shipping app cannot reach is a misleading listing.

The run writes to `store/<app>/screenshots/<platform>/<device>/<lang>/NN-<screen>.png`, where both
`deliver` and the support-page build pick them up.

### 3.2 Marketing graphics

Only one is required: Play's **feature graphic** (1024 × 500). The App Store requires none beyond
screenshots and the icon.

Optional, and worth doing once screenshots exist:

- **Captioned screenshots** — a device frame with one line of copy above it. This is the usual
  store style, and it raises conversion.
- **Promotional artwork** for App Store featuring.

All three come from one renderer: HTML templates styled with apex's design tokens, with the
caption from `store/<app>/<lang>/captions.txt`, rendered to PNG by a headless browser. The
renderer is a script, the templates are reviewed like code, and a new language costs a
translation, not a designer.

**Device frames** (decided 2026-10-15) are a layer in the same renderer, not a second tool:

| Platform | Frame source |
|---|---|
| iPhone, iPad, Mac, and the laptop around a browser | Apple's product bezels ([Apple Design Resources](https://developer.apple.com/design/resources/)) |
| Android | Google's device frames (Android Studio / Device Art Generator) — website/marketing only, **not** Play listings (§3.2.1) |
| Web | our own browser-window template (address bar, window controls), optionally inside the MacBook bezel |

- **Why not `frameit`.** fastlane's framer ships its own frame set, which lags new devices by months
  and has no browser frame. Apple publishes bezels for every new device at launch, the Mac ones
  included. Placing a capture in a bezel's transparent screen area is a few lines in an HTML
  template, so `frameit` would add a Ruby and ImageMagick dependency for little.
- ⚖️ **Apple's bezels may be used to show our app, but, as we read the terms, not redistributed.** The repo is
  public, so the bezel files never enter it — they live like licensed fixture photos (§3.3). Only
  the framed renders, which show our app, are published. The terms as read on 2026-10-16 are in
  §3.2.1; counsel confirms them before the first framed image goes public.
- **The support pages keep a CSS frame** (rounded corners, border, shadow) and need no bezel: it
  works for every platform and the placeholders, and does not go stale with new hardware. Framed
  renders are for the stores and marketing.

#### 3.2.1 Third-party asset terms — as read 2026-10-16

⚖️ **Not legal advice.** What the official texts say, read on 2026-10-16/17, with the URL; counsel
confirms each ⚖️ before the asset is used in public. Re-read the source when a rule below is acted
on — every one of these pages is amended without notice.

**Apple product bezels.** The Design Resources page states no licence of its own; its Product
Bezels section defers to the
[App Store Marketing Guidelines](https://developer.apple.com/app-store/marketing/guidelines/),
whose Marketing Agreement licenses the badge, icon and *"certain product images provided to You by
Apple … only in connection with Your applications that are available for download on the App Store
… and only while You are a member of the Apple Developer Program"*.
- **Allowed:** a render of our screenshot in a bezel, on our site and in the listing — *"Use
  Apple-provided product bezels in all your marketing materials to display your app on the Apple
  devices it supports."*
- **Required:** *"Always use the latest-generation devices for which your app is currently
  developed"*; *"Use only the devices and colors provided"*; at least *"200 px onscreen"* device
  height; *"fictional account information"*; captions *"beside the product images, not on top of
  them"*.
- **Never:** modify the bezel — *"adding reflections, shadows, highlights … cropping, tilting, or
  obstructing … animating, flipping"*; *"don't add cases or covers"*; *"don't include images of
  competing products"* (*"References to multiple platforms … only in copy or with badges"*) — so
  **no iPhone bezel beside an Android frame in one layout**; *"Rendering in 3D or creating any
  simulation of an Apple product"* — read here first as "the neutral CSS frame must not imitate an
  Apple device (no Dynamic Island, no Apple-shaped hardware)"; no suggestion of *"an association
  with Apple"*; redistribution of the files (§3.3 keeps them out of the repo).
- ⚖️ **Risk accepted, 2026-10-17 (maintainer, deliberate):** the site's carousel draws an
  **iPhone-style frame of its own** around iOS captures — rounded body and rim, Dynamic Island,
  subtle side buttons, **no Apple logo** — and an Android-style one around Android captures,
  following the visitor's "iPhone | Android" switch (`web-about-tenshu-public.md` §9). This revises
  the "must not imitate an Apple device" reading above **for that CSS frame only**. What stays:
  it is drawn by us in CSS from the device's proportions and never uses, traces or derives from
  Apple's bezel files (`how-to/store-listings.md` §6); the two frames never share a stage (the switch shows one platform,
  and an entry falls back to the other platform's captures in *their* frame); the vendor guard for
  **pre-framed** images (`oneFrameVendor`) is unchanged; and a trademark and non-affiliation line
  sits under the carousel on both pages (*"Device frames are illustrations. iPhone is a trademark of
  Apple Inc., Android is a trademark of Google LLC. Tenshu is not affiliated with or endorsed by
  Apple or Google."*).
- **The Android frame (2026-10-18, maintainer request):** a generic *contemporary* Android phone,
  "inspired by current Samsung Galaxy phones but not copying them" — thin uniform bezels, a flat
  body with squarer corners than the iPhone frame, a fine neutral rail, a small centred punch-hole,
  right-edge keys (`web-about-tenshu-public.md` §9). ⚖️ Kept generic on purpose: no Samsung (or
  Google) logo or wordmark, no vendor camera-ring or lens-surround style, no curved edge display or
  distinctive edge curvature, no vendor colour names, nothing traced from or derived from a vendor's
  product images or device art. It is drawn in CSS from generic proportions like the iPhone-style
  frame, and the Android device-frame terms below (Pixel art) are untouched by it.
- ⚖️ Counsel: does the Marketing Agreement (accepted per download of the badges) govern the bezels
  too, and so restrict framed iOS renders to apps **live on the App Store**? Read literally, a framed
  render on the website before release is outside it.

**App Store / Mac App Store badges** (same page).
- **Required:** only the artwork Apple provides, *"Minimum badge height is … 40 px … onscreen"*,
  clear space *"one-quarter the height of the badge"*; the localised artwork — *"Never translate
  App Store or create your own localized badge"*; *"Use one App Store badge per layout"*, placed
  *"first in the lineup of badges"* and subordinate to the main message; the pre-order badge until
  release, then *"replace the pre-order badge with the download badge"*.
- **Never:** *"Don't modify, angle, or animate the App Store badge"*; no trademark symbols on the
  badge or outside U.S.-only material.
- **Credit lines**, wherever legal notices are given: *"When the App Store badge is used, credit
  both Apple and the Apple Logo"*; outside the U.S. *"____ is a trademark of Apple Inc., registered
  in the U.S. and other countries and regions"*
  ([third-party guidelines](https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html));
  App Store is a registered service mark
  ([Apple trademark list](https://www.apple.com/legal/intellectual-property/trademark/appletmlist.html)).
  No non-affiliation statement is required for apps (only for publications), but marks must not
  *"imply Apple's affiliation with or endorsement"*. Badge licence: live apps only (above).

**Google Play badge** ([Partner Marketing Hub](https://partnermarketinghub.withgoogle.com/brands/google-play/google-play/lockups-icons-badges/#badges);
`play.google.com/intl/en_us/badges` redirects there).
- **Required:** the current artwork from the Hub, height *"a minimum of 28px"*, clear space
  *"one-quarter of the height of the badge"*, the localised badge, *"the same size or larger than
  the other badges"*.
- **Never:** *"Don't change the badge color"*, *"remove or rearrange badge elements"*, *"alter the
  scale of the wordmark or icon"*; never a Play logo on a device screen.
- ⚖️ The legal line *"Google Play and the Google Play logo are trademarks of Google LLC."* and the
  "only for apps on Google Play / link to Play" rule were **not** found on the live Hub page (the
  downloads sit behind a terms click-through); confirm them there before the badge ships.

**Android device frames.** The frame images are Android Studio's `device-art-resources`
(AOSP `platform/tools/adt/idea`); no licence file was found for them. developer.android.com's
[content licence](https://developer.android.com/license) is Apache 2.0 for documentation and
CC BY 2.5 for *"All other site content"*, and excludes *"Google's trademarks and other brand
features"*; Google's [brand terms](https://partnermarketinghub.withgoogle.com/brands/google/trademarks-and-terms/terms-and-conditions/)
put *"screenshots, or other distinctive features"* under approval.
- **Never in a Play listing:** the
  [Device Art Generator](https://developer.android.com/distribute/marketing-tools/device-art-generator)
  says *"Do not use graphics created here in your feature image or screenshots for your Google Play
  store listing"*, and Play's [preview-asset guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
  list *"Device imagery"* under *"Avoid"* for screenshots and the feature graphic (a
  recommendation; Wear OS screenshots must not be framed at all).
- ⚖️ Counsel: may a Pixel-framed render go on our website at all, and under CC BY 2.5 with the
  AOSP attribution paragraph, or does the Pixel hardware design need Google's approval? Until then,
  Android renders use the neutral CSS frame.

**"Android" as a word** ([brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines)).
Plain text is fine as *"a descriptor … followed by a proper generic term"* and never in an app name
(*"for Android"*); capitalised, never plural or possessive. *"Any use of the Android name needs to
include this attribution"*: *"Android is a trademark of Google LLC."* No robot, no Android wordmark.

**Never-shown marks.** Placeholder content must not show third-party logos: Apple's guidelines
make us *"responsible for securing the rights to all materials used in screen content"*, and Play
lists *"Third-party trademarked characters or logos without proper permission"* under *Avoid*.

### 3.3 Where the images live

Decided by the maintainer, 2026-10-14. **No image is in git** — neither what the pipeline generates
nor the photos it is fed. The repo keeps text: manifests, generators, copy.

| | What | Where | How the repo refers to it |
|---|---|---|---|
| **Generated** | store screenshots (§3.1), later the rendered graphics (§3.2) | R2 bucket `tenshu-assets`, public at `https://assets.tenshu.app/sha256/<sha256>.png`, `Cache-Control: public, max-age=31536000, immutable` | `store/<app>/screenshots.manifest.json`: per screen (`<platform>/<device>/<lang>/NN-screen`, and `<platform>/<device>/<lang>/dark/NN-screen` for a dark-mode capture — §4.5) its sha256, width, height and size, one line each |
| **Source** | fixture photos and documents | a URL + pinned sha256 per entry (licensed photos: the **private** bucket `tenshu-store-fixtures`), or a recipe (`"generate": "placeholder"`) | `store/fixtures/<app>/manifest.json`, with source, licence, author |
| **Templates** | caption/feature-graphic templates (§3.2), when they exist | the repo | — they are code, reviewed as code |
| **Frames** | device bezels (§3.2) | the **private** bucket `tenshu-store-fixtures` — vendor terms forbid redistribution | `store/frames/manifest.json`: per frame its vendor download, sha256, licence and screen-area geometry |

- **Content-addressed.** The key is the hash of the bytes, so an object never changes, the year-long
  `immutable` cache is true, an unchanged screen costs nothing to republish, and a PR changing a
  screenshot is a one-line manifest diff — "this page now shows this image" — that a reviewer can
  open by URL. `scripts/store-publish.sh` uploads first and rewrites the manifest last; its
  `--verify` HEADs every manifest URL, so a page never links an image R2 does not have.
- **The support pages read the manifest from disk**, never the network: a slot the manifest lists
  becomes an `<img>` on R2 with its width and height, any other a placeholder. iOS pages show the
  6.9" iPhone capture.
- **The website shows WebP copies, the stores the PNGs** (2026-10-18). Every published PNG also gets
  WebP derivatives (540 and 960 px wide), made by `store-publish.sh` and uploaded next to it under
  their **own** hash (`sha256/<hex>.webp`), recorded in `store/<app>/screenshots.webp.json` keyed by
  the PNG's hash — a sidecar rather than a manifest field, so a run of an older `store-publish.sh`
  (which rewrites the manifest whole) cannot drop them. The pages use them in `srcset`; the PNG stays
  the `src` and what App Store / fastlane take. ~38 MB of PNGs became ~1.8 MB (540) + 3.6 MB (960)
  of WebP: [`how-to/store-screenshots.md`](../how-to/store-screenshots.md) §7.1.
- **Fixture files are fetched or generated at run time**, into a gitignored cache, and a download
  whose hash differs from the pin fails the run — a different file is a different photo, with a
  licence nobody checked. Licensed photos go to a *separate, private* bucket: Unsplash/Pexels-style
  terms allow commercial use but not redistribution as a collection, a public URL per photo in a
  public repo's manifest is that collection, and R2 makes a whole bucket public, never a prefix.
  ⚠ The bucket is for copies **we** hold. An entry may instead fetch from the **licensor's own
  host** (an `images.unsplash.com` URL, pinned by sha256): that is a link, not a copy we
  redistribute, so it needs neither the bucket nor keys. The landing set's recipe does this
  (`store/fixtures/landing/`, [`how-to/store-screenshots.md`](../how-to/store-screenshots.md) §9,
  2026-10-17); the bucket stays the home of a photo with no stable public source.
- **Stock photo terms (read 2026-10-16).** [Unsplash](https://unsplash.com/license): *"free,
  including for commercial purposes, without permission from or attributing the photographer"*;
  not *"sold without significant modification"* or compiled into *"a similar or competing
  service"*; the [terms](https://unsplash.com/terms) §5 exclude *"Trademarks, logos, or brands"*,
  *"People's images if they are recognizable"* and *"Works of art"* from the licence, with no
  warranty of non-infringement. [Pexels](https://www.pexels.com/license/): free, attribution *"not
  required"*; never *"sell unaltered copies"*, *"redistribute … on other stock photo or wallpaper
  platforms"*, *"imply endorsement of your product by people or brands on the imagery"*, or show
  identifiable people *"in a bad light"*. Neither mentions model or property releases.
  **Rule:** fixture photos show **no recognisable person, logo, brand or artwork** — landscapes,
  food, objects — so the uncleared parts of either licence never arise; record author and URL
  anyway. ⚖️ Counsel confirms that photos inside app screenshots in a store listing are ordinary
  commercial use under both licences (neither text addresses it).
- **A guard, not a convention:** `scripts/check-store-assets.py` (run by a pre-commit hook when
  `store/` is staged, and by both store scripts) fails on anything under `store/` that is not text,
  or on any generated capture.

**Why not Git LFS.** The files are mostly *generated*: they would be re-committed on every run and
kept forever, and every clone, cloud session and CI job would pay LFS bandwidth and storage quota
for pictures almost none of them look at. **Why not a separate repository.** A page edit, the
screenshot slot it adds and the fixture behind it should land in one PR and one review; across two
repositories they drift, and the manifest would point across a boundary nobody checks. R2 keeps the
bytes out of git while the pointer — the manifest — stays in the same PR as the page.

Infrastructure: [`deploy/cloudflare-assets`](../../deploy/cloudflare-assets/) (a sibling of the
tiles and models buckets); runbook: [`how-to/store-screenshots.md`](../how-to/store-screenshots.md)
§3 and §7. A cloud session that needs to read the images needs `assets.tenshu.app` on its
environment's network allowlist.

## 4. Support pages — a new public page pattern

### 4.1 The URL

`https://tenshu.app/apps/<app>/support/<platform>/<lang>`, for example
`/apps/drive/support/ios/de`.

**The pattern, not just the page.** Every public page about an app has the shape
`/apps/<app>/<page>/<platform>/<lang>`, with `<platform>` and `<lang>` left off where a page does not
vary by them:

| Page | Example | What it is |
|---|---|---|
| (overview) | `/apps/drive/en` | the app's marketing page, and the store's marketing URL |
| `support` | `/apps/drive/support/ios/de` | this section |
| `download` | `/apps/drive/download/android/en` | Android built 2026-11-04: the APK and its install guide (`android-direct-apk-distribution.md` item 5); other platforms future: the store badge, or the desktop installer |
| `changelog` | `/apps/drive/changelog/ios/en` | future: release notes, the same text as the store's What's New |

The segment order is fixed — app, page, platform, language — so a switcher only ever replaces the
last one or two segments, and every page type gets the same switchers for free. A page type that
needs a new dimension adds it **after** language, never between, so existing links keep working.

The maintainer also considered `/support/<app>/…` (support as its own app). `/apps/<app>/…` won
because it puts every page about one app under one prefix, which the `download` and `changelog`
pages need.

**Why not `/drive/support/ios/de`:** apex already owns `/drive` and `/control`. Both sit behind
the signed-in `ControlShell` (`web/apex/src/App.tsx`), and `/drive` redirects into the web Drive
app. A public help page under the same prefix would make one path segment mean two things: an app
you use and a page about an app. `/apps/<app>` is free, and it is the natural home for the
marketing URL (§1.1) as well.

- **Platform:** `ios`, `android`, `macos`, `windows`, `linux`, `web`.
- **Language:** `en`, `de`, `fr` (D5; French added 2026-10-16 for the site only — no app is
  translated into French, so a French page quotes the English labels with a French gloss). ⚠ The
  `de` and `fr` pages are machine translations flagged `reviewed: false` in their front matter until
  a native speaker has read them; the privacy sections must follow any change counsel makes to the
  English one. A language with no screenshot of a screen borrows the English capture
  (`resolveShot`), under its own key, so a capture published later takes over unchanged.
- `/apps/<app>/support` picks a platform from the browser's user agent and a language from
  `Accept-Language`, then redirects. Every other URL is explicit, so a store links to exactly
  the page it means. App Store Connect takes a support URL **per language**, so each store
  language links to its own page.
- The page header has two switchers, platform and language. Each is a link to the sibling URL,
  not client state, so it works without JavaScript and a shared link shows what the sender saw.
  Platform (four pills) sits at the start of the row (it is what the page is about, a content
  tab); language is the site's one language picker at the trailing edge (a page-wide setting),
  which stays there when it wraps below the pills on a phone (§4.6).

### 4.2 How the pages are built

As static HTML, generated at build time and served by apex's Caddy beside the single-page app,
for three reasons:

1. **The single-page app is the wrong host.** Its catch-all route sends every unknown path to
   `/`, it starts a session probe on load, and its first paint needs JavaScript. A support page
   has to work for a reviewer's crawler, a search engine, and a user whose app just failed to
   start.
2. **They are content, and content wants review.** Each page is Markdown (§4.3) with front
   matter, in `web/apex/content/apps/<app>/support/`, built by the same Vite build into plain HTML
   using apex's styles. A wording fix is a one-file PR.
3. **Screenshots are files.** They come from §3's output, so the support page and the store
   listing always show the same build.

As built: a small separate generator (`web/apex/support-pages/generate.ts`) for the page body,
run by the Vite build and by `pnpm -F apex build:support`. Only the header and footer are the
SPA's React components, prerendered to HTML with no runtime shipped (§4.6).

### 4.3 What a page holds

One template for every app, in this order:

1. **Get started** — the single most basic use case, as numbered steps with a screenshot each.
   For Drive: *install Control, set it up, open Drive, upload a file, find it on another device.*
   Platform-specific, because the steps differ.
2. **How {app} keeps it private** — §4.4.
3. **Common problems** — the questions support would otherwise answer by hand. The repo already
   knows several: *"Drive says set up Tenshu Control"*, *"a file shows as Local only"*,
   *"my backup never starts"* (quota), *"a new device can't see my files yet"*. Each answer links
   to the matching screen.
4. **Requirements** — OS versions, and which other Tenshu apps this one needs.
5. **Contact** — the support address, and the reminder that support never needs the recovery
   phrase.

Content per page: one Markdown file per app and language for the shared sections, with
platform-specific blocks marked inline (`::: ios` … `:::`), so a page that is the same on every
platform is written once.

### 4.4 "How {app} keeps it private"

The section the user asked for, and the one that most needs care, because it makes claims.

- **Every claim cites the document that makes it true.** For example: *"Tenshu's servers store
  only encrypted data they cannot read"* → House Rule 1 and `sync/threat-model.md`. *"A share
  link's key never reaches our server"* → the `#k=` fragment, [ADR 0018](../adr/0018-file-sharing-mode-a.md) and `file-sharing.md`.
  *"Face grouping runs on your phone"* → `ios-on-device-inference.md`. A claim with no source is
  not published.
- **Each app gets its own three or four specifics,** not the same paragraph ten times: Drive
  (end-to-end encrypted files, link sharing with the key in the link), Control (keys never leave
  your devices, the recovery phrase, no account needed), Photos (on-device recognition), Timeline
  (location stays on your devices, Wi-Fi places without a scan), Fit (health data never
  readable by us).
- **No named competitors** (D6). *"Most cloud drives can read your files"* is a claim about the
  market, and it is defensible. *"Service X reads your files"* is a claim about a company. EU
  comparative advertising has to be objective and verifiable, and ⚖️ counsel reads the section
  before it is public.
- ⚠ **A claim on this page is a commitment the threat model has to keep.** Add the support pages
  to the list of things a threat-model amendment has to re-read (House Rule 2): a change that
  weakens a published claim is a change to a public promise.

### 4.5 Theme, dark screenshots and the step layout

Decided by the maintainer, 2026-10-15.

- **Light / Dark / Auto, Auto by default.** A three-way switch in the footer of the support pages
  and of apex's landing page. The choice is per browser (`localStorage["tenshu.theme"]`, one key for
  the SPA and the pages since Caddy serves both from one origin) and is applied before first paint
  by an inline `<head>` script that sets `<html data-theme="light|dark">` — absent for Auto. The
  tokens (`web/shared/tokens.css`) already let the attribute win over `prefers-color-scheme`, and pin
  `color-scheme` with it. The script and key live in `@tenshu/web-tokens/theme`, **opt-in**: web
  Drive does not include the script, so it never gets the attribute and keeps following the OS.
  ⚠ With JavaScript off there is no attribute and no switch (it is `hidden` until its script runs):
  the page follows the OS, and nothing is broken.
- **Dark screenshots** are a second manifest key form, `…/<lang>/dark/NN-screen`, after the language
  like any new dimension (§4.1's rule). ⚠ The light key keeps its old form with no appearance
  segment, so the captures published before dark existed resolve unchanged. A slot with both
  captures carries both `<img>`s and CSS shows the one for the effective theme (a `<picture>` source
  with a media query could not follow the manual switch); a slot with only one shows it in both
  themes — a missing dark capture never becomes a placeholder. The hidden one is `loading="lazy"`,
  so it is not fetched while scripting is on; without scripting browsers turn lazy loading off and
  both are fetched (support.css has the measurement).
- **Steps put the text beside the screenshot** from 52 rem (832 px) up and stack below it; the phone
  captures are 15 rem wide (~520 px tall) there, so a step fits one viewport.
  ⚠ **Not a carousel**: it would hide steps from scanning, find-in-page, printing and many
  screen-reader users, and engagement past the first slide is low. Numbered steps with the
  picture beside the text is what help centres converge on. A possible later refinement for wide
  screens is a *sticky* screenshot column that swaps as the steps scroll past (one image visible,
  text scrolling); it costs an IntersectionObserver script, a no-JS fallback (today's layout),
  print and reduced-motion rules, and it re-introduces a hidden-state the reader cannot scan — not
  planned.

### 4.6 One header, one footer

Decided by the maintainer, 2026-10-15: *"everything should be as similar as possible, using the
same components"* — the support pages will be linked from the landing page, and a second header
style read as a different site.

- **The same components, not a copy.** The pages' header and footer are the landing page's own
  `MarketingHeader` / `MarketingFooter` (with `BrandLockup`), rendered to HTML at build time by
  `web/apex/src/landing/supportChrome.tsx` (`renderToStaticMarkup`, a `StaticRouter`, an i18next
  instance in the page's language, the CTA in its logged-out state). The generator gets that
  renderer injected; `support-pages/loadChrome.ts` loads it through Vite's module runner because
  it is TSX. Change the header once and both surfaces change; the tests compare the pages against
  the components' output byte for byte.
- ⚠ **Never hydrated, so the chrome must work with JavaScript off**: every link a plain `<a>` (the
  CTA is a link to `/start`), the phone menu a native `<details>`, the theme switch the
  `StaticThemeSwitch` that the inline script wires. A component change that needs React to be
  usable breaks the support pages — `MarketingHeader`'s own header comment says so.
- **The stylesheet is the SPA's** (`src/index.css`: Tailwind, apex's tokens, the base rules),
  compiled with `@tailwindcss/node` for exactly the classes the generated pages contain and
  optimised by Lightning CSS like the Vite build. `support.css` holds only what a support page has
  (switchers, table of contents, prose, steps, screenshots) and is compiled into
  `@layer components`, so it cannot restyle the shared chrome.
- **The language picker** (`src/landing/LanguagePicker.tsx`) is one component in both places —
  under a support page's title and in the footer beside the theme switch: a `<details>` whose
  summary names the current language and whose list holds a plain `<a hreflang lang>` per
  language, each in its own name, the current one `aria-current` — not a `<select>`, which needs
  a script to navigate, nor pills, which do not scale past a few languages. On the landing page
  it links `/en`, `/de`, `/fr` (`/` keeps detecting); on a support page both pickers link that
  page's own siblings, so a reader never lands on the marketing page. A followed link stores the pick under
  `tenshu.lang` (try/catch, per viewer, like `tenshu.theme`); it only decides what an address
  without a language shows — `/` and the `/apps/<app>/support` redirect. The redirect pages have
  no language of their own and show no picker.
- **One header and footer on every public page.** Besides the landing page and these static
  pages, the SPA's other public pages — the signup-closed notice at `/onboarding`, the share
  viewer `/s/:id`, the invite page `/i` — wear the same pair through `MarketingLayout`, with a
  logged-out CTA (no session probe) and a picker that switches language in place, since those
  pages have no URL per language. Flows holding key material (onboarding while open, `/lock`,
  `/recovery`) keep `OnboardingShell` so no nav link invites leaving mid-flow; the signed-in
  app keeps `ControlShell`.
- **The imprint** is generated beside the support pages at `/legal/imprint/<lang>`
  (`support-pages/imprint.ts`; the privacy policy is planned at `/legal/privacy/<lang>`) and every
  footer links it in the page's language. ⚖️ German is the binding text, copied from the company's
  imprint; en/fr are unreviewed translations that say so. ⚠ No phone number until the business
  one exists (`TODO(imprint)`), and no EU ODR link — that platform closed in July 2025.
- **"Support" in the header nav**, beside Docs, leads to the support hub (§4.7) on every page: a
  static page's to `/apps/support/<lang>` in its own language, the SPA's and the redirect pages'
  to `/apps/support`, which picks one. On the hub and every support page it is the current
  section (`aria-current="page"`); the breadcrumb (§4.7) says where in it the reader is.
- **No eyebrow line.** "Support · iPhone and iPad" above the title repeated the nav's current entry
  and the platform switcher's current pill. The `<h1>` is the app's store name; `<title>` and the
  meta description still name the support page and the platform.

### 4.7 The support hub and Getting started

Decided by the maintainer, 2026-10-16:

- **URLs.** The hub is `/apps/support/<lang>` (en, de, fr); a bare `/apps/support` picks the
  language the way the `/apps/<app>/support` redirect pages do — the stored `tenshu.lang` choice,
  then the browser's list. The page new users start from is **Getting started**, at
  `/apps/support/getting-started/<lang>` (hyphenated, language last), with the same bare-path
  redirect.
- ⚠ **`/support` stays free.** Nothing is generated there and nothing redirects from it: it is
  reserved for a possible future support *app*. An earlier draft of §4.6 named it as a hub
  candidate; that is withdrawn. A test asserts no file is written under `support/` and no page
  links `/support`.
- **Design.** All ten apps (Control, Drive, Photos, Maps, Timeline, Calendar, Contacts, Fit, Home,
  Mia) plus a Getting started entry, as names arranged around a carousel of representative
  screenshots of the selected one (2–3 phone captures per app and platform; Control's welcome
  screen for Getting started). On wide screens the names flank the stage, left and right; below
  64rem the stage gives way to a row of cards (below). Hover or keyboard focus on a name selects it and a mouse click
  follows its link; on touch a tap only selects, and the stage's button ("Open Drive support") opens
  the page, as does a click on the screenshots. ⚠ The hub **keeps** that button — it is the page's
  purpose; only the landing card dropped it (2026-10-17). Light and dark captures follow the theme
  like a support page's (§4.5).
- **Name lists (2026-10-17, shared with the landing page).** Every name carries its app icon
  (Getting started: Tenshu's mark; greyed-out apps' icons dimmed). On wide screens the lists
  **mirror** each other: left right-aligned with the icon after the name, right left-aligned with
  the icon before it, so each side's icons form one straight column next to the stage; 46 px
  icons, 1.25rem names, spread evenly over 75 % of the phone's height. The hub's left list has six
  entries, so on short windows (≤ 800 px) it grows past 75 % rather than crowding.
- **"iPhone | Android" (2026-10-17).** A switch at the end of the dots' row — pause and dots at the
  start, the switch at the end, one row as wide as the phone, the same on the landing page — picks
  the captures' platform for every entry — default by the visitor's OS (`defaultShotPlatform`, `hub.ts`, the one copy of the
  rule, embedded in the hub script), remembered in `tenshu.shotPlatform` — each platform in its own
  CSS frame (§3.2.1's accepted risk). `HUB_APPS[].screens` is `{ android, ios }`. An entry with one
  platform's captures shows those with its switch disabled and a note. Without JavaScript the hub
  shows Android and no switch. The hub's phone is never narrower than that row (14.875rem,
  `--phone-min`), so the row stays one line on the hub at every size; it is 238 px at 1280×800 and
  390×844 (`web-about-tenshu-public.md` §9 *One control row*).
- **Narrow: a row of cards (2026-10-18, replacing the chips).** Below 64rem (phones and tablets)
  the hub shows the landing page's swipeable row — one card per entry, Getting started first, each
  its icon, name, "Coming soon" where it applies, its first screenshot and the "Open X support"
  label; the whole card is the support link (none for a coming-soon card, which still takes focus).
  One "iPhone | Android" switch above the row, the app icons as the position indicator under it.
  It is static markup that swipes and snaps without JavaScript; `HUB_SCRIPT_BODY` reveals and wires
  the switch and the indicator and re-points the cards' links at the visitor's platform like the
  stage's. Contract and rationale: `web-about-tenshu-public.md` §9 *Narrow*.
- **One frame (2026-10-17, replacing "no auto-rotation" and the stage that glided to the selected
  entry's phone count).** The stage is one phone frame of fixed size whatever is selected, so the
  names never move under the pointer; the selected entry's screenshots cross-fade inside it every
  4 s, with dots to pick one and a pause/play button (WCAG 2.2.2, APG Carousel pattern), holding
  while the pointer or keyboard focus is on the phone or dots, the tab is hidden or the stage is
  offscreen, and not at all under `prefers-reduced-motion`. ⚠ The rotation is within an entry
  only: the **selection never changes on its own**. Rationale and the full contract:
  `web-about-tenshu-public.md` §9 *One frame*.
- **Shared with the landing page (2026-10-16).** The carousel is one component,
  `web/apex/src/landing/carousel/AppCarousel.tsx` + `appCarousel.css`: the hub renders it statically
  (`supportChrome.tsx`, `renderToStaticMarkup`) and enhances it with its own script; the landing
  page's Apps section renders it live. The phone is drawn at real size (75 mm) in a CSS frame of its platform,
  shrinking only where the window cannot fit them, and the stage fills the window under the page
  head — `web-about-tenshu-public.md` §9 carries the frame modes and sizing rules.
- **Progressive enhancement.** The hub is static HTML first — two lists of entries, each with its
  screenshots and a real link — so it reads the same for App Review, a crawler and a visitor with
  JavaScript off. The carousel is one small inline script (`support-pages/hub.ts`
  `HUB_SCRIPT_BODY`), a literal string like the other inline scripts; it moves the panels into a
  stage, reveals the rotation controls and the platform switch (in the static markup, `hidden`),
  applies the visitor's platform, runs the cycle and points
  each link at the visitor's own platform when the app has a page for it (the
  static link is the app's `defaultPlatform`). The app bundle is never loaded.
- **Coming soon.** Only apps with support pages are links. The rest are greyed out, marked
  "Coming soon" (the landing page's own translation), with no link, and an app without
  screenshots shows a neutral placeholder card. Enabling an app is **one line**: `support: true`
  in `HUB_APPS` (`support-pages/hub.ts`) once `content/apps/<app>/support/` exists — the build
  fails if the two disagree, either way.
- **Breadcrumb.** Every support page opens with "Support › Drive › Android" (translated): Support
  links the hub in the page's language, the app's name its `/apps/<app>/support` redirect page,
  the platform is plain text with `aria-current="page"`, all in a `<nav aria-label>`. Getting
  started has "Support › Getting started"; the redirect pages have the steps they know.
- **Getting started content** (`web/apex/content/support/getting-started/<lang>.md`): what Tenshu
  is; install Tenshu Control first and set it up, linking Control's page per platform; then add
  apps and other devices; how privacy works in three sourced items (§4.4 applies); where to get
  help. Every sentence reuses approved wording from the Control and Drive pages and the landing
  page — no new claims, and no "open source" claim while the repository is private. ⚠ de and fr
  are `reviewed: false` like the other translations.

**Roadmap.** The other eight apps get their support pages one at a time (phase S6, one per PR);
each flips its hub entry to `support: true` in the same PR, and adds its `screens` once the
screenshot pipeline captures it. A support app, if it is ever built, takes `/support`.

- **Fit (S6, 2026-10-22)** — the first: iOS and Android only (not distributed on macOS or the web yet), en/de/fr.
  Its claims follow `store/fit/claims.md` and the Android code; Android has no published captures
  yet, so its Fit slots render as "Screenshot coming soon" placeholders and the hub shows the
  iPhone set with the switch disabled (`HUB_APPS` `screens.android` empty, as on the landing page).
- **Calendar and Contacts (S6, 2026-10-22)** — the same shape as Fit: iOS and Android only, en/de/fr,
  every claim from `store/<app>/claims.md` and the Android code. ⚠ The two platforms differ in
  substance, not only in labels: Contacts cannot add, edit, import or invite on iOS, and on Android
  Calendar's reminders are Control's notifications. The iOS pages and the hub use the published
  iPhone captures (`store/<app>/screenshots.manifest.json`); Android has no captures yet, so
  its slots render as "Screenshot coming soon" placeholders and the hub's Android switch stays
  disabled (`HUB_APPS` `screens.android` empty).
- **Photos (S6, 2026-10-24)** — the same shape: iOS and Android only, en/de/fr, iOS claims from
  `store/photos/claims.md`, Android's from `photos-shared-ui`/`photos-shared-logic`. ⚠ The platforms
  differ in substance: iOS has no People (no face grouping, PIO-8; on-device AI off in the shipping
  build), Android has People through Control's *Group photos by people* and its backup-source list
  in Settings; **neither** can share, export or edit a photo (PH-G11), and the pages say so. The iOS
  page and the hub use the App Store set (`ios/iphone69/en/01–05`); Photos has no `android/phone`
  captures (only the landing set, which is never a support shot), so the Android block shows
  Control's screens only and the hub's Android switch stays disabled.

### 4.8 The pricing page — the one place that names a price

Decided by the maintainer, 2026-10-31: app pages, store copy and support pages say only "5 GB
free" and link here, so a price change touches one page, not every page.

- **URL:** `/pricing/<lang>` (en, de, fr), generated like the roadmap (`support-pages/pricing.ts`);
  Caddy 302s `/pricing` by Accept-Language. The header's **Pricing** entry (between Vision and
  Roadmap) and the landing FAQ's "Is Tenshu free?" answer lead here.
- **One copy of the figures:** `web/apex/src/control/services/plans.ts` holds quotas, prices and
  product ids. The in-app plan picker (`tiers.ts`) and this page both render from it. The Terms
  must state prices too (it is the contract), so `generate.test.ts` fails when the Terms' plan list
  and `plans.ts` disagree. What is charged is still the Stripe price the commerce service maps the
  product to; change that first.
- **What it says:** every app is free; three alike cards (no "most popular" badge, §13 of
  `control-subscription-ux.md`); prices include VAT and may differ in the stores; what never costs
  anything; "coming later" (yearly plans, pay-as-you-go AI). Each FAQ answer says no more than the
  Terms section it links (`#storage`, `#cancellation`, `#withdrawal`, `#plans`, `#closure`).
- ⚠ **Every card's button leads to `/start`.** The plan picker's checkout path
  (`/control/services/…`) sits inside `ControlShell`, which sends a browser with no realm (every
  visitor of this page) back to `/`. So the page says everyone starts on Free and upgrades in
  Control → Services; a "start with Basic" flow through onboarding would be new work.
- ⚠ **No "open source" on it** while the repository is private (the test checks), and nothing
  "later" described as available.

### 4.9 App marketing pages: `/apps/<app>/<lang>`

Shipped for Drive, then Photos (2026-10-31), designed on the canvas as direction A, then Control
(2026-11-01) in the same direction with no canvas board of its own: its privacy sentences are
`store/control/claims.md`'s. Calendar, Contacts and Fit (2026-11-01) the same way, from their `store/<app>/claims.md`. With Fit,
every app the landing carousel links has its page; Home and Mia have neither page nor link. Each page lists its
own store targets (`AppPageSet.downloads`), and its "what we do differently" blocks are data with a
picture each (`DifferenceWidget`: sees / shareLink / flow / table / code), so a new app is copy only. Built like the pricing
page: `web/apex/support-pages/appPages.ts` holds the copy (en source, de/fr unreviewed) and the body,
`generate.ts buildAppPages` writes `/apps/<app>/<lang>` plus the neutral language list, and Caddy's
`@appPageNeutral` 302s `/apps/<app>` by Accept-Language.

- **The carousel switches one app at a time.** `landingApps.ts APPS_WITH_PAGES` lists the apps with
  a page and must equal `APP_PAGES` (tested), and so must the Caddy regex (tested). An app without a
  page keeps leading to its support entry.
- **Store buttons only where the app is.** `AppPageSet.downloads` holds a URL per target, or null,
  which renders "Coming soon" and links nowhere. The hero row starts with the first available
  target as primary; an inline script (CSP-hashed) moves the detected system first as primary only
  if the app is there, and otherwise just says it is coming. Without JavaScript the row stays as
  built. Parity rows are `yes` / `soon` / `no` (not part of the app), each with its own icon.
- **No price** (§4.8 links it), **no competitor named** (D6), **no "open source"**, **no em dash**:
  `generate.test.ts` checks the last three.
- The hero shows the `phonelanding` captures (the landing page's photos), falling back to English.

## 5. Phases — and where to start

| Phase | Delivers | Needs |
|---|---|---|
| **S0** | The ITMS-90683 upload errors fixed (done) | — |
| **S1** | `/apps/<app>/support/<platform>/<lang>` for **Drive on iOS, en + de**: the content model, the static build, the switchers, sections 1, 3, 4, 5 with placeholder images | D2, D3, D5 |
| **S2** | `store/` for Control and Drive, en + de, and `scripts/store-push.sh` for the App Store | an App Store Connect API key (Admin creates it) — ✅ **en built 2026-10-15** (§2.2 as built); de, and the first real push, open |
| **S3** | The screenshot pipeline for Control and Drive on iOS, feeding S1 and S2 | the fixture content (§3.1) |
| **S4** | The privacy section (§4.4) for Control and Drive | ⚖️ counsel |
| **S5** | Android: `supply`, the feature graphic, the Play screenshots | a Play service account; [`play-store-declarations.md`](../how-to/play-store-declarations.md) |
| **S6** | The other eight apps, one per PR — Fit, Calendar and Contacts built 2026-10-22, Photos 2026-10-24 (§4.7) | each app archived (`ios-testflight.md` §7) |
| **S7** | Captioned and framed screenshots (Apple bezels, then Android and the browser frame, §3.2) and promotional artwork | S3; the bezels in the private bucket — groundwork built 2026-10-15: `scripts/store-frame` (`web/store-frame`), `store/frames/manifest.json` with the two Apple bezels unpinned, framed renders published as device `<device>framed` ([`how-to/store-listings.md`](../how-to/store-listings.md) §6) |

**Start with S1.** It is the only phase that settles something every other phase depends on —
the URL (which the store metadata references), the content model (which the descriptions borrow
copy from), and the screenshot slots (which S3 has to fill). It also needs no credentials and no
counsel. S2 can run beside it once the API key exists.

**Not on this list but on the same critical path:** the privacy policy and imprint (§1.4). Nothing
here can make a listing public without them, so they should start with counsel now, in parallel.

## 6. Open questions

- ✅ **Store names: "Tenshu <App>"** — "Tenshu Drive", "Tenshu Control", … (maintainer,
  2026-10-14; reasoning in §6.1). The bundles' display names stay short ("Drive").
- ✅ **iPad: kept** (maintainer, 2026-10-14). The apps are mostly compatible, and early iPad use is
  expected to be small; if users report problems it can still be dropped. This makes 13" screenshots
  mandatory and means a reviewer may test on an iPad.
- **Who writes and translates the copy.** The German texts need a native reviewer either way.
- ✅ **Support address: `support@tenshu.app`**, one for all apps, forwarding into the company
  inbox (maintainer, 2026-10-14; reasoning in §6.2). ⚠ The alias does not exist yet.

### 6.1 Store names

Apple's rules (App Review Guidelines 2.3.7, and the App Store Connect limits): at most 30
characters, unique on the store, no keywords, prices or other apps' names stuffed into it, and it
should match what the app is. "TS Drive" and "Tenshu Drive" both comply. **Decided: "Tenshu
Drive"**, for these reasons:

- **Length only matters in the store.** The home screen shows the bundle's display name, which
  stays "Drive". "Tenshu Drive" is 12 of 30 characters, and the store gives it a full line.
- **"TS" means nothing to someone who has not heard of Tenshu**, and that is everyone at launch.
  Search is the main way an unknown app is found, and nobody searches for "TS".
- **"TS" is taken elsewhere.** It reads as TypeScript to developers, and it is a common prefix
  of other apps and brands, so it does not distinguish us.
- **The brand is the thing being built.** Ten apps that each repeat "Tenshu" teach the name.

Either way, the subtitle (30 characters) carries what the app does, e.g. *"Encrypted file
storage"*.

### 6.2 The support address

Neither store requires a particular domain. Apple asks for a support URL, and that page must let
a user reach you; the review contact is separate and private. Play shows a developer email
publicly. A German company's imprint needs a working email anyway.

`contact@cajetan.company` would work. **Decided: one** address on the product domain,
**`support@tenshu.app`**, forwarding into the same inbox:

- An address printed in a store listing and on support pages is expensive to change later. An
  alias keeps the published address stable while the inbox behind it can change.
- Users see the brand they downloaded, not a company name they do not know.
- One address for all apps is enough; the app is in the subject or the form. Per-app addresses
  add work without telling you anything the message does not.

