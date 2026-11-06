# Second-device entitlement enforcement — closing the paired-device gap

**Status:** Draft v0.1 (2026-07-10). No code. Surfaced by an on-device pass: with
`ENTITLEMENT_ENFORCE=true` on the production relay, a **paired** second device (phone) that
inherited the realm could neither sync nor sign in — it emitted a **402 storm** (~1401 rejected
`POST /sync/push` in 4 minutes, ~6/sec, no backoff) and showed "Syncing your changes…" forever.

Related: `docs/tech-debt.md` → *"Entitlement Enforcement — Client Sends Doomed Writes on
Record-vs-Token Divergence"* (this is the concrete, reproduced instance); the two-carrier design in
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) D2; the relay
enforcement in [`commerce-billing-plane.md`](commerce-billing-plane.md) §8.

## 1. The problem

Entitlement has **two carriers** (D2):

| Carrier | Purpose | Lifetime | Acquired via |
|---|---|---|---|
| Synced **`Entitlement` record** | client-side tier gating (`hasStorage`, `StorageStatus`) | long (`validUntil`) | syncs to **every** realm device, incl. joiners |
| Bearer **token** (`X-Entitlement`) | **relay** write enforcement | short (72 h TTL) | `POST /commerce/entitlement/token`, **session-gated** |

Pairing conveys the **realm** (keys + synced rows), so a joiner gets the *record* → `hasStorage=true`
→ the client **attempts to sync**. But pairing conveys **no commerce session**, and the token
endpoint is session-gated, so the joiner **cannot fetch a token** → every gated write (`/sync/push`,
content `PUT`, share/grant drops) is `402`. And because the device shows storage as *active* (from the
inherited record), the UI offers **no sign-in path** — a dead end.

Two defects compound:
- **Product gap:** a paired second device has no way to obtain a write credential under enforcement.
- **Client behaviour:** the doomed writes retry with **no backoff** and **no user-facing signal**
  (spins "Syncing…" instead of "sign in to sync on this device").

The entitlement is **owner-level** (the account binds to `ownerId`); the paired device *is* the same
owner and proves it to the relay via its device-key JWT. So the owner is entitled — only the
*per-device token-fetch* is blocked.

## 2. Options considered

1. **Per-device sign-in UI.** A session-less device shows "sign in to sync on this device" →
   email-OTP → its own session → token. *Pro:* no plane coupling. *Con:* every device re-does
   email-OTP (friction); needs the missing UX; still leaves the record-vs-token seam for
   commerce/session outages.
2. **Pairing bootstraps commerce.** The established device hands the joiner a commerce credential
   over the E2E pairing channel. *Pro:* low friction. *Con:* couples the sync and commerce trust
   boundaries (deliberately separate, `commerce-billing-plane.md` D1) and puts a bearer credential on
   the pairing wire — more than the joiner needs, and a threat-model expansion.
3. **Owner-bound token from device identity.** Commerce issues a token to any device that proves
   `ownerId` via its realm/device key, no session. *Pro:* conceptually cleanest. *Con:* commerce
   must verify **sync-plane identity** — a new cross-boundary dependency we don't otherwise need.
4. **Relay accepts the synced `Entitlement` record as the credential** (not only the short token).
   Every paired device already holds it; it is already commerce-signed + owner-bound, and the relay
   already owns the verify machinery (`RelayEntitlementVerifier` / `EntitlementSignature.publicKeyFor`
   under the same `ENTITLEMENT_ISSUER_PUBKEY`). *Pro:* smallest change, no new sync value, no new
   dependency, closes the gap for every device at once. *Con:* see §3.

## 3. Recommendation — Option 4, as a **hybrid**, with eyes open

Make the relay accept **either** a fresh token **or** the signed `Entitlement` record in
`X-Entitlement`; the client presents the token when it has a session, else falls back to the record it
already holds. This closes the gap now and keeps the token path intact for when its advantages matter.

**Why it wasn't ranked first — the downsides to weigh (answering "what am I missing?"):**

1. **Forced-revocation window is coarser** (adversarial case only — not the everyday one). Split the
   two:
   - *Cooperative expiry (voluntary cancel, subscription lapse) — a non-issue, handled client-side.*
     The client reads `exp` off the credential and refreshes **before** it (`CachingEntitlementTokenProvider`
     already does this at `exp − skew`), so the happy path is **zero** failed requests; worst case one
     `402` → "renew to keep syncing." The credential choice doesn't affect this.
   - *Involuntary/forced revocation (chargeback, fraud flag, hack) — the real concern.* Enforcement
     exists precisely because you **can't trust the client to self-stop** (a bounced-card user still
     wants their storage). This is a **relay-side** window, and it's the only thing the credential
     choice governs. With the **token**, commerce refuses to mint a new one for the flagged account, so
     the current token dies within its short TTL (~72 h) → the relay stops honouring it. With the
     **record**, the relay is zero-trust — it checks only signature + `validUntil`, never re-asks
     commerce — so it keeps honouring the record until `validUntil` (potentially weeks), whatever
     commerce decided. So "coarser" = the relay keeps accepting writes from a force-revoked account for
     as long as the record lives.
   *Scope + mitigation:* forced early revocation is **rare**, and the damage is bounded — a flagged
   account keeps consuming quota/storage for the window, never decryption (I1 is independent). It's
   really just "how short is the credential TTL": short-`validUntil` records **renewed on sync** make
   the record's window as tight as the token's (cost: a renewal treadmill). One hybrid-specific catch —
   a non-cooperating client presents whichever accepted credential lives **longest**, so tight
   forced-revocation needs *both* credentials short (or dropping the hybrid).
2. **Entrenches `ownerId`-binding — a step *away* from the token's blinding-ready future (A30), not a
   hard block.** The strong-privacy plan (`commerce-billing-plane.md` Phase 6 / I-CB-1 strong) makes
   the relay's credential *unlinkable*: commerce **blindly signs**, and — the load-bearing part —
   the verifier is `ownerId`-agnostic (A30: *"`ownerId` is an optional claim; the verifier never
   requires it"*), so blinding is a **token-issuance swap with no relay-verifier change**. Option 4's
   relay check is the opposite — it verifies `record.ownerId == authenticated owner` over a signature
   that *covers* `ownerId` — so it's inherently linkable; a blinded, `ownerId`-free record could never
   pass it. Consequence: blinding later means **going token-only for the relay credential (unwinding
   option 4)**, not the clean pure-issuance change the token path was designed to keep. **Two things
   temper this, though:** (a) A30 *already* exists in the minimal variant — commerce mints
   entitlements *for* an `ownerId` (session-gated `/mint` sees both the account and the `ownerId`), so
   it already holds `account↔ownerId` regardless of what the relay accepts; option 4 doesn't *create*
   the link, it just makes relay *enforcement* depend on the already-linkable artifact. And (b) the
   relay already sees `ownerId` via the device JWT, and Phase 6 is distant/optional. So: real, but low
   practical cost.
3. **Relay gains a dependency on the proof's wire schema.** The token is a compact, self-contained
   bearer string; the `signed-record.v1` proof is a richer synced `Data` composite. The relay verifies
   a `SignedEntitlement` shape (it can — `EntitlementSignature.fieldsOf`/`message`), which couples the
   relay's *proof channel* to that record's schema evolution. Contained by the `<type>.v<n>` versioning
   — a schema change ships as a new proof *type*, old peers ignore it.
4. **Quota.** Relay-side quota is deferred (D6), but when it lands the **token carries the quota
   claim** and the record may not — the relay would need a `ProductCatalog` lookup from `productId`
   or a quota field on the record.

For **v1** — redemption-code / early accounts, no live subscriptions, no blinding, relay quota
deferred — downsides 1–4 are largely latent, so Option 4-hybrid is the right call: it makes second
devices *just work* with the least machinery, and we revisit if/when subscriptions + blinding + relay
quota make the token's properties load-bearing.

## 4. Sketch (as built — separate-header proof channel, Q3)

The two credentials are **different trust models**, so they ride **different headers** — the token
path stays single-purpose and strict, the proof path is a generic self-verifying channel:

- **`X-Entitlement`** — bearer token *only*. Delegated/online trust, short TTL, session-gated.
  Verified strictly and never re-interpreted (unchanged).
- **`X-Entitlement-Proof`** — a generic `<type>.v<n>:<payload>` **entitlement proof** the relay
  verifies cryptographically itself (zero-trust of commerce liveness). The relay dispatches on the
  type prefix to a registered verifier. **v1's one type is `signed-record.v1`** = the synced
  `SignedEntitlement` record (base64url JSON of the six signed fields). A Rei on-chain purchase proof
  later is a *new registered type* on the same header — additive, no token-path change.

- **Relay (`requireEntitlement`):** if `X-Entitlement` is present, verify it as a token and use that
  answer (no fall-through — a bad token is a final 402/403). Else if `X-Entitlement-Proof` is present,
  `verifyProof` it (signature under `ENTITLEMENT_ISSUER_PUBKEY` over `EntitlementSignature.message(...)`,
  `now ∈ [validFrom, validUntil]`, `ownerId == authenticated owner`). Neither → `402 entitlement_required`;
  any proof failure (incl. unknown type) → `402 entitlement_invalid`.
- **Client:** the token provider sets `X-Entitlement` when a session can obtain a token; an independent
  proof provider sets `X-Entitlement-Proof` from the locally-synced record. A session-less device sends
  only the proof; the relay prefers the token when both are present. **Also fix the storm:** back off
  `/sync/push` on repeated 402 (don't hammer), and surface a distinct state ("storage needs attention on
  this device") instead of an indefinite "Syncing…".

## 5. Open questions

- **Q1. RESOLVED (2026-07-10): yes.** `IdentityServiceCommonModule.kt:52` registers `Entitlement`
  (`Entitlement.Schema`, which carries `PUBLISHER_SIGNATURE`) as *"one Syncable row per owner,
  propagated to every paired device."* Empirically confirmed — a paired phone's ObjectStore holds
  `tenshu/identity/entitlement` + `tenshu.commerce`. `StorageStatus` is the *locally-derived* (not
  Syncable) projection on top. So the joiner already holds the full signed record; **no extra syncing
  needed**, and P1 collapses to a no-op.
- **Q2. RESOLVED (2026-07-11): short TTL + client renewal.** Commerce stamps `validUntil = now + 7 days`
  by default (`ENTITLEMENT_TTL_HOURS`, down from 30 days; Stripe subscribers keep `currentPeriodEnd`),
  and the client **renews on the session-holding device**: `initEntitlementWriter` runs a periodic tick
  (and re-checks on every session start) that re-mints the current product once the record passes the
  **halfway point of its own window** (`mintEntitlement` is renewal-aware; `saveSignedEntitlement` writes
  a later `validUntil`). Deriving the threshold from the record's own window adapts to any TTL and
  self-limits to ~one renewal per half-window (no sync churn). Effect: a good-standing record never
  silently expires, and a *revoked* account (commerce declines to re-mint) lapses within the TTL —
  bounding the forced-revocation window (downside 1 / T-CB-08) to ~the TTL, tunable toward the token's
  ~72 h. The exact TTL is **not load-bearing for v1** (no live subscriptions to force-revoke); it's a
  config knob. `EntitlementWriter` + `EntitlementWriterTest`.
- **Q3. RESOLVED (2026-07-11, Track B2 — revised): a *separate* `X-Entitlement-Proof` header carrying
  a generic, type-tagged proof.** The first cut reused `X-Entitlement` with a `record.v1:` scheme
  prefix; on review that overloaded the strict token header with a looser credential and made the
  header impossible to reason about locally. Revised: `X-Entitlement` is **token-only** (checked as
  strictly as possible, never re-interpreted), and a distinct **`X-Entitlement-Proof`** header carries
  a self-describing `<type>.v<n>:<payload>` *entitlement proof*. **v1 registers one type,
  `signed-record.v1:`** + base64url(JSON of the six signed `SignedEntitlement` fields —
  `ownerId`/`publisherId`/`productId`/`validFrom`/`validUntil`/`sig`, `sig` base64). The relay
  dispatches on the type prefix to a registered `ProofVerifier` (`RelayEntitlementVerifier.verifyProof`
  → signature under `ENTITLEMENT_ISSUER_PUBKEY` over `EntitlementSignature.message(...)`, then
  `now ∈ [validFrom, validUntil]` in ms); the `ownerId == authenticated owner` check is in
  `resolveEntitlement`. Any proof failure (unknown type / malformed / bad sig / expired / wrong owner)
  → `402 entitlement_invalid`. This also opens the "expert" proof channel the **Rei** on-chain purchase
  proof will use — a new registered type, no token-path change (the reason the channel is generic).
- **Q4.** Do we still want the per-device sign-in UI (Option 1) as the path for a device that *isn't*
  paired into a funded realm (a genuinely separate account)? Likely yes — Options 1 and 4 are
  complementary, not exclusive.

## 6. Threat-model deltas (land per implementing phase, House Rule #2)

- Relay now accepts a long-lived, `ownerId`-bound record as a write credential → **revocation latency**
  bounded by record `validUntil` (vs. token TTL). Note under A28/A29/A30 + the §8 enforcement surface.
- No new relay visibility (the relay already authenticates `ownerId` via the device JWT); no key
  custody change; House Rule #1 preserved.

## 6a. Recovery-after-block observation (2026-07-10, measured — not a data-loss bug)

While diagnosing the above, a device (the **desktop**, which had lost its in-memory session after an
unrelated process kill) made writes during an enforce window and `402`'d them. Two things observed,
one reassuring and one worth fixing:

- **No data loss.** After enforcement was turned off, a **client restart re-drove the stranded writes**
  — both the `File` entity *and* the content blob — and the peer converged (the image + its bytes
  reached the relay and synced to the phone). So the store→relay push *is* reconstructed on startup;
  the earlier worry that pending pushes were orphaned was wrong (checked the relay ~25 s post-restart,
  too early — the drain took a few minutes).
- **Recovery is not prompt, though.** The stranded writes did **not** auto-drain when the block cleared
  (enforce→off); they sat un-pushed until a **restart** kicked the drain. There's no eager
  "the write-block just cleared → re-push now" trigger — recovery waits for a restart (or a long
  incidental retry). That's the modest real gap here, and it folds into P0 below.

## 7. Phasing

- **P0 (client hardening, ships alone):** on the push loop — **(a)** back off on repeated `402`
  (don't hammer — the storm was ~6 req/s), **(b)** surface a "needs attention on this device" state
  instead of an indefinite "Syncing…", and **(c)** **eagerly re-drive stranded writes when the block
  clears** (token acquired / enforcement relaxed / reconnect) rather than waiting for a restart (§6a).
  Stops the storm + the dead-end UX + the slow-recovery lag, regardless of the credential decision.
- **P1:** ~~confirm Q1; sync the signed record to joiners if needed~~ — **DONE/no-op** (Q1 resolved:
  the full signed record already syncs to every joiner).
- **P2:** relay accepts the record credential (§4) + threat-model amendment.
- **P3:** client record-fallback in the token provider.
- **P4 (optional):** per-device sign-in UI (Option 1) for the non-paired / separate-account case.

## 8. A lapsed free grant must downgrade, not go write-dead (2026-09-07, shipped)

The 402 storm this doc opens with had a **second cause**, found on production nine weeks later and
wearing the same symptom. Owner `c2162a91…` was refused every content write for hours — 8010 × 402
against 8534 × 200 in a 3 h window, essentially all `PUT /sync/content/blob` — while `POST /sync/log`
and `fetch-entity-blocks` kept returning 200. Metadata synced; **bytes did not**. A Drive listing
files whose content is nowhere is worse than an outage, because nothing surfaces it.

### 8.1 Why it was not the paired-device gap

The device's JWT was valid and the record credential was wired on all three platforms. The record
had simply **expired**: `tenshu.drive.free`, `validFrom 2026-08-31T08:31:54Z`,
`validUntil 2026-09-07T08:31:54Z` — the default 7-day `ENTITLEMENT_TTL_HOURS` window, lapsed that
morning.

⚠⚠ **The mitigation recorded in T-CB-08 assumes a renewal path that the free tier does not have.**
`initEntitlementWriter` maps its mint intent from `publisherClient.account`:

```kotlin
intents = accountFlow.map { account -> account?.let { EntitlementMintIntent(...) } }
```

A `null` account yields a `null` intent and `mintEntitlement` is never called — no error, no log
line, no retry. The identity-light **redeem** path (`initRedeemedEntitlementWriter`) hands the device
"a commerce-signed grant with no session to drive the mint writer above" and only *persists* it. So a
redeemed free grant is minted exactly once and renewed never: **expiry is its steady state.**
Verified by running Control directly (launchd stopped first — there is no single-instance guard):
several minutes of stdout showed startup, `admitted=true` and the ordinary sweeps, and not one
entitlement / mint / commerce line, while the record stayed byte-identical and the relay kept
refusing. Commerce was up and `RelayConfig.COMMERCE_URL` was correct; the path was not failing, it
was not running.

### 8.2 Why the diagnosis was slow — two circular comments

Both halves of the credential path justified themselves with the consequence of their own behaviour:

- The client's `proofHeader` withheld an out-of-window record because *"a doomed write is a 402, no
  point sending it"* — but the write was doomed **because** the record was withheld.
- Withholding also made expiry **indistinguishable from having no record at all**: both produce
  `entitlement_required`, whose message is *"the client attached nothing"*. The log pointed at
  absence; the cause was expiry.

⚠ Note for future triage: `402` is *no credential presented*; **out of storage is `507`/`over_quota`**
on a different path (`ContentRouting`) and `resolveEntitlement` returns `Allowed(quotaBytes)` before
any metering runs — so an owner with no credential **cannot** be over quota, because nothing measured
them. There were zero 507s throughout.

### 8.3 The fix

**An expired record floors to Free instead of failing closed**, mirroring the rule already applied to
an unknown `productId` (`quotaForProduct`: never unlimited, never a wall).

- **Relay** (`RelayEntitlementVerifier`): past `validUntil` the proof stays `Valid`, metered at
  `StorageTiers.FREE_BYTES` rather than its own tier. Not-yet-valid still `Invalid`.
- **Client** (`EntitlementRecordCredentialProvider.proofHeader`): gates the **start** bound only, so
  an expired record is presented. Sending it can only gain authority; the tier is the relay's call.

This makes the free tier's missing renewal path harmless rather than fatal — free lapsing to free is
a no-op — while a lapsed **paid** record still drops to 5 GiB at `validUntil + 1 ms`, since the
one-sided skew leeway is unchanged.

⚠ Deliberately **not** changed: signature verification, owner binding, the not-yet-valid rejection,
and the bearer **token** path, which keeps its own expiry (a short-TTL session credential has a
renewal path by construction). Metering is unchanged, so D8's second-device bypass stays closed.

Threat model: T-CB-08 amended in the same PR (House Rule #2). Residual delta — a force-revoked owner
keeps **free-tier** write capacity indefinitely instead of going write-dead; judged Low, because the
free tier is available to anyone who provisions an owner, so denying it buys nothing.

⚠ **Still open:** the free tier has no renewal and now no longer needs one, but a *paid* account
whose session is lost silently stops renewing by the same mechanism and will drop to Free at its next
expiry with no user-visible signal. Surfacing "your plan lapsed because this device is signed out"
belongs to the commerce/UI plane and is not addressed here.
