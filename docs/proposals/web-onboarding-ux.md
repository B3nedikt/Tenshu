# Tenshu Web Onboarding — UX Specification

**Status:** Draft v0.1
**Scope:** The user-facing detail of every onboarding and returning-user flow on `tenshu.app`.

This document expands [`docs/web/architecture.md`](../web/architecture.md) §8 with screen-by-screen
copy, wireframe descriptions, edge cases, error states, and per-browser instructions for the
operations that need browser-specific guidance. The architecture doc specifies *what* happens
at each step; this doc specifies *what the user sees and reads*.

For wireframes: this doc uses text-based descriptions (layout, hierarchy, key elements). When
the project gains a design tool with shareable links, those should replace the text
descriptions here.

---

## 1. Conventions

- **Headings (H1, H2)** indicate flow milestones; the user sees them as page titles or large
  headings on the screen.
- **Body** copy is written for an end user — not a developer. Avoid jargon. Where Tenshu has
  a specific concept (realm device, recovery phrase, passkey), the first mention in any flow
  should briefly anchor it.
- **CTAs** (call-to-action buttons) are in `[Brackets]`. The primary action is bold-equivalent;
  secondary actions are plain.
- **Inline help** is denoted by `( ? )` and expands to a tooltip on hover/focus.
- **Tone**: clear, calm, technically honest. Tenshu is for users who want to understand what
  the system does. Don't oversimplify; don't over-jargon.

---

## 2. Three Landing States

When a user opens `tenshu.app`, the apex inspects:

1. Whether IndexedDB (`tenshu.app` origin) contains a Tenshu state record.
2. Whether the WebAuthn session token is still valid.

| IndexedDB | Session valid | State                      | Flow                                  |
|-----------|---------------|----------------------------|---------------------------------------|
| Has state | Yes           | Active session             | Direct to control plane               |
| Has state | No            | Locked                     | §3 Returning-user unlock              |
| Empty     | —             | First time on this browser | §4 First-time, branches into 4a or 4b |

State is determined synchronously on first navigation; no network round-trip needed.

---

## 3. Returning User: Locked

The user has used Tenshu on this browser before but their session has expired (or they've
explicitly logged out, or the browser restarted).

### Screen 3.1 — Welcome Back

**Title**: "Welcome back, {device_name}"

(`{device_name}` is the user-given name for this realm device, stored locally. Defaults to
*"This Browser"* if the user never named it.)

**Body** (one paragraph):

> Your Tenshu data is locked. Use your security key or device passkey to unlock.

**Layout**:

- Centred card, ~480px wide on desktop.
- Top: small Tenshu logomark.
- Title (H1).
- Body paragraph.
- Primary CTA: `[Unlock with passkey]`
- Secondary link: `Lost your passkey?` → §6 Recovery flow.
- Footer (small, muted): a link to the help docs explaining what a passkey is.

### Screen 3.2 — Browser WebAuthn Prompt

This is a native browser dialog, not a Tenshu-rendered screen. The user sees their browser's
standard credential picker (Touch ID / Windows Hello / hardware key prompt). On success, the
flow proceeds. On failure, screen 3.3.

### Screen 3.3 — Unlock Failed

**Title**: "Couldn't verify"

**Body**: depends on the failure mode reported by WebAuthn:

- *Cancelled by user*: "You cancelled the unlock. Try again when you're ready."
- *Wrong credential*: "That security key isn't registered for this browser. Use a different
  one or recover via your recovery phrase."
- *Hardware error*: "Your security device couldn't be reached. Make sure it's plugged in (or
  Bluetooth-paired) and try again."
- *Unsupported*: "This browser doesn't support passkeys. To unlock, use your recovery phrase
  on a supported browser, or switch to one." (See §11 for browser support.)

**CTAs**:

- `[Try again]` — re-issues the WebAuthn challenge.
- `Use recovery phrase` — link to §6.

---

## 4. First Time on This Browser

IndexedDB is empty. The flow asks one question to branch:

### Screen 4.0 — Welcome

**Title**: "Welcome to Tenshu"

**Body** (one paragraph):

> Tenshu is a private app ecosystem. Your data stays end-to-end encrypted across your devices,
> and apps you install can only access what you let them.

**Question**: "Are you new to Tenshu, or do you have an account on another device?"

**CTAs**:

- `[I have Tenshu on another device]` → §4a
- `[I'm new to Tenshu]` → §4b

The ordering puts existing-account first because returning users on a new device are common
(picking up a friend's laptop, a new work machine) and the new-user flow is longer.

---

## 4a. First Time, Existing Realm Device

The user has Tenshu on a phone, laptop, or desktop and is adding this browser to their realm.

### Screen 4a.1 — Pair This Browser

**Title**: "Add this browser to your realm"

**Body**:

> To add this browser to your realm, you'll pair it with one of your existing devices. This
> keeps your data secure: even Tenshu can't add a device to your realm without your existing
> devices approving it.

**Layout**:

- A QR code, ~256×256px, prominently displayed.
- Below the QR code, the manual pairing string (12-character base32 code — fallback for
  hardware that can't scan).
- To the right (on desktop) or below (on mobile): step-by-step instructions:
  > 1. Open the Tenshu app on your existing device.
  > 2. Go to *Realm Devices → Add new device*.
  > 3. Scan this QR code (or enter the manual code).

**Status indicator** (live, below the QR):

- *Waiting for your existing device to scan…* (with subtle spinner)

**CTAs**:

- `Cancel` — returns to §4.0.

The QR code expires after 5 minutes per [`pairing-flow.md`](../sync/pairing-flow.md); on
expiry, the screen replaces the QR with `[Generate a new QR code]`.

### Screen 4a.2 — Approval Pending

**Title**: "Approve on your existing device"

(Shown after the existing device scans, before approval.)

**Body**:

> Your other device received the request. Look for a notification or open the Tenshu app to
> approve.

**Status**: A short-authentication-string (SAS) is displayed:

> Confirm this code matches on your other device:
>
> **482 913**

The user verifies the SAS appears identically on both screens before approving on the
existing device. This rules out an active attacker who substituted a man-in-the-middle key.

### Screen 4a.3 — Pairing Successful

**Title**: "Welcome back to Tenshu"

**Body**:

> This browser is now part of your realm. Let's set up secure unlock for next time.

**CTAs**:

- `[Set up passkey]` → §4a.4

### Screen 4a.4 — Set Up Passkey

**Title**: "Add a passkey for this browser"

**Body**:

> Passkeys let you unlock Tenshu quickly using your device's biometrics or a security key. Your
> passkey is unique to this browser; it's not shared with your other devices.

**Layout**:

- Small explainer below the body: "If your hardware doesn't support passkeys, you can skip
  this and use your recovery phrase to unlock instead — but unlocking will take longer each
  time."

**CTAs**:

- `[Set up passkey]` — invokes WebAuthn registration.
- `Skip` (less prominent) — proceeds without a passkey; unlock will require recovery phrase.

The browser dialog handles the actual passkey creation. On success, the apex stores the
credential ID alongside the master-key wrapper in IndexedDB.

### Screen 4a.5 — Browser Sync Warning

**Title**: "About browser sync"

**Body**:

> If you use Chrome Sync, Firefox Sync, or another browser-sync feature, this browser's Tenshu
> data may be shared across all browsers signed into your sync account.
>
> When that happens, Tenshu treats those browsers as **one** realm device, not several. This
> is your choice — there are reasons to want it that way (instant access on a new machine
> after sync) and reasons not to (a sync-account compromise reaches your Tenshu data).

**Question**: Do you want this browser to be its own realm device?

**CTAs**:

- `[Yes, make this its own realm device]` → §4a.6 (per-browser sync-opt-out instructions)
- `Skip — I'll decide later` → §4a.7

### Screen 4a.6 — Per-Browser Sync Opt-Out

Branches based on detected browser. Browser detection via `navigator.userAgent`; if
ambiguous, show a chooser.

**Chrome / Edge**:

> 1. Open Settings → You and Google → Sync and Google services.
> 2. Click *Manage what you sync*.
> 3. Switch to *Customize sync*.
> 4. Disable the toggle for **Site Settings**.
>
> This excludes IndexedDB for `tenshu.app` from sync.

**Firefox**:

> 1. Open Settings → Sync.
> 2. Under *What's syncing*, click *Change*.
> 3. Disable *Site Settings*.

**Safari**:

> Safari syncs site data through iCloud. To exclude `tenshu.app`:
>
> 1. Open System Settings → Apple ID → iCloud.
> 2. Click *Manage* next to iCloud Drive.
> 3. Find Safari and turn off *Browse with Safari* — *or* use Private Browsing for `tenshu.app`,
     > which Safari excludes from iCloud sync automatically.

(Safari's options here are unfortunately less granular than other browsers'.)

**Other**: a generic "consult your browser's settings for site-data sync" message.

**CTAs**:

- `[I've done this]` → §4a.7
- `Skip for now` → §4a.7 (the warning re-surfaces in the control plane)

### Screen 4a.7 — Layered Explainer

**Title**: "How you can use Tenshu"

**Body**:

> Tenshu works on the web in three ways. You can use any combination — they all work together.

**Layout**:

Three icon-and-description blocks, side-by-side on desktop, stacked on mobile:

1. **Web** (you're using this now)

   > First-party Tenshu apps work in any modern browser. No install needed.

   No CTA — this is what the user has.

2. **Browser extension** (optional)

   > Adds support for third-party Tenshu apps on websites outside `tenshu.app`. Coming soon
   > once Tenshu Rei is operational.

   CTA: `Tell me when it's available` (sets a flag; the user gets one notification when v1.1
   ships).

3. **PWA install** (optional)

   > Install Tenshu as an app for icon launching, offline support, and a window without
   > browser chrome.

   CTA: `[Install Tenshu]` — triggers the browser's PWA install prompt.

**CTAs**:

- `[Continue to control plane]`

### Screen 4a.8 — Control Plane

The main app. Out of scope for this doc; covered in `docs/web/control-plane-ux.md` (when
written). The user lands here with all their existing devices visible in the device list.

---

## 4b. First Time, New User

The user has never used Tenshu. This flow sets up their master identity, their recovery
phrase, and their first realm device.

### Screen 4b.1 — Brief Intro

**Title**: "How Tenshu works"

**Body** (slightly longer; sets expectations):

> Tenshu is built on a simple idea: your data belongs to you. When you set up Tenshu, you
> create a *realm* — a personal space where your apps live and your data syncs end-to-end
> encrypted across your devices.
>
> Apps you install in your realm can only access what you let them. Tenshu's servers see
> ciphertext, not your data. If you lose your devices, a recovery code lets you start over
> without losing anything.

**CTAs**:

- `[Got it, let's start]` → §4b.2
- `Tell me more about Tenshu` → external link to a longer explainer page.

### Screen 4b.2 — Generate Master Identity

**Title**: "Setting up your realm"

**Body**:

> We're generating your realm's identity in your browser, locally. This identity never leaves
> your device unencrypted.

A subtle progress indicator runs for 1–2 seconds while the BIP-39 entropy is generated and the
Argon2id-derived key wrapper is computed. No user action required.

**Auto-advance** to §4b.3 on completion.

### Screen 4b.3 — Show Recovery Phrase

**Title**: "Save your recovery phrase"

**Body**:

> This is the only way to recover your realm if you lose all your devices. Write these 24 words
> down on paper or store them in a password manager. Don't take a screenshot — your phone's
> photo backup may be visible to others.

**Layout**:

- 24 words displayed in a 4×6 grid, each numbered. Plain monospace font, generous spacing.
- Below the words: `[Copy to clipboard]` button (with a 30-second countdown after click,
  during which a banner reads *"Clipboard will be cleared in 30 seconds"*).
- Below that: a checkbox the user must tick — "I've stored this somewhere safe."

**CTAs**:

- `[Continue]` — disabled until the checkbox is ticked.

**Inline warnings**:

- A red banner: *"Tenshu support will never ask for your recovery phrase. If anyone — even
  someone claiming to be from Tenshu — asks for it, refuse."*
- A yellow note: *"This phrase looks similar to a Bitcoin or Ethereum wallet seed phrase, but
  it is **not** one. Don't enter it into a cryptocurrency wallet — your data won't be
  recovered, and the wallet can't decrypt it."*

### Screen 4b.4 — Confirm Recovery Phrase

**Title**: "Let's check you saved it"

**Body**:

> Enter the words at these positions to confirm you wrote them down.

**Layout**:

- Three (or five, configurable) random word positions shown as labelled input fields.
  Example: *Word 7*, *Word 14*, *Word 21*.
- Each field accepts only valid BIP-39 words (autocomplete suggests as the user types).
- Below: `[Verify]` button.

**Error state**: if any word is wrong, show:

> One or more words don't match. Make sure you copied your phrase exactly, then try again.

with `[Try again]` (returns to §4b.3 to re-display the phrase) or `[Re-enter the words]` (lets
the user retry on this screen).

After 3 failed attempts in a session, force `[Re-display phrase]` to make sure the user still
has access to it.

### Screen 4b.5 — Set Up Passkey

Same as §4a.4. The text is slightly longer because this is the user's first encounter with
the concept.

### Screen 4b.6 — Browser Sync Warning

Same as §4a.5 / §4a.6.

### Screen 4b.7 — Layered Explainer

Same as §4a.7.

### Screen 4b.8 — Storage Tier (Informational)

**Title**: "About storage"

**Body**:

> Tenshu's free tier gives you {free_tier_size} GB of encrypted storage on Tenshu's relay.
> This is enough for documents, notes, and most photos. If you fill it up, you can upgrade
> to a paid tier from the control plane.
>
> Storage is on a Tenshu-operated server, end-to-end encrypted. Tenshu's servers store the
> ciphertext, but cannot read your data.

**CTAs**:

- `[Continue]` (the only option — no upgrade flow during onboarding)

(`{free_tier_size}` is read from the App Console / Tenshu Store config at runtime.)

### Screen 4b.9 — Empty Control Plane Tour

The user lands in the control plane. A brief tour highlights three areas:

1. **Devices** — *"This is your realm. Right now it has just this browser. Add more devices
   when you want to access Tenshu from your phone, laptop, or desktop."*
2. **Apps** — *"This is where you'll install Tenshu apps. Drive (file management) is
   available now; more first-party apps are coming."*
3. **Recovery** — *"Manage which volumes are included in your server-side recovery backup
   here. By default, your master identity is included; you can opt in to backing up specific
   volumes."*

The tour is dismissible; users who skip it can revisit it from a help link in the control
plane.

---

## 5. Returning User: Active Session

When the user opens `tenshu.app` and the session is still valid, no flow is needed. They land
directly in the control plane. The session token in IndexedDB has a configurable lifetime
(default: 24 hours).

If the session has expired but the IndexedDB state is intact, that's the *Locked* state —
§3.

---

## 6. Recovery Flow (Lost All Passkeys / All Devices)

The user has lost access to every realm device, or has lost all of this browser's registered
passkeys. The recovery phrase is the cold-storage backup.

### Screen 6.1 — Recovery Entry

**Title**: "Recover access"

**Body**:

> If you've lost access to all your devices and passkeys, enter your 24-word recovery phrase
> below to recover your realm.
>
> This is a slow operation by design — it takes a few seconds to derive your master key from
> your recovery phrase. That delay is what protects your phrase from brute-force guessing.

**Layout**:

- A textarea or 24 individual word inputs (configurable; for accessibility, the textarea is
  the default, with auto-completion).
- A live indicator showing how many of the 24 words have been entered.
- BIP-39 word-list validation: invalid words are highlighted in red.

**CTAs**:

- `[Recover]` — disabled until 24 valid words are present.

### Screen 6.2 — Recovery Processing

**Title**: "Recovering your realm…"

**Body**:

> This may take 5–10 seconds.

A progress indicator while Argon2id derives the master key from the entered phrase, the
backup blob is fetched from the relay, and the master identity is reconstructed.

### Screen 6.3 — Recovery Successful

The user is treated as a returning user with a brand-new device (this browser). The flow
proceeds through the §4a.4 (passkey setup), §4a.5–§4a.6 (browser sync), §4a.7 (layered
explainer), and finally lands them in the control plane.

The control plane shows:

- This browser as a new realm device.
- A recovery banner: *"Your realm was recovered from your recovery phrase. Your other
  devices, if any are still functional, will need to be re-paired."*

### Recovery Failures

- *Phrase doesn't decode*: "The phrase you entered isn't valid. Check for typos and try
  again."
- *Backup blob not found on relay*: "We couldn't find a backup for this recovery phrase. If
  you set up Tenshu recently, your backup may not have synced yet — try again in a few
  minutes." (Edge case; happens if the recovery flow is invoked before the initial backup
  upload completed.)
- *KDF parameters out of date*: "This recovery phrase was created with an older version of
  Tenshu. Recovery is still possible, but takes longer." (Future-proofing; covers the case
  where Argon2id parameters are tightened over the years.)

---

## 7. Edge Cases

### 7.1 Browser Closes Mid-Flow

If the user closes the tab during onboarding (4b especially — they generated a master identity
but didn't save the recovery phrase), the IndexedDB state is *not* committed until §4b.4
(confirm-recovery-phrase) succeeds. So a partial flow leaves no trace; reopening `tenshu.app`
returns to §4.0 fresh.

This is intentional: a master identity that the user can't recover is dangerous (data could be
written to the realm that the user can never get back). Confirming the recovery phrase is the
gate.

### 7.2 Passkey Unsupported

If the browser doesn't support WebAuthn (rare in 2026 but possible in corporate / locked-down
environments), §4a.4 / §4b.5 offers a passphrase fallback:

**Title**: "Set up a passphrase"

**Body**:

> Your browser doesn't support passkeys. Set a passphrase to unlock Tenshu — pick something
> you'll remember, but that no one would guess.

The passphrase is run through Argon2id (same parameters as the recovery phrase) to derive a
session key. Strength meter encourages a strong choice.

**Browsers without WebAuthn or Argon2id support**: extremely rare; the flow shows a
"Recover via recovery phrase only" mode where the user types the phrase on every login. Slow
but functional.

### 7.3 PWA Install Declined

If the user declines the PWA install in §4a.7 / §4b.7, the apex behaves as a regular tab.
This is fully supported but loses the always-available-proxy benefit for the browser
extension's request routing. The control plane's "How you're using Tenshu" panel offers a
re-prompt for PWA install at any time.

### 7.4 Existing Device Approves Pairing After Browser Closes

In §4a.1, if the user closes the tab after generating the QR code but before the existing
device approves, the pairing waits indefinitely on the existing device's side. On the
browser side, when reopened, the apex sees no IndexedDB state and routes the user back to
§4.0.

The existing device's pairing UI shows the request in a "pending" state with a 5-minute
expiry (per the pairing flow). After expiry, the request is silently dropped on both sides.

### 7.5 Multiple Tabs Opened During Onboarding

If the user opens `tenshu.app` in multiple tabs during onboarding (race condition), only the
first tab to commit IndexedDB state "wins." Subsequent tabs detect the IndexedDB state on
load and route to §3 (Locked) instead of §4 (First-time). The unfinished tabs become "Locked"
tabs and get the welcome-back prompt.

### 7.6 Network Error During Recovery

If the relay is unreachable when the user enters their recovery phrase (§6.2), the flow
shows a retry option. The recovery phrase is *not* re-derived between retries — only the
relay fetch is. So a flaky network doesn't cause repeated 5-second Argon2id waits.

---

## 8. Copy Guidelines

- **Avoid jargon when possible.** "Realm device" is unavoidable Tenshu terminology, but
  introduce it on first mention with a brief explanation.
- **Don't say "encrypted" without context.** Users hear "encrypted" everywhere; what matters
  is *who has the keys*. When relevant, say "end-to-end encrypted across your devices" or
  "encrypted on Tenshu's servers but only your devices can decrypt it."
- **Don't infantilise.** Users on Tenshu are choosing a privacy-focused tool. Treat them as
  capable of understanding the trade-offs you're presenting.
- **Be honest about limitations.** Browser sync, passkey-unsupported environments, network
  errors — call them out. Hiding limitations breeds distrust later.
- **Action-oriented buttons.** `[Continue]` is fine when the next step is obvious;
  `[Set up passkey]` is better when the action varies. Don't use `[OK]`.
- **Error messages should be specific and actionable.** Not *"Something went wrong"*; instead,
  *"Your security key isn't registered for this browser. Use a different one or recover via
  your recovery phrase."*

---

## 9. Wireframe Notes

This doc is text-only. When the project gains a design tool, screens described here should be
mocked up at three breakpoints: mobile (375px), tablet (768px), desktop (1280px+). The general
layout principles:

- **Centred card on large screens**, full-bleed on mobile.
- **One primary action per screen**. Avoid forcing the user to choose between multiple equal
  paths.
- **Progressive disclosure**. Each onboarding step is its own screen — no scrolling tour, no
  multi-step forms on a single screen.
- **No dark patterns**. The PWA install and extension install are offered as informational
  yes/no, not as required-to-proceed steps.
- **High-contrast accessible defaults**. The flow contains security-critical text (recovery
  phrase) that *must* be readable. WCAG AA at minimum.

---

## 10. Open Items

- **Internationalisation.** The flows are written in English. Tenshu's recovery code uses the
  BIP-39 English wordlist (per the threat model's §11.2 implementation note); recovery flows
  in other languages need to either show the English wordlist (with a translation note) or
  use the BIP-39 wordlist for the user's locale (which complicates recovery on a different
  locale's browser). Decide before localising.
- **Accessibility audit.** WCAG AA is the floor. A real audit on the flows above — particularly
  the recovery-phrase display (timing-out clipboard, reading 24 words to a screen reader) —
  needs to happen before launch.
- **Visual design.** This doc covers content and structure; it does not commit to visual
  design (colors, type, illustration style). That's a separate workstream.
- **Animation.** Screens transition; there's no spec for how. Defer until visual design lands.
- **The "remember me" question for passkeys.** Some users will want passkey-with-no-passphrase
  (high convenience, lower security); others will want passphrase-on-top-of-passkey (lower
  convenience, higher security). The flow above defaults to passkey-only. A future setting
  in the control plane may let users opt up.
- **Re-onboarding.** What if a user wipes their browser data on `tenshu.app` but still has
  paired devices in their realm? Currently they go through §4a (existing realm device)
  again; that works but might be confusing for someone who forgot they had Tenshu before.
  Edge case; revisit after observing real users.

---

## 11. Browser Support Reference

For the WebAuthn fallback decision in §7.2:

| Browser                      | WebAuthn passkeys | Argon2id               | PWA install     | Status                        |
|------------------------------|-------------------|------------------------|-----------------|-------------------------------|
| Chrome 110+ (desktop)        | ✓                 | ✓ (via libsodium-wasm) | ✓               | Full support                  |
| Firefox 115+ (desktop)       | ✓                 | ✓                      | ✓               | Full support                  |
| Safari 17+ (macOS / iOS)     | ✓                 | ✓                      | ✓ (with limits) | Full support                  |
| Edge 110+                    | ✓                 | ✓                      | ✓               | Full support                  |
| Older Chrome / Firefox       | ✗ (Touch ID only) | ✓                      | ✓               | Passphrase fallback           |
| Lockdown enterprise browsers | varies            | ✓                      | varies          | Recovery-phrase-only fallback |

---

## 12. References

- [`docs/web/architecture.md`](../web/architecture.md) — the architecture this UX implements.
  In particular: §4 (auth model, master-key location), §8 (the high-level onboarding flow),
  §10 (layer coexistence, the "How you're using Tenshu" panel).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — T-RC-01 (recovery code handling),
  §11.2 (24-word entropy and BIP-39 caveat), §10 (browser sync risk).
- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — the pairing protocol used in
  §4a.1–§4a.3. The web is just one variant of the requester device.
- [`docs/proposals/web-extension.md`](web-extension.md) — the extension's onboarding (in-store
  install,
  permission grants) is out of scope for this doc; it lives there.
