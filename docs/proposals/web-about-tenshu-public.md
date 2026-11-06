# About Tenshu — Public Narrative (Content Design)

**Status:** Draft v0.1 — copy live on `/`. As built, Blocks 1–4 carry static code-drawn
illustrations ([`docs/design/landing-illustrations.md`](../design/landing-illustrations.md)) and
Block 7 the app carousel; §12's mockup catalog is the original plan, annotated with what landed.
**Scope:** The user-facing "About Tenshu" content on `tenshu.app`'s home route. This is the first
deliverable in a small family of audience-specific
decks ([investor and developer versions](#14-reusing-this-content-for-future-decks) come later). It
is web content, not a slide deck — but the content modules defined here are designed to be reused in
literal-deck form when the other audiences need them.

This document specifies the nine content blocks that make up the home-page narrative, the copy for
each (shippable drafts, not placeholders), the interactive mockup catalog the React components will
need, copy/tone principles, and the modular structure that lets the content be reused across future
decks.

It does **not** commit to visual design (colors, typography, illustration style) — that's a separate
workstream once Tenshu has a brand system. The content here is structure-and-copy; visual design
layers on top.

---

## 1. Audience and Goals

A user lands on `tenshu.app` having heard "Tenshu" mentioned once on a blog post or from a friend.
They have ~90 seconds of patience before they bounce. The narrative must:

1. **Hook them in 5 seconds**: one-sentence value prop that's clear without context.
2. **Articulate the problem in 30 seconds**: why the user should care that current apps and
   platforms aren't private.
3. **Show Tenshu's answer in 30 seconds**: the proxy-mediated, semantic-permission, E2EE-by-default
   architecture, in plain language.
4. **Demonstrate it in 60 seconds**: interactive mockups that make the concept tangible.
5. **Ask for the sign-up**: a clear, low-friction CTA.

If the user reads everything, they leave with a working mental model of what Tenshu is, what they
can do today, and what's coming. If they only read the hero and the first block, they at least know
whether Tenshu is for them.

---

## 2. Content Blueprint at a Glance

Nine blocks, scrollable, embedded in `web/apex/`'s home route:

1. **Hero** — one-sentence promise, primary CTA, hero visual.
2. **The problem** — what's wrong with how apps work today.
3. **Tenshu's answer** — proxy-mediated apps and semantic permissions.
4. **Your realm** — the core concept: your data, photos, and what you own, end-to-end
   encrypted across your devices, which you add and remove at will.
5. **Honest about the limits** — what a breached Tenshu can and can't see, framed by the
   whitepaper's Zero-Trust Backend principle.
6. **Multiplatform** — one encrypted core on Android, desktop, and web (iOS planned).
7. **Apps** — one carousel of every first-party app, shared with the support hub (§9).
   - *(7a.)* **Developers** — the planned SDKs and the single schema language (§9a).
   - *(7b.)* **Store** — Tenshu Rei and the Tenshu Store in user language (§9b). ⚠ Held back,
     not on the page.
8. **Hardware** — the Tenshu Hub and the Mia speaker, both in planning.
   - *(8a.)* **Founder** — who is building Tenshu, why, and an open invitation (§10a).
9. **Sign-up + FAQ** — primary CTA, frequently-asked questions.

The first five blocks are the **core concept module**. They're identical (modulo length and depth)
across all three future decks (public, investor, developer). The last four are **public-specific**.

---

## 3. Block 1 — Hero

**Goal**: 5-second hook. The user knows whether Tenshu is for them by the end of this block.

**Headline (H1)**:

> Your apps. Your data. Your control.

**Subhead (H2 / lead paragraph)**:

> Tenshu is an open-source app platform where you decide what every app can see and do. Your data
> stays end-to-end encrypted across your devices. Apps run in a sandbox you control — they can't
> reach
> the internet without going through you.

**Primary CTA**: `[Get started]` — leads to the §4b new-user onboarding flow per [
`web-onboarding-ux.md`](web-onboarding-ux.md).

**Secondary CTA**: `Learn more` — anchor link that scrolls to Block 2.

**Hero visual**: see Mockup 1 (§12). A short looping animation — three devices syncing files
end-to-end encrypted, with a "🔒" overlay during transit and clear plaintext on the devices
themselves. Roughly 6-second loop, autoplays muted, doesn't loop aggressively.

**Layout notes**:

- Above the fold on desktop and mobile.
- Visual on the right (desktop) or below the headline (mobile).
- Subhead at most 4 lines on mobile; 2–3 on desktop.

---

## 4. Block 2 — The Problem

**Goal**: in ~30 seconds, the user should feel that the privacy story they have today isn't enough.

**Headline (H2)**:

> When you install an app, you give up more than you realize.

**Body**:

> Modern apps need to talk to the internet to do their job. But once they're online, you have no
> real way to know what data they're sending, where, or to whom.
>
> The permission systems on your phone are coarse. *"Allow location access"* tells you nothing about
> what the app actually does with your location. Once an app has the permission, you're trusting the
> developer, the app store, and every contractor downstream.
>
> Most apps mean well. Some don't. Almost none can be verified to behave the way their privacy
> policy claims.

**Visual**: see Mockup 2. A 5-second illustration showing a phone with data flowing outward in many
directions, each labeled — *Developer*, *Advertiser*, *Cloud backup*, *Third-party SDK*, *Data
broker*. The directions are visible but not animated aggressively; the goal is recognition, not
anxiety.

**Length**: 3 short paragraphs. Skimmable.

---

## 5. Block 3 — Tenshu's Answer

**Goal**: introduce the architectural idea (proxy + semantic permissions) in language a
non-technical user understands.

**Headline (H2)**:

> Apps that can only do what you let them.

**Body**:

> Tenshu turns the relationship around. Apps don't talk to the internet directly — they talk to a
> proxy app on your device. The proxy is yours. It enforces the permissions you've granted. It shows
> you exactly what each app is doing.
>
> Every Tenshu app declares what it wants to do in human-readable terms. Not *"access network"* but
*"send your photos to the photo printing service."* You grant or deny each one. The proxy enforces
> it.
>
> Apps you don't fully trust get a shorter leash. Apps you do trust still go through the same
> controls — in case they're compromised later, or sold, or quietly change their mind about what
> they
> do with your data.

**Visual**: see Mockup 3. An interactive demo showing a fake "Photo Printer" app trying to print a
photo. The user can click *Approve* or *Deny* on the permission dialog the proxy shows, and watch
the request flow through (or fail). Two clicks total to complete the demo.

**Length**: 3 paragraphs. The third paragraph is important — it inoculates against "but I trust my
apps already."

---

## 6. Block 4 — Your Realm

**Goal**: introduce the *realm* — Tenshu's core mental model — and make E2EE concrete in
passing. This block merges the former "End-to-End Encrypted, Plainly" and "Realm Devices"
blocks: the realm is the one concept a user has to internalise, and the encryption +
multi-device story are properties *of the realm*, not separate topics. The
honest-about-limits material that used to be an inline note here is now its own block
(§7) so it reads as a deliberate section, not a footnote.

**Headline (H2)**:

> Your realm: everything you own, encrypted across every device.

**Body**:

> Your files, your photos, your devices — even what you own, like your storage — all of
> it lives in one private space Tenshu calls your *realm*. It's the whole picture of your
> digital life, and it belongs only to you.
>
> Your realm syncs across your devices through Tenshu's relay, but only your devices hold
> the keys. The relay only ever moves encrypted data — if Tenshu's servers were breached,
> there'd be nothing in them to read.
>
> Adding a device is a 30-second scan and a tap to confirm. Remove one — a stolen phone, a
> laptop you're selling — and whatever was on it stays unreadable to whoever finds it
> next. Your realm is yours to grow and prune as your devices change.

**Visual**: **the "realm ring"** — `RealmArt`
(`web/apex/src/landing/illustrations/RealmArt.tsx`), **landed**. It replaced `DevicesArt`
(devices syncing, since deleted), which duplicated what the hero (M1) and the answer block
already show and didn't carry the one thing this block adds — the *realm* as a unified
whole.

**The realm ring (as built):** the realm's distinct concept is *one private space that is
the same on every device, opaque on the relay*. Devices of different kinds (phone, tablet,
laptop, desktop) are arranged in a **ring**, each showing the *same* readable item (a small
photo glyph) — your stuff, present and legible wherever you are. At the **centre of the
ring sits the Tenshu cat mark** (the codified `MarkSolid`, embedded verbatim — no geometry
fork): the cat *is* the realm, the thing that ties the devices together (ties marketing to
the brand). A **relay node off to the side** carries the same item but only as opaque
ciphertext blocks — same data, unreadable there. Light, looping animation (per §12's ~6s
loop convention): a gentle sync pulse travels around the ring, the photo crosses to the
relay and becomes a lock, and the cat gives a small idle "breathing" tic for warmth.
Motion is opacity/scale only and gated behind `prefers-reduced-motion` in `index.css`
(same convention as the existing `scroll-behavior` rule); reduced motion degrades to the
static frame — a complete, legible composition on its own — with no JS. Supersedes the M5
pairing-demo mapping for this block (pairing is a device-management detail, not the realm
concept). Synthesised from two maintainer seeds: "animated, with the cat in it" +
"different devices in a circle, files on the devices but encrypted on the relay."

**Length**: 3 short paragraphs: name the realm (framed by example first, per §13), the
E2EE promise, devices in and out. Deep recovery mechanics (24-word code, per-volume
opt-in) are intentionally *not* here — they belong in onboarding and the docs, not the
narrative. The relay-metadata honesty moved to §7.

---

## 7. Block 5 — Honest About the Limits

**Goal**: carry the trust beat that §13 calls load-bearing. Formerly an inline note inside
the encryption block; promoted to its own section so it reads as a deliberate, confident
statement rather than fine print. Framed in the **whitepaper's voice** — the four Data
Privacy & Security Design Principles, especially **Zero-Trust Backend** (whitepaper
§"Data Privacy & Security Design Principles": *"A breached Tenshu service should yield
only ciphertext and timing metadata, never plaintext or long-term keys … a
non-trust-bearing convenience layer, not a single point of failure."*).

**Headline (H2)**:

> Honest about the limits.

**Body**:

> No system is perfectly private, and we won't pretend otherwise. Tenshu's relay never
> sees your data — but it does see *metadata*: how often your devices sync, roughly how
> big each encrypted block is, and which of your devices are talking to each other.
>
> Tenshu is built so that this is all it ever could see. Anywhere Tenshu has to run a
> server, it's designed under one assumption: breaking it changes nothing. A compromised
> Tenshu service gives up only encrypted data and timing — never your content, never your
> keys. It's a convenience layer, not something you have to trust.
>
> We tell you exactly what leaks, and design every part of Tenshu to leak as little as
> possible. ([Read our threat model.](../sync/threat-model.md))

**Visual**: **none — text-only for now** (deliberate). A candid, typographic statement
reads as more honest than a diagram here; and any "what the relay sees" graphic risks
either contradicting the message (a padlock/encryption visual says "perfectly safe,"
undercutting the *what we can't hide* point) or re-importing the technical detail the
narrative trimmed. `EncryptionArt` was briefly slotted here and pulled for exactly the
first reason (since deleted).

**Future visual — "relay's-eye view" (parked idea, good for later):** the only concept
that *supports* rather than fights the message. The `Relay` mark (it already renders
faint hex "ciphertext" rows) showing only what the relay can actually observe — opaque
ciphertext blocks of *varying size* (the block-size metadata), a timing tick (sync
frequency), and stub connectors to two anonymous device boxes (which devices talk) — and
pointedly **no** recognisable file or photo anywhere. It illustrates the honest limit:
shape + timing + endpoints, contents never. Would be a new `LimitsArt` component, not a
reuse. Supersedes the old Mockup 4 framing for this block (M4's ciphertext-transit loop
belonged to the retired encryption block).

**Length**: 3 short paragraphs — what it can see, the Zero-Trust reframe, the transparency
promise + threat-model link. This is the moment a thoughtful user decides whether to trust
Tenshu's narrative; it must read as confident honesty, never as a hedge.

---

## 8. Block 6 — Multiplatform

**Goal**: in ~15 seconds, establish that Tenshu isn't tied to one device or OS — your realm
follows you everywhere. Sits right after the trust beat (§7) so the "is this real?" question
is answered with reach.

**Headline (H2)**:

> Tenshu runs where you do.

**Body**:

> One end-to-end-encrypted core, built once and shipped to every device you own. Your realm
> looks and works the same whether you're on your phone, your laptop, or the web.

A small grid of platform cards, each with a name, a one-line detail and a one-line blurb, in
this order: **Android**, **iOS**, **Desktop** (Windows · macOS · Linux, a native desktop app),
**Web** (`tenshu.app`, nothing to install; it can be added to the home screen, since apex ships a
web manifest).

**Decided 2026-10-16:** all four platforms are part of the first release, so the cards carry **no
availability badge**: per-card status read as confusing. Keep the blurbs in plain words ("desktop
app", not "build"; no "proxy" or "control plane").

**Visual / layout**: four `PlatformCard`s in a responsive grid (`Block6Multiplatform.tsx`).

**Length**: 1 lead paragraph + 4 cards. Short; this is a reassurance beat, not a deep dive.

---

## 9. Block 7 — Apps

**Goal**: make "what can I actually run on Tenshu?" concrete, and show it rather than list it.

**Decided 2026-10-16 (maintainer):** one carousel of the apps, shared with the support hub
(`/apps/support/<lang>`, `store-presence-and-support-pages.md` §4.7), replacing the three tiers
(two shipping galleries, "And the rest of the set", "Still being built").

**Headline (H2)**:

> Apps you actually control.

**Body**:

> Every app is end-to-end encrypted. Only your devices can read what's in them.

⚠ **Never state how many apps there are.** A count goes stale the day an app ships; the old lede
said "Seven apps run on Tenshu today" while the set was ten.

**The carousel**: the app names arranged around a stage that shows the selected app as a card:
its real icon, name and one line, and its phone. Hover or keyboard focus selects; with a
mouse a click on a name follows its link; on touch a tap only selects, and the card's **name** and
its **phone** open the app (the card has no button — *Layout*, below). The **selection never changes on its own**; only the selected app's
screenshots step through the one phone (*One frame*, below). The roster and order are
the hub's (`support-pages/hub.ts` `HUB_APPS`): Control, Drive, Photos, Maps, Timeline, Calendar,
Contacts, Fit, Home, Mia.

- **Status:** only an app that is not usable yet says so — Home and Mia carry a quiet
  "Coming later" (de *Kommt später*, fr *À venir*) on their card, and nothing in the list of names.
  No other app has a badge of any kind. Move an app out of `COMING_LATER`
  (`src/landing/carousel/landingApps.ts`) the day it is usable, rather than softening its line.
- **Links:** an app links its support page (`/apps/<app>/support`, which picks the platform and
  language) exactly when the hub does — Control, Drive, Photos, Calendar, Contacts and Fit today. The others have no link.
  ⚠ **One seam:** `landingAppHref` (`src/landing/carousel/landingApps.ts`) is the only place the
  landing carousel's per-app target is decided; the list name, the card's name and the phone all
  carry it. **Planned:** each app gets its own **marketing page** at `/apps/<app>/<lang>` (inside the
  reserved `/apps/<app>/…` scheme); the landing carousel will link there, and that page links to
  support. When it exists, `landingAppHref` returns it (given the language) and nothing else changes.
- **Copy:** each card's line is under `block7.apps.<app>.tagline` in
  `locales/{en,de,fr}/landing.json`. Control's line is *"The app that holds your keys and connects
  your devices."*; the others keep their earlier blurbs, with the too-technical ones shortened
  (Contacts' handshake, Maps' tile server, Mia's intent parsing) and a new one for Photos.
- **The browser extension is gone** from the section and the copy: it had no module and no date.

**Icons**: the real app icons (ADR 0026), from each iOS asset catalogue's `icon_1024.png` and
`icon_1024_dark.png`, shipped with the site as 128 px WebPs under `web/apex/public/icons/apps/`
(dark in dark mode). `scripts/web-app-icons.py` regenerates them; `--check` reports drift.

**Layout** (maintainer, 2026-10-17, after reviewing the one-phone carousel on a MacBook — the section was taller
than the screen, the name lists looked ragged, the names were small and the button was too much):

- **One screen on desktop.** Heading and lede stay centred above. From **64rem (1024 px)** the card
  is **horizontal** — icon, name and line on the left, the phone with its control row on the right
  — so nothing of the card sits above the phone. The phone stays real size (75 mm) but is clamped
  so heading + lede + stage + control row + the trademark footnote fit under the sticky header:
  `--phone-w = min(75mm, (100svh − --stage-offset) × 0.463)`, `--stage-offset` **17.5rem** on the
  landing card (the budget is spelled out in `appCarousel.css`; change it with Block7Apps' heading
  block), 19.875rem on desktop windows under ≈ 796 px tall, where the control row wraps (below).
  Measured in Chrome with the heading scrolled just under the header (the first fit-one-screen layout → this rework): the
  phone is 75 mm (283 px) at 1920×1080, 1512×982 **and now 1440×900** (was ≈ 261 px), 241 px at
  1280×800 (was ≈ 215), 208 px at 1024×768 (was ≈ 200), 249 px on a 390×844 phone; the footnote's
  bottom edge is within the viewport at every desktop size (791 of 800 px at 1280×800, 759 of 768
  at 1024×768). Below 64rem the stage is replaced by a row of cards (*Narrow*, below).
- **No box around the stage** (maintainer, 2026-10-17). The "card" is a layout, not a box: no
  background, border or radius — the phone's own frame was already a box, and the card around it
  read as a box in a box. Icon, name, line and phone sit on the section's background; the
  highlighted entry in the lists says which app is shown, and the phone's own frame shadow anchors
  the stage.
- **The text is static, top-anchored above centre** (maintainer, 2026-10-17). The block's **top** sits
  at **32 % of the phone's height** (`--stage-text-anchor`, `appCarousel.css`, `.hub--cards`), so a
  typical name + line lands near the optical centre (~40 %). ⚠ Top-anchored, not centred: a centred
  block moved the app name with every tagline length as the visitor hovered between apps. The name
  is 2rem. **No app icon in the stage text:** the highlighted list entry already shows its icon, and
  a second copy beside the phone was redundant (removed the same day).
- **Mirrored name lists.** Left list right-aligned with each icon **after** its name ("Control ▣"),
  right list left-aligned with the icon **before** it ("▣ Calendar"): every icon sits on the stage
  side, so each side's icons form one straight column. Not circular.
- **Bigger names.** 46 px icons, 1.25rem labels, each side's entries spread evenly
  (`space-between`) over a box **75 % of the phone's height**, centred on the phone; the selected
  entry keeps its highlight (a rounded box around icon and name). Chips on narrow screens carry
  28 px icons (36 px from 40rem).
- **No "Open X support" button on the landing card**: the card's name and the phone are the links
  (`landingAppHref`). ⚠ **The hub keeps its button** — there it is the page's purpose, and on touch
  a tap on a name only selects. The mirrored, bigger lists apply to the hub too (its names now
  carry icons — Getting started has Tenshu's mark); the horizontal card is landing-only.
- **Narrow: a row of cards** (maintainer, 2026-10-18 — the chips wrapping into 4–5 rows above the
  phone "did not really look good on mobile"). Below **64rem** — phones *and* tablets: at 768 px the
  names still do not fit beside a stage, and a row of 22rem cards with neighbours on both sides
  reads well there — the stage is `display:none` and `CardRow` (`AppCarousel.tsx`, CSS `.hub-m`)
  shows instead; from 64rem it is the other way round, so the **desktop layout is untouched** (a
  Playwright box comparison at 1512×982 and 1280×800 is identical before and after). One card per
  entry in a native `overflow-x: auto; scroll-snap-type: x mandatory` row, each card
  `scroll-snap-align: center`, **80 % of the row wide** (at most 22rem) so the next one peeks in;
  the row bleeds into the column's 1.5rem gutters so the peek reaches the screen edge and only the
  row scrolls sideways. A card is icon, name, line (the hub: "Coming soon"), and the entry's
  **first** screenshot in the drawn frame — no rotation, no per-card dots; each card is its own
  height, tops aligned (stretched to the tallest, a short line left a hole above the phone). **The whole card is the one link** (`landingAppHref`; on the hub the support page,
  the link named "Open X support", which the card also shows as a button-shaped label); a card with
  no target is not a link but takes focus itself, so Tab and the arrow keys reach every card.
  The "iPhone | Android" switch sits **once, above the row** — the same state, OS default and
  `tenshu.shotPlatform` as the stage's; a card with no captures for the chosen platform shows the
  other's with the one-line note. **Indicator: the app icons, one 28 px button per card** ("Show
  Drive", `aria-current` on the card in view, a tap scrolls to it) — chosen over dots because with
  ten or eleven entries anonymous dots say only "somewhere in the middle", while the icons say which
  app each position is and double as a jump list; eleven fit one line on a 375 px phone. It follows
  the scroll position (read once per frame), never a timer. The row is a `region`, each card a
  `group` named after its entry; focus scrolls a card to the centre, ←/→ move between cards, and
  under reduced motion every scroll is instant. Without JavaScript the row swipes and snaps on its
  own and shows Android; the switch and the indicator stay hidden. ⚠ `.hub-m` is the stage's
  **sibling**, never its child — the desktop CSS keys on `.hub-list:first-child` / `:last-child`.

**Screenshots**: the published captures (`store/<app>/screenshots.manifest.json`, images on
`assets.tenshu.app`), **per platform** — `LANDING_SCREENS` (`landingApps.ts`) lists
`{ android: [...], ios: [...] }` per app (the hub: `HUB_APPS[].screens`). An app without captures
shows a neutral placeholder phone, never a broken image.

- **The "iPhone | Android" switch** (maintainer, 2026-10-17) sits at the end of the dots' row on
  both pages (*One control row*, below) and picks which platform's captures **every** entry shows, each in its own frame. Default by the
  visitor's OS — iPhone on macOS, iOS and iPadOS; Android on Windows, Linux, Android and ChromeOS
  (`navigator.userAgentData.platform`, else the user agent) — by **one rule**,
  `defaultShotPlatform` in `support-pages/hub.ts`, which the landing page calls and whose source the
  hub's inline script embeds (a test pins both). The choice is remembered in `localStorage`
  `tenshu.shotPlatform` (per viewer, try/catch like `tenshu.theme`). Without JavaScript: Android, no
  switch.
- **Fallback:** an app with captures on one platform only shows those, framed as what they are, with
  its switch **disabled** and a one-line note ("No iPhone screenshots yet"). The platforms' viewers
  share one grid cell and the shots box is the taller (Android) frame's height, so switching moves
  nothing — the shorter iPhone frame is centred in it.
- iOS today: Control (support captures, both pages) and Drive (support set, hub). Drive's and
  Photos' **iOS landing sets are slots** (`ios/iphone69landing/<lang>/NN-screen`, mirroring
  `phonelanding`): unpublished keys are skipped, so they show Android until they land.

- **The landing content set** (2026-10-17): Drive and Photos show their own captures of a realm
  full of real stock photos — Drive's files, a photo folder as a grid and a preview; Photos' library,
  an album and one photo full screen — published under the device `phonelanding`
  (`android/phonelanding/<lang>/[dark/]NN-screen`) and named `{ screen, landing: true }`. They are a
  separate set so the support pages keep their plain captures. Control keeps its support captures
  (welcome, settings). How they are made, and the photo recipe (Unsplash, licensor-hosted URL +
  sha256, nothing in git): [`how-to/store-screenshots.md`](../how-to/store-screenshots.md) §9.
- ⚠ Maps has no landing capture: its Android app has no map of saved places to show and no way to
  make one (same §9). Timeline and the rest show the placeholder until they get a walk.

**Device frame and size** (shared with the hub):

- Each capture sits in a **phone frame drawn by us in CSS**, following its platform (2026-10-17):
  - **Android** — a **generic contemporary Android phone** (maintainer request, 2026-10-18: "more
    contemporary, familiar to most Android users, inspired by current Samsung Galaxy phones but not
    copying them"): thin **uniform** black bezels (2.6 % of the width on every side), a flat body
    with corners squarer than the iPhone frame's (7 % vs 15.5 %, concentric with the screen's), a
    fine brushed-neutral rail round the glass (the outer 0.7 % of the bezel, a gradient between two
    token-derived greys, lighter in dark mode), a small centred punch-hole lens, and a volume rocker
    over a power key on the right edge. ⚖️ **What was deliberately left out**, so it is nobody's
    device: any logo or wordmark, a vendor's camera-ring or lens-surround treatment, a curved
    ("edge") display or rail, a vendor colour name, and anything traced from a product photo or
    render (`store-presence-and-support-pages.md` §3.2.1). ⚠ The rail sits **inside** the old
    inset and the keys outside the box, so the frame's box is exactly the previous one and the
    layout did not move (measured: 283.5 × 611.8 px at 75mm, unchanged).
  - **iPhone** — an **iPhone-style illustration**: rounded body with a metal rim, the Dynamic Island,
    subtle side buttons, **no Apple logo**. ⚖️ **The maintainer accepted the risk of an iPhone-like
    illustration on 2026-10-17**, a deliberate decision that revises the earlier "the CSS frame must
    not imitate an Apple device" for this frame (`store-presence-and-support-pages.md` §3.2.1).
    ⚠ It is drawn from the device's proportions, **never** used, traced or derived from Apple's
    bezel files, which may not be redistributed (`store-presence-and-support-pages.md` §3.3).
  - ⚠ The screen's corner radius clips the capture: the older Android captures come from an emulator
    profile with no rounded display, so the clock's "9" sits ~1 % from the edge and anything over
    ~50 px of a 1080 capture cuts it; the Pixel-profile recaptures (rounded display, r = 104 px)
    inset their own status bar. The Android screen radius is **4.4 %** of the frame (~47 px of 1080):
    under the older captures' limit, verified at 1× and 2× with both kinds. Raise it only once no
    square-corner capture is left in any manifest.
- **Web-sized images** (2026-10-18): each `<img>` names the capture's **WebP copies** in `srcset`
  (540 and 960 px wide, `store/<app>/screenshots.webp.json`, `how-to/store-screenshots.md` §7.1)
  with `sizes` for the phone, and keeps the full PNG as `src` (the fallback; deliberately **not** a
  `srcset` candidate, so a zoomed 2× screen never picks it). `width`/`height` stay, and the PNG's
  aspect ratio is pinned inline because a copy's height is rounded. Everything is lazy except what is
  on screen at load: the stage's first phone (in the effective theme, `fetchpriority="high"`) or the
  row's first two cards — decided by React from the layout and theme on the landing page, and by
  the hub script once it has enhanced the hub. Measured on the landing page at 2×: 11.1 MB of PNGs →
  0.89 MB of WebP on desktop, 3.4 MB → 0.13 MB on a phone.
  - Captions and labels go beside the screenshots (the card's text), never on top of them.
- **Per-screenshot frame mode** (`CarouselShot.framed`): absent, the CSS frame; present
  (`{ vendor, bare }`), **pre-framed** — the image already carries a real device bezel (rendered by
  `scripts/store-frame`, D7, once the licences are checked), so the carousel draws no frame and no
  screen radius and shows it at the same width.
  ⚠ **One vendor per stage:** an Apple-bezel render and a Google-frame render never share a layout
  (§3.2.1 of the store doc). `oneFrameVendor` (`carouselModel.ts`) enforces it on the data: if a
  carousel's pre-framed captures span both vendors, every one of them falls back to its `bare`
  capture in the CSS frame (one with no bare capture is dropped). A test pins it. In `LANDING_SCREENS` a key is then `{ screen, framed: true }`, looked up at
  `…/<lang>/framed/NN-screen` (and `…/<lang>/dark/framed/…`). Nothing uses it yet; when the first
  one is published, `support-pages/generate.ts` `MANIFEST_KEY_RE` must accept the `framed`
  segment too.
- **Trademark note**, in `AppCarousel`'s `footnote` slot under the carousel on both pages, small and
  muted, since the iPhone-style frame and the switch name both marks: *"Device frames are
  illustrations. iPhone is a trademark of Apple Inc., Android is a trademark of Google LLC. Tenshu is
  not affiliated with or endorsed by Apple or Google."* (`block7.frameNote`, hub `HubCopy.frameNote`;
  de/fr machine-translated, unreviewed like the rest of those locales).
- **Real size:** the phone is drawn at a typical phone's physical width, 75 mm at CSS's nominal
  96 dpi (no DPI detection), and shrinks only where the window cannot fit it — by width, and by
  height (the viewport less the header, the page head or heading block, and everything under the
  phone). The size depends on the viewport alone, never on what is selected or on the platform.
- **One frame, stepping through the screenshots** (maintainer, 2026-10-17; supersedes the same
  day's "the stage follows the selection"). The stage is **one phone frame, always the same size**,
  on both pages and at every breakpoint, and the names beside it never move. The earlier stage was
  as wide as the selected entry's phone count (1–3) and glided between widths; hovering from one
  app to the next then grew and shrank the stage and pushed the names outward, so the pointer had to
  chase the list it was reading — content moving under the pointer. Now:
  - the selected app's screenshots **cross-fade inside the frame every 4 s** (`ADVANCE_MS`,
    `carouselModel.ts`; the hub script's `ADV` is pinned to it) — Drive: files → photos → preview,
    and round again. An entry with one screenshot, or a placeholder, does not advance. ⚠ The
    rotation is **within an app only**: the selection is the visitor's, and never changes on its own;
  - selecting an app shows its **first** screenshot and restarts its cycle;
  - **dots** under the phone, one per screenshot: real buttons labelled "Screenshot 2 of 3: Drive"
    (`block7.screenshotLabel`, hub `HubCopy.screenshot`), `aria-current` on the one showing, and a
    click picks it;
  - a visible **pause/play button** beside the dots (WCAG 2.2.2 Pause, Stop, Hide), following the W3C
    WAI-ARIA APG Carousel pattern: the viewer is a `region` with `aria-roledescription="carousel"`
    labelled with the app's name, each screenshot a `group` with `aria-roledescription="slide"`, the
    button's label says what it will do ("Pause screenshots" / "Play screenshots"), and the slides'
    container is `aria-live="off"` while rotating and `"polite"` when not. The pause is the
    visitor's and survives changing apps;
  - the cycle also **holds** while the pointer is over the phone or the dots, while *keyboard*
    focus (`:focus-visible`) is in them — a dot clicked with the mouse is held by the hover alone,
    or one click would stop the cycle until the next — while the tab is hidden
    (`visibilitychange`) and while the stage is offscreen (`IntersectionObserver`). The pause
    button itself is outside the hold, so pressing Play under the pointer plays;
  - **reduced motion:** no timer at all, an instant swap instead of the fade, the dots still pick,
    and no pause button (nothing moves to pause);
  - the incoming screenshot fades in **on top** of the outgoing one, which stays opaque until it is
    covered, so the frame never dips through the background mid-fade;
  - **one control row** under the phone, as wide as the phone (maintainer, 2026-10-17; it was two
    rows, the switch under the dots): pause and dots at its start, the "iPhone | Android" switch at
    its end, on both pages; under it one reserved line for the switch's note ("No iPhone screenshots
    yet"), end-aligned. The controls are each platform viewer's and the switch is the panel's, so
    CSS places them on one grid row of `.hub-device` (the switch's box is `display: contents`); the
    markup, the labels, the dots' buttons, the switch's labelled group and the disabled fallback are
    unchanged. Where the phone is narrower than the row needs (**14.875rem**, 238 px: pause, three
    24 px dot targets, the 123 px switch — a container query on the row's own width) the switch
    wraps under the dots, start-aligned, and never overflows. That happens only on the landing page
    on desktop windows under ≈ 796 px tall (1024×768), which budget the extra line; the hub's phone
    has the row's width as its floor (`--phone-min`), so its row never wraps — the hub has no
    fit-one-screen budget (its button sits under the stage). 238 px phone at 1280×800 and 390×844;
  - the row has a fixed height, reserved by an entry with one screenshot too, as is the switch's
    (an entry with no captures keeps its room, invisible), and on a narrow landing card the phone
    sits at the card's foot — so nothing moves when the card's line above is longer;
  - nothing hidden takes the pointer: unselected panels and a hidden platform's viewer are
    `pointer-events: none`, so the phone under the pointer is always the selected app's link.
  ⚠ Nothing on the carousel may vary with the selection in a way CSS could size the stage by
  (`data-stage-count` is gone); `AppCarousel.test.tsx` and `hub-script.test.ts` assert the stage
  markup is identical across selections, and the PRs measured the lists' and the stage's boxes in
  Chrome (the one-phone carousel at 1440/1280/1024/390; the layout rework at 1440×900, 1512×982, 1280×800,
  1920×1080, 1024×768 and 390×844, light and dark, both platforms, both pages: no box moves between
  selections or on a platform switch, each side's icons share one x, the lists span 75 % of the
  phone on the landing page).
- **Without JavaScript** (the hub): an entry's Android screenshots sit side by side in its card, as
  before (the iPhone viewer is in the markup, not shown); the dots, the pause button and the switch
  are in the markup but `hidden`, and no carousel semantics are set until the script runs.
- **The same frame on the support pages**: every step screenshot (`generate.ts` `shotHtml`) sits in a
  `.shot-frame`, the carousel's frame at the step column's width (`--shot-w`) rather than real size.

**One component**: `src/landing/carousel/AppCarousel.tsx` (+ `appCarousel.css`, imported by
`src/index.css`, which the support-page build compiles too). The landing page renders it live; the
hub renders it statically through `supportChrome.tsx`'s `renderToStaticMarkup` and keeps its own
inline script and no-JS fallback. The class names stay `hub-*` / `data-hub-*`: the hub's script
and tests were written against them first.

Mia the *app* lives here; the Mia *speaker* and the Hub are hardware (§10). The non-app roadmap
items (Web Tenshu, Tenshu Rei, App Console & Store) live only in `milestones.md`.

---

## 9a. Developers — "Build private apps in months, not years."

**Goal**: the vision for developers. Tenshu is meant to make end-to-end encrypted apps cheap to
build, not only to ship its own. Copy under `developers` in `locales/en/landing.json`.

Two moves. First the SDK promise: encryption, sync, sharing and recovery out of the box, on all four
platforms, usable by AI coding agents. Then the one piece of reasoning a developer needs: a single
schema language describes data, services, permissions and the wire format, instead of protobuf +
REST + a loosely matching permission manifest, so a granted permission is exactly what the code can
do.

⚠ Nothing here ships yet: the block wears an **"In planning"** badge and stays in the future tense.

**Visual**: `BlockDevelopers.tsx`, text-only.

## 9b. Store — "An app store that doesn't own you."

⚠ **Held back 2026-10-16: not on the page until Rei has something to show; copy kept here.**
`BlockStore.tsx` and the `store` locale entry were removed from the founder/developer landing change before it
merged, so only that change's unmerged branch history holds them. To bring the block back, restore
the component from there, put the copy below back under `store` in
`locales/{en,de,fr}/landing.json` (translating it), and re-add the test that pins the Rei wording.

**Goal**: Tenshu Rei and the Tenshu Store (`milestones.md` *App Console & Tenshu Store*) for an
end user and the digital-sovereignty audience: two gatekeepers and their up-to-30% fee today, then a
store where anyone can check who published an app and what it may do, with fair in-app purchases and
purchases that survive switching stores.

**Headline (H2)**:

> An app store that doesn't own you.

**Body**:

> Today two companies decide which apps you can install, take up to 30% of every purchase, and make
> it painful to leave.
>
> Tenshu is building toward something different: an app store on Tenshu Rei, an open trust layer
> where anyone can check who published an app and what it's allowed to do. Fair in-app purchases,
> and what you buy stays yours, even if you switch stores.

⚠ Rei is an **"open trust layer"** in public copy, never a blockchain or ledger, even though the
whitepaper describes it as a distributed-ledger protocol. To this audience those words read as crypto
speculation. The block's test pinned it; restore that test with the block.

**Visual**: `BlockStore.tsx`, text-only, **"In planning"** badge.

---

## 10. Block 8 — Hardware

**Goal**: introduce Tenshu's hardware ambitions — the platform isn't only software. Both
items are early; this block signals direction, not a ship date.

**Headline (H2)**:

> Tenshu, in the room with you.

**Body**:

> Tenshu isn't only software. We're designing hardware that brings your realm into your home
> — ambient compute you own, with no one else listening in.

Two side-by-side cards, each with a placeholder photo, a description, and an **"In planning"**
badge:

- **Tenshu Hub** — a smart-home device that runs a Tenshu proxy. Ambient compute in your home
  without handing a big-tech company a microphone in every room; pairs with your phone and
  hosts your realm where you live.
- **Mia speaker** — a Bluetooth speaker that gives Mia (the on-device assistant, §9) a voice
  in the room. Speech recognition runs locally; your voice never leaves your realm unless you
  grant the permission.

**Visual**: `Block8Hardware.tsx`, two `HardwareCard`s reusing `ScreenshotPlaceholder`
(landscape) for the device photos until real product photography exists (§15 Open Items).

**Length**: 1 lead paragraph + 2 cards. Honest and brief — these are in planning.

---

## 10a. Founder — "Who's building this."

**Goal**: put a named person behind the pitch just before the sign-up, and say honestly that
Tenshu is one person so far, turned into an open invitation rather than an apology.

**Voice**: first person, deliberately, while there is one founder. Switch it to "we" once the
company has more members; the rest of the page already says "we".

**Copy**: lives in `locales/en/landing.json` under `founder` (German and French under the same key,
machine-translated pending review like the rest of the landing, `languages.ts` `reviewed`). It makes four moves: the
founder's background (nine years of apps for banks, insurers and carmakers), the name (the
*tenshu* is the keep at the centre of a castle, from which the realm is ruled), "so far on my own"
plus the source going public soon as the reason to trust it, and an invitation to try it, build on it, partner or
invest. ⚠ It deliberately names no amounts, rounds or timelines.

⚠ The company line spells out **Cajetan UG (haftungsbeschränkt)** in full: dropping the suffix is
a personal-liability risk under German law, and the block's test pins it.

**Visual**: `BlockFounder.tsx`, text-only like Block 5, with links to GitHub, LinkedIn and the
contact address. A portrait can sit above the heading once one exists.

⚠ "Since 2022" is the maintainer's claim to keep accurate; it appears in all three languages.

The same introduction, in longer and shorter forms, is used on the company site
(cajetan.company/ueber-uns) and on LinkedIn, so keep the facts in step across all three.

---

## 11. Block 9 — Sign-up + FAQ

**Goal**: convert the interested user; answer the obvious questions before they have to ask.

**Headline (H2)**:

> Ready to try?

**Primary CTA card** (prominent, top of block):

- `[Sign up]` — leads to §4b new-user onboarding from [`web-onboarding-ux.md`](web-onboarding-ux.md).
- `I already have Tenshu` — leads to §4a existing-realm pairing.

**FAQ section** (collapsible, ~6 entries):

> **Is Tenshu free?**
>
> Yes, with a generous free tier — currently {free_tier_size} GB of encrypted storage. Paid tiers
> for more storage (and, in the future, premium third-party apps you can pay for once).

> **What if Tenshu shuts down?**
>
> Your data is on your devices. The sync relay is open-source — you can run your own. Your recovery
> code works regardless of who's running Tenshu. The platform is designed so that *the worst case*
> is
> operationally inconvenient, not catastrophic.

> **Why open source?**
>
> Because privacy claims you can't verify aren't privacy. Every line of Tenshu is auditable, and the
> architecture is designed so that a compromised Tenshu (or a Tenshu under legal compulsion) cannot
> undo your privacy. ([See our threat model.](../sync/threat-model.md))

> **How does this compare to Signal, iCloud, etc.?**
>
> Tenshu is broader. Signal protects your messages; iCloud syncs your data with Apple holding the
> keys for some things; Tenshu is a whole platform where apps live and your data is yours
> throughout.
> We use ideas from many of these: end-to-end encryption (like Signal), seamless multi-device sync (
> like iCloud), open-source with reproducible builds (like F-Droid).

> **What about the data I create today on other apps?**
>
> Tenshu doesn't import directly — most other apps don't expose their data in usable formats. As
> Tenshu's ecosystem grows, individual apps add import paths (Drive imports from Google Takeout,
> Timeline imports from Google Location History, etc.). Migration is a real concern; we treat it
> seriously.

> **Where can I learn more?**
>
> [Whitepaper](../whitepaper.adoc) · [Architecture](../web/architecture.md) · [Threat model](../sync/threat-model.md) · [GitHub](https://github.com/...) · [Roadmap](../milestones.md).

**Length**: 6 questions, each answered in 1–3 sentences. Avoid marketing-speak; answer honestly.

---

## 12. Mockup Catalog

The interactive mockups are built once as React components in `web/apex/` (per the architecture
decision) and reused across this narrative, future decks, and possibly the actual product UI. Each
mockup is its own component, accepts props for variation, and can be exported as a static frame (for
the investor and developer decks).

**As built:** M1–M3 landed as static, code-drawn line art (`M1Hero`, `ProblemArt`, `AnswerArt`, plus
`RealmArt` for Block 4) — not the animations and interactive demos described in the M1–M3 rows below,
which remain the original plan. Their shared rules: [`docs/design/landing-illustrations.md`](../design/landing-illustrations.md).

| ID     | Used in                   | Type                   | Description                                                                                                                                                                                                                                                          |
|--------|---------------------------|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **M1** | Block 1 (Hero)            | Looping animation, ~6s | Three devices arranged in a triangle, syncing via animated lines. The sync lines show a "🔒 ciphertext" indicator during transit. Devices show plaintext (a recognizable file or photo).                                                                             |
| **M2** | Block 2 (Problem)         | Static / subtle hover  | A phone in the centre with arrows fanning out, each labeled with a category of data recipient. Hovering an arrow reveals a short example.                                                                                                                            |
| **M3** | Block 3 (Tenshu's answer) | Interactive demo       | A mock "Photo Printer" app makes a request; the proxy shows a permission grant dialog with the human-readable description; the user clicks *Approve* or *Deny*; the demo plays out. Two clicks total. Reset button to replay.                                        |
| **M4** | *(retired)*               | —                      | The old ciphertext-transit loop belonged to the retired "End-to-End Encrypted, Plainly" block. Block 5 (Limits) is **text-only** now; its future visual is the "relay's-eye view" (§7), not this. Its stand-in `EncryptionArt` was deleted.                               |
| **M5** | *(reassigned)*            | Interactive demo       | The device-pairing demo (three phones; a fourth joins via QR + SAS confirm). No longer Block 4's visual — Block 4 (Your Realm) now ships the **"realm ring"** (`RealmArt`, §6, **landed**). This pairing demo still has a home as an onboarding / Control illustration. Its stand-in `DevicesArt` was deleted. |
| **M6** | Block 7 (Apps)            | Carousel + real screenshots | **Landed as the shared app carousel** (§9): real captures from the store manifests in a neutral CSS phone frame; apps without captures show a placeholder phone. Richer captures (stock photos) plug in through `LANDING_SCREENS`. The line-art stand-in `TodayArt` was deleted. |
| **M7** | *(retired)*               | —                      | The Today/Soon/Later roadmap timeline (`M7Timeline`) was removed when Block 7 became the gallery-driven Apps section. Apps now live in the §9 carousel; the broader roadmap lives in `milestones.md`. The component was deleted — reinstate from git history if a future deck wants the timeline visual. |

### Reuse opportunities

- **M1** is the strongest abstract-concept explainer and likely appears in *every* future
  deck (investor and developer) as a static frame or short loop. Joining it: the "realm
  ring" (§6, Block 4 — `RealmArt`, **landed**), and the still-future "relay's-eye view"
  (§7, Block 5). (M4 retired; M5 is an interactive pairing demo, not an
  abstract-concept explainer, and is no longer mapped to a landing block.)
- **M3** maps closely to the actual permission-grant UX in Control / the apex. The same component,
  with different props (the permission text, the icon for the requesting app), should be reusable as
  the production UI element.
- **M6** is product marketing — it shows what we have. The Apps carousel (§9) is the same
  component as the support hub's; per-app screen keys in `LANDING_SCREENS` choose the captures.
- **M7** is retired from the landing (the roadmap timeline was removed when Apps became
  gallery-driven). The canonical roadmap is `milestones.md`; reinstate `M7Timeline` from git
  history if a future investor/developer deck wants the timeline visual.

### Implementation notes

- Built as React components under `web/apex/src/landing/illustrations/` (the illustrations) and
  `web/apex/src/landing/carousel/` (M6).
- Animations respect `prefers-reduced-motion` and degrade to static frames.
- Not built: interactive demos (M3's permission grant, M5's pairing) and a static SVG / PNG export
  script for PDF decks.

---

## 13. Copy and Tone Principles

A few rules that should hold across the whole narrative:

- **Plain language, no jargon.** "End-to-end encrypted" is unavoidable but should be plainly
  explained on first use. "Proxy" is unavoidable but is introduced with the helper *"a proxy app on
  your device."* "Realm device" is Tenshu-specific and should be framed by example before being
  named.
- **Don't oversell.** "We're building this; here's the roadmap; you can watch" is more credible
  than "all this is coming soon!" The audience knows the difference. The Apps (§9) and Hardware
  (§10) sections apply this literally: shipping apps carry an "Available" badge, everything else
  an "In planning" one. The Multiplatform cards (§8) carry no badge, because every platform ships at
  the first release.
- **Don't infantilise.** Tenshu is for users who are choosing a privacy-focused tool; treat them as
  capable. Don't talk down to them about encryption or threat models.
- **Honest about limits.** Block 5 ("Honest about the limits") is critical — what the relay
  *can* see, framed by the whitepaper's Zero-Trust Backend principle. It is its own block,
  not a footnote inside the realm block, precisely because hiding limitations creates
  distrust later when users find them out. Keep it confident, never a hedge.
- **One clear primary action per block.** The hero has one CTA. The sign-up block has one CTA. Don't
  dilute.
- **Active verbs.** *"Add your devices"* not *"Devices can be added."*
- **No marketing-speak.** No "revolutionary," "game-changing," "unparalleled." If a sentence could
  appear on any startup's homepage, rewrite it.

---

## 14. Reusing This Content For Future Decks

This narrative is structured so that future decks can pull from it without reinventing the wheel.

### For the investor deck (later)

The core concept module (Blocks 1–5) becomes the deck's *Problem* and *Solution* sections, possibly
compressed:

- Block 1 → cover slide
- Block 2 → "The problem" slide (single slide)
- Blocks 3 + 4 → "Our approach" slides (2–3 slides)
- Block 5 → "What this looks like for users" slide (1 slide)

Then the deck adds investor-specific modules: market sizing, competitive landscape, business model,
team, ask. The roadmap visual the investor deck wants is `M7Timeline` — retired from the landing
(§12) but reinstatable from git history — driven from `milestones.md`.

### For the developer deck (later, after the SDK works)

The core concept module appears compressed (1–2 slides — developers don't need 5 slides to
understand E2EE). Then the deck adds developer-specific modules: Tenshu IDL, semantic permissions in
code, the SDK with examples, monetization mechanics, distribution (Tenshu Rei, App Console, Tenshu
Store). The Apps section (§9) gets a developer-flavored version showing what's available to build
against.

### Maintaining consistency

When this content updates (a new app ships, a roadmap item moves), the future decks need updating
too. Keep the source of truth in this doc; downstream decks reference and import (literally, for the
React mockups; conceptually, for the copy).

---

## 15. Open Items

- **Visual brand.** Colors, typography, illustration style. Out of scope for this content design;
  needs its own workstream once Tenshu has a designer or design partner. The narrative's structure
  is brand-agnostic.
- **Richer screenshots in the Apps carousel (§9).** Control and Drive show their published store
  captures; the others a placeholder phone. A later round takes richer captures with stock photos —
  publish them and add the keys to `LANDING_SCREENS`.
- **Real hardware photography (§10).** The Hub and Mia-speaker cards use `ScreenshotPlaceholder`
  stand-ins; replace with real product photography (or renders) once the devices have a look.
- **The free-tier size.** `{free_tier_size}` is a placeholder. The actual number depends on the
  storage cost and the App Console / Tenshu Store decisions in `milestones.md`.
- **Contact / community channels.** The FAQ has a *Where can I learn more?* answer that should also
  include community channels (Discord? Matrix? GitHub Discussions?) once those exist.
- **A11y review.** The landed illustrations are static, each with one `aria-label`; if the M3
  permission-grant or M5 pairing demo is ever built, its screen-reader narration needs explicit copy.
  Audit before launch.
- **Translation.** This is English. Tenshu's audience is international; translation is a real
  follow-up. The content design above maps cleanly to translation, with the caveat that idioms and
  tone don't always translate; per-locale review is needed.

---

## 16. References

- [`docs/web/architecture.md`](../web/architecture.md) — the architecture this narrative reflects.
  In
  particular: the multiplatform / three-layer model (Multiplatform + Apps, §8–§9), realm devices and
  the pairing flow (Block 4), the apex/relay split for Block 5.
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — the flow the *Sign up* and *I
  already have
  Tenshu* CTAs lead into.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — referenced from the Block 4 inline note
  and the FAQ.
- [`docs/whitepaper.adoc`](../whitepaper.adoc) — the long-form vision; FAQ links to it.
- [`docs/milestones.md`](../milestones.md) — the source of truth for the roadmap; feeds the
  "Coming later" set of the Apps carousel (§9) and the Hardware (§10) cards, and the retired `M7Timeline` if reinstated.
- Future: investor pitch deck (TBD location); developer evangelism deck (TBD location, after
  `@tenshu/sdk` works).
