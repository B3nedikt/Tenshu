# Tenshu — Low-Friction Onboarding & Account Reframe

**Status:** Draft v0.3.
Phase 1, 2a, 2b, 2c, 3a, 3b, 3c, picker skip-if-account, mock Publisher persistence, and
the biometric-on-Android sub-track (proxy primitives, UI delegate, Settings toggle) have all
landed on master. Phase 2d (joiner persistence + wrapper payload generalisation, §10.6 below)
is in flight; Phases 4 (real Publisher), 5 (Rei Key validation), §7.2 Levels 1–3, and
§7.3 sync-removes-affordance remain.
**Scope:** A redesign of first-run onboarding for both **Control** (Android, Desktop) and the
**apex** web surface, plus a new **Services** section in Control where account, billing, and
storage tier are managed. The redesign reframes account creation from "verification gate" to
"sync enablement," defers recovery-phrase capture in favour of biometric / TEE-backed unlock,
and introduces an anti-abuse path that does not collect PII by default.

This document is a delta on the existing flows in
[`docs/proposals/control-onboarding-ux.md`](control-onboarding-ux.md) and
[`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md). It does not redefine the
cryptographic protocol — the handshake in [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md)
is untouched, and master-identity derivation from the recovery phrase is unchanged. What
changes is *when* the user is asked to write the phrase down, *when* they are asked to create
an account, and *how* those two asks are framed.

This proposal carries threat-model amendments (§9) and must land alongside the corresponding
edits to [`docs/sync/threat-model.md`](../sync/threat-model.md) per House Rule #2.

---

## 1. Conventions

This doc uses the same conventions as `control-onboarding-ux.md` §1:

- **Body** copy is end-user-facing — calm, clear, technically honest.
- **CTAs** appear in `[Brackets]`. Primary actions are the first CTA listed.
- **Inline help** is denoted `( ? )` and expands to a tooltip.
- **Platform notes** call out Android-vs-Desktop-vs-web splits per screen.
- User-visible strings are quoted as *"like this"*. Internal identifiers are in `backticks`.

Where this doc references a screen number from `control-onboarding-ux.md` (e.g. §4b.5),
the reference is to the *current* spec; the redesign replaces or reorders many of those
screens, and the diffs are spelled out per section.

---

## 2. Motivation

The current onboarding spec is correct, but reads as ceremonious. A user opening Tenshu
for the first time is presented, in order, with: a brief intro, master-identity generation,
a 24-word recovery phrase to write down on paper, a confirmation step that re-enters three
of those words, a biometric / passphrase / skip choice, and an optional subscription
picker. Only after all of that does any data persist locally — a deliberate commit-gate at
§4b.4 ensures the user does not end up with data that cannot be recovered.

There are two distinct frictions baked into that flow:

**Friction A — the recovery-phrase ceremony fires before the user has decided that Tenshu
is worth their time.** A new user evaluating the app is asked to perform a serious,
official-feeling backup step (find a pen, find paper, transcribe 24 words, re-enter three
of them) before they have created or stored anything. The ceremony is honest — the phrase
really is the master key — but it is timed wrong. Users who churn out at the recovery-phrase
screen never become users; users who push through often skip the *real* backup step (writing
down the words) and tick the checkbox without doing the work, leaving them in a worse
position than if they had been asked later.

**Friction B — the current model is wide open to sybil abuse.** Every fresh master-identity
generation produces a new `ownerId` with whatever storage tier the relay grants by default
(currently 1 GB free). There is no human-verification step, no payment step, no rate-limit
per signup. A script can mint identities indefinitely. The existing anti-abuse story is a
single line in [`docs/proposals/sync-backend-relay-plan.md`](sync-backend-relay-plan.md)
§6.2 about per-device and per-owner rate-limiting, which constrains a single identity's
*throughput* but not the *creation rate* of new identities. At any non-trivial scale this is
load-bearing.

These two frictions look unrelated at first, but they are tangled: both fire at the same
moment (the first-run flow), both put the user under pressure to perform a serious-feeling
action, and both undersell what the user actually gets in return. The reframe in §3 is to
recognise that **both are properly about sync, not about access**, and to defer them to the
moment when the user has indicated they want sync.

---

## 3. Design principles

The redesign rests on three principles, in priority order.

### 3.1 Signup gates sync, not access

A user who installs Tenshu on a single device and never wants to sync should never be
asked to create an account. They should never see the relay, never touch the Publisher,
never enter an email, and never pay anything. The app should work fully — file management,
biometric unlock, the whole product — purely locally.

The moment the user attempts to add a second device, three things become true at once:

1. They now want the relay (the relay is *what* multi-device sync is).
2. They now have a backup story (the second device is a copy of the first).
3. They now create cost for Tenshu (relay storage, bandwidth).

That coincidence is the natural place to introduce account creation. The framing is not
*"prove you're not a script"* — it is *"to sync your data across devices, you'll need a
free account."* The user understands exactly what they get.

### 3.2 Defer ceremony until value is established

The 24-word recovery phrase is generated silently at first-run (it has to be — the master
key is derived from it). What changes is whether the user is *shown* the phrase during
onboarding. By default, no.

Instead, the daily-unlock primary is the device's biometric, with the master key wrapped
in the platform secure element (Android StrongBox / Keystore, iOS Secure Enclave, web
WebAuthn-PRF). This is already an option in the current spec at §4b.5 — the redesign
promotes it from a post-phrase step to the default, and inverts the relationship: phrase
display is the secondary action, not the primary one.

The recovery phrase remains available from Settings at all times. A persistent affordance
("Set up account recovery") sits on the home screen and in Settings until the user has
either captured the phrase or set up sync. Progressive escalation (§7) tightens that
affordance over time as the user accrues data they would not want to lose.

### 3.3 The pseudonymity gradient is the user's to walk

Account creation introduces PII (email, payment token). That is a real cost — Tenshu's
distinguishing claim is that it does not see your data, and adding an email-bound account
shifts the claim to *"the relay does not see your data, but the Publisher knows your
email."* That is still a much stronger position than mainstream cloud storage, but it is
not the strongest possible position.

For users who want the strongest position, two paths exist alongside the free account:

- **One-time license via Play Billing / Stripe / Apple IAP** — purchase a lifetime tier
  through a platform-store flow. No email collected by Tenshu. Anti-abuse comes from the
  financial friction itself. The store handles the identity.
- **"Rei Key" — paste a key from elsewhere** — for future deployment where storage
  capacity is granted by a non-Tenshu source (self-hosted Rei instance, employer-issued
  key, peer-to-peer license). Placeholder in v1; not yet validated against anything.

The Services tab (§6) is where the user picks their position on this gradient and can
move between options as their needs change. The free account is the recommended default
for casual users; the one-time license is recommended for users who do not want to give
Tenshu an email; the Rei Key path is for users who already have a key from somewhere
else.

---

## 4. Reshaped first-run flow — Control (Android & Desktop)

This section replaces §4 and §4b of `control-onboarding-ux.md`. §4a (returning user with
an existing realm on another device, paired via QR / code) is largely unchanged and is
called out where it is reordered.

### Screen 4.0 — Welcome

**Layout** — vertical centred, generous whitespace.

- Tenshu logomark, centred at the top of the content area, ~96 px tall.
- Title (H1): *"Welcome to Tenshu"*
- Body (one paragraph): *"Tenshu is a private place for your files. Everything is
  encrypted on this device — nobody else can read it, including us."*

**CTAs**, presented as two equally-weighted cards stacked vertically (mobile) or
side-by-side (desktop, when there is horizontal room):

- `[Start fresh on this device]` — proceeds to Screen 4.1.
- `[I have Tenshu on another device]` — proceeds to the existing §4a pairing flow.

Both cards are equally prominent. Neither is styled as "primary." A user who has another
device is *not* on the secondary path — they are on a different but equally-valid path.

Diff from current spec: the existing Screen 4.0 styles "I'm new to Tenshu" as the primary
big button with "I have Tenshu on another device" as a smaller secondary link. This is
reversed for users who actually have another device, and the redesign treats both equally.
The logo is also lifted above the title (currently it sits below) to make the surface feel
friendlier.

### Screen 4.1 — Set up unlock

**Title** (H1): *"Set up unlock"*

**Body**: *"Use {biometric_name} to unlock Tenshu. Your data stays locked when this
device is locked."*

`{biometric_name}` resolves to *"Face Unlock"*, *"Touch ID"*, *"your fingerprint"*, etc.
based on the platform's preferred biometric. Desktop without a biometric sensor falls
back to *"a PIN"*.

**Layout**:

- Tenshu logomark at the top, smaller than 4.0 (~48 px).
- Title.
- Body paragraph.
- Primary CTA: `[Use {biometric_name}]` — invokes the platform biometric enrolment /
  confirmation prompt. On success, the master key is wrapped in the secure element and
  the app proceeds to Screen 4.2.
- Secondary link, quiet text below the CTA: *Set a passphrase instead* — opens the
  current §4b.5 passphrase path.

Diff from current spec: this screen now fires *before* any recovery-phrase capture (the
inversion in §3.2). The passphrase option is demoted from a peer choice to a quiet
secondary link — biometric is the strongly recommended default.

**Cryptographic detail**: the master identity is generated silently during this screen.
The 24-word phrase exists from this point on; it is simply not displayed. The wrapping
key for the secure-element-backed daily unlock is derived per
[`docs/sync/threat-model.md`](../sync/threat-model.md) §5 A1 — no change to that derivation.

### Screen 4.2 — You're in

> ⚠⚠ **Amended 2026-08-24 (S2-pre P4, `ios-services-status-and-purchase.md` §3.11).** The body
> below promised readiness the realm could not deliver: `StorageSyncGate` (shipped since this was
> written) holds a new realm local-only until an account is bound, and a **host** cannot be funded
> by a peer — `joinedFundedRealm` is false for the device that started the realm. A user who tapped
> straight through therefore had a Tenshu that would never reach another device and was never told.
> The body now names local-first as the fact it is, and **one** of this screen's dropped secondary
> CTAs is back: *Turn on sync* → the email-OTP screen. It is a link, not a gate.
>
> ⚠ The other two stay dropped, and the reason still holds: *Add another device* and *Set up
> account recovery* are discoverable a tap later on Home. Sync is not — nothing else on a fresh
> install leads anywhere that opens the gate.

**Title** (H1): *"You're set up"*

**Body**: *"Your Tenshu is ready. Open Drive to start adding files, or come back here to
add another device or set up account recovery."*

**Layout**:

- Tenshu logomark.
- Title.
- Body.
- Primary CTA: `[Open Drive]` — exits onboarding, opens the Drive app (Android) or the
  default Drive window (Desktop).
- Secondary buttons, in a quieter style below:
  - `[Add another device]` — enters the §5 account-creation flow (sync requires an
    account; see §5.1).
  - `[Set up account recovery]` — enters the deferred-phrase-capture flow (§7.4).

This screen replaces the existing §4b.6 ("optional subscription") and §4b.7 ("recovery
manifest picker") screens. Both of those are now actions the user can take later from
the Services tab; neither blocks getting into Tenshu.

**State at this point**: A working local Tenshu realm exists. The user has biometric
unlock. The recovery phrase has been generated but is not displayed and is not stored.
No account exists. No relay interaction has occurred. The user can use Drive fully on
this device. If the user loses this device right now, they lose their data — but they
also have no data, so the loss is symmetric with not having installed the app.

### Screen 4.3 — Persistent affordance (post-onboarding)

This is not strictly an onboarding screen, but is the mechanism that makes the deferral
in 4.1 safe. The Control home screen and the Drive empty state both carry a persistent
affordance:

> *Tenshu is only on this device. If you lose it, you lose your data.*
> `[Add another device]` *or* `[Set up account recovery]`

The affordance is dismissible-but-restorable: dismissing it hides it for 72 hours, after
which it returns. It cannot be permanently hidden until one of the two actions has been
taken. The exact escalation pattern is spelled out in §7.

---

## 5. Account creation — entered at sync time

The account-creation flow is entered from any of three places, all post-onboarding:

- `[Add another device]` on the home screen affordance or in Settings.
- `[Get more storage]` in the Services tab.
- A device-pairing attempt that fails because the user does not yet have a relay account.

The flow is identical in all three entry points. The wording of the entry framing differs
per screen, but the steps below are the same.

### 5.1 The account picker

**Title** (H1): *"To sync your data, pick a plan"*

**Body**: *"Tenshu syncs your data across your devices through our relay server. We never
see your files — they're encrypted on your device first. Pick how you'd like to set up
your account."*

**Layout** — three cards, vertically stacked on mobile, in a row on desktop:

1. **Free account** (recommended badge) — *"1 GB. Verify your email. Free forever."*
   `[Sign up free]`
2. **Lifetime** — *"50 GB. One-time payment. No email needed."* `[Buy through {store}]`
3. **Have a key already?** — *"If you have a Rei Key or invite code, paste it here."*
   `[Enter a key]` — opens a smaller text input.

`{store}` resolves to "Google Play", "the App Store", or "Stripe" based on the platform
binding.

A small secondary link at the bottom: *"Why do you need an account at all?"* — opens an
inline explainer covering the abuse-resistance reasoning, in plain language. This is
important: the user is being asked to do something Tenshu has not asked of them before;
they should be able to learn why.

**Diff from current spec**: The existing `control-subscription-ux.md` has a subscription
picker slotted between local-unlock and the recovery-manifest screen, and is optional. The
redesign moves the picker out of onboarding entirely, makes it conditional on actually
wanting sync, and reframes the question from "pick a tier" to "pick how you authenticate."

### 5.2 The free-account path

Selecting `[Sign up free]` opens a single-screen form:

**Title**: *"Create your free Tenshu account"*

**Fields**:

- Email address.
- (Optional) Display name — defaults to *"Your Tenshu"*. Shown only on this account's
  Services tab; never sent to the relay.

**Layout** — minimal. The email field is the focus. A small note: *"We use your email only
to verify you're a real person and to recover your account if you lose your devices. We
never see your files."*

**CTAs**:

- `[Send verification email]` — primary.
- `Cancel` — returns to 5.1.

On submit, the email is sent to the Publisher (see [`tenshu-publisher.md`](tenshu-publisher.md)),
which sends a verification link. Screen 5.2.1 ("Check your email") follows, polling the
Publisher for verification status. The user does not have to leave the device — they can
also tap the link in their email and the polling picks up the change.

Once verified, the Publisher allocates a free-tier entitlement for this `ownerId`, the
relay accepts uploads, and the user is returned to whichever screen invoked the flow
(`[Add another device]` → pairing, `[Get more storage]` → Services, etc.).

**v1 mock**: the Publisher's email-verification flow is mocked. The "Send verification
email" button waits 1500 ms and proceeds as if verified. A feature flag flips this to real
Stripe / SendGrid integration once the Publisher's in-memory v1 (per `tenshu-publisher.md`)
is replaced with persistent state and a real mailer. This is acceptable because v1 is
unreleased; the mock lets us ship the flow end-to-end and iterate on copy.

### 5.3 The one-time-purchase path

Selecting `[Buy through {store}]` invokes the platform's billing flow:

- **Android**: Google Play Billing Library, in-app purchase product `tenshu.lifetime.50gb`.
- **Desktop**: Stripe Checkout opened in the platform browser, returning via a deep link
  back to Control.
- **iOS** (future, not v1): StoreKit, equivalent product ID.

On successful purchase, the store returns a receipt. The Publisher validates the receipt
(server-side) and grants the corresponding entitlement to the `ownerId`. No email is
collected during this flow — the store has the user's identity, Tenshu does not.

**Conversion path**: a one-time-license user who later wants to add an email (e.g. for
recovery purposes, or to share storage with a family member through some future feature)
can do so from the Services tab at any time. The conversion is Publisher-side: the
existing entitlement is preserved and an email is attached to the same `ownerId`. No crypto
state changes; the master identity and the device set are untouched.

**v1 mock**: like the free-account path, billing is mocked behind a feature flag. The
purchase button shows a fake "Processing…" state for 2000 ms and proceeds as if successful.
The real billing integration is gated separately, since it requires store approvals and is
likely the longest-lead-time piece of this proposal.

### 5.4 The Rei Key path

Selecting `[Enter a key]` opens a text field:

**Title**: *"Paste your Rei Key"*

**Field**: monospaced text input, accepts a string of unknown format (v1 is a placeholder
— we have not yet defined the key encoding).

**CTAs**:

- `[Continue]` — submits the key to the Publisher for validation. Until Rei exists, this
  endpoint always returns "unknown key" and the screen shows an inline error: *"This key
  isn't recognised yet. Rei is coming soon."*
- `Cancel` — returns to 5.1.

The Rei Key path ships in v1 as a UI placeholder. It is deliberately visible so that the
architectural intent (federated capacity grants) is communicated to early users; the
backend validation is a stub. When Rei lands, only the Publisher's validation endpoint
changes — the UI does not need rework.

---

## 6. Services tab

A new top-level section in Control's Settings, called **Services**. This is where the
user manages their relationship to Tenshu's hosted infrastructure (and, eventually, to
any third-party Rei instance they have linked).

The name *Services* is deliberate. It reads as "things you plug into" rather than
"things you have to set up." It composes with the future federation story without
committing to it now: the same UI will list "Tenshu Relay (Anthropic-hosted)" alongside
"My self-hosted Rei" once the latter exists. Calling it *Account* or *Billing* would
hardcode the assumption that there is a single provider.

### 6.1 Top-level Services screen

**Layout** — a list of cards, one per service the user is connected to:

- **Tenshu Relay** (always present once an account exists; absent on a no-account
  install)
  - Plan: *"Free"* / *"Lifetime"* / *"Rei (via {source})"*
  - Storage: *"34 MB of 1 GB used"* with a progress bar.
  - Identity: *"Verified with mail@example.com"* or *"Lifetime purchase via Google Play"*
    or *"Linked via Rei Key"*.
  - CTAs: `[Get more storage]`, `[Change identity method]`, `[Disconnect]`.

A bottom row offers `[+ Add a service]` for the future Rei case.

### 6.2 Get more storage

This opens the same account picker as §5.1, with the current plan pre-marked and upgrade
paths foregrounded. A free-tier user sees lifetime and Rei Key as options. A lifetime
user sees additional storage packs (not in v1; sized when concrete).

### 6.3 Change identity method

This is the conversion path described in §5.3. A one-time-purchase user can add an email,
or vice versa, or paste a Rei Key. Each conversion is Publisher-side and never touches
the master identity.

### 6.4 Disconnect

A destructive action that ends the user's account with the Tenshu relay. Their local
realm continues to work; sync stops. Their data is not deleted from the relay immediately
— a 30-day soft-delete window applies (sized in
[`tenshu-publisher.md`](tenshu-publisher.md) — placeholder until concrete).

The disconnect screen warns explicitly that other devices on the same realm will lose
sync, and offers `[Cancel]` as the primary CTA, with `[Disconnect]` requiring a confirm-by-
biometric step.

---

## 7. Recovery-phrase escalation policy

The deferral in §4.1 only works if there is a credible path back to phrase capture before
the user has data they would regret losing. This section spells out when and how the nag
fires, and what the user sees at each level.

### 7.1 Phrase generation and storage

The phrase is generated at master-identity creation (Screen 4.1) and persisted to the
device's `SecureStorageDelegate` under `Keys.RECOVERY_PHRASE`. Storage is platform-secure:
Android Keystore-backed `EncryptedSharedPreferences` on Android, the macOS Keychain (or
equivalent) on Desktop, the WebAuthn-PRF-unwrapped IndexedDB slot on web. After
`setUpLocalUnlock`, the plaintext is wrapped under a passphrase-derived key and removed
from the plaintext slot; the unwrapped form re-appears in memory only during an unlocked
session (via `unlockLocal` → `initialize`). The deferred-capture flow in §7.4 reads from
this persisted form when the user opts in.

**Joiner devices never possess the phrase.** A device that enters the realm via §4a
pairing receives the master device's *derived keys* (`sharedIdentityKey` +
`sharedVolumeKey`) and the realm `ownerId` over the pairing channel — the BIP-39 phrase
never leaves the originating device. Per §10.6 (Phase 2d), joiners persist those
derived keys + ownerId under the unlock wrapper instead of `RECOVERY_PHRASE`, and §7.4
phrase-capture is unavailable on a joiner-only device. Either the master device retains
the phrase or the user has no on-device path back to the BIP-39 form. This is consistent
with the protocol's existing guarantee (`docs/sync/pairing-flow.md` and threat-model §5
A1) — Phase 2d just makes the on-disk wrapper payload reflect what the device actually
has.

This deviates from an earlier draft of this proposal which claimed the phrase could be
*re-derived* from the master key. That claim was cryptographically wrong: the platform's
key derivation is `phrase → Argon2id → master keys`, which is one-way by construction.
There is no inverse function from the master keys back to the BIP-39 entropy, so true
re-derivation isn't possible. Persisted-and-reread is the alternative, and the only one
that lets the deferred-capture UX work without changing the cryptographic primitives. The
phrase therefore has the same at-rest protection as every other secret in
`SecureStorageDelegate` — including the wrapper key itself — and is recoverable any time
the device is unlocked.

The corresponding threat-model amendment lives in
[`docs/sync/threat-model.md`](../sync/threat-model.md) §5 A9 (storage location +
re-derivation note) and §3 I7 (deferral-window residual).

### 7.2 Escalation levels

The phrase-capture affordance escalates through four levels, indexed by both *time since
first-run* and *data accrued*. Whichever threshold triggers first promotes the affordance.

**Level 0 — Quiet hint.** From first-run. The home-screen affordance reads:
*"Tenshu is only on this device. `Set up recovery` or `Add another device`."*
Dismissible for 72 hours.

> ⚠⚠ **As built (2026-10-30), that copy is superseded.** Level 0 shows while *this device holds a
> phrase the user has not confirmed saving* (`admission-revocation-names-the-capability.md`
> §5b.12.1), and that trigger fires on realms that sync — where "only on this device" is false.
> The realm's synced `hasStorage` picks the wording: `false` → *"Your data is only on this device"*
> with **Set up sync** leading and **Save recovery phrase** beside it; `true` or unknown → *"Save your
> recovery phrase"* with that one action. ⚠ **"Add another device" is gone**: pairing gives the new
> device no copy of the phrase, so offering it read as a fix for a risk it does not touch. Table in
> `RecoveryAffordanceViewModel`'s KDoc.

**Level 1 — Active reminder.** Triggers when:
- 7 days have passed since first-run, OR
- 100 MB of data has been added to Drive, OR
- 10 files have been added.

The affordance moves from a small banner to a small modal on next app open, framed as
the fork from §3:
> *"If you lose this phone right now, your Tenshu data is gone. Pick one:*
> *— Set up sync — gives you a backup across devices.*
> *— Write down your recovery phrase — works even without an account."*

The user can dismiss, but the home-screen affordance becomes non-dismissible.

**Level 2 — Block-and-confirm before risky actions.** Triggers when:
- 30 days have passed since first-run, OR
- 500 MB of data has been added, OR
- Any of: switching biometric, factory-resetting the device, uninstalling.

At this level, certain user actions are interstitial-gated by the fork screen above. The
user cannot dismiss the gate; they must pick a path. Both paths are full features — there
is no degraded option. "Set up sync" enters §5; "Write down your recovery phrase" enters
§7.4.

**Level 3 — Persistent banner.** Triggers when neither path has been taken after 60 days
of active use. A persistent non-dismissible banner at the top of the home screen reads:
*"Your data is not backed up. Set up sync or write down your recovery phrase."* The banner
does not interfere with usage but is always visible until resolved.

The exact thresholds (7 days, 100 MB, etc.) are placeholders. They should be tuned once
we have telemetry on actual usage patterns; the framework here is what matters.

### 7.3 The "Set up sync" path

Choosing "Set up sync" from any of the escalation screens enters §5.1 (the account
picker). After successful sync setup, the recovery-phrase affordance is hidden — the
second device is now the recovery story. The phrase remains available in Services →
Tenshu Relay → Advanced for users who want belt-and-braces, but is no longer nagged.

(Note: this is a deliberate trade-off. A two-device sync setup is recoverable only as
long as at least one device survives. Total loss of both devices, simultaneously, with
no phrase captured, is unrecoverable. The threat-model amendment in §9 calls this out as
a documented residual. For users who want strict adherence to current invariant I7, the
"Write down your recovery phrase" option remains available and unchanged.)

### 7.4 The "Write down your recovery phrase" path

This is the existing §4b.3 + §4b.4 of `control-onboarding-ux.md`, lifted out of onboarding
and made available on-demand. The screens are unchanged: phrase display with `FLAG_SECURE`,
confirmation step requiring three random word positions, force re-display after three
failures.

The single change is that this flow can now be entered *post-data* — the user might be
capturing the phrase with 200 MB of files already in their realm. The cryptographic
behaviour is identical (the phrase is re-derived from the secure-element-wrapped master
key), but the UX framing differs: instead of *"this is your one-time chance,"* the copy
reads *"this phrase has been protecting your Tenshu since day one. Write it down now so
you can recover if you lose this device."*

**Availability on joiner devices.** Per §7.1, a device that joined the realm via §4a
pairing does not have the BIP-39 phrase — only the derived keys. The "Set up account
recovery" affordance is hidden on a joiner-only device; the user is directed to view the
phrase on the master device (or any other device that originated via §4 or §7.3
recovery-claim). Settings → Sync surfaces this explicitly when the phrase is
unavailable, so the user understands *why* the affordance is missing rather than
suspecting a bug.

---

## 8. Apex (web) flow

> **Implementation status (2026-06-23):** web side **shipped + test-covered**
> (on-device live-relay round-trip pending). The §10 phasing originally landed
> the deferral for **Control only** (Phase 2 *Modules touched:
> `:control-shared`*); web-roadmap 6.6 ports §8 to the apex. As built:
> §8.1 chooser reframed to `[Sign up free]` / `[I have Tenshu already]` /
> `[Just try it locally]`; the new-user path drops the show/confirm phrase
> ceremony (generate → commit → tail); §7.4 capture moved to a Settings
> "Set up account recovery" entry (`AccountRecoverySetupPage`, route
> `/control/settings/recovery/setup`), sourcing the phrase via a new
> `Recovery.revealPhrase()` SDK-boundary op (apex mock returns it; live SDK
> returns `unavailable`/"coming soon" until the Kotlin actual lands — the wrap
> blob doesn't carry BIP-39 entropy yet, same gap as re-display). Intent
> routing: signup → §5.1 picker, try-mode → control home + the non-dismissible
> `TryModeBanner`. No protocol change; reuses I7 + T-OB-01 (§9). §7.2 Levels
> 1–3 + §8.2's post-upload convert modal remain unbuilt on web (Level-0 nag is
> the standing `TryModeBanner`). Per-flow log in
> [`docs/web/roadmap.md`](../web/roadmap.md) 6.6.

Browsers are not durable storage in the way phones are. A user who installs Tenshu on
their phone and chooses "Maybe later" has a working app sitting on a device they will
keep for years. A user who visits `tenshu.app` and chooses "Maybe later" has a working
app sitting in a browser profile that could be cleared at any time (incognito tab, browser
update, "clear site data" click, OS reinstall). The deferral pattern from §4 does not
translate.

The apex flow therefore differs in one structural way: **on web, signup is more eagerly
prompted, but it is still framed as sync-enablement, not access-gating.**

### 8.1 Apex Welcome (replaces existing apex §4b.1)

**Title**: *"Welcome to Tenshu"*

**Body**: *"Tenshu in this browser will work for now, but to keep your data safe across
browser updates and devices, you'll want sync."*

**CTAs**:

- `[Sign up free]` — primary. Enters §5.1.
- `[I have Tenshu already]` — secondary. Enters the existing apex pairing flow.
- `[Just try it locally]` — tertiary, quiet text link. Enters a Try-mode (§8.2).

The default path on web is to sign up. The deferral is still offered, but it is third in
the visual hierarchy because the cost of deferring on web is much higher than on Android.

### 8.2 Try-mode

A user who picks `[Just try it locally]` enters a Try-mode session:

- Master identity is generated and IndexedDB-persisted (per the existing apex
  architecture).
- WebAuthn-PRF unlock is set up.
- A persistent banner across the top reads: *"You're trying Tenshu locally. Your data
  lives only in this browser. `Sign up free` to save it across devices."*
- The banner is not dismissible.
- After any file upload, a single modal fires: *"This file is only in this browser. Sign
  up free to keep it safe across devices."* — `[Sign up free]` / `[Continue trying]`.
- The phrase-capture flow (§7.4) is available from Settings.

Try-mode is honest about what the user gets and aggressive about converting them. The
"just try it" path is meaningful — it's how a privacy-conscious user evaluates Tenshu
before giving an email — but it is not framed as a long-term mode.

### 8.3 Apex account creation

§5.1's account picker is the same on web, with one platform difference: the lifetime
purchase path uses Stripe Checkout (no Play Billing on the web), and a future deep-link
flow lets a desktop or mobile install pick up the same purchase via the Publisher.

The web account picker does not need an `[Enter a key]` placeholder in v1 — it can be
introduced when Rei does. (No harm including it for parity if implementation cost is
trivial.)

---

## 9. Threat-model amendments

This redesign requires concrete edits to
[`docs/sync/threat-model.md`](../sync/threat-model.md). The edits are summarised below; the
full text will land in the same PR per House Rule #2.

### 9.1 Invariant I7 — recoverability from declared loss

**Current text** (paraphrased): *"A user who does not retain their recovery code cannot
be recovered by any party."*

**Amendment**: I7 holds in the steady state. Onboarding now creates a transient window
during which the user has a working Tenshu but has not yet captured the recovery phrase.
During this window:

- A user with one device only and no recovery-phrase capture: if they lose the device,
  their data is unrecoverable. This is the deliberate residual T-OB-01 (§9.2).
- A user with two or more devices: the additional devices serve as recoverable copies.
  Phrase capture is no longer load-bearing for recoverability, but remains available for
  the belt-and-braces case where all devices are lost simultaneously.

The phrase itself is unchanged in role and lifecycle (derived from master entropy,
deterministic, never persisted). What changes is the *timing* of user-visible capture.

### 9.2 New residual — T-OB-01 (Onboarding deferral)

**Asset:** A9 (recovery phrase) usability during the deferral window.

**Threat:** A user on a single-device install loses the device before they have either
(a) added a second device, (b) captured the phrase. Data unrecoverable.

**Mitigation:**

- Default escalation in §7 ensures the user is prompted before the window persists into
  high-data-value territory.
- The Level 2 block-and-confirm gate at 30 days / 500 MB ensures any user who has accrued
  meaningful data has been forced through a fork-screen choice.
- The single-device-no-sync banner (§7.2 Level 3) provides persistent visibility.

**Accepted residual:** Users who churn out within the deferral window without engaging
either path. By design, these users have no significant data; the loss is bounded by what
they had time to accrue, which is small. Telemetry (when added) will measure the actual
distribution of data accrued per dismissal-time to validate this assumption.

### 9.3 New asset / surface — Publisher account binding

**Currently:** the Publisher is an optional v1 component (per `tenshu-publisher.md`); a
user can use Tenshu fully without ever creating a Publisher account.

**After this redesign:** the Publisher is still optional for single-device users. For
multi-device users, a Publisher account binding is mandatory (it is what mediates the
relay's per-owner entitlement). The Publisher's stored state grows to include:

- Email address (free-tier path) or platform-store receipt (lifetime path) or Rei Key
  reference (Rei path).
- `ownerId` mapping to entitlement.
- (Optional, later) display name.

The Publisher's trust boundary is unchanged: it does not see plaintext user data, it does
not see the master key, and it does not see the relay's encrypted content. Adding
account-creation flow does not relax House Rule #1 (zero-trust backend).

### 9.4 No protocol changes

The pairing handshake (`docs/sync/pairing-flow.md`), the master-key derivation
(threat-model §5 A1), the relay's zero-knowledge invariants, and the recovery-blob
mechanism are all untouched. This proposal is UX-and-flow only; the cryptographic surface
is unchanged.

### 9.5 Unlock-wrapper payload — device-role-dependent (Phase 2d)

**Currently** (pre-Phase-2d): `Keys.UNLOCK_WRAPPER` and `Keys.BIOMETRIC_WRAPPER` wrap the
BIP-39 phrase. `setUpLocalUnlock` / `setUpBiometricUnlock` short-circuit `false` when
`Keys.RECOVERY_PHRASE` is missing — which is *always* the case on a joiner device,
because the phrase never leaves the originating device per the pairing protocol.

**After Phase 2d:** the wrapper payload is *whichever of the two equivalent identity
forms this device actually holds*:

- **Master device** (created via `CreateRealm` or `RecoveryClaim`): wraps the BIP-39
  phrase. `unlockLocal` / `unlockBiometric` decrypt the phrase and call `initialize()`,
  which re-runs `phrase → Argon2id → master keys`. Unchanged from today.
- **Joiner device** (paired via §4a): wraps `sharedIdentityKey + sharedVolumeKey +
  ownerId` (the §10.6 "DerivedIdentity" tuple). `unlockLocal` / `unlockBiometric`
  decrypt the tuple and call `initializeWithKeys(keys, ownerId)` — which is exactly
  what `PairingCompletion` does today in memory.

**Threat-model impact:** the wrapped payload is asset-A1-equivalent on both sides.
A1 in the threat model is *"the per-realm master identity material this device needs to
authenticate to the relay and decrypt sync blocks"*; for the master device that's the
phrase (from which everything else is derived), for the joiner that's the derived keys
themselves. The at-rest protection — Argon2id-stretched wrapper key + AEAD —
applies identically; the integrity gate (`looksLikeMnemonic` for the master path) is
replaced by an AEAD authentication-tag check that already fails-closed on wrong
wrapper-key. Where the threat-model previously said *"the wrapper protects the recovery
phrase"*, the post-Phase-2d wording is *"the wrapper protects the device-local form of
A1 — phrase on master devices, derived-keys tuple on joiners"*.

**No new asset, no new trust boundary.** The joiner device already had to persist the
derived keys somehow to survive a restart (otherwise it could not sync) — Phase 2d
makes that persistence the same wrapper-protected blob as on the master device, instead
of leaving the keys in `IDENTITY_KEY` / `VOLUME_KEY` slots that were never reloaded by
`SyncManagerImpl.init` (the present bug — see §10.6). The change reduces residual
risk (less unreloaded sensitive material on disk; consistent unlock gate on both
devices) without expanding the relay's view of anything.

**Joiner-only devices lose §7.4 phrase capture.** Documented in §7.4 — not a residual,
a derived consequence of the pairing protocol's existing guarantee that the phrase
never leaves the originator.

---

## 10. Implementation phases

Six phases, ordered by independence and risk. Phase 2d was added in v0.3 of this
proposal after the original Phase 2 landed; it covers a defect surfaced once joiner
devices started exercising the redesigned flow (Welcome → "I have Tenshu on another
device" → Pair → SetupUnlock). The other phases are unchanged.

### Phase 1 — Visual polish (no behaviour change)

Pure UI changes, ship as a small PR with screenshots in the description.

- Logomark above wordmark on Screen 4.0 in both Control and apex.
- Equal-weight CTAs on Screen 4.0 (no primary / secondary hierarchy).
- Reword Screen 4.0 body to *"Tenshu is a private place for your files..."* per §4.0.

**Risk:** none. No threat-model amendment needed. Ship independently of the rest of this
proposal.

**Modules touched:** `:control-shared` (the screen Compose), `web/apex/` (the React
component). No protocol or Publisher changes.

### Phase 2 — Defer phrase capture; biometric as default

The structural change to the first-run flow. Requires the threat-model amendments in §9.

- Reorder Screens 4.1 / 4.2 per §4.
- Remove the §4b.3 phrase-display and §4b.4 phrase-confirm screens from the onboarding
  path. (The screens themselves are not deleted — they are reused in §7.4.)
- Implement the persistent affordance and the Level 0 hint (§7.2). Levels 1–3 land in
  Phase 4 once we have a place to tune the thresholds.
- Add a *Set up account recovery* entry to Settings that opens the §7.4 capture flow.
- Threat-model amendments per §9 land in the same PR.

**Risk:** moderate. The threat-model amendment is the load-bearing review point. The
implementation itself is mostly a flow reorder.

**Modules touched:** `:control-shared` (onboarding screens, home-screen affordance,
Settings entry). `:identity-service` is not touched — phrase generation is unchanged.

### Phase 2d — Joiner persistence + wrapper-payload generalisation

**Problem.** Post-Phase-2, the joiner pairing path (Welcome → "I have Tenshu on another
device" → PairingWizard → SetupUnlock → Home) has two related defects:

1. *State doesn't survive a restart.* `SyncManagerImpl.initializeWithKeys(keys, ownerId)`
   sets `derivedKeys` and `currentOwnerId` in memory and stores `IDENTITY_KEY` +
   `VOLUME_KEY` blobs in `SecureStorageDelegate`, but does not persist `ownerId` and
   `SyncManagerImpl.init` does not rehydrate any of these on construction. On the next
   process start, `currentOwnerId == null` and `derivedKeys == null` → `getSyncStatus()`
   returns `ownerId = null` → `TenshuLandingStateRepository.detect()` returns
   `FirstTime` → user lands back on the Welcome screen with an apparently-fresh app, even
   though `IDENTITY_KEY` and `VOLUME_KEY` are sitting in `SecureStorage`.

2. *Joiner can't set up local unlock.* Both `setUpLocalUnlock` and `setUpBiometricUnlock`
   in `SyncManagerImpl` start with
   `val phrase = storageDelegate.getSecret(Keys.RECOVERY_PHRASE) ?: return false`.
   That's a host-device invariant — the joiner never has the phrase by design (pairing
   transmits the *master keys*, not the entropy, per
   [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md)). So on a joiner the proxy call
   always returns `false`, `SetupUnlockViewModel` reports an error, and from the user's
   perspective the biometric toggle in Settings → Unlock silently flips back to OFF
   (because the rollback at
   `control-shared/.../UnlockSettingsViewModel.kt:94` clears the on-device blob on
   proxy refusal).

Both come from the same root cause: the unlock layer was designed for the master-device
case where `RECOVERY_PHRASE → Argon2id → master keys` is the canonical path, and the
joiner case was never wired through.

**Fix shape.** Two coupled changes; spelled out in detail in §9.5 of this doc, file-level
notes below.

1. *Generalise the wrapper payload.* The wrapper is no longer "the recovery phrase under
   PBKDF2 + AEAD". It is "the device-local form of A1 under PBKDF2 + AEAD" — a tagged
   payload of either kind:
   - `MASTER:<phrase>` on host devices (paths: CreateRealm, RecoveryClaim).
   - `JOINER:<sharedIdentityKey>|<sharedVolumeKey>|<ownerId>` on joiner devices (path:
     PairingCompletion).
   The wrapper format and KDF are unchanged; only the inner plaintext changes shape, and
   the tag prefix lets `unlockLocal` / `unlockBiometric` decide whether to call
   `initialize(phrase)` or `initializeWithKeys(keys, ownerId)`.

2. *Rehydrate on construction.* `SyncManagerImpl.init` is extended to detect a
   wrapped-joiner-payload (or any in-memory-only initialised state from a prior session)
   the same way it detects the legacy plaintext `RECOVERY_PHRASE`. Specifically:
   - The legacy plaintext-RECOVERY_PHRASE auto-load stays as-is (covers pre-Phase-2
     installs).
   - A new joiner-rehydrate path: when `IDENTITY_KEY` and `VOLUME_KEY` are present in
     storage AND a new `Keys.OWNER_ID` slot is present AND no wrapper exists, `init`
     rebuilds `derivedKeys` + `currentOwnerId` directly (no Argon2 — keys are already
     derived). This handles the "joiner who tapped Skip on SetupUnlock" case.
   - When a wrapper exists (either host or joiner shape), `init` does *not* auto-load —
     the device stays sealed until `unlockLocal` / `unlockBiometric` runs, identical to
     the host-device locked-state today.

**What persists, exactly.** On a joiner, after PairingCompletion:

| Slot                    | Value                                | Lifetime                                    |
|-------------------------|--------------------------------------|---------------------------------------------|
| `Keys.IDENTITY_KEY`     | `sharedIdentityKey` bytes (legacy)   | Retained for backwards compat, no new reads |
| `Keys.VOLUME_KEY`       | `sharedVolumeKey` bytes (legacy)     | Retained for backwards compat, no new reads |
| `Keys.OWNER_ID`         | `currentOwnerId` (new — Phase 2d)    | Cleared on Disconnect                       |
| `Keys.JOINER_PAYLOAD`   | derived-keys tuple (new — Phase 2d)  | Cleared once wrapped, identical to RECOVERY_PHRASE |
| `Keys.UNLOCK_WRAPPER`   | AEAD over tagged payload             | Set by setUpLocalUnlock                     |
| `Keys.BIOMETRIC_WRAPPER`| AEAD over tagged payload             | Set by setUpBiometricUnlock                 |

`Keys.JOINER_PAYLOAD` is the joiner-side analogue of `Keys.RECOVERY_PHRASE` — a transient
plaintext slot that exists between `initializeWithKeys` and the first `setUpLocalUnlock`
/ `setUpBiometricUnlock` call, then is deleted along with the legacy `IDENTITY_KEY` /
`VOLUME_KEY` slots. The legacy slots are kept solely so a Phase-2-or-earlier joiner that
has them but no `JOINER_PAYLOAD` can be migrated on first Phase-2d boot — read once,
write `JOINER_PAYLOAD` + `OWNER_ID` (from session memory if available, else surface a
re-pair prompt), then `init` from the new shape.

**SetupUnlockViewModel.onSkip.** The Skip button stays — Phase 2d does not impose a
block-and-confirm gate that is otherwise §7.2 Level 2's job — but its behaviour
changes. Today `onSkip` flips `done=true` and lets the user land on Home with no
wrapper; after Phase 2d, the underlying realm state (joiner derived keys, or host
plaintext phrase) is still in `SecureStorage` and survives a restart via the
init-rehydrate path. The device is unencrypted-at-rest in the App.kt sense — anyone
with filesystem access reads the realm — but that matches the pre-§4b.5 default and is
honestly the *same* as the legacy plaintext-phrase path. The §7.2 escalation is the
right place to push the user toward a real wrapper; Phase 2d just makes "no wrapper"
not lose the realm on restart.

**Risk.** Moderate. Touches `SyncManagerImpl`'s init / setup / unlock flows — the
proxy's most security-critical surface area. Mitigations:

- The change is structural, not cryptographic: same KDF, same AEAD, same wrapper format.
  Only the inner-plaintext encoding gains a discriminating prefix.
- `PairingCompletion` is untouched; the over-the-wire pairing handshake is unchanged.
- The threat-model amendment in §9.5 is the load-bearing review point.

**Modules touched:** `:sync-service-proxy` (`SyncManagerImpl`, +
`PairingCompletion` storage of `OWNER_ID` / `JOINER_PAYLOAD`), `:sync-service` (no schema
changes — `setUpLocalUnlock` / `setUpBiometricUnlock` / `unlockLocal` /
`unlockBiometric` signatures unchanged). `:control-shared` is untouched on the view-model
side — `SetupUnlockViewModel.onSkip` semantics are clarified in doc but the code is
unchanged. The §7.4 phrase-capture path on joiner-only devices is hidden in a follow-up
(out of scope for the Phase 2d PR — gated on whether a `MasterIdentityRepository` can
report "this device has the phrase" cleanly).

**Verification.** `:sync-service-proxy:jvmTest` for the proxy round-trips
(joiner init → simulated restart → re-hydrated state → setUpBiometricUnlock →
unlockBiometric → derivedKeys + ownerId match). `:control-shared:desktopTest` for the
view-model edge cases (joiner with no `RECOVERY_PHRASE` but with `JOINER_PAYLOAD` can
now reach `done=true` via biometric/passphrase). On-device Android sweep is recommended
but not strictly blocking — the change is platform-common Kotlin and the Android
binding is the same `AndroidSecureStorageDelegate` already in use.

### Phase 3 — Services tab scaffolding; account picker with mocked Publisher

Build the post-onboarding account path end-to-end, with the Publisher mocked.

- New top-level Settings section: Services (§6).
- §5.1 account picker reachable from `[Add another device]` and from Services.
- Free-account path with mocked email verification (1500 ms delay, no real mailer).
- One-time-purchase path with mocked billing (2000 ms delay, no real store integration).
- Rei Key path as UI-only placeholder.
- Disconnect flow as a confirm-by-biometric destructive action.

The mock for the Publisher allows us to ship the flow end-to-end and iterate on copy
before the Publisher v1 has real persistent state.

**Risk:** low for the flow itself; the Publisher mock is explicitly v1-scoped. The
threat-model surface is the new Publisher account binding (§9.3), which is amended in
this PR.

**Modules touched:** `:control-shared` (new Services screens), `:identity-service-client`
(account-state observable), new `:publisher-client` or similar (probably co-located with
identity service until the Publisher has a real backend).

### Phase 4 — Real Publisher integration

Replace the mocks from Phase 3 with the actual Publisher v1 (or v2 — depending on where
`tenshu-publisher.md` is by then). Wire Stripe / Play Billing / Apple IAP behind the
existing UI.

Threshold tuning for §7.2 escalation levels happens here, once we have telemetry.

**Risk:** moderate. Real billing integration is the longest-lead piece; store approvals,
webhook handlers, receipt validation. Decoupled from the UI work in Phase 3 so the UI
can land first.

### Phase 5 — Rei Key validation

Wire the Rei Key path to a real Rei validation endpoint once Rei exists. Until then the
placeholder from Phase 3 stays.

**Risk:** sized when Rei is concrete.

---

## 11. Open items

- **Try-mode duration on web** — should the apex Try-mode have a hard upper bound (e.g.
  the local-only realm expires after 30 days unless the user signs up)? Argument for:
  IndexedDB-only is a fragile state and a long Try-mode user has built up loss-prone
  attachment. Argument against: the user explicitly chose to defer; expiring their data
  is paternalistic. Defer the decision; ship Phase 1–3 with no expiry and observe.

- **Lifetime tier sizing.** §5.1 names 50 GB as the lifetime-purchase amount without
  defence. Set when concrete; depends on relay storage costs at Phase 4.

- **Display-name handling on the Publisher.** §5.2 makes display name optional. Worth
  deciding: does the relay ever see it (it shouldn't), does the recovery blob carry it
  (probably useful), is it editable post-creation (yes, trivially).

- **Telemetry for §7.2 thresholds.** The placeholder thresholds (7 days / 100 MB / etc.)
  are guesses. We need a minimal, privacy-respecting metric (e.g. *"escalation level
  reached"* with no payload) to tune them. Coordinate with whatever observability story
  Tenshu commits to in general.

- **Apex `[Enter a key]` parity.** §8.3 leaves the Rei Key field as a v1 optional on
  web. If web parity is cheap (the input is just a text field with a backend stub),
  include it from Phase 3.

- **Conversion path UX detail.** §5.3 names one-time-license → email-attached as
  Publisher-side. The actual user-visible flow (a screen in Services → "Add an email to
  your lifetime account") needs its own §-level spec; placeholder for now.

- **Drive vs Control entry points.** This doc assumes the first-run user opens Control,
  not Drive. On Android, Drive is the user-facing app; Control is more of a settings
  surface. Decide: does opening Drive first-run trigger Control's onboarding, or does
  Drive have its own minimal onboarding that hands off to Control for account creation?
  Likely the former, but the deep-link choreography needs a §-level spec.

- **What happens to existing users.** Tenshu has no released userbase yet, so this is a
  freebie. If anyone has been testing pre-redesign builds, their existing realms continue
  to work (their phrase was captured under the old flow; they have it). No migration is
  needed. Worth confirming with whoever has been testing.

- **"Skip" vs "Maybe later" copy.** §4 uses *"Start fresh on this device"* and *"I have
  Tenshu on another device"* with no explicit "Maybe later." The deferral is structural,
  not framed as skip. Verify with the design call that this lands the way it reads on
  paper.

---

## 12. References

- [`docs/proposals/control-onboarding-ux.md`](control-onboarding-ux.md) — the existing
  native onboarding spec; this doc replaces §4 / §4b and reframes §5 (subscription).
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — the existing apex
  onboarding spec; this doc replaces §4b.
- [`docs/proposals/control-subscription-ux.md`](control-subscription-ux.md) — the
  existing subscription-picker spec; superseded by §5–§6 of this doc.
- [`docs/proposals/tenshu-publisher.md`](tenshu-publisher.md) — the Publisher component;
  this doc extends Publisher state and flows.
- [`docs/proposals/sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §6.2 —
  per-device / per-owner rate-limiting (the existing anti-abuse surface).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — A9, I7, recovery-phrase
  lifecycle. Amendments in §9 above.
- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — pairing handshake; not changed
  by this doc.
- [`docs/web/architecture.md`](../web/architecture.md) §4.4 — apex WebAuthn-PRF unlock;
  composes with §4.1 of this doc.
