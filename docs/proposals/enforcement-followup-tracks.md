# Entitlement-enforcement follow-up tracks (handoff)

**Status:** created 2026-07-10 from an on-device production-test session that turned
`ENTITLEMENT_ENFORCE=true` on against the live relay. Enforcement **works** (accounted device →
`/sync/push` `200`; un-accounted → `402`), but the client's handling of the enforce window is rough.
This doc splits the remaining work into independently-pickup-able tracks.

⚠⚠ **Status correction, 2026-08-26: enforcement is ON in production.** The line below used to read
*"Enforce is currently off on the relay; do not turn it back on until Track A lands"* — the
maintainer's own iPhone was measured taking `402 entitlement_required` from the production relay on
this date, which is proof it is on. Track A's items did land (the backoff and the
`CONNECTIVITY_NEEDS_ATTENTION` state both exist). What that pass also found is that the state Track
A shipped answers the wrong question for the commonest case — see **Track D**.

Parent design: [`second-device-entitlement-enforcement.md`](second-device-entitlement-enforcement.md).
Tech-debt entry: `docs/tech-debt.md` → *"Entitlement Enforcement — Client Sends Doomed Writes …"*.

## Already done this session (context)

- Commerce issuer deployed (`commerce.service.tenshu.app`, dev seed); relay pubkey wired.
- Android commerce-URL bake · `allowBackup=false` (stop Auto-Backup restoring the
  ObjectStore) · Android release signing · reset+commerce runbook · reset scripts.
- **B1 resolved** (see Track B): the full signed `Entitlement` record already syncs to every joiner.

## Parallelism at a glance

| | Track A (client hardening) | Track B (2nd-device credential) | Track C (signing) |
|---|---|---|---|
| Gated on a decision? | no | **yes — B0** | no |
| Touches | client sync loop + Drive UI | relay verifier + client token provider | app `build.gradle.kts` |
| Run concurrent with… | C, B2 | C, A (B2 only) | everything |
| **Status** | **in progress** (subagent → PR for review) | **B0 decided + B1 done**; B2 in progress (subagent → PR); B3 after A | **DONE** |

Rule of thumb: **A ∥ C ∥ B0/B1** now; **B2 ∥ A** (relay side, independent); **B3 after A** (both touch
the client token/sync path — don't fork them into simultaneous autonomous sessions).

---

## Track A — client enforcement hardening (P0; highest value, unblocks re-enabling enforce)

**Goal:** an honest client behaves gracefully during an enforce window instead of a 402 storm + a
dead-end "Syncing…". Ships alone; independent of the credential decision.

1. **Backoff on repeated `402` on the push loop.** Observed ~1401 rejected `POST /sync/push` in 4 min
   (~6/sec, no backoff). Add exponential backoff keyed on `402`; stop hammering.
2. **A "needs attention on this device" state** instead of an indefinite "Syncing…". There is already a
   `SyncHealthStatus` signal (see the *Control→Drive sync-health signal* work) to extend with an
   `EntitlementBlocked`/`NeedsStorageSetup` state, surfaced in Drive.
3. **Eagerly re-drive stranded writes when the block clears** (token acquired / enforcement relaxed /
   reconnect) — do NOT wait for a process restart. Observed: after enforce→off, stranded writes sat
   un-pushed until a **restart** re-drove them (no data loss, but no prompt recovery either — proposal
   §6a).

**Files / seams:** the push path (`sync-service-proxy` push + `RestContentBlobStore`), the entitlement
token provider (`control-shared-logic/.../EntitlementTokenClient.kt` — `CachingEntitlementTokenProvider`),
the sync scheduler in `control-shared-proxy`, and Drive UI for the state. ⚠ This is the *"Proxy
Background Lifecycle is entangled"* tech-debt area — go carefully.
**Verification:** re-enable enforce on a **staging** relay (or the prod relay briefly), drive an
un-entitled device, confirm: bounded retry (not a storm), a clear UI state, and that writes drain
promptly once a token arrives / enforce relaxes — **without** a restart. Android → smoke gate.

## Track B — second-device credential (option 4: relay accepts the synced record)

**Problem:** a paired 2nd device inherits the realm + storage record (so it *attempts* to sync) but has
no commerce session → can't fetch a bearer token → `402`s under enforce, with no sign-in path.

- **B0 — DECIDED (2026-07-10): option 4** (relay accepts the already-synced signed `Entitlement`
  record as a write credential, alongside the token). Downsides (revocation window, `ownerId`-binding
  vs. blinding) are documented as real-but-low-cost in the parent proposal §3. **Revised 2026-07-11
  (Q3):** rather than overload `X-Entitlement`, the record rides a *separate* `X-Entitlement-Proof`
  header as the first type (`signed-record.v1`) on a **generic entitlement-proof channel** — the token
  header stays single-purpose/strict, and the same channel is where the future **Rei** on-chain
  purchase proof will slot in as a new type.
- **B1 — DONE (resolved this session).** *Does the full signed record reach a joiner?* **Yes.**
  `IdentityServiceCommonModule.kt:52` registers `Entitlement` (`Entitlement.Schema`, carrying
  `PUBLISHER_SIGNATURE`) as *"one Syncable row per owner, propagated to every paired device."*
  Empirically confirmed: the paired phone's ObjectStore holds `tenshu/identity/entitlement` +
  `tenshu.commerce`. So **no extra syncing is needed** — the joiner already holds the full signed
  record. Parent proposal Q1 + P1 collapse.
- **B2 — DONE (relay accepts the proof credential).** `RelayEntitlementVerifier` +
  `resolveEntitlement` (`sync-relay-server/.../routing/EntitlementEnforcement.kt`) keep `X-Entitlement`
  **token-only** (checked strictly, its answer final) and add a separate **`X-Entitlement-Proof`**
  header carrying a generic `<type>.v<n>:<payload>` proof, dispatched by a `ProofVerifier` registry.
  v1's one type, `signed-record.v1:` + base64url(JSON), is the synced `SignedEntitlement` — verified
  under `ENTITLEMENT_ISSUER_PUBKEY` over `EntitlementSignature.message(...)`, with `ownerId ==
  authenticated owner` + `now ∈ [validFrom, validUntil]` (ms). Any proof failure (incl. unknown type)
  → `402 entitlement_invalid`; the token path keeps its `403` owner-mismatch and takes precedence when
  both headers are present. No quota claim on the record → proof-authorised writes are unmetered in v1
  (relay quota deferred, D6). Q3 resolved (parent §5). Threat-model T-CB-08 (§8.15, revocation-window
  residual). Tests: `EntitlementEnforcementTest` (HTTP) + `RelayEntitlementVerifierTest` (unit). Adding
  a Rei on-chain proof later = one new registered `ProofVerifier` type, no token-path change.
- **B3 — client presents the proof when no token is available.** *First cut landed emitting
  `record.v1:` on `X-Entitlement`; being reshaped in a follow-up PR to match B2's revised contract —
  emit `signed-record.v1:` on `X-Entitlement-Proof` from an independent proof provider (the token
  provider keeps `X-Entitlement`).* Inert until B2 merges; enforce is off, so no ordering risk.
- **B4 (optional, later):** per-device commerce sign-in UI (option 1) for a device that is *not* paired
  into a funded realm (a genuinely separate account). Complementary, not a substitute.

**Verification:** a paired 2nd device with **no** session writes successfully under enforce (its
`/sync/push` returns `200`, carrying the record); an un-entitled realm still `402`s.

## Track C — Android release signing — **DONE**

`release` signingConfig on `:control` + `:drive-android`: real keystore from `local.properties`
`tenshu.releaseStoreFile`/`…StorePassword`/`…KeyAlias`/`…KeyPassword` (or `TENSHU_RELEASE_*` env), else
debug-keystore fallback so `assembleRelease` always yields an installable apk. For a distributable
build, drop a real keystore's coords into `local.properties`.

---

## Track D — the unfunded realm is never named (P0 for a first-run user; 2026-08-26)

**Parent finding:** `docs/tech-debt.md` → *"One 402, two opposite causes, one banner"*. Track A gave
the 402 a state; this track gives it the *right* state, and puts it where a user will meet it.

### D0 — the discriminator

⚠ **First, what is NOT broken.** Drive's steady-state banner is already correct here:
`SyncBanner.evaluate` orders `!storage.hasStorage -> LocalOnly` **before** `health.needsAttention`,
so an unfunded realm renders *"No storage subscription — files are local-only"*, and Control's
Services screen reports `planKey = "none"`. **Do not "fix" the banner or the precedence chain** —
an earlier draft of this track proposed exactly that and was wrong.

What the steady state does not carry is the **consequence**: that writes are being refused and a
backlog is accumulating (108 blocks on the maintainer's desktop, 5 on the iPhone). And the
banner renders on `FilesScreen` alone.

⭐ **The signal D1 needs already exists and costs one round-trip.**
`EntitlementRecovery.recoverIfStale()` returning `Fetched(applied = 0, usable = false)` means the
relay was asked for the owner's record *directly* — `fetchEntityBlocks` bypasses the cursor — and
has none. **Authoritative: no catch-up, no waiting, no inference from a timeout.** It is currently
`println`-ed; D1 needs it as a value the wizard can read.

### D1 — the joiner's last step (the case this track was raised for)

**Scope:** a user who picks *"I have Tenshu on another device"*, completes the handshake, and lands
in a realm that cannot sync. Right now they get "You're in your realm", and then nothing ever
happens, forever, with no error on any screen.

⚠⚠ **Key it off D0's fetch, not off a failed pull.** *Reads are ungated* — the relay's own contract
is "only write handlers call `requireEntitlement`" (`EntitlementEnforcement.kt`, §9 Q5). A joiner on
an unfunded realm **pulls fine**; it is its own *writes* that never land. A screen keyed on "we
couldn't pull the devices" would be keyed on something that does not happen, and on this realm the
empty pull is a *consequence* (nobody could ever push), not the cause.

**Shape:** after `PairingKeyInstaller` installs the keys, run the D0 fetch once. Three outcomes:

| outcome | what the wizard does |
|---|---|
| record found, in window | today's Success step, unchanged |
| fetch **failed** (transport) | today's Success step, unchanged |
| fetch succeeded, **no record** | the new step |

⚠⚠ The middle row is load-bearing and is the same rule `PairingWizardViewModel.onSelectShow`
already follows for its role branch: **only a successful read may produce the negative verdict.** A
transport failure telling a paying user their realm is unfunded is worse than saying nothing.

**Copy:** it must say the realm has no storage, that this device is in the realm regardless, and
that nothing will sync until a plan is bound — not "an error occurred".

⚠ **A joiner cannot mint.** Minting needs a `PublisherAccount`; a joiner has the realm but no
commerce session (`second-device-entitlement-enforcement.md`). "Try to get the entitlement" is
therefore *already implemented and already running* — it is D0's fetch, and the answer is that none
exists. The only thing that creates one is an account, so the CTA is **"Set up storage"** into the
commerce flow **on this device** (a joiner may bind an account — A30's re-bindable link), with a
"Not now" that proceeds to Success.

⚠ **Do not gate pairing on this.** You must be able to join before you can pay; the handshake
succeeding is correct.

### D2 — the host says it first

The **host** knows before the joiner does: its own pushes are already 402-ing. B-8
([`ios-control-handover.md`](ios-control-handover.md)) just gave the host a "Device added" terminal
— the natural place for the same sentence, so the person who *ran* the pairing hears it on the
device they are holding.

### D3 — route from the symptom to the screen that fixes it

Control's Services screen already holds the answer (`planKey = "none"`); nothing points a user at it
when that is why nothing syncs. A realm can also lapse long after any pairing, so D1's one-shot
screen is not sufficient on its own.

⚠ Reuse Drive's `SyncBanner` vocabulary and its precedence rather than inventing a second taxonomy
in Control — that ranking is carefully reasoned and has already been got wrong twice
(`StorageFull` vs `NeedsAttention`; the Control states `Skew`/`NotInstalled`), including once in this doc's own
first draft. ⚠ `CONNECTIVITY_NEEDS_ATTENTION` has exactly one consumer in the repo
(`drive-shared-logic`), so anything Control renders is new plumbing, not a second reader of an
existing one.

### D5 — the joiner's failures are two hardcoded English words

`PairingWizardViewModel.awaitHandshake` maps every non-success to `"Pairing failed"` or
`"Pairing timed out"`. No cause, no retry, not localised. Live cost, 2026-08-26: a joiner whose
decrypt failed its MAC check told the user *"Pairing failed"* while the host said "Device added"
(`tech-debt.md` → *"The joiner's decrypt fails its MAC check"*).

⚠ **The retry machinery is not what is missing.** `AwaitPairingImplementation` already tolerates 3
consecutive relay-poll failures (~15 s) inside a ~5-minute budget, resets the counter on any good
poll, and burns the in-proxy handshake on cancel. What is missing is *what the user is told*.

⚠⚠ **Do not port Drive's banner machinery for this.** A `SyncBannerState` + health row + precedence
chain models a *persistent ambient condition* on a long-lived surface, where nobody is waiting and
no button is possible. A wizard is a *bounded foreground transaction* with a user watching and a
definite outcome — which is exactly where a **Retry button** belongs, and the one thing an ambient
banner can never offer. Transfer the honesty rules (name the cause; never say "offline" when the
relay answered), not the mechanism.

**Shape:** a typed `PairingFailure` beside the existing `PairingScanError` —
`RelayUnreachable` / `TimedOut` / `HandshakeMismatch` — with localised copy and Retry on the
recoverable cases. ⚠ The file already uses this pattern for the *scan* path and the host half has
`hostConnectionLost` vs `hostDeviceUnrecognized`; only the joiner's network/crypto path was left
behind. This is extending an in-file idiom, not importing one.

### D4 — bound the queue

Unbounded backlog against a wall that needs a purchase to clear: 108 blocks on the maintainer's
desktop, 5 on the iPhone, retried indefinitely. Out of scope for the UX slices above; recorded so it
is not lost.

---

## Suggested sequencing

1. **Now:** B0 decision (maintainer). Track A picked up by a fresh session (the unblock for re-enabling
   enforce). B2 can run in parallel (relay-only).
2. **After A:** B3 (client record-fallback), then re-enable enforce on staging and run the full
   2nd-device + un-entitled matrix.
3. **Later/optional:** B4 sign-in UI; the blinding upgrade (parent §3 downside 2) if/when Phase 6.
