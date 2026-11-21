# Store screenshot stories — one story per app, and desktop captures

**Status:** v0.1 (2026-11-19) — **plan, no code.** The per-app sets in §3 are agreed with the
maintainer; nothing is captured yet. Feeds the apex hero redesign (§5), the store listings and
the support pages.

**Scope:** which screens each first-party app's screenshot set shows and in what order, for
Drive, Photos, Calendar, Contacts and Fit; the desktop captures none of them have today; the
seeded content those captures need. The capture *mechanics* stay in
[`how-to/store-screenshots.md`](../how-to/store-screenshots.md), the listing plumbing in
[`store-presence-and-support-pages.md`](store-presence-and-support-pages.md).

---

## 1. Why

The current sets are whatever screens the UI tests reached first (`01-files`, `02-photos`,
`03-preview` …). They show that each app exists, not why anyone would pick it: none of
Photos' People, search by content, Contacts' invitation handshake or Drive's Finder integration
appears in any published set. And there are **no desktop captures at all** — every
`store/<app>/screenshots.manifest.json` carries only `android/phone`, `ios/iphone69` and
`ios/ipad13` (plus the `*landing` stock-content sets for Drive and Photos, phone only).

## 2. Rules every set follows

1. **Frame 1 is the promise** — the app's everyday main screen with believable content. It is
   also the frame the apex hero cycles through (§5), so it must read at a glance and at small size.
2. **Frames 2–3 are what only Tenshu does** — the privacy-shaped features a reader would not
   expect from an encrypted app (on-device face grouping, no directory, a link the server can't
   open).
3. **The rest are everyday parity** — the features whose absence would put someone off.
4. **3–5 frames, never more.** A set is a story, not a feature inventory.
5. **A platform shows only what it ships.** Where a frame's feature is missing on a platform, that
   platform's set drops the frame — never a capture of a disabled or flagged-off screen. The ⚠
   notes in §3 record today's gaps; re-check them when capturing, they close fast.
6. **Same content everywhere.** Phone, tablet and desktop captures of one frame show the same
   seeded realm, so a laptop and a phone side by side show the *same* files (§5 depends on it).

## 3. The sets

### Drive — "Your files, everywhere, readable only by you"

| # | Frame | Notes |
|---|-------|-------|
| 1 | One folder of real-looking files | Hero frame. Desktop and phone show the same folder. |
| 2 | Finder integration — *Tenshu Drive* in the Finder sidebar, files opening as ordinary files | **Manual** (§4.2), macOS only. Drive's File Provider extension, `drive-desktop/macos/FileProviderExtension/`. |
| 3 | Automatic backup — the photo-library backup on the phone, Synced Folders on desktop, with progress | "You don't have to remember." |
| 4 | Share by link — the link sheet | Caption: the key lives in the link, the server can't open the file. |
| 5 | Built-in viewer — a PDF or video open in the app | |

### Photos — "A library that knows your photos, without anyone else seeing them"

| # | Frame | Notes |
|---|-------|-------|
| 1 | The mosaic with the year scrubber | Hero frame. |
| 2 | People — faces grouped into people, ideally with the *"Is this Anna?"* suggestion | ⚠ Android + macOS desktop only. iOS has it behind `TENSHU_PHOTOS_FACES` = `NO` ([`ios-face-grouping.md`](ios-face-grouping.md)); web shows named people only. [`photos-people-and-pets.md`](photos-people-and-pets.md). |
| 3 | Search by what's in the photo ("beach", "dog") | ⚠ Not on iOS yet (PIO-7, [`ios-on-device-inference.md`](ios-on-device-inference.md)), hidden on web. Caption: it runs on your device. ⚠ Pick queries the stock content answers well — the relevance floor ([`inference-service.md`](inference-service.md) §35) shows nothing for a weak match. |
| 4 | Editing — crop, adjust, filters | [`photos-editing.md`](photos-editing.md). |
| 5 | Duplicate cleanup — choose the copy to keep | |

### Calendar — "Your schedule, private even on the lock screen"

| # | Frame | Notes |
|---|-------|-------|
| 1 | Week or month view with several coloured calendars | Hero frame. |
| 2 | Subscribed calendars — public holidays, a team schedule, updating on their own | [`calendar-external-sources.md`](calendar-external-sources.md). |
| 3 | A reminder with its title hidden on the lock screen | System UI: captured on the emulator / simulator, not by the app's UI test (§4.2). |
| 4 | Bring your calendar over — `.ics` import once, or *Keep in sync* | |

### Contacts — "No directory. People find you only if you let them."

| # | Frame | Notes |
|---|-------|-------|
| 1 | The contact list, with portraits | Hero frame. |
| 2 | Connect by invitation — the QR / link | The story only Tenshu tells: [`contact-invitation-handshake.md`](contact-invitation-handshake.md). |
| 3 | Verify a person — the six-digit code, and the key-change warning | |
| 4 | Import — the address book, one contact, or a `.vcf` file | The import sheet ([`contacts-app.md`](contacts-app.md) §9.5) lists what the platform supports: Android all three sources, desktop `.vcf` only. ⚠ Not on iOS (no add/edit there yet). |
| 5 | Duplicates and merge | Typically follows an import, so it sits right after it. |

⚠ On iOS the set is frames 1–3 and 5: there is no add, edit or import on iPhone and iPad yet.

### Fit — "Your whole training history, without the cloud"

| # | Frame | Notes |
|---|-------|-------|
| 1 | Home — today's active minutes against the goal | Hero frame. |
| 2 | Recording live — route on the map, pace, heart rate from a Bluetooth strap | The strap has to be faked in the fixture; a live recording can't run in a UI test. |
| 3 | Workout detail — route, laps, heart-rate curve | |
| 4 | Sleep | |
| 5 | Bring your history — Apple Health / Health Connect, Google Takeout, `.gpx` / `.tcx` | |

## 4. What has to exist first

### 4.1 Seeded content for Calendar, Contacts and Fit

Only Drive and Photos have a stock-content realm today (`store/fixtures/landing/`,
`how-to/store-screenshots.md` §9), and only for the phone. The others need their own, or frames
2–5 read as test data:

- **Contacts:** the existing invented address book (`store/fixtures/contacts/manifest.json`,
  28 people) gains **portraits of generated people** (§4.1a) through
  `generate_contacts.py <out> --portraits DIR`, which embeds each as the card's `PHOTO` — the path
  a real address-book export takes. Still owed: one pending invitation, one verified contact, one
  key-change warning (the duplicate pairs exist).
- **Calendar:** a believable week across 3–4 calendars, one subscribed holiday calendar, a
  repeating event.
- **Fit:** a few months of history with real GPS routes (from an open source of tracks, not the
  maintainer's own), laps, heart rate, sleep nights.
- **Photos:** ⭐ **the maintainer's own photos, not stock** (decided 2026-11-19: the Unsplash set
  reads as artificial and lacks variety), staged by `store/fixtures/promo/stage_photos.py` from
  `~/Movies/Tenshu promo/photos/<Album>/`, which strips everything but capture date and
  orientation. Today that is 19 photos from one week (Sintra, two cats); it needs ~40 more across
  the §3 search subjects (dog, food, coffee, bicycle, autumn, rain, night lights, snow), Fit-adjacent
  scenes and home life. Capture dates are spread over months and years in the fixture, so the
  scrubber has a history without needing old photos. **Faces for People come from generated
  people (§4.1a)**, never from the maintainer's own library.

### 4.1a Generated people

⭐ **Decided 2026-11-19:** People and Contacts show **generated people**, not stock models, not
historical portraits (an endorsement reading and estates enforcing publicity rights, plus narrow
variety), not real friends (a face on a public page for good). A generated person has no
copyright and no personality right, and the cast can be chosen for variety.

⚠ The rule for the maintainer's own photos — *no people, brands, legible signs or number plates*
— stands. Generated people are the only faces in any fixture.

**Five recurring people** appear in Photos (People needs 5–8 photos of each) **and** in Contacts
with the same face, so *"Anna"* in People is Anna in Contacts:

| Contact (manifest) | Who | Fixed look |
|--------------------|-----|------------|
| Hannah Becker | family (sister), 26, German | long wavy dark-blonde hair in a messy bun, light freckles, gold hoops, no glasses |
| Elena Petrova | family (mum), 54, piano teacher | chin-length ash-brown bob, fair skin, laugh lines, small pearl studs |
| Amara Okafor | friend, 29, paediatrician | long thin twists half-up in a bun, deep brown skin, small gold hoops |
| Tomás Oliveira | friend, 28, coffee roaster | dark curly hair, full trimmed beard, olive skin |
| Kwame Mensah | friend, 33, solicitor | short twists, full beard, dark skin, gold hoop in one ear |

**Fourteen single portraits** for Contacts only: Aiko Tanaka (30, Japanese, sleek bob), Ana Lucía
Torres (34, Mexican, hair tied back), Chloé Martin (27, French, curly red hair), Daniel Weber (31,
German, short beard), Devika Rao (28, Indian, long braid), Farah Haddad (31, Lebanese, dark wavy
hair), Grace Liu (41, Chinese-American, short hair), Isabela Costa (24, Brazilian,
sun-lightened curls), Layla Hassan (29, wears a hijab), Mateo Fernández (26, Spanish,
headphones round his neck), Mei Chen (68, Chinese, short grey hair), Noah Bergström (23, Swedish,
beanie), Yusuf Demir (32, Turkish, moustache), Zanele Dlamini (25, South African, braids).
⭐ The cast skews 20s–early 30s, like the users the apps are for (maintainer, 2026-11-19); Elena
(a parent), Grace (a firm's partner) and Mei (the neighbour) are the deliberate older faces, and
the manifest's birthdays match these ages. The other
nine contacts keep their monogram, as a real address book does — including *Tom Oliveira*, the
photo-less duplicate of Tomás.

**The prompts, ready to paste:** [`store/fixtures/people/prompts.md`](../../store/fixtures/people/prompts.md) — every one with the file name, album and capture date it gets.

**The style block, in every prompt** — this is what keeps them from reading as artificial:

> Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture with pores and small imperfections, not
> retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand
> names, no readable signs.

**Per recurring person**, the portrait first, then the rest with it attached as the face reference
(*"the same person as in the reference image"*):

1. Contacts portrait — head and shoulders, outdoors, plain soft background, looking at the camera.
2. Laughing at a café table, seen across the table.
3. Walking on an autumn path, three-quarter view.
4. Cooking or at a dinner table at home, evening lamp light.
5. On a trip — a seaside viewpoint or old town street, a little farther away.
6. A selfie, arm's length, slightly off-centre.

**Four group photos**, so People sees faces in company: Hannah and Elena with a birthday cake;
Amara, Tomás and Kwame at a dinner table; Hannah and Tomás on a hike; all five at a picnic.

**Where they go:** portraits as `~/Movies/Tenshu promo/people/contacts/<Given> <Family>.jpg`
(the generator fails on a file matching nobody); the People photos as albums under
`~/Movies/Tenshu promo/photos/` beside the maintainer's own (*Hannah's birthday*, *Dinner at
Amara's* …), so `stage_photos.py` takes them like any album. Neither is ever committed. ⚠ A
generated image has no capture date and `stage_photos.py` falls back to the file's mtime — set one
before staging, or every person lands on the day they were generated.

**Before capturing:** run [`check_people.py`](../../store/fixtures/people/check_people.py) — desktop's
People pipeline ported (Vision → AuraFace int8 → `clusterFaces` at 0.44, file-path order) over the
staged promo set — and expect every visible group to be one person; a cast the clusterer splits or
merges makes frame 2 a bug report. The app on the seeded realm is still the proof.

⭐ **Measured 2026-11-19, and it changed the cast to four.** With every generated photo in:
- ⚠⚠ **Hannah and Elena merged** into one 11-face person: all five Elena scene shots drew a blonde
  woman of ~40, not the 54-year-old portrait — the model ages women down towards its default.
- Hannah's two newest shots (a later session) formed a **second Hannah**.
- **Profile faces split** off as two unnamed "people" (Amara and Tomás side-on in the F2 group).

Held out (moved to `~/Movies/Tenshu promo/held-out/`, not deleted): Elena's five, Hannah's café
`IMG_1612`, F2 `IMG_1661`. **People now shows four: Tomás 6, Amara 5, Kwame 5, Hannah 4**, every
other face a hidden group of one. Elena stays in Contacts only until a set of her that looks 54
clusters on its own.

⚠⚠ **The four are only stable in this exact set.** Kwame and Tomás sit at **0.41–0.51** on several
pairs (both bearded, one generator's golden-hour style); in 300 shuffled visiting orders they merge
in ~37%, and pruning the worst bridges only gets that to ~3%. The product's order is fixed by file
path, so the fixture is deterministic — but **adding, removing or renaming any promo photo is a
re-run of the check**, never a safe edit.

The F1/F4 third attempts (right faces, Elena finally looking her age) were tried and held out too:
both come out clean in path order — F1 gives Hannah 6; F1 + F4 without F3 gives a fifth person,
Elena — but a wrong grouping in **271 of 300** shuffled orders against the current set's **115**.
A grouping that thin flips on desktop's own decode, so **frame 2 ships with four**.

### 4.2 Desktop captures — automated vs manual

Each app gets the frames of §3 that are its own windows, at **one fixed window size** (the hero's
laptop screen, §5), light + dark, `en` + `de`, captured from the same seeded realm.
They are published as a new device, `desktop/macos` (and `desktop/macoslanding` for the
stock-content set), in the existing manifest.

**Automated:** everything inside an app window — `scripts/store-screenshots-desktop.sh` drives the
installed apps over a capture realm through macOS Accessibility and captures the window
([`store-screenshots.md`](../how-to/store-screenshots.md) §10). An offscreen Compose test was the
first plan; driving the shipping app over a real realm shows real thumbnails, sync state and
People groups, which a test fixture would have to fake.

**Manual, once per language × theme:** what is OS chrome rather than our window — the Finder
sidebar with the File Provider location (Drive 2), the Control tray menu, macOS notifications.
Scripting these (AppleScript plus `screencapture -l <window>`) is fragile and they rarely change,
so they are taken by hand and published under the same keys; the doc records how each was set up.
The phone lock-screen reminder (Calendar 3) is system UI too, but `adb` and `simctl` can capture
it, so it stays scripted.

## 5. The apex hero this feeds

The landing hero shows the five apps (Drive, Photos, Calendar, Contacts, Fit) as an icon row
under the headline, Proton-style. The graphic cycles through each app's **frame 1**:

- **Desktop:** a laptop and a phone side by side, both switching to the same app at the same
  moment, showing the same content (rule 6) — "your data on every device" without a word.
- **Mobile:** the phone alone.
- One clock drives the icon row and both devices; hovering or tapping an icon stops on that app;
  reduced motion shows Drive's still frame. Icons jump to that app's card in the Apps carousel.

The current hero art (`M1Hero`, devices around the relay) moves to the *How it should be* block
it illustrates, and the "app ecosystem" sentence moves into the *Publish anywhere, trusted
everywhere* block. The hero waits on the `desktop/macoslanding` frame 1 of all five apps.

**As built (2026-11-20)** — `web/apex/src/landing/hero/` (`HeroShowcase.tsx`, `hero.css`), data in
`carousel/landingApps.ts` `HERO_APPS` / `desktopShot`:

- The laptop shows the published `desktop/macoslanding` frame 1 of all five apps, and the phone
  `android/phonehero` — an Android emulator paired into the same realm (`store-screenshots.md`
  §10.3) — so both devices show the same content (rule 6), en/de, light/dark.
- Icon order and the clock: Drive, Photos, Calendar, Contacts, Fit, every 4 s (`ADVANCE_MS`). A
  hover or keyboard focus holds an app. Every icon links to its app's own page (`/apps/<app>`, not
  the Apps section below — scrolling the visitor down read as a broken link); on touch the first
  tap shows the app and stops the clock, a tap on the showing app opens its page.
- The pause/play button (WCAG 2.2.2) sits in the laptop screen's corner and appears only on hover
  over the devices or keyboard focus; beside the icons it read as a sixth app. Never on touch.
- ⚠ The devices are sized by the viewport's **height** as well as its width, so headline, row,
  devices, CTA and *Learn more* fit one screen from a 768 px-tall window up (`hero.css`).
- Not done from the plan above: `M1Hero` was **not** moved into *How it should be* — that block
  already has its own drawing (`AnswerArt`), so `M1Hero` is simply off the page; and the "app
  ecosystem" lede stayed in the hero.

## 6. Order of work

1. Seeded content (§4.1), Calendar / Contacts / Fit first — every later step reads it.
2. Desktop frame 1 for all five apps — unblocks the hero (§5).
3. The hero itself, in `web/apex`.
4. The remaining frames of §3 on every platform, then the manual desktop captures.
5. Re-point each `store/<app>/listing.json` `ios.screenshots.screens` and `landingApps.ts`
   `LANDING_SCREENS` at the new keys, in §3's order.

## 7. Open questions

- **Q1 — captions.** Do the store frames carry a caption band (`captions.txt` exists per
  locale), and does the hero use the bare capture? Recommendation: captions in the store, bare in
  the hero.
- **Q2 — Windows.** Desktop captures are macOS-only here; a Windows set waits for the Windows
  store listings to need one.
