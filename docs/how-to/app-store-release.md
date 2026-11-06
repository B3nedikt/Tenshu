# App Store release — from a TestFlight build to "submitted for review"

**Status (2026-10-17):** nothing is submitted. Control and Drive have their listing files, their
iPhone screenshots and review notes; the other eight have a privacy-label draft and review notes
only. The submission is blocked on items no file in this repo can close: the privacy policy page
on production (it is built — `web/apex/support-pages/privacy.ts`, 2026-10-23 — and serves once an apex
image carrying it is deployed; counsel review is still owed), Control being live before the others, iPad screenshots, the export compliance codes, the DSA trader
status and the support pages on production (§1, §3.2, §4).

This is the checklist that takes **one app** from *a build is in TestFlight* to *Distribution → Add
for Review* in App Store Connect. It collects what the other documents each own half of:

| Document | Owns |
|---|---|
| [`ios-testflight.md`](ios-testflight.md) | the binary: archive, export, upload, capabilities, export compliance |
| [`store-listings.md`](store-listings.md) | the listing files under `store/` and their push with `scripts/store-push.sh` |
| [`store-screenshots.md`](store-screenshots.md) | the 6.9" iPhone and 13" iPad captures |
| [`store-presence-and-support-pages.md`](../proposals/store-presence-and-support-pages.md) | the design: §1.1 every field, §1.4 the legal path, §4 the support pages |
| `store/<app>/privacy-label.md` | ⚖️ the App Privacy answers, typed in by hand |
| `store/<app>/review/notes.txt` | the App Review notes (§3) |

Android (Play) and the Mac App Store are separate tracks: Play is phase S5 and has its own
[`play-store-declarations.md`](play-store-declarations.md); the Mac App Store is its own track
(`desktop-macos-distribution.md` D11/D12 and §4.3.2; the sandbox spike is deleted and the production
Control and Drive builds carry the App Review question).

## 1. The order — Control first, alone

⚠⚠ **Every other app needs Control installed on the same device, so App Review must be able to
install Control from the App Store.** A reviewer of Drive cannot install a Control that is only in
TestFlight, and the "Get Tenshu Control" screen of every other app says *not on the App Store yet*
while `TENSHU_CONTROL_APP_STORE_ID` is empty (`ios-onboarding/onboarding.yml`, ADR 0022/0025: on iOS
a store link is offered exactly when the id is configured). So:

1. Submit **Control** on its own. It needs no other app.
2. When Control is approved, **release it** (an approved app held for manual release is not
   downloadable, so a reviewer still cannot install it).
3. Set `TENSHU_CONTROL_APP_STORE_ID` to Control's Apple ID (App Store Connect → App Information →
   *Apple ID*, a number) in `ios-onboarding/onboarding.yml`, in a PR, and archive and upload a new
   build of each other app from that commit.
4. Submit those builds. Drive first: Photos shows what Drive's backup put in the realm.

⚠ **Hold back the apps a reviewer cannot exercise on one device.** An app that does little is
rejected under guideline 4.2 (minimum functionality) or 2.1 (app completeness). Read from the Swift
sources on 2026-10-17 (release configuration; every `DEV_HARNESS` path is compiled out):

| App | What a reviewer can do on a fresh device | Verdict |
|---|---|---|
| Calendar | events, calendars, subscriptions, `.ics` import/export, reminders | submit |
| Fit | Apple Health import, record and save a workout | submit |
| Photos | albums; the library fills from Drive's backup | submit after Drive |
| Timeline | turn capture on; until iOS delivers a visit, minutes after leaving a place, the feed shows an empty state that says why | ⚠ risky: a reviewer who does not carry the device sees an empty app |
| Maps | lists are empty (places come from Timeline), "Saved" cannot save, "Contribute" is a stub, the map shows only the bundled Florence demo tiles, and region download says it is not available | ⚠ hold back |
| Contacts | rename yourself; no add-contact and no invitation UI on iPhone | ⚠ hold back |
| Home | read-only; empty without a Tenshu Hub; Settings is a placeholder | ⚠ hold back |
| Mia | chat over an empty realm answers "nothing matched"; never asked on a real device (`mia-query-layer.md` §19) | ⚠ hold back |

The review notes of all ten are written regardless, so a held-back app needs only a build.

## 2. Status per app

Today's state, 2026-10-17; the listing rows 2026-10-23. ✅ done · ◐ partly · — not started · n/a does not apply.

| | Control | Drive | Photos | Maps | Timeline | Calendar | Contacts | Fit | Home | Mia |
|---|---|---|---|---|---|---|---|---|---|---|
| App Store Connect app record | ✅ | ✅ | ✅ | — | — | ✅ | ✅ | ✅ | — | — |
| Build uploaded to TestFlight | ✅ 10-14 | ✅ 10-16 | — | — | — | — | — | — | — | — |
| Archive passes `ios-archive.sh` | ✅ | ✅ | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 | ✅ 10-17 |
| Release build walked through on a phone | — | — | — | — | — | — | — | — | — | — |
| Listing text, `en-US/` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — |
| `claims.md` (privacy sentences' sources) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ◐ Play only | ◐ Play only |
| Categories, `age_rating.json`, `listing.json` | ✅ | ✅ | ✅ | — | — | ✅ | ✅ | ✅ | — | — |
| Screenshots, iPhone 6.9" | ✅ | ✅ | ✅ | — | — | ✅ | ✅ | ✅ | — | — |
| Screenshots, iPad 13" | — | — | ✅ | — | — | — | — | — | — | — |
| Review notes, `review/notes.txt` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `privacy-label.md` draft | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ⚖️ privacy label reviewed by counsel | — | — | — | — | — | — | — | — | — | — |
| Support page (dev lab `tenshu.xyz` only) | ◐ | ◐ | ◐ built, not yet deployed | — | — | — | — | — | — | — |
| Export compliance code in `project.yml` | — | — | — | — | — | — | — | — | — | — |
| Pushable by `store-push.sh` | ✅ | ✅ | ✅ | — | — | ✅ | ✅ | ✅ | — | — |
| Submitted for App Store review | — | — | — | — | — | — | — | — | — | — |

The build rows come from `ios-testflight.md` (Control `1.0 (202610141319)`; Drive `202610161256`,
uploaded without the encryption key, §4). *Pushable* means `store-push.sh <app> --dry-run` assembles
the listing and renders its preview; a real push still needs the app record (row 1), and a submission
the iPad 13" set — every app builds with `TARGETED_DEVICE_FAMILY` "1,2", so App Store Connect wants
both slots, and `store-push.sh` warns while the iPad set is missing. ⚠ **Photos** has its categories
and age rating, but its only iOS captures are the landing page's (`ios/iphone69landing`), which are
not App Store screenshots: it gets `listing.json` and moves to `PUSHED_APPS` once an `ios/iphone69`
set is published, and until then `store-push.sh photos` refuses and says so
([`store-listings.md`](store-listings.md) §2, §4).

## 3. The checklist, per app

Copy this block into the PR or issue that releases an app, one per app. **Who** is the column that
matters: an agent can close every *Files* item with a PR; the *App Store Connect* items have no API
or are answered once by the account holder; ⚖️ items wait on counsel; *Infrastructure* items are
production deployments.

### 3.1 Template

```markdown
## <App> — App Store release <version>

### Files in the repo (agent, by PR)
- [ ] store/<app>/en-US/: name ("Tenshu <App>"), subtitle, description, keywords, promotional text,
      support_url — `python3 scripts/store-listing.py check <app>` is green
- [ ] store/<app>/claims.md: every sentence under the description's privacy heading has a source
- [ ] store/<app>/primary_category.txt, secondary_category.txt, age_rating.json, listing.json
- [ ] screenshots, iPhone 6.9" (1320 × 2868): published, in screenshots.manifest.json, listed in listing.json
- [ ] screenshots, iPad 13" (2064 × 2752): the same — mandatory, the apps declare iPad support
- [ ] support URL https://tenshu.app/apps/<app>/support/ios/en — the page's content is in web/apex/content/apps/<app>/support/
- [ ] marketing URL (store/shared/en-US/marketing_url.txt, https://tenshu.app)
- [ ] privacy policy URL (store/shared/en-US/privacy_url.txt, https://tenshu.app/legal/privacy — Caddy 302s it to /de, /fr or /en by the browser's language)
- [ ] store/<app>/review/notes.txt: matches the build being submitted (labels, permission prompts)
- [ ] ITSEncryptionExportComplianceCode: <code> beside ITSAppUsesNonExemptEncryption in <app>-ios/project.yml
- [ ] (not Control) the build was archived after TENSHU_CONTROL_APP_STORE_ID was set (§1)
- [ ] `scripts/store-push.sh <app> --dry-run`, Preview.html read by a person

### App Store Connect, by hand (maintainer)
- [ ] the app record exists: bundle id app.tenshu.<app>.ios, name "Tenshu <App>", primary language English (U.S.), SKU
- [ ] the build has finished processing and is attached to version <version>
- [ ] `scripts/store-push.sh <app>` pushed the listing from a clean tree (the agent cannot use the ASC key)
- [ ] App Privacy: every answer from store/<app>/privacy-label.md, after counsel; agrees with PrivacyInfo.xcprivacy
- [ ] App Privacy → the privacy policy URL reads back as the pushed one, and the page loads
- [ ] age rating: the pushed answers read back in the console; the judgement calls in §4 checked
- [ ] categories and copyright read back ("2026 Cajetan UG (haftungsbeschränkt)")
- [ ] Pricing and Availability: Free; all countries (or the EU excluded while the DSA status is open, §3.2)
- [ ] Availability: untick "Make this app available on Mac (iPhone and iPad apps on Apple silicon)" and Apple Vision Pro
- [ ] export compliance: "uses encryption — standard algorithms", France covered (ANSSI 34767729); or the build's code is set
- [ ] content rights: third-party content? (Maps, Timeline and Fit draw OpenStreetMap-derived tiles, attributed in the map: yes, with the right to use it; the others: no)
- [ ] App Review Information: contact + notes set once with `scripts/store-push.sh <app> --review-info` (§5.1); then the demo account by hand (sign-in required: Yes)
- [ ] Version Release: manual release (so Control's id can be set before the others ship, §1)
- [ ] Distribution → Add for Review → Submit (⚠ not TestFlight → External Testing, §4)

### ⚖️ Counsel
- [ ] the privacy policy text (one page for every app; Fit's health data and Timeline's location named) — published
      2026-10-23 *before* counsel review, at the maintainer's decision; the review is owed, with the open items
      in [`store-presence-and-support-pages.md`](../proposals/store-presence-and-support-pages.md) §1.4
- [ ] store/<app>/privacy-label.md, its open items answered
- [ ] the privacy and health/location claims in the description (claims.md) and on the support page
- [ ] the imprint (it links from the support page's footer)

### Infrastructure (production, not the dev lab)
- [ ] https://tenshu.app/apps/<app>/support/ios/en serves the support page (not the landing page)
- [ ] https://tenshu.app/legal/privacy redirects (302) to the policy in the browser's language, and /legal/privacy/en
      serves it (built by `web/apex/support-pages/privacy.ts`; the redirect needs an apex image carrying
      web/apex/Caddyfile's `@legalNeutral` rule — `curl -sI -H 'Accept-Language: de-DE' …` shows `Location: /legal/privacy/de`)
- [ ] support@tenshu.app and hello@tenshu.app deliver to the inbox (send a test from outside)
- [ ] a real sign-in on production commerce from the submitted build: the OTP arrives (only if sync is reviewable, §5.2)
```

### 3.2 Account-wide, once

These are answered once for the developer account, not per app, but each blocks every submission:

- [ ] **DSA trader status** (App Store Connect → Business → *Digital Services Act*): Cajetan UG is a
  trader, which needs a verified address, **phone number** and email. ⚠ The business phone number
  does not exist yet. Without a trader declaration the apps are not distributed in the EU.
- [ ] **Agreements**: Business → Agreements has nothing waiting for the Account Holder (the free-apps
  agreement is enough; nothing here is paid).
- [ ] **App Review contact phone**: the same gap. The contact is private to Apple, so a personal
  number works until the business number exists.
- [ ] **App encryption documentation**: uploaded; each app gets its code once Apple approves it.

## 4. Known traps

- **ITMS-90129** — a display name that is a system app's ("Photos", "Maps") is refused at upload.
  Every app is "Tenshu <App>" (`ios-testflight.md` §7).
- **ITMS-90592** — once an app has encryption documentation in App Store Connect, a build with
  `ITSAppUsesNonExemptEncryption: true` and no `ITSEncryptionExportComplianceCode` is refused, until
  Apple issues the code. While it is pending, upload from a local branch with the key removed and
  answer the question per build (Drive, 2026-10-16).
- **Maps' location wording** — Maps links CoreLocation only through MapLibre and never asks; its
  purpose strings say so. Do not "fix" them into a real request, and expect the reviewer to ask
  (review notes say it).
- **HealthKit, guideline 5.1.3** — health data may not be used for advertising, sold, or stored in
  iCloud; the privacy policy must say how it is used. Fit stores it in the user's encrypted realm on
  Tenshu's relay (⚖️ `store/fit/privacy-label.md` item 4). The HealthKit capability must be on the
  App ID, and the description must say Fit works with Apple Health.
- **The privacy policy is linked inside every app, guideline 5.1.1(i)** (and Play's User Data
  policy): Control → Settings → About (and a "How we handle your data is explained in our privacy
  policy" line on the sign-in sheet, because `register` sends `PRIVACY_VERSION`; ⚠ it informs and
  never says "you accept" — the GDPR legal bases are Art. 6(1)(b)/(f), not consent); Drive → Settings; Photos →
  Settings (⚠ now in every build, not only with on-device AI); Fit → Profile → About; Contacts →
  You; Timeline → Settings → About; Mia → Settings; Home → Settings (the placeholder tab); Maps →
  the settings gear's *Offline maps* screen. The same rows exist on Android and desktop, and the web
  Control's Settings, its sign-up and web Drive's Settings link it too. Calendar has no settings,
  profile or about screen, so its link sits at the end of the *Calendars* management screen, with
  the open-source licences row (`docs/oss-licences.md` §2).
  The language rule (German → `/de`, French → `/fr`, else `/en`) is written three times — Kotlin
  `PrivacyPolicy` (`:core-common`), Swift `ios-release/Sources/PrivacyPolicy.swift` (compiled into
  every app by the `ReleaseApp` template), TypeScript `web/shared/legal.ts` — and
  `PrivacyPolicyParityTest` (`:ui-core`) pins the two copies to the Kotlin one.
- **Background location, guideline 5.1.5 / 2.5.4** — Timeline asks for *Always*; the review notes
  explain why, and capture is off until the user turns it on. Fit declares `UIBackgroundModes:
  location` and uses it only while recording; its notes say so.
- **The Health sheet follows a tap, never a launch** — `HealthKitImporter.autoRefreshReady` stays
  false until the read request has been answered, so the launch/foreground refresh cannot be what
  shows the sheet; the first import is *Import from Apple Health* (Home's empty state, Profile →
  Data sources). Do not "simplify" that gate away: without it a fresh install put the all-types
  sheet up unasked (5.1.1 / 5.1.3).
- **No sample data on an iPad without HealthKit** — `HealthKitImporter` offers no source there and
  Home says Apple Health isn't available; it used to fall back to the labelled fixture
  (`ReferenceHealthImporter`), which a manual import wrote into the realm. Keep the fixture out of
  every shipping iOS path.
- **Maps' region copy must match the iOS renderer** — iOS draws only downloaded regions, the
  bundled Florence demo, or a sketch; it never streams tiles (B-4). Copy that says otherwise is a
  2.3 risk (`RegionPickerView.swift`, `MapsPlaceDetailView.swift`).
- **Add for Review is the App Store review.** Distribution → *Add for Review* submits the version
  for the App Store. TestFlight's beta review is TestFlight → *External Testing* → a group → *Submit
  for Review*. They are separate queues and a pass in one says nothing about the other.
- **Long pasted `!` commands wrap and break** in an agent session's shell. Put the command in a
  script file and run the file (as the Mac App Store spike's upload did).
- **The agent cannot use the App Store Connect key.** Uploads and `store-push.sh` pushes are the
  maintainer's runs.
- **`overwrite_screenshots`** replaces every screenshot set of the pushed language, iPad included:
  hand-uploaded iPad screenshots are deleted by a push that does not carry them
  (`store-listings.md` §5). Use `--no-screenshots` until the manifest has the iPad set.
- **A build bakes its commerce origin.** A build from a checkout without `tenshu.billingMock=false`
  and `TENSHU_COMMERCE_URL` uses the in-process dev minter, which production's relay does not trust,
  so "Turn on sync" fails in review. Archive on the Mac whose environment carries both.
- **Age rating judgement calls** — Drive's `userGeneratedContent: false` (share links,
  `store-listings.md` §5); Fit probably answers `healthOrWellnessTopics: true`; Calendar's reminders
  and Contacts' invitations are not messaging.
- **Drive's privacy wording** — the claim "file and folder names are encrypted too" sits beside a
  share link's plaintext file name on the relay (`store/drive/privacy-label.md` item 1). ⚖️ Counsel
  reads both together.

## 5. App Review notes and the demo account

### 5.1 How the notes reach App Store Connect

`store/<app>/review/notes.txt` is the whole of it. `scripts/store-listing.py assemble` copies it to
`metadata/review_information/notes.txt` in the assembled folder, and **only `store-push.sh --review-info`**
uploads it, as the version's *App Review Information → Notes* (`fastlane/Fastfile`, lane `push`). A plain
push deletes it before deliver runs and sends no review details at all. `check` fails when
the file is missing or over 4000 characters, and when anything else is in `review/`.

deliver would also read `first_name.txt`, `last_name.txt`, `phone_number.txt`, `email_address.txt`,
`demo_user.txt` and `demo_password.txt` from the same folder. **None of them is committed**: the
contact is personal data and this repository is public, and a demo password in it would be
published. The contact comes from the Keychain on `--review-info` (store-listings.md §5); the demo
account is entered by hand.

⚠⚠ **Any `--review-info` push turns *Sign-in required* OFF** — deliver sets
`demo_account_required=false` whenever it writes review details without a demo account. So after one
(the first per app, or any later notes edit) re-enter the demo account in App Store Connect. A plain
push never touches the section.

⚠ Control, Drive, Fit, Calendar, Contacts and Photos are pushable today (`PUSHED_APPS` in
`scripts/store-listing.py`). The other four notes are ready for the PR that completes their listings;
until then, paste them into the console.

**What the notes say, for every app:** install Tenshu Control first and set it up ("Start fresh on
this device", "Continue", "I've saved it"); then open the app; what to look at; that everything
reviewable works on one device without an account; that "Turn on sync" in Control is optional and
signs in with an emailed one-time code; and that the data is end-to-end encrypted, so we cannot see
or restore it from the server. Per app they add the permission prompts and when they appear.

### 5.2 Does App Review need a demo account?

**Decided (2026-10-24): yes — a review sign-in, though signing in stays optional.** Every flow a
reviewer can see on one device works without an account: Control sets up a realm locally, and each
app reads and writes it. An account only adds sync (which needs a second paired device to show
anything) and storage plans. But a reviewer who wants to try "Turn on sync" would need a mailbox for
the emailed code, and Guideline 2.1 asks for access to everything behind a login. So the version's
**App Review Information → Sign-in required** carries:

- **User name:** `<address from Keychain commerce-review-address-apple>` (shaped `apple-review-<suffix>@tenshu.app`)
- **Password:** `<code from Keychain commerce-review-signin-code-apple>`

That address is unpublished and Apple's alone (Google has its own); it accepts its own fixed
**6-digit** code in place of the emailed one and is never sent mail by commerce
(`commerce-accounts-and-sessions.md` D13, threat model T-CB-12). Six digits so it fits the existing
code field, with no client change; what keeps it safe is a persisted lockout (10 wrong codes per
account per rolling 24 hours: ≈ 274 years to exhaust, ≈ 0.37 % a year) on top of an address nobody
outside the two stores knows. It stays on between submissions. ⚠⚠ The address and the code go
**only** into App Store Connect's private fields — never into `store/<app>/review/`, a
`demo_user.txt`/`demo_password.txt` (§5.1: none is committed, and this repository is public) or a
commit. Generating them, putting the HMAC on the box and rotating: `play-console-answers.md` §14.

What the notes say, for every app (`store/<app>/review/notes.txt`): signing in is optional; the
sign-in details in App Review Information are for trying sync — "Turn on sync" in Control, the user
name as the email, the password where Control asks for the 6-digit code from the email (no email is
sent to that address). Buying a storage plan needs that free sign-in first — but on iOS that clause
is moot today: iOS Control sells no plan (`control-ios/Sources/ServicesView.swift`: "Deliberately NO
purchase affordances"), so the iOS notes say "a new account is free" and stop there.

The options weighed before the decision, kept for the record: no demo account (any readable address
works; a reviewer without one sees an error); a review mailbox we can read (a live mailbox credential
held by Apple, and Scaleway TEM only sends); a redeem code (builds purchase UI iOS deliberately
lacks); and the fixed code on an allowlist, chosen with its threat-model entry.

## 6. Not verified

- No app has been submitted, so none of this has met a reviewer. Update §4 with what the first
  rejection or approval says.
- The labels and prompts in the review notes were read from the Swift sources on 2026-10-17, not
  from a release build on a phone.
- Whether App Review accepts an app that requires a companion app from the same developer, installed
  first, is Apple's call; guideline 2.1 allows it when the notes say how.
