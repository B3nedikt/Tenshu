# iOS Services — status surface now, a purchase path for iOS-only users

**Status:** v0.4 (2026-08-26). ⚠⚠ **The Services sign-in gate moved from `sourceKey == "none"` to
unfunded on 2026-08-26 — see the callout in §3.10**: a stale local account hid the only affordance
iOS has, in exactly the state that needed it. **S1 SHIPPED 2026-07-24**; **S2-pre P0–P4 shipped 2026-08-24** (the iOS commerce origin, §3.8; the "sign in **or** create" copy, §3.7; the sign-in bridge + SwiftUI screen, §3.9/§3.10; the onboarding offer, §3.11). ⭐⭐ **Device-verified on a real iPhone against production 2026-08-25 — §3.12**: a realm that could not sync went 32 → 39 blocks at the moment of sign-in, with no peer involved. **S1 as built** — ServicesStatusSnapshot (:control-shared-logic) + ServicesStatusModel/ControlSurface.servicesStatusModel() (:tenshu-sdk-ios) + ServicesView/Settings section (control-ios). ⭐ On-device as of the §3.12 pass — the Services card was read on the phone (it showed "No storage plan yet", the unfunded state) and is the surface the sign-in CTA hangs off. ⚠ S2**a/b** carry two ⚖️ external
dependencies (counsel + Apple business terms); **S2-pre carries neither** — see §3.6.
Companion docs: [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md)
(the shipped Stripe self-MoR rails + the deferred Ph4b IAP),
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) (carrier
decisions D1–D6), `storage-entitlement-sync` (realm-level entitlement inheritance).

## 1. Problem

Control-on-iOS has **no Services surface at all**. The Kotlin commerce client layer is
wired (real `CommercePublisherClient` + mint/token clients in the iOS
`PlatformProxyModule`, dev-seed-gated in debug), but every Services screen —
`ServicesScreen` (status), `FreeAccountSignupScreen` (email-OTP), `CheckoutScreen`,
`AccountPickerScreen` — is Compose in `:control-shared`, which iOS does not compile.

Two distinct user populations, two distinct gaps:

- **Multi-device users** (today's realm shape): the desktop/Android account holder buys;
  the iPhone inherits the realm entitlement via the synced `Entitlement` +
  `X-Entitlement-Proof` (verified on-device). Their gap is only *visibility*:
  the phone can't show tier, quota, or usage. → **S1**.
- **iOS-only users**: no path to an account or a subscription at all. Today they'd be
  stuck at Free-tier-less local-only. → **S2**.

## 2. S1 — SwiftUI Services status surface (read-only; ready to build)

**Scope.** A `ServicesView` section in the existing SwiftUI `SettingsView`:

- Plan + state: Free / Basic 50 GiB / Premium 2 TiB, read the same way
  `ServicesScreen` reads it — the **synced entitlement** first (2nd-device case: "plan
  derived from the synced entitlement, no local credential"), the local
  publisher-client session second.
- Usage gauge: `GET /sync/usage` (retention-quota P1, live) → used / quota bar, the
  80%-warning styling mirroring `control-subscription-ux.md` §2.3.
- Account state: signed-in email when a local commerce session exists; otherwise the
  2nd-device framing ("Storage active via your paired realm — manage on the device
  that owns the account").
- Explicit non-goals: **no signup, no checkout, no billing portal** — S1 must not
  trip App Store purchase rules and must ship before any S2 decision.

**Shape.** The established `{App}Surface` seam (timeline/fit-ios pattern): a
Compose-free Kotlin `ServicesStatusSurface` (iosMain, beside the `CommercePublisherClient` wiring) exposing
a snapshot + flow of `(plan, source, usedBytes, quotaBytes, accountEmail?)`, sourced
from the same components `ServicesViewModel` binds (`publisherClient`,
`storageStatusModelFlow()`, the synced-entitlement read). SwiftUI consumes it via the
existing SKIE bridge conventions (⚠ suspend `Boolean` → `KotlinBoolean.boolValue`).

**Verification.** Kotlin unit test on the surface (entitled-via-realm vs local-session
vs free); `xcodebuild` per the iOS-slice rule (Kotlin tests don't compile SwiftUI);
sim A/B against a provisioned realm. Effort: **small** — one Kotlin file, one Swift
view, DI registration; no protocol or schema change.

## 3. S2 — a purchase path for iOS-only users

### 3.1 The legal landscape actually helps (⚠ as of the 2026-01 knowledge cutoff — verify before committing)

The user's recollection is right, and it is broader than the EU:

- **EU (DMA):** under Apple's revised EU terms (post the April-2025 anti-steering
  fine), apps may **link out to external web checkout** or use alternative PSPs
  in-app. Apple still takes fees on the new terms (store-services fee + the Core
  Technology Commission on externally-purchased digital goods), and requires the
  external-purchase entitlements, disclosure sheets, and transaction reporting.
- **US:** the Epic v. Apple contempt ruling (April 2025) forces Apple to allow
  **commission-free external purchase links** from US-storefront apps. In effect as
  of the cutoff, **but under appeal** — do not build a strategy that collapses if it
  is stayed.
- **Japan:** the Mobile Software Competition Act (effective Dec 2025) mandates
  allowing external payment links; South Korea has similar older rules.
- **Rest of world:** standard App Store rules — digital goods (a storage
  subscription qualifies) must use **StoreKit IAP**.

⚖️ **Gate G1 (blocking, before any S2 code):** counsel/maintainer verification of the
*current* state of all four bullets, plus the fee math (whether the EU CTC + store
fees still beat IAP's 15/30% for our price points). This doc's legal picture is a
2026-01 snapshot of a fast-moving area.

⚖️ **Gate G2 (blocking):** a **paid Apple Developer account** ($99) + App Store
Connect. Every S2 variant needs it (external-purchase entitlements are profile-gated;
IAP needs products in ASC; distribution beyond 7-day free-tier profiles needs it
anyway — it is the same gate the App-Group/App-Attest work has been parked behind).

### 3.2 Option A — external purchase link (reuses everything we shipped)

The entire Stripe self-MoR flow (accounts, email-OTP, checkout sessions, billing
portal — commerce Ph2–Ph4) is web-shaped already. Option A is a SwiftUI signup/manage
screen + **hand-off to Safari** for checkout:

1. SwiftUI email-OTP signup against the existing commerce endpoints (pure client
   code; the Kotlin clients already exist — this is the "Swift signup UI"
   deferred there).
2. "Upgrade" button → external purchase link (Apple entitlement + disclosure sheet
   where required) → the existing Stripe Checkout page in Safari.
3. Return leg: the **`tenshu://checkout-result` deep link** (commerce Layer-2,
   designed but unbuilt — small, and it benefits Android/desktop too) → app refreshes
   the session → entitlement minted → synced to the realm as today.
4. EU-terms bookkeeping: Apple's external-purchase reporting API on the commerce
   side (a new, bounded `:commerce-service` duty — transaction report submission).

**Coverage:** EU + US (+ JP/KR), i.e. plausibly the whole initial market. **Cost:**
small client slice + deep link + reporting duty; no change to the money path we
already run. **Risk:** region-gated (needs the storefront-detection dance and an IAP
or nothing-fallback elsewhere); US leg tied to the injunction's survival.

### 3.3 Option B — StoreKit IAP (Ph4b, the global path)

Yes — **both sides change**, and it is the structurally bigger slice:

**Backend (`:commerce-service`):** a second purchase **rail** beside Stripe —
- App Store Server API client: verify JWS transactions, map
  `productId ↔ tier` (products authored in App Store Connect), bind via
  `appAccountToken ↔ commerce account` (the email-anchor decision already
  anticipates IAP: email is the universal recovery anchor *including* IAP).
- **App Store Server Notifications v2** webhook: renewals, grace periods, refunds,
  revocations → entitlement state transitions (the Stripe webhook's twin).
- MoR split: for IAP **Apple is the merchant of record** (their VAT/tax), while the
  Stripe rail stays self-MoR — two regimes coexisting is normal and doesn't disturb
  reconciliation decisions D1–D6; the entitlement carrier (synced record + token) is
  rail-agnostic by design.

**Frontend (SwiftUI):** StoreKit 2 paywall (products, purchase, pending states),
**Restore Purchases**, subscription-management hand-off
(`manageSubscriptionsSheet`), and the same signup/status chrome as Option A (an IAP
purchase still wants the email anchor attached for recovery).

**Cost:** the largest commerce slice since Ph4; touches money-path code with
webhook-driven state. **Benefit:** worldwide, review-proof, independent of
injunction/DMA churn.

### 3.4 Recommendation

Sequence, don't choose:

1. **S1 now** — read-only status; no gates, small, immediately useful to every
   multi-device user.
2. **S2a = Option A** behind gates G1+G2 — it monetises iOS-only users in the EU/US
   for a fraction of IAP's cost by reusing the shipped Stripe rails, and the
   deep-link + signup-UI work it needs is *also* prerequisite chrome for IAP.
3. **S2b = Option B (Ph4b)** when App-Store-wide distribution is actually scheduled
   — it is the only worldwide answer, and nothing in S2a is throwaway on the way
   there.

## 3.5 ⚠⚠ What the storage gate turned this into — measured 2026-08-24

This doc frames S2 as *monetisation*: an iOS-only user "stuck at Free-tier-less
local-only" (§1). With `StorageSyncGate` shipped, that framing understates it. The gate
holds a personal realm local-only until a subscription is bound, so an iOS-created realm
does not merely fail to earn — **it never syncs, and nothing tells the user why.**

Observed end to end. A realm created on iOS sat at **0 blocks** on the relay while two
devices were correctly paired and registered; the Services card said only *"your storage
details aren't available yet"*, which promises an outcome that cannot arrive. The host was
unfunded, `joinedFundedRealm` is false for a host (it joined nothing), so the gate stayed
shut and no push ever left the device.

### The rescue, and why it works

Signing in from the realm's **desktop peer** fixed it: a joiner shares the realm's
`ownerId`, so `POST /commerce/account/verify` bound *that* owner, `bindOwner` superseded
the previous binding, and the Free entitlement was minted for the new realm. Blocks went
**0 → 11**; the iOS host then picked the entitlement up **by pull** and opened its own gate.

⭐ That last step is only possible because `SyncScheduler` reads
`pullAllowed = pushAllowed || realmRelayRegistered` — a registered realm keeps pulling
while the storage gate is shut. ⚠ `StorageSyncGate`'s own KDoc claimed the opposite
("nothing is pushed to or pulled from the relay") until it was corrected on the same day;
read literally it says this rescue is impossible.

⚠⚠ **And the rescue needs a second, non-iOS device.** A genuinely iOS-only user has no
peer to sign in from, so their realm is local-only permanently, with no in-app path out.
✅ **Closed 2026-08-25** — S2-pre P0–P4 give the phone its own path, device-verified in §3.12.

⚠⚠ **`bindOwner` supersedes the binding; it does NOT revoke the record already minted.** Worth
stating because the obvious fear — "the rescue steals funding from whatever realm the account was
on" — is wrong, and acting on it would cause the damage it imagines. An entitlement is a *signed*
record with its own validity window (a Free grant runs ten years); the relay verifies signature,
owner and expiry and has **no view of commerce's `account_owner` table at all** (D5 keeps account
lifecycle inside commerce), and `StorageSyncGate` reads the record the devices already hold. So
superseding changes which owner *future* mints name; it does not reach back. A rescue therefore
*adds* a funded realm rather than moving funding between two.
⚠ Reasoned from the mechanism and consistent with what production shows (`entitlement_history`
holds both grants, each valid to 2036); **not** directly tested — that would need a push from the
superseded realm, and no device on it was exercised.

### 3.6 ⚠ The sign-in half is NOT counsel-gated

§4 puts the SwiftUI email-OTP signup in **S2-pre** behind **G2**, and the external-purchase
work in S2a behind **G1 + G2 + counsel**. Worth stating plainly, because the two are easy to
conflate: **G1 (counsel) blocks *purchasing*, not signing in.**

⚠ And G2 — the $99 developer account + App Store Connect — is attached to S2-pre for the
`tenshu://checkout-result` deep link and for distribution. An email-OTP screen calling
`POST /commerce/account/verify`, an endpoint that has shipped and which desktop already
drives through `PublisherClient.verifyOtp`, needs **no external-purchase entitlement and no
ASC product**. So the piece that closes the dead end above is the least gated thing in S2.
(Precedent: the App-Group work was parked behind the same $99 gate and turned out not to
need it.)

### 3.7 ⚠ The signup copy describes creation, but the flow re-binds

`POST /commerce/account/verify` calls `bindOwner`, which **supersedes** the account's live
owner — the documented purpose being *"purchase recovery after recovery-code loss (the old
`ownerId` is dead; the new one inherits the entitlement)"*. So a returning user entering a
known email is **signed back in**, keeping their account and entitlement.

The UI said `free_signup_heading` = *"Create your free Tenshu account"* and offered
*"Sign up"*. That hid the one fact that would reassure someone re-binding after a device
loss — no second account, no second charge — and it is the same screen both cases must use,
because only the server knows which it is (`findAccountByEmail`).

**Fixed in S2-pre P1** (all platforms, since the copy is shared): `free_signup_title` /
`free_signup_heading` now read *"Sign in or create"*, `free_signup_body` leads with the
returning-user case, and the Services CTA is *"Sign in or create an account"*. The
`free_signin_*` variant (D11) stays — it is the framing for a user the app already knows is
returning, and only that entry point can say so with certainty.

⚠ One flow honesty note found while doing it: the CTA routes through **`AccountPicker`**,
whose Free card says *"5 GB"* — which reads as a downgrade to a returning Premium user, who is
not picking a plan at all. `account_picker_returning` now says so in words (Free routes to the
same OTP screen; `bindOwner` restores their real plan, the local `Plan.Free` being a
placeholder the synced entitlement corrects — D5). ⚖️ The cleaner fix is for a sign-in CTA to
skip the picker entirely; that is a navigation change on three platforms and was left out of a
copy phase.

⚠ It is also **absent from onboarding entirely**, which is how a fresh realm reaches the
state in §3.5: created, paired, registered, and permanently unable to sync. (S2-pre P4.)

### 3.8 ⚠⚠ The iOS commerce origin was a loopback — found building S2-pre

Before any of this could work on a device, one line had to change, and it is worth recording
because it was invisible from every doc: `PlatformProxyModule` (iosMain) bound the **relay**
origin to production (`https://relay.tenshu.app`) and the **commerce** origin to
`http://localhost:8081`, overridable only through `NSProcessInfo` — an environment a shipped
iPhone build does not have. So the OTP screen §3.6 calls "the least gated thing in S2" would
have posted to a loopback that answers nothing, on every real device, with no knob to correct
it. Nothing was wrong on Android: it had a **build-time** seam already
(`generateProxyRelayConfig` in `control-shared-proxy/build.gradle.kts`, precedence
`-P` → `$ENV` → `local.properties` → the production default) and simply had never been wired to
iOS. S2-pre P0 registers that same generated `RelayConfig` on `iosMain` and adds
`ProxyOriginSelection`, which honours the env override in a **debug** binary only — the
discipline `DevRelayOverride` already applied to the relay URL.

⚠ Consequence for verification: a plain debug build still defaults to `TENSHU_BILLING_MOCK=true`
and so binds `MockPublisherClient`, which never touches commerce. A device pass against the real
issuer needs `tenshu.billingMock=false` plus a real `tenshu.commerceUrl` at build time (or the env
vars in the shell that builds it).

### 3.9 The sign-in bridge is UI-only — the mechanism was already wired

Worth stating because it sets the size of what remains. `FreeAccountSignupViewModel` lives in
`:control-shared-logic/commonMain`, which already compiles for iOS, and the iosMain
`platformProxyModule` already binds a real `CommercePublisherClient` **and** already passes
`publisherClient.account` to `initEntitlementStorage` beside the commerce minter. So a successful
`verifyOtp` on iOS already ends in a minted Free entitlement, `StorageCapacityHolder` figures, and
an open `StorageSyncGate` — with no further plumbing. Every part of §3.5's dead end except the
screen was in place.

P2 is therefore a bridge, not a feature: `ControlSurface.signInViewModel()` hands SwiftUI the same
state machine Compose drives, plus `signInStepKey` / `signInCanVerify` flatteners so no
SKIE-bridged sealed class reaches Swift (the `genesisStepKey` / `SyncDashboardView` discipline).

⚠⚠ Its default is `NotConnectedPublisherClient`, never a mock — the [`ios-control-notifications-and-shares.md`](ios-control-notifications-and-shares.md) rule, that the safe
state is the one you get by doing nothing. One deviation from the not-connected family is
deliberate and documented at the class: it reports failure by **returning** each interface's
`Error` arm rather than throwing, because `FreeAccountSignupViewModel` consumes those results in a
bare `viewModelScope.launch` with no `try`, so a throw would escape uncaught instead of reaching
the screen. `disconnect()` is the one no-op — its postcondition ("no local binding, no session")
already holds when nothing is bound.

⚠ `OTP_LENGTH` was spelled privately in **two** places (the view model and the Compose screen) and
the iOS surface would have been the third; it is now one public constant on the view model's
companion.

### 3.10 The screen, and the three things it had to get right

`control-ios/Sources/SignInView.swift` binds P2's surface: email step → 6-digit code step, with
resend and use-a-different-email, reached from **Services** whenever this device is **unfunded**
(`planKey == "none"`). A device on `syncedRealm`, or one whose own account carries a plan, is
already funded and manages its account where that account lives, so it is offered nothing.

> ⚠⚠ **The gate was `sourceKey == "none"` until 2026-08-26, and that stranded the state it was
> meant to rescue.** `servicesStatusSnapshot` sets `localAccount` from the presence of a
> `PublisherAccount` **alone** — `sourceKey = if (account != null) "localAccount" else
> "syncedRealm"` — so "signed in" and "funded" are independent, and the pair
> *(localAccount, planKey "none")* is reachable whenever a device-local session outlives its
> binding: a commerce reset, an account bound to a realm the device has since left, or the window
> before a fresh sign-in syncs back. Observed on the maintainer's iPhone that day — commerce held
> **zero** `account` rows and **zero** `account_owner` bindings while the card still named
> `tenshu-test@…`. The screen rendered "No plan" beside that email with **no hint** (`sourceLabel`
> had no `localAccount` case either) and **no sign-in link**; since `SignInView`'s only other entry
> point is onboarding, and S1 is deliberately read-only (no disconnect — §2), the device had no
> route out at all. Gate on unfunded, not on the absence of an account.
>
> ⭐ Complements [`enforcement-followup-tracks.md`](enforcement-followup-tracks.md) **D3**, which
> routes a user from the symptom to this screen: D3 gets them here, this gets them out. D3's
> premise — *"Control's Services screen already holds the answer (`planKey = "none"`)"* — was true
> of the text and, on iOS, false of the actions until this change.

Three details are worth keeping, because each is a bug that would have looked like a design choice:

1. **The code field reads back from the view model; the email field does not.** The established
   iOS pattern (`NewUserModel`) owns field state locally and never writes back, which avoids
   fighting the cursor. But `onCodeChange` *normalises* — digits only, capped at six — so a
   locally-owned code field would keep displaying characters the view model discarded, against a
   CTA that stays disabled for reasons the screen does not show. The read is synchronous, so there
   is no echo loop.
2. **The pushed view owns the pop.** A `dismiss` captured in `ServicesView` would close the Storage
   screen the user came to see, instead of returning them to it with their new plan on it.
3. **`.textContentType(.oneTimeCode)`**, so iOS offers the emailed code from the banner rather than
   sending the user out of the app to read it.

⚠ `installFakes()` gives the DEBUG capture routes a `MockPublisherClient` over an **in-memory**
delegate rather than the Keychain one. A capture must not depend on what a previous mock signup
left in the bucket — the same route would otherwise photograph a bound account one day and the form
the next — and `installFakes` runs inside `ControlIosApp.init`, where a Koin container plus a
Keychain read is real failure surface on a path whose only job is to draw a screen.

⚠⚠ **Still owed: nothing here has been run.** The Swift compiles and the Kotlin is tested, but no
one has typed an address into this screen. The device pass is the P5 item, and it is the only thing
that can show the round trip §3.5 describes — sign in on the iPhone, watch blocks go 0 → N.

### 3.11 Onboarding said "You're set up" about a realm that could not sync

§3.7 ends by noting the account step is *absent from onboarding entirely*, which is how a fresh
realm reaches §3.5's state. Fixing that turned out to be two changes, and the copy one matters more
than the button.

**The terminal screen asserted an outcome it could not deliver.** *"Your realm is ready."* is true
of the realm and misleading about what the user gets: `StorageSyncGate` holds it local-only until
an account is bound, and a **host** cannot be rescued by a peer — `joinedFundedRealm` is false for
the device that started the realm. So a user who tapped straight through had a Tenshu that would
never reach another device and was never told. Local-first is the design (ADR 0019), so the line
now states it as the fact it is rather than as a warning: *"Your realm is ready on this device.
Until you turn on sync, everything stays here — nothing leaves this device."*

**Then the remedy, directly under the sentence it answers.** A secondary *Turn on sync* CTA on
`YoureSetUp` (Compose) and its SwiftUI twin, routing to the email-OTP screen. ⚠ Secondary, never a
gate — finishing without an account is a legitimate choice, not a mistake to block. On iOS it sits
above the recovery-phrase ceremony so fact and remedy are read together, rather than after two exit
buttons where it would be missed.

⚠ This restores rather than contradicts `onboarding-low-friction.md` §4.2, whose own spec listed a
secondary account CTA here that the implementation had shrunk away. The two CTAs that spec dropped
(*Add another device*, *Set up account recovery*) were dropped for a reason that does not apply to
this one: both are discoverable a tap later on Home, whereas nothing else on a fresh iOS install
leads anywhere that opens the gate.

⭐ **Deliberately not a state-machine change.** The CTA lives in the two views;
`NewUserViewModel` and its tests are untouched, so the genesis flow gains no new step, no new
terminal state and no new way to fail. On Android/desktop it routes **straight to the OTP screen,
not via `AccountPicker`** — a first-run user turning on sync is not shopping for a plan, and Free
is where the picker would have sent them anyway (§3.7's open flow question, answered for this one
entry point only).

### 3.12 ⭐⭐ The device pass — measured 2026-08-25, on a real iPhone against production

P0–P4 shipped as "builds and is tested". This is the run that makes them *work*. iPhone 17e,
signed debug build off `e87407e4`, real production commerce and relay.

| Claim under test | Result |
|---|---|
| The SwiftUI screen renders and drives the shared view model | ✅ |
| `POST /commerce/account/register` sends a **real** OTP | ✅ `202` in 733 ms |
| `POST /commerce/account/verify` binds this device's realm | ✅ `200`, `bindOwner` → the phone's own owner |
| The mint writer fetches the signed record | ✅ `entitlement/mint 200`, `tenshu.drive.free`, valid to 2036-08-22 |
| The relay-write bearer token issues | ✅ `entitlement/token 200` |
| **`StorageSyncGate` opens on-device, with no peer involved** | ✅ blocks **32 → 39**, stamped at the moment of sign-in |
| It stays open for the whole realm | ✅ **39 → 47** an hour later, from a *different* device |

⭐ **The first real OTP send in the project's history.** `commerce-production-deploy` had carried
*"a real OTP send has NEVER been exercised — the boot banner proves configuration, not that
Scaleway accepts the credentials or that mail escapes spam"* since 2026-08-07. It does: accepted,
delivered, and read within ten seconds. ⚠ `SmtpEmailSender` sends **synchronously on the request
thread**, which is why a successful send shows up as a 733 ms `/register` — and why a failure
would have been a 500, not a false `202 otp_sent`.

⚠⚠ **The OTP cannot be read from production logs, by design** — the pass was nearly run the wrong
way. `SmtpEmailSender` never logs the code (*"the verification secret stays in the message body.
That asymmetry with `LoggingEmailSender` is the point of having two classes rather than one with a
flag"*), and `CommerceConfig.validate()` refuses to start the logging sender when `DEV_MODE=false`.
For an OTP-only account model the code *is* the credential. Read it from the mailbox; do not
"temporarily" switch the sender.

⚠⚠ **Use a fresh email unless you intend to move an entitlement.** Signing in binds *this device's*
realm. If the account is live-bound elsewhere the binding moves (§3.5 — the record does not, but the
binding does), so a verification run on a personal device should create its own account rather than
reuse the one the main realm depends on. That is how this pass was run.

⚠ What the pass does **not** cover: only the create-a-new-account path was exercised end to end.
The returning-user re-bind — the case §3.7's copy fix exists for — was reasoned about and left
untested, because testing it means moving a live binding.

## 4. Phasing

| Phase | Contents | Gates | Size |
|---|---|---|---|
| S1 | `ServicesStatusSurface` (Kotlin, iosMain) + SwiftUI `ServicesView` (plan/usage/account state) | none | S |
| S2-pre P0 | Build-time commerce origin for iOS (`RelayConfig` on `iosMain` + `ProxyOriginSelection`) — **shipped** | none | XS |
| S2-pre P1 | "Sign in **or** create" copy, all platforms (§3.7) — **shipped** | none | XS |
| S2-pre P2 | `ControlSurface.signInViewModel()` + step/CTA flatteners + `NotConnectedPublisherClient` (§3.9) — **shipped** | none | S |
| S2-pre P3 | SwiftUI `SignInView` + the Services entry point (§3.10) — **shipped** | none | S |
| S2-pre P4 | The onboarding offer — a secondary CTA on "You're set up", both platforms (§3.11) — **shipped** | none | S |
| S2-pre P5 | Device pass on a real iPhone against production (§3.12) — **done 2026-08-25** | none | S |
| S2-pre | `tenshu://checkout-result` deep link (all platforms) + SwiftUI email-OTP signup over existing endpoints | G2 (deep link only — **not** the OTP screen, §3.6) | S–M |
| S2a | External-purchase entitlement + Safari checkout hand-off + EU reporting duty | G1, G2, counsel | M |
| S2b (=Ph4b) | StoreKit 2 paywall + App Store Server API + Notifications v2 rail in `:commerce-service` | G2, ASC products, S2-pre | L |

Threat-model: S1 none (read-only over existing surfaces). S2a/S2b amend the commerce
group (A28–A30) per phase, House Rule #2 — notably the new inbound webhook surface
(S2b) and the external-report egress (S2a).
