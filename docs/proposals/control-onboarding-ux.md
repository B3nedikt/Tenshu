# Tenshu Control — Onboarding & Recovery UX

**Status:** Draft v0.1
**Scope:** The user-facing detail of every first-run, pairing, recovery, and unlock flow inside
the **Control** app on Android and Desktop. The web equivalent lives in
[`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md); this doc is the native counterpart.

This document expands the existing protocol-level specs with screen-by-screen copy, wireframe
descriptions, edge cases, and error states. It does **not** redefine the protocol.

- The cryptographic handshake for pairing is in [
  `docs/sync/pairing-flow.md`](../sync/pairing-flow.md);
  this doc describes what the user sees while that handshake runs.
- The pairing UX hardening (SAS, fingerprint, denied-path, single-use semantics) committed to
  in [`docs/proposals/sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) is
  treated
  as in-scope here — the screens below assume those steps have landed in the protocol.
- The recovery design — recovery manifest, what's recoverable, the BIP-39 mnemonic — is in
  [`docs/sync/threat-model.md`](../sync/threat-model.md) §11.1 and §11.2; the typed
  exception subtypes the recovery screen branches on are in
  [`docs/proposals/bip39-decode.md`](bip39-decode.md) §1.1.

For wireframes: this doc uses text-based descriptions (layout, hierarchy, key elements). When
the project gains a design tool with shareable links, those should replace the text
descriptions here.

---

## 1. Conventions

- **Headings (H1, H2)** indicate flow milestones; the user sees them as page titles or large
  headings on the screen.
- **Body** copy is written for an end user — not a developer. Avoid jargon. Where Tenshu has
  a specific concept (realm device, recovery phrase, recovery manifest), the first mention in
  any flow should briefly anchor it.
- **CTAs** (call-to-action buttons) are in `[Brackets]`. The primary action is bold-equivalent;
  secondary actions are plain.
- **Inline help** is denoted by `( ? )` and expands to a tooltip on press / hover.
- **Tone**: clear, calm, technically honest. Tenshu is for users who want to understand what
  the system does. Don't oversimplify; don't over-jargon.
- **Platform**: Control runs on Android and Desktop. Most flows are identical on both; where
  they differ (camera-vs-no-camera, biometric availability, system PIN), the platform split is
  called out per screen.

---

## 2. Three Landing States

When the user opens Control, the app inspects the local Tenshu state directory:

| Local state | App-lock state            | State                     | Flow                                  |
|-------------|---------------------------|---------------------------|---------------------------------------|
| Has realm   | Unlocked / within timeout | Active session            | Direct to Control home                |
| Has realm   | Locked                    | Locked                    | §3 Returning-user unlock              |
| Empty       | —                         | First time on this device | §4 First-time, branches into 4a or 4b |

State is determined synchronously on app start; no network round-trip needed. The "locked"
state is reached after the user-configurable inactivity timeout (default: 15 minutes on
Android, 60 minutes on Desktop) or an explicit *Lock now* action.

### 2.1 "Has realm" must be a durable question (as-built, 2026-08-25)

⚠⚠ **The first column above is answered by `SyncStatus.ownerId`, and it must be sourced from
`SyncManager.residentOwnerId` — the persisted `Keys.OWNER_ID` marker — never from the in-memory
`currentOwnerId`.** `SyncManagerImpl.init` loads the keys back on a *background* dispatcher,
because `initialize` Argon2id-stretches the recovery phrase and that cannot run on Android's main
thread inside `ProxyService.onCreate`. For the seconds that takes, `currentOwnerId` is null. Every
reader in the sync loop tolerates that window by design; **this table does not**, because "Empty"
routes to §4.0 *"Start fresh on this device"* and `LandingStateRepository.detect()` decides **once**
per launch with no retry.

Sourcing it from the in-memory value shipped Control-on-Android onto the fresh-install onboarding
screen on roughly every cold start of a provisioned device (measured on a OnePlus CPH2581: §4.0 at
a 0.5 s and a 1.5 s probe after process start, Home at 3 s; fixed and re-verified on the same
device at 0.3/0.5/0.8/1.2 s). `PairingWizardViewModel` reads the same field to choose the *offerer*
role — "install keys onto this blank device" — so the same window pointed a realm-holding device at
a flow that installs keys over it.

Consequences worth holding onto:

- **"Has realm" ≠ "keys are loaded."** `LandingState.Active` means the device belongs to a realm;
  `SyncStatus.isEnabled` is the flag for "can this device sync right now". Screens that need live
  keys read the latter.
- **The marker is already lifecycle-correct**: `initialize` / `initializeWithKeys` write
  `Keys.OWNER_ID`, and every discard path clears it via `Keys.CUSTODY_SLOTS`, so a forgotten or
  wiped device still reaches onboarding.
- **A failed status read is not "Empty" either.** `detect()` retries a failing read rather than
  routing a set-up user to the destructive screen — the house form of *not-connected is a failure,
  never an empty success*.
- ⚠⚠ **Tests cannot see this window by default.** Every other `SyncManagerImpl` test injects
  `Dispatchers.Unconfined`, which runs the autoload inline; that is why the suite stayed green
  through the defect. `ColdStartOwnerIdTest` parks the autoload on a paused dispatcher and asserts
  *inside* the window, pinning `isEnabled == false` on each assertion to prove it is still there.
- iOS reached the same conclusion independently and locally — `ControlSurface.hasResidentIdentity()`
  reads this same slot precisely because `landingStateKey()` "answers `first_time` … for one whose
  runtime has not loaded yet". That workaround now sits on top of a source that is no longer
  transiently wrong.

---

## 3. Returning User: Locked

The user has set up Tenshu on this device before but the app is locked.

### Screen 3.1 — Welcome Back

**Title**: "Welcome back, {device_name}"

(`{device_name}` is the user-given name for this realm device, picked during pairing or
first-run. Defaults to the device hostname if the user never named it.)

**Body** (one paragraph):

> Your Tenshu realm is locked. Use your device biometric or PIN to unlock.

**Layout**:

- Centred card on Desktop, full-bleed on Android.
- Top: small Tenshu logomark.
- Title (H1).
- Body paragraph.
- Primary CTA: `[Unlock]` — invokes the platform biometric/PIN prompt (Android `BiometricPrompt`,
  macOS `LAContext`, Linux/Windows fallback to a Tenshu-rendered PIN screen).
- Secondary link: `Lost access to all devices?` → §8 Recovery flow.

### Screen 3.2 — Platform Biometric / PIN

This is a native OS dialog, not a Tenshu-rendered screen. The user sees the system's standard
biometric prompt (Face / fingerprint / Watch) or PIN entry. On success, the flow proceeds. On
failure or cancel, screen 3.3.

### Screen 3.3 — Unlock Failed

**Title**: "Couldn't unlock"

**Body**: depends on the failure mode reported by the platform:

- *Cancelled by user*: "You cancelled the unlock. Try again when you're ready."
- *Biometric not recognized*: "We didn't recognize that. Try again, or use your device PIN."
- *Too many failures*: "Too many failed attempts. Wait a moment, then try again — or recover
  from your recovery phrase."
- *Hardware unavailable*: "Your device's biometric sensor isn't reachable right now. Use your
  device PIN to unlock."

**CTAs**:

- `[Try again]` — re-issues the platform prompt.
- `Use recovery phrase` — link to §8.

---

## 4. First Time on This Device

Local state is empty. The flow asks one question to branch:

### Screen 4.0 — Welcome

**Title**: "Welcome to Tenshu"

**Body** (one paragraph):

> Tenshu is a private app ecosystem. Your data stays end-to-end encrypted across your devices,
> and apps you install can only access what you let them.

**Question**: "Are you new to Tenshu, or do you have an account on another device?"

**CTAs**:

- `[I have Tenshu on another device]` → §4a
- `[I'm new to Tenshu]` → §4b

The ordering puts existing-account first because users adding a new phone or laptop to an
existing realm are common; the new-user flow is longer.

---

## 4a. First Time, Existing Realm Device (Pairing as Requester)

The user has Tenshu on a phone, laptop, or desktop and is adding *this* device to their realm.
This is the requester side of the pairing handshake described in
[`pairing-flow.md`](../sync/pairing-flow.md). The host side (where the existing device generates
the QR and approves) is documented in §6.

### Screen 4a.1 — Open Tenshu on Your Other Device

**Title**: "Open Tenshu on your other device"

**Body**:

> To add this device to your realm, you'll connect it with one of your existing devices. This
> keeps your data secure: even Tenshu can't add a device to your realm without your existing
> devices approving it.

**Layout**:

- Top: a brief two-step instruction:
  > 1. On your existing device, open Tenshu Control.
  > 2. Go to *Realm Devices → Add new device*.
- Below: two ways to continue:
    - **Scan QR code** — primary CTA on Android (camera available).
    - **Enter pairing code** — primary CTA on Desktop without webcam; secondary CTA on Android.

**CTAs**:

- `[Scan QR code]` (Android primary) — invokes the camera scanner.
- `[Enter pairing code instead]` — opens the manual-entry screen (§4a.2b).
- `Back` — returns to §4.0.

### Screen 4a.2a — Scan QR Code

A camera viewfinder full-screen with a scan box overlay. Below the viewfinder:

> Hold the QR code from your existing device inside the box.

**Permissions**: if camera permission is denied, falls through to §4a.2b with a notice
*"Camera access denied. Enter the pairing code below instead."*

**CTAs**:

- `Cancel` — returns to §4a.1.
- `Enter code instead` — switches to §4a.2b.

On a successful decode → §4a.3.

### Screen 4a.2b — Enter Pairing Code

**Title**: "Enter the pairing code"

**Layout**:

- A single text field accepting the 12-character base32 code from the host's screen.
- Auto-uppercase, hyphen-insertion every 4 characters for readability.
- Live validation: invalid characters are rejected on input; the field highlights green when
  the code parses to the expected shape.

**CTAs**:

- `[Continue]` — disabled until the code parses.
- `Back` — returns to §4a.1.

On valid code → §4a.3.

### Screen 4a.3 — Name This Device

**Title**: "Name this device"

**Body**:

> Pick a name for this device. You'll see it in your realm's device list — *"Sarah's
> MacBook"*, *"Living Room Hub"*, anything you'll recognise.

**Layout**:

- Single text field, prefilled with a sensible default (`{platform} {model}` if available).
- Below the field: short helper *"You can rename this later in Settings → Realm Devices."*

**CTAs**:

- `[Continue]` — disabled if the field is empty.

After this screen, this device posts the `PairingRequest` to the relay (per pairing-flow.md
Step 2) and starts polling for the response → §4a.4.

### Screen 4a.4 — Approval Pending

**Title**: "Confirm on your other device"

**Body**:

> Your other device received the request. Look there for the approval prompt.

**Status**: A short-authentication-string (SAS) is displayed prominently, per
[`pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 2:

> Confirm this code matches on your other device:
>
> **482 913**

The user verifies the SAS appears identically on both screens before approving on the
existing device. This rules out an active attacker who substituted a man-in-the-middle key.

**Status indicator** below the SAS:

- *Waiting for approval…* (with subtle spinner)

**CTAs**:

- `Cancel` — abandons the pairing attempt; the local ephemeral keypair is discarded.

The pairing window is 5 minutes (per `pairing-flow.md` §5). On expiry, the screen replaces
the SAS with `[The request expired — start over]` → §4a.1.

### Screen 4a.5 — Pairing Successful

**Title**: "You're in your realm"

**Body**:

> This device is now part of your realm. Your existing data will sync over the next few minutes.

**Layout**:

- A brief progress indicator showing initial sync (number of blocks remaining) — informational,
  the user can dismiss and continue.
- A note: *"Don't worry if sync takes a few minutes on slower connections."*

**CTAs**:

- `[Continue]` → §4a.6.

### Screen 4a.6 — Set Up Local Unlock

**Title**: "Lock Tenshu when you're not using it"

**Body**:

> Set up biometric unlock for fast access. Your biometric stays on this device — Tenshu never
> sees your fingerprint or face.

**Layout**:

- Auto-detected: shows whichever biometric the device supports (fingerprint, face, neither).
- If neither: offers a Tenshu-managed PIN.

**CTAs**:

- `[Set up biometric unlock]` — invokes the platform `BiometricPrompt` enrolment.
- `Use device PIN instead` — falls through to the platform credential prompt or, on platforms
  without a system credential prompt, a Tenshu-rendered PIN screen.
- `Skip — unlock with recovery phrase only` (less prominent) — proceeds without local unlock;
  every unlock will require typing the recovery phrase. Strongly discouraged via copy.

After completion → §4a.7.

### Screen 4a.7 — Welcome to Control

The Control home screen with the device list visible. A one-time tour highlight points at:

1. **Realm Devices** — *"This is every device in your realm. Right now you can see your other
   devices and this one."*
2. **Recovery** — *"This is where you manage what's backed up to your recovery phrase. Take a
   look when you have a moment."*

The tour is dismissible.

---

## 4b. First Time, New User

The user has never used Tenshu. This flow sets up their master identity, their recovery
phrase, their first realm device, and their initial recovery manifest.

### Screen 4b.1 — Brief Intro

**Title**: "How Tenshu works"

**Body** (slightly longer; sets expectations):

> Tenshu is built on a simple idea: your data belongs to you. When you set up Tenshu, you
> create a *realm* — a personal space where your apps live and your data syncs end-to-end
> encrypted across your devices.
>
> Apps you install in your realm can only access what you let them. Tenshu's servers see
> ciphertext, not your data. If you lose your devices, a recovery phrase lets you start over
> without losing anything you chose to back up.

**CTAs**:

- `[Got it, let's start]` → §4b.2
- `Tell me more about Tenshu` → external link to a longer explainer page.

### Screen 4b.2 — Generate Master Identity

**Title**: "Setting up your realm"

**Body**:

> We're generating your realm's identity on this device. This identity never leaves the device
> unencrypted.

A subtle progress indicator runs for 1–2 seconds while the BIP-39 entropy is generated and
the Argon2id-derived key wrapper is computed. No user action required.

**Auto-advance** to §4b.3 on completion.

### Screen 4b.3 — Show Recovery Phrase

**Title**: "Save your recovery phrase"

**Body**:

> This is the only way to recover your realm if you lose all your devices. Write these 24 words
> down on paper or store them in a password manager. Don't take a screenshot — your phone's
> photo backup may be visible to others.

**Layout**:

- 24 words displayed in a 4×6 grid, each numbered. Plain monospace font, generous spacing.
  On Android phones in portrait, the grid stacks to 2×12; the layout always preserves
  per-word numbering.
- Below the words: `[Copy to clipboard]` button (with a 30-second countdown after click,
  during which a banner reads *"Clipboard will be cleared in 30 seconds"*; on platforms that
  don't expose programmatic clipboard clearing, the banner reads *"Remember to clear your
  clipboard after pasting somewhere safe"*).
- Below that: a checkbox the user must tick — "I've stored this somewhere safe."

**CTAs**:

- `[Continue]` — disabled until the checkbox is ticked.

**Inline warnings**:

- A red banner: *"Tenshu support will never ask for your recovery phrase. If anyone — even
  someone claiming to be from Tenshu — asks for it, refuse."* (Per `threat-model.md` T-S-01,
  social-engineering defense.)
- A yellow note: *"This phrase looks similar to a Bitcoin or Ethereum wallet seed phrase, but
  it is **not** one. Don't enter it into a cryptocurrency wallet — your data won't be
  recovered, and the wallet can't decrypt it."* (Per `threat-model.md` §11.2 UX caveat.)

**Screen security**: the activity is marked `FLAG_SECURE` on Android (blocks screenshots and
screen-recording); on Desktop, the window is marked as not screen-shareable where the platform
exposes that hint.

### Screen 4b.4 — Confirm Recovery Phrase

**Title**: "Let's check you saved it"

**Body**:

> Enter the words at these positions to confirm you wrote them down.

**Layout**:

- Three random word positions shown as labelled input fields. Example: *Word 7*, *Word 14*,
  *Word 21*.
- Each field accepts only valid BIP-39 words (autocomplete suggests as the user types).
- Below: `[Verify]` button.

**Error state**: if any word is wrong, show:

> One or more words don't match. Make sure you copied your phrase exactly, then try again.

with `[Try again]` (returns to §4b.3 to re-display the phrase) or `[Re-enter the words]` (lets
the user retry on this screen).

After 3 failed attempts in a session, force `[Re-display phrase]` to make sure the user still
has access to it.

### Screen 4b.5 — Set Up Local Unlock

Same as §4a.6.

After this screen, the new-user flow optionally enters the subscription flow per
[`control-subscription-ux.md`](control-subscription-ux.md) §3 (product picker → checkout →
entitlement arrival, with a single-tap escape to the free tier). The user lands back here
after either subscribing or skipping; the recovery-manifest picker (§4b.6) is the next
screen regardless.

### Screen 4b.6 — Recovery Manifest Picker

**Title**: "What should be backed up?"

**Body**:

> Your master identity is always backed up — that's what your recovery phrase restores. You
> can also choose specific data to back up to Tenshu's relay, encrypted, so you can get it
> back if you lose every device.

**Layout**:

- A short explainer at the top: *"Backups are end-to-end encrypted. Tenshu's servers store
  ciphertext only — they can't read what's backed up. The recovery phrase is what unlocks
  it."*
- Below: a list of Tenshu volumes. At first-run on a new realm there are usually only one or
  two — the default Drive volume and any opted-in early-bird volumes. Each row shows the
  volume name and a toggle.
- Below the list: a footer note *"You can change this any time from Settings → Recovery."*

**CTAs**:

- `[Continue]`

This screen sets the initial recovery manifest per
[`threat-model.md`](../sync/threat-model.md) §11.1. The default is master-identity-only
(every toggle off); selecting any volume opts it into the encrypted server-side backup blob.

### Screen 4b.7 — Welcome to Control (Empty Realm)

The Control home screen. The realm device list shows one entry — this device. A one-time
tour highlight points at:

1. **Realm Devices** — *"Add your phone, laptop, or other devices here when you want them to
   share your realm."*
2. **Apps** — *"Install Tenshu apps here. Drive (file management) is available now; more
   first-party apps are coming."*
3. **Recovery** — *"Manage what's included in your encrypted backup here."*

The tour is dismissible.

---

## 5. Returning User: Active Session

When the user opens Control and the app is already unlocked (within the inactivity timeout),
no flow is needed. They land directly in the Control home screen.

If the timeout has elapsed but local state is intact, that's the *Locked* state — §3.

---

## 6. Pairing — From the Host Side

The user is on an existing realm device and wants to add another. This documents what *they*
see — what the requester sees during the same handshake is in §4a.

### Screen 6.1 — Realm Devices

(Reached from Control's main navigation: *Settings → Realm Devices*.)

**Title**: "Realm Devices"

**Layout**:

- A list of every realm device — name, platform icon, last-seen time. The current device is
  marked with a small *"This device"* badge.
- Above the list, a primary CTA: `[Add new device]` → §6.2.
- Per row, a kebab menu with `Rename`, `Revoke from realm` (with a confirmation dialog).

### Screen 6.2 — Display QR + Pairing Code

**Title**: "Add a device to your realm"

**Body**:

> On your new device, choose *"I have Tenshu on another device"*, then either scan this code
> or type the pairing code below.

**Layout**:

- A QR code, prominently displayed (~256×256dp on Android; ~320×320px on Desktop).
- Below the QR code: the manual pairing string in large monospace text, formatted as
  `XXXX-XXXX-XXXX` (12 base32 characters with hyphens). Copy-to-clipboard button next to it.
- Below that: a status indicator:
  > *Waiting for the new device to scan or enter the code…* (with subtle spinner)
- A countdown (visible but not prominent): *"This code expires in {mm:ss}"*. Default 5 minutes.

**CTAs**:

- `Cancel` — returns to §6.1; the host's `PairingContext` is invalidated immediately per
  [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 5.

The QR code expires after 5 minutes per [`pairing-flow.md`](../sync/pairing-flow.md) §6; on
expiry, the screen replaces the QR with `[Generate a new pairing code]`.

### Screen 6.3 — Approval Prompt

(Shown after the requester device posts a `PairingRequest` matching the secret.)

**Title**: "Approve new device?"

**Body**:

> A device wants to join your realm:
>
> **{device_name}** ({platform})

Below, prominently per [`pairing-flow-improvements.md`](sync-pairing-flow-improvements.md)
Steps 2 and 3:

> Confirm both of these match on the new device:
>
> SAS code: **482 913**
> Public-key fingerprint: **F4A2-9C71-K8M3**

**Layout**:

- Both verification strings shown in large, easy-to-read monospace.
- A short note below: *"Both should match exactly. If anything looks different, deny — someone
  may be trying to intercept your realm."*

**CTAs**:

- `[Approve]` — primary, distinguished colour. Disabled for 2 seconds after the screen
  appears to discourage rubber-stamping.
- `[Deny]` — secondary, but visually present.

On Approve: the host computes the shared secret, encrypts the master identity keys, and posts
the `PairingResponse` per `pairing-flow.md` Step 3 → §6.4.

On Deny: the request is rejected; the relay is informed; the new device receives the *denied*
path per [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 10. The host returns to §6.1.

### Screen 6.4 — Approval Confirmation

**Title**: "Device added to your realm"

**Body**:

> **{device_name}** is now a realm device. Your data will start syncing to it over the next
> few minutes.

**CTAs**:

- `[Done]` — returns to §6.1, where the new device now appears in the list.

### Host-Side Errors

- *Pairing window expired before the requester scanned*: the host's QR screen times out and
  shows `[Generate a new pairing code]`. No notification is shown to the requester (it has no
  way to know the host's window).
- *Requester abandoned mid-flow*: the host's QR screen continues to wait until the window
  expires. Cancelling on the host invalidates the secret per Step 5.
- *Host's `PairingContext` invalidated by another trigger* (app backgrounded for >2 minutes,
  device locked, app killed): the user returning to Control sees §6.1 with no active QR; the
  former QR is invalid. Re-tapping `[Add new device]` generates a fresh context.

---

## 7. Recovery Flow (Lost All Devices)

The user has lost access to every realm device — phone gone, laptop wiped, etc. — and is
recovering on a fresh install. The recovery phrase is the cold-storage backup.

The entry point is §3.1 *"Lost access to all devices?"* on a returning-but-locked install,
**or** the §4.0 welcome screen via a less-prominent secondary action *"Recover from a recovery
phrase"*. Both lead here.

### Screen 7.1 — Recovery Entry

**Title**: "Recover your realm"

**Body**:

> If you've lost access to all your devices, enter your 24-word recovery phrase below to
> recover your realm.
>
> This is a slow operation by design — it takes a few seconds to derive your master key from
> your recovery phrase. That delay is what protects your phrase from brute-force guessing.

**Layout**:

- 24 individual word inputs in a 4×6 grid (Android: stacks 2×12 in portrait). Each field has
  per-word BIP-39 autocomplete: as the user types, valid completions appear; the field
  accepts only words from the BIP-39 English wordlist.
- A live counter at the top: *"{n} of 24 words"* — turns green at 24.
- A `[Paste from clipboard]` button at the top that splits a pasted phrase across the 24
  fields, lower-casing and trimming whitespace per `Bip39.decode()`'s handling.
- Below the grid: an inline warning *"Don't share this phrase with anyone — including
  Tenshu."*

**CTAs**:

- `[Recover]` — disabled until 24 valid words are present.
- `Back` — returns to the entry point (§3.1 or §4.0).

### Screen 7.2 — Recovery Processing

**Title**: "Recovering your realm…"

**Body**:

> This may take 5–10 seconds.

A progress indicator while:

1. `Bip39.decode(phrase)` produces the entropy.
2. Argon2id derives the master key from the entropy.
3. The recovery blob is fetched from the relay.
4. The master identity is reconstructed from the blob.

This screen never shows progress percentages — the steps are not uniformly long, and a
percentage would either be misleading or expose the cryptographic timing.

### Screen 7.3 — Recovery Successful

**Title**: "Welcome back"

**Body**:

> Your realm is recovered. This device is now your only realm device — if you find your other
> devices later, you'll need to re-pair them.

**Layout**:

- A small banner acknowledging what was restored:
  > Restored: master identity{volumes_appendix}.

  where `{volumes_appendix}` reads *", plus {n} backed-up volumes"* if the recovery manifest
  included volumes, or empty otherwise.
- A reminder: *"You'll see all your devices and apps re-appear over the next few minutes as
  things sync."* (Edge: only true if any of those things were in the recovery manifest. If the
  user opted out of all volumes during onboarding, the recovered realm is effectively empty
  beyond the master identity.)

**CTAs**:

- `[Continue]` → §4a.6 (set up local unlock for this newly-recovered device).

### Recovery Failures — branching by `Bip39InvalidMnemonicException` subtype

The `Bip39.decode()` call throws three distinct subtypes per
[`docs/proposals/bip39-decode.md`](bip39-decode.md) §1.1. The UI branches accordingly to
give actionable feedback rather than a generic "invalid phrase" message.

- *`UnknownWord(word)`*: highlight the offending field(s) in red, and show inline copy:
  > *"'{word}' isn't a valid recovery word. Check spelling, or pick a suggestion from the
  > autocomplete."*
  Stays on §7.1 with the partial phrase preserved.
- *`InvalidLength(wordCount)`*: not normally reachable from §7.1 (the UI gates `[Recover]` on
  exactly 24 valid words), but can occur on pasted input. Show:
  > *"Recovery phrases are 24 words. You entered {wordCount}."*
- *`ChecksumMismatch`*: every word is in the wordlist but the phrase as a whole isn't valid.
  This is the "you mistyped one of your words" case. Show:
  > *"This phrase doesn't look right. Double-check each word — even one wrong word breaks
  > recovery."*
  Stay on §7.1 with the entered words preserved.

After 5 consecutive failures, surface a more prominent help link: *"Trouble recovering? Read
our recovery troubleshooting guide."*

### Recovery Failures — non-cryptographic

- *Backup blob not found on the relay*: "We couldn't find a backup for this recovery phrase.
  If you set up Tenshu recently, your backup may not have synced yet — try again in a few
  minutes." Edge case; happens if recovery is invoked before the initial backup-blob upload
  completed.
- *Network unreachable*: "Tenshu's relay isn't reachable. Check your network and try again."
  The recovery phrase is *not* re-derived between retries — only the relay fetch is. So a
  flaky network doesn't cause repeated 5-second Argon2id waits.
- *KDF parameters out of date*: "This recovery phrase was created with an older version of
  Tenshu. Recovery is still possible, but takes longer." (Future-proofing; covers the case
  where Argon2id parameters are tightened over the years.)

---

## 8. Recovery Manifest Editing (Settings)

Reached after onboarding from *Settings → Recovery*. Lets the user change which volumes are
included in the encrypted backup blob, per
[`threat-model.md`](../sync/threat-model.md) §11.1.

### Screen 8.1 — Recovery Manifest

**Title**: "What's in your backup"

**Body**:

> Your master identity is always backed up. The volumes below can also be included; including
> a volume means it can be recovered if you lose every device, but it costs storage and
> upload time.

**Layout**:

- A list of volumes — every Tenshu volume the user is part of. Each row:
    - Volume name and source app.
    - Toggle for *"Included in recovery backup"*.
    - Below the toggle, small status: *"Last backed up {timestamp}"* or *"Not backed up"*.
- Footer: *"After changing this, your devices will re-upload the backup blob. This may take a
  few minutes."*

**Editing semantics**: every toggle change triggers a new signed re-upload of the recovery
blob (per `threat-model.md` T-RC-05). The previous blob is superseded; the relay garbage-
collects but cannot guarantee secure erasure, so the *content* of any volume that was once
backed up is potentially-recoverable by an attacker who obtains the recovery phrase, until
the recovery phrase itself is rotated. The screen surfaces this caveat once, on the first
time the user toggles a volume *off*:

> Removing a volume stops new backups, but data that was already in earlier backups stays
> recoverable to anyone with your recovery phrase. To remove that data from past backups,
> rotate your recovery phrase from *Settings → Recovery → Rotate phrase*.

(Rotate-phrase is a separate flow, deferred — see §11.)

---

## 9. Edge Cases

### 9.1 App Closes Mid-Onboarding

If the user closes Control during onboarding (4b especially — they generated a master identity
but didn't save the recovery phrase), the local state is *not* committed until §4b.4
(confirm-recovery-phrase) succeeds. So a partial flow leaves no trace; reopening Control
returns to §4.0 fresh.

This is intentional: a master identity that the user can't recover is dangerous. Confirming
the recovery phrase is the gate.

### 9.2 Pairing-as-Requester: Camera Permission Denied

§4a.2a falls through to §4a.2b with a notice. The user is never blocked by camera permission
because manual code entry is always available.

### 9.3 Pairing-as-Requester: QR Scanned but Approval Times Out

The host's pairing window is 5 minutes (host side §6.2 countdown). If the requester scanned
in time but the user is slow to approve on the host, the requester's §4a.4 (approval pending)
shows the SAS until the window expires. On expiry, both sides return to their respective
start screens with a notice.

### 9.4 Pairing-as-Host: Requester Denied After Approval

Currently impossible — the protocol has no "deny after approve" path; once the host approves
and the response is posted, the keys are out. This is acknowledged in `threat-model.md` T-S-04
(Compromised requester device). No UX mitigation; the user revokes the device from §6.1 if
they realise after the fact.

### 9.5 Recovery Used While Realm Has Live Devices

A user can technically recover while their other realm devices are still functional. The
threat model accepts this; the recovered device joins as a fresh device — it does *not*
displace existing devices. The §7.3 message *"if you find your other devices later, you'll
need to re-pair them"* is technically too strong in this case; the existing devices keep
working. We accept the over-warning as the safer copy.

### 9.6 Multiple Control Instances on Same Device

Not supported. Control is a single-instance app on Android (single-task) and Desktop (single
process via lock-file on launch). A second launch attempt is silently re-routed to the
existing window.

### 9.7 User Denies Biometric / PIN Setup at §4a.6 / §4b.5

Allowed but discouraged. If the user skips local unlock entirely, every app-open requires the
recovery-phrase typed-input flow. The decision is recorded; the user can change it later from
*Settings → Lock*.

### 9.8 Realm Device Revoked While Paired

If another realm device revokes this device while it's open, the next operation that touches
the relay returns a revocation error. Control transitions to a one-time *"This device was
removed from your realm"* screen, requires the user to acknowledge, then wipes local state
and returns to §4.0. The user can re-join via §4a (pair from the still-active devices).

---

## 10. Cross-References Between Flows

Three places where flows overlap; calling them out so the implementation doesn't accidentally
write the same UI three times.

- **§4a.6 (set up local unlock) and §4b.5 (set up local unlock) are the same screen.**
  Implementation should share one composable; the entry condition is "we just paired, set up
  unlock now."
- **§7.3 (recovery successful) feeds into §4a.6.** Recovery is essentially a bootstrap that
  produces a freshly-paired-with-self device; after the master identity is reconstructed,
  the rest of the flow is identical to the existing-realm pairing.
- **§4a (existing-realm pairing) and §6 (host-side pairing) are two halves of one handshake.**
  When a single user pairs their own two devices, they walk through both flows
  simultaneously — one device on §4a screens, the other on §6 screens. The protocol guarantees
  the SAS values match if and only if no MITM is in play.

---

## 11. Open Items

- **Recovery-phrase rotation.** Mentioned in passing in §8.1 but not specified. The flow
  should: pick a new BIP-39 phrase, re-encrypt the recovery blob with it, re-distribute to
  devices, invalidate the old phrase. Sized as a follow-up doc.
- **Multi-device pairing during onboarding.** The current §6 flow assumes one requester at a
  time. If a user wants to pair their phone *and* laptop in quick succession, they currently
  go through §6 twice; that's fine but the host's pairing-context invalidation rules might
  catch them out. Worth documenting whether the host can have multiple pairing windows open
  simultaneously, or whether the protocol enforces serialisation.
- **Pairing across the public internet (no LAN).** Today's `pairing-flow.md` doesn't require
  LAN proximity — the relay brokers — but the SAS comparison assumes both users can see both
  screens. Long-distance pairing (helping a family member onboard remotely over a video call)
  is a real use case that needs explicit copy: *"Read the SAS code aloud to the other person;
  if it doesn't match, deny."*
- **Internationalisation.** The flows are written in English. Tenshu's recovery phrase uses
  the BIP-39 English wordlist (per `threat-model.md` §11.2 implementation note); recovery
  flows in other languages need to either show the English wordlist (with a translation note)
  or use the BIP-39 wordlist for the user's locale. The decision is tracked in
  `docs/tech-debt.md` §"BIP-39 Wordlist — English-Only".
- **Accessibility audit.** WCAG AA is the floor. A real audit on the flows above —
  particularly §4b.3 (recovery-phrase display: timing-out clipboard, screen-reader behaviour
  on 24 words) and §7.1 (24 word inputs with autocomplete) — needs to happen before launch.
- **Visual design.** This doc covers content and structure; it does not commit to visual
  design (colours, type, illustration style). That's a separate workstream and lives in
  `docs/design/`.
- **Animation.** Screens transition; there's no spec for how. Defer until visual design
  lands.
- **Threat-model amendment cross-check.** This UX doc commits to the SAS + fingerprint
  display from [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md). The threat-model amendments those steps
  imply (closing T4) need to be reflected in `threat-model.md` once the protocol-side
  changes land — not part of this doc, but worth tracking in the same PR.

---

## 12. Copy Guidelines

- **Avoid jargon when possible.** "Realm device", "recovery phrase", "recovery manifest" are
  unavoidable Tenshu terminology. Introduce each on first mention with a brief explanation.
- **Don't say "encrypted" without context.** Users hear "encrypted" everywhere; what matters
  is *who has the keys*. Say "end-to-end encrypted across your devices" or "encrypted on
  Tenshu's servers but only your devices can decrypt it."
- **Don't infantilise.** Users on Tenshu are choosing a privacy-focused tool. Treat them as
  capable of understanding the trade-offs you're presenting.
- **Be honest about limitations.** Camera-permission denials, biometric absences, network
  failures — call them out. Hiding limitations breeds distrust later.
- **Action-oriented buttons.** `[Continue]` is fine when the next step is obvious;
  `[Set up biometric unlock]` is better when the action varies. Don't use `[OK]`.
- **Error messages should be specific and actionable.** Not *"Something went wrong"*; instead,
  the per-subtype recovery messages in §7.

---

## 13. Wireframe Notes

This doc is text-only. When the project gains a design tool, screens described here should be
mocked up at three breakpoints: phone-portrait (360dp), tablet (720dp), desktop (1280px+).
General layout principles:

- **Centred card on Desktop**, full-bleed on Android.
- **One primary action per screen**. Avoid forcing the user to choose between multiple equal
  paths.
- **Progressive disclosure**. Each onboarding step is its own screen — no scrolling tour, no
  multi-step forms on a single screen.
- **No dark patterns**. Skipping local-unlock or skipping the recovery-manifest opt-ins are
  always offered as informational yes/no, not as required-to-proceed steps.
- **High-contrast accessible defaults**. Recovery-phrase displays must be readable. WCAG AA at
  minimum.
- **Screen-recording protection.** §4b.3 (phrase display) and §7.1 (phrase input) are marked
  no-screenshot / no-screen-share where the platform exposes that hint. On Android,
  `FLAG_SECURE`. On macOS, `NSWindow.sharingType = .none`.

---

## 14. References

- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — handshake protocol.
- [`docs/proposals/sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) — the SAS,
  fingerprint, denied-path, single-use-secret items this doc treats as in-scope.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §11.1 (recovery design) and §11.2
  (recovery-phrase implementation note).
- [`docs/proposals/bip39-decode.md`](bip39-decode.md) — the typed
  `Bip39InvalidMnemonicException` subtypes that §7's error branching depends on.
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — the web equivalent of this doc;
  shapes are intentionally parallel where they can be.
- [`docs/agent.md`](../agent.md) §6 (Shared App Module Conventions) — Screen / Content split,
  Compose Preview requirements, and the JVM-testability rule for Screen composables.
