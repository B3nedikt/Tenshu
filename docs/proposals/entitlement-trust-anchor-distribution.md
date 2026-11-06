# Entitlement trust-anchor distribution — where the client's issuer key comes from

**Status:** Draft v0.2 — **decisions D1–D3 taken 2026-08-07** (maintainer walkthrough). ⭐ **D3a
(2026-10-12) is built**: a build can be compiled to trust one more issuer.
Opened by a live incident (§1).
**Owner:** Benedikt

> ⭐⭐ **Outcome in one line: the client's trust anchor stops driving the numbers.** After D1–D3,
> figures come from the relay, the credential is forwarded unverified for the relay to judge, and
> unverifiable records are retained rather than dropped — so the incident in §1 would have been an
> error event and a slightly stale gauge rather than a two-hour mystery. ⚠ It is *not* purely
> diagnostic: `hasStorage` is a fail-closed sync gate and must stay locally derived (D2 amendment),
> so a stale anchor can still close it. §3 records how each decision contributes; §5 is the phasing.

**Consumes:** [`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) (D2 two
carriers, D4 multi-issuer verifier, D5 enforcement is relay-side),
[`commerce-billing-plane.md`](commerce-billing-plane.md),
[`second-device-entitlement-enforcement.md`](second-device-entitlement-enforcement.md) (the proof path).
**Adjacent:** [`retention-quota-model.md`](retention-quota-model.md) (P1 `GET /sync/usage`).

> **Read §2 before §4.** The temptation is to jump to "build a key-distribution mechanism". §2 is
> why we did not: the check is not a security boundary, and §3's decisions reduced it to a
> diagnostic. §4 is a parked sketch, not a plan.

---

## 1. Why this exists

On 2026-08-07 the deployed commerce issuer was promoted from the committed dev seed to a real
`ISSUER_SEED`. The relay's anchor is configuration (`ENTITLEMENT_ISSUER_PUBKEY`) and was rotated in
seconds. **The client's anchor is a compiled-in constant**, so every device silently rejected every
entitlement the real issuer minted:

```
WARN SyncProcessor: dropping Entitlement with invalid publisher signature
     (ownerId=…, publisher=tenshu.commerce)
```

The user-visible symptom was a Services card stuck on *"Setting up your storage"* with a perfectly
valid, owner-matching, in-window entitlement **in the local store**. It presented as intermittent
because the **writer does not verify what it stores** while the **sync path does**: a fresh mint
wrote the row and figures read `true`; the next pull verified, dropped it, and figures fell to
`false`.

The multi-key verifier fix fixed *acceptance* — `publicKeysFor` returns a **set** and any trusted key may verify — which
buys the overlap window rotation needs. It does **not** fix *distribution*: the anchor is still in the
binary.

**The cost that remains.** Adding or retiring an issuer key is an **app release**. On mobile that is
App Store / Play review latency — days — during which every not-yet-updated device rejects every
record signed by the new key. A key rotation is therefore not an ops action but a fleet migration,
and the failure mode during it is the silent one above.

⚠ Note the asymmetry that made this expensive to find: **the same rotation is a config change on the
relay and a release on the client**, and only one of those was in anyone's head.

---

## 2. What is this check actually protecting? — the load-bearing question

Before designing distribution, be clear what breaks if the client verifies nothing.

**It is not the money boundary.** `RelayEntitlementVerifier` verifies both carriers itself against
its own configured key — explicitly *"zero-trust of commerce liveness"*. A client that accepts a
forged entitlement still gets `402` on its next write. This is already the documented position:
reconciliation **D5** — enforcement is relay-side, `:storage-service` is *UX gating only*.

**It is not a boundary against the device owner.** The key is shipped in the same binary as the
check. Anyone willing to run a fake commerce service can equally patch the app or write rows straight
into their own store. The check costs that adversary about a minute.

**What it does buy:** the `Entitlement` row is **synced**, and the relay is untrusted by construction
(House Rule #1). Without verification a malicious or compromised relay could inject an arbitrary
entitlement row into a realm and change what the user's own client believes — quota displayed, local
upload gating, the Tier-2 capacity holder. That is **UX integrity against an untrusted transport**,
and the damage is a wrong number or a local refusal, not stolen storage.

⚠⚠ **The condition under which this analysis flips:** if a paid capability is ever gated *purely
client-side with no relay round-trip* — an offline-only premium feature — the client check becomes a
genuine revenue boundary and everything below must be revisited. Nothing today is in that shape.

### 2.1 The reframe: two jobs, only one of which needs an anchor

The signed record does two unrelated things:

| Job | Who must verify | Does the client need the issuer key? |
|---|---|---|
| **(i) Credential** — presented to the relay as `X-Entitlement-Proof` so a session-less paired device can write | The **relay** | **No.** The client only carries opaque bytes; the relay verifies. |
| **(ii) Figures** — what the UI shows and what local gating uses | Nobody, strictly | Only if figures must come *from the record*. |

⭐ Job (ii) does not require a signature at all if the figures come from **the authority that
enforces them**. The relay already knows the entitlement — it is what refuses the write. Asking the
relay "what am I entitled to" is both simpler and *more correct* than deriving it from a record the
relay may have superseded.

⚠ **But the endpoint does not expose it today.** `GET /sync/usage` returns
`UsageResponse(usedBytes)` and nothing else (`ContentRouting.kt:183`), so the *limit* — the half that
actually drives `hasStorage` and the gauge's denominator — is still record-derived. D2(2) therefore
costs an additive relay change (return the entitled limit alongside usage), not merely a client
rewire. That is small, but it is real work and it is on the relay, so scope it as such.

⚠ Check before assuming "additive is free": an older client parsing a response with a new field
fails under kotlinx-serialization unless its `Json` sets `ignoreUnknownKeys`. Verify the client's
decoder configuration before treating this as a no-coordination change — and if it is not lenient,
this is a `RELAY_PROTOCOL_VERSION` conversation (HR#9), not a quiet addition.

If figures become relay-sourced, **the client needs no issuer trust anchor for the UI**. ⚠ That is
not by itself enough to remove the anchor — verification of inbound records would still need a key.
What removes it is combining relay-sourced figures with *retaining* rather than dropping
unverifiable records (§3, D1+D2). v0.1 of this document got that wrong and claimed D2 alone
sufficed.

⚠ The honest counter: **offline**. A device with no connectivity still has to show something and
still has to decide whether to accept a local write. But that is served by a *cached last-known
figure* — your own previously-fetched data, needing no signature — degrading to "last known as of
<time>". Verification is not what makes a cached number trustworthy.

---

## 3. Decisions — taken 2026-08-07

### D1 — Failure policy: DECIDED — retain, distrust for figures, report loudly, verify at read

Today's behaviour **drops the block**. That is the most damaging available option and buys the
least: it is silent (no error event, no UI signal, only a `println` the packaged app discards),
indistinguishable from "no entitlement yet", and unrecoverable by the user.

⚠⚠ And it does not deliver its own invariant. "Everything in the store verified at ingest" is
already false, because the local mint path writes records **without** verifying. Dropping pays the
full cost of strictness for a guarantee the system does not hold — and that disagreement between the
two halves *is* the oscillation users saw (fresh mint → figures true → next pull → dropped → false).

**Decided:**

1. **Verify at read, not at ingest.** A client update carrying a new key then re-trusts
   already-stored rows with no re-sync. In the §1 incident this alone would have made the multi-key verifier fix take
   effect instantly on existing data.
2. **A record that fails verification is retained, not dropped**, and is excluded from any
   figures-derivation — with a typed `ErrorEvent` and a UI state that *names* the failure instead of
   showing "setting up".
3. **It is still forwarded as the `X-Entitlement-Proof` credential.** ⚠ The relay is the *better
   judge*: it holds the current key, and in this very incident it would have accepted the record the
   client rejected. The client is cautious about what it **displays**; the authority decides what it
   **accepts**.

⚠ Point 3 is load-bearing, not a nicety. `EntitlementRecovery` exists precisely because a device
without a usable entitlement is **write-dead** (measured 2026-08-04: an iPhone write-dead for an
estimated 13 hours). Dropping the record on a key mismatch would defeat that recovery path outright
and strand every session-less paired device on `402`. Retention keeps it working. ⚠ Note
`EntitlementRecovery` checks only the validity *window*, not the signature, so it adds no anchor
dependency of its own.

### D2 — Figures source: DECIDED — the relay; "unknown" is acceptable offline
### ⚠⚠ D2 AMENDED 2026-08-08 (P1c): `hasStorage` cannot move — it is a gate, not a figure

Figures come from **the authority that enforces them**. The relay already knows the entitlement — it
is what refuses the write — so asking it "what am I entitled to" is both simpler and *more correct*
than deriving it from a record the relay may have superseded (upgrade, closure, revocation).

⚠ **Cost, and it is not zero:** `GET /sync/usage` returns `UsageResponse(usedBytes)` and nothing else
(`ContentRouting.kt:183`). The **limit** — the half that actually drives `hasStorage` and the gauge
denominator — is still record-derived, so this needs an additive relay change. Verify the client
decoder's `ignoreUnknownKeys` before assuming that addition is coordination-free; if it is not
lenient this is a `RELAY_PROTOCOL_VERSION` conversation (HR#9), not a quiet field.

**Accepted trade:** a device that has *never once been online* shows storage as **unknown** rather
than a number. Narrow — such a device has nothing else to show either — and it buys deleting a whole
category of problem.

**⚠⚠ The amendment.** "Figures come from the relay" turned out to conflate two things that live in
the same `StorageFigures` struct:

| Field | What it really is | Relay-sourced? |
|---|---|---|
| `usedBytes`, `limitBytes` | Display figures — the gauge | **Yes** |
| `hasStorage` | An **authorization gate** — `storageAllowsSync` returns `figures?.hasStorage == true`, and it fails closed | **No** |

Sourcing `hasStorage` from the relay deadlocks: offline ⇒ the relay cannot answer ⇒ `hasStorage`
false ⇒ the sync gate closes ⇒ the device cannot reach the relay ⇒ it can never learn it is
entitled. The gate would block the request that opens the gate.

⚠ Note precisely *which* devices. A **fresh joiner** is already covered — `PairingKeyInstaller` sets
`joinedFundedRealm`, and the gate opens on `hasStorage || joinedFundedRealm` for exactly this
reason. What has no latch is an **established device restarting while offline**. That, not the
bootstrap, is the load-bearing case.

⚠ Consequence for D2's shape: with `hasStorage` staying local, an entitled device always has the
record-derived tier as its offline floor — so the landing is **option 3 (hybrid)**, not option 2.
Not a reversal of the decision; it is what the decision becomes once the gate carve-out is
respected. The relay is authoritative when it answers; the record covers when it does not.

⚠ This also retires the persisted-last-known-limit idea sketched during P1c: with the record
fallback retained there is no gap for it to fill. The one residual is cosmetic — a device that
upgraded but has not re-minted shows the older tier while offline, self-correcting on the next
relay answer. Deliberately not smoothed, because caching "the best limit we ever saw" would
over-claim after a downgrade.

### D3 — Anchor location: DECIDED — keep the compiled-in set; do not build distribution

⚠ D2 alone did **not** remove the anchor, contrary to what v0.1 predicted: D1's verification (even
retained-and-distrusted) still needs a key. What removed it was D1's *retention* plus D2 together —
see the summary at the top.

**Decided: the compiled-in set from the multi-key verifier fix stays.** No manifest, no configuration, no synced row.

Rationale: after D1+D2 the anchor gates **nothing functional** (§3.1). Owning a key-distribution
mechanism forever — with rollback resistance, offline root custody, a bootstrap floor and clock
dependence (§4) — to protect a check whose worst failure is now a spurious error event is a poor
trade.

⚠ **Revisit trigger, stated weakly on purpose:** before a *planned* issuer-key rotation where the
diagnostic noise or the release coupling actually becomes a problem. v0.1 framed this as
launch-blocking; after these decisions it is not. Do not let the §4 sketch imply more urgency than
that.

### D3a — AMENDED 2026-10-12: one more issuer, chosen at compile time

D3 stands — the set is still compiled in, and there is still no manifest, no runtime configuration
and no synced row. What changed is that **one member of the set can be supplied by the build**:

```
TENSHU_EXTRA_ISSUER=tenshu.commerce.dev:<base64 Ed25519 public key>     # or -Ptenshu.extraIssuer / local.properties
```

**Why.** The dev lab (`ops-dev-lab.md`) runs a commerce with its **own** seed and its own issuer id
— that is its isolation property. No client trusted it, and the D2 amendment says what that costs:
`hasStorage` is derived from the locally verified record and fails closed, so a first device on
that plane never opens its sync gate. The alternatives were hard-coding the dev key (every release
client then trusts a test plane's issuer, and rotating the dev seed is a client release) or running
the plane on the committed dev seed (anyone can forge against it).

**Shape, and the three properties that make it safe to have:**

- **Additive, under a new id only.** `:identity-service`'s build refuses the built-in ids, and
  `publicKeysFor` resolves them before it looks at the additional issuer — so no value of the
  variable can replace or widen what `tenshu.commerce` is trusted under. Pinned by
  `additionalIssuer_cannotShadowABuiltInPublisher`.
- **Never in force in a release binary.** Compiled in is not in force: `trustBuildConfiguredIssuer`
  turns it on at proxy start-up, and only when the platform's release signal says debug. The
  variable is read from whatever shell ran the build, and a release cut in a terminal still set up
  for the dev lab must not ship trusting it. ⚠ The web has no such split — an image is built *for*
  a plane — so there the guard is **provenance**: the build records whether the value came from
  this invocation (`-P` on the command line, or the environment) or from a standing file
  (`local.properties`, any `gradle.properties`), and the bundle puts only the former in force
  (`issuerInForce`, `explicitOnly`). A line left in a standing file after an Android dev-lab build
  would otherwise reach a production image built later on the same machine. ⚠ `findProperty` cannot
  make that distinction — it answers from `~/.gradle/gradle.properties` as readily as from `-P` —
  so the build reads `startParameter.projectProperties`. A production image is therefore safe as
  long as `build-apex-image.sh` is not *handed* the
  variable. Pinned by `explicitOnly_ignoresAnIssuerInheritedFromAStandingFile`.
  ⚠⚠ **What the issuer does on the web today is narrower than on a device.** The web reader is
  wired with no `verifyRecord`, so it derives figures and `hasStorage` from *any* stored record,
  trusted publisher or not — D2's fail-closed gate does not exist there (`tech-debt.md` *"The web
  entitlement reader verifies nothing"*). On the web the trust set feeds only the merge-time
  diagnostic, so the variable decides whether a dev-plane record raises `EntitlementUnverifiedError`,
  not whether the client syncs. The provenance guard is the right shape for the day the reader
  verifies; until then it is not a trust boundary, and nothing here should be read as one.
- **Visible.** A build without the variable is byte-for-byte plausible and fails only as "unknown
  publisher" at runtime — the same trap as an un-inlined origin. The proxy prints
  `Entitlement trust: publishers=[…]` at start-up, on every platform; that line is the only place
  the two builds differ before something breaks.

⚠ **This is not D3's revisit trigger and does not make the issuer key rotatable.** A production
rotation still ships as a release. It parameterises one slot for planes that are not production.

⚠ Not exercised on iOS or desktop: the first client pointed at the dev plane is Control on the
Android emulator.

### 3.1 What the anchor still does, after D1–D3

| Path | Source after these decisions | Needs the anchor? |
|---|---|---|
| Figures / UI / local gating | The relay (D2) | No |
| Offline figures | "unknown" — no record fallback (D2) | No |
| Credential to the relay | Retained, forwarded unverified (D1.3) | No |
| Inbound ingest | Retained, not dropped (D1.2) | No |
| `hasStorage` — the sync gate | The record, verified locally (⚠ D2 amendment) | **Yes** |
| **Telling us a signature did not match** | The compiled-in set (D3) | **Yes — diagnostic** |

⚠⚠ **Corrected by the D2 amendment (2026-08-08).** v0.2 claimed the anchor ends up *purely*
diagnostic. That is true of the displayed numbers but not of the gate: `hasStorage` is still derived
from the locally verified record, so a stale anchor can still close the sync gate. The blast radius
shrank a great deal — it no longer garbles the UI or strands paired-device writes — but "purely
diagnostic" overstated it.

⚠ Residual to keep in view: because the anchor no longer gates anything, a genuinely forged record
now reaches the relay as a credential. That is fine — the relay refuses it — but it means the client
has stopped being a filter, and any future consumer of the stored record must not assume otherwise.

## 4. Sketch — PARKED (D3 decided against building this)

⚠ Retained only so whoever hits D3's revisit trigger does not re-derive it — and so the costs below
are visible when they weigh it. Nothing here is scheduled.

A `KeyManifest`: `{ publisherId, keys: [{ key, notBefore, notAfter }], version }`, signed by a root
key pinned in the client. Served by the relay (untrusted transport is fine — the signature is the
trust) and cached locally with monotonic `version` to prevent rollback.

⚠ Non-obvious requirements, each of which is a way to get this wrong:

- **Rollback resistance.** A cached manifest must never be replaced by a lower `version`, or an
  untrusted relay can pin the fleet to a retired key.
- **Root custody.** The root becomes the most valuable key in the product and must be offline. A root
  compromise is unrecoverable-by-design without a second pinned root — so pin **two** from day one,
  or accept that root rotation is a release.
- **Bootstrap.** A brand-new device with no cached manifest and no connectivity has only the
  compiled-in set. So (1) does not disappear under (2); it becomes the floor.
- **Clock dependence.** `notBefore`/`notAfter` make verification clock-dependent, and
  `MigrationContext` precedent says we are wary of that. A device with a wrong clock rejects valid
  keys.

---

## 5. Phasing

- **P0 — stop the silent failure. SHIPPED 2026-08-08.** Verification moved to the read path,
  unverifiable records retained rather than dropped, still forwarded as the credential, and a typed
  `EntitlementUnverifiedError` raised into the `ErrorEvent` stream (visible under Settings → Errors).
  ⚠⚠ **The Services state that names the failure was deliberately NOT built** — see below; P0
  removes the *silence*, not the misleading copy.

  ⚠ Why the UI half was deferred rather than skipped: the cheap route (a flag on `StorageStatus`) is
  blocked — that is a positional composite, so a new field is a reshape owing a version bump +
  `SchemaMigration` + lockstep clients (HR#9), which is disproportionate for a UI hint. The correct
  route is a new local-only single-row signal (the `SchemaFenceStatus` precedent), and **P1 deletes
  the need for it**: once figures come from the relay, an unverifiable record no longer makes
  `storage` null, so there is nothing for a dedicated state to say. Building it now means building
  a surface P1 removes.

  ⚠ **Accepted cost until P1:** an unverifiable record still renders as *"Setting up your storage —
  this usually takes a moment"*, the exact copy that made the 2026-08-07 incident hard to read. The
  failure is now durably recorded for anyone who looks; the card still misleads anyone who does not.
- **P1 — relay-sourced figures (D2). SHIPPED 2026-08-08** in three slices: P1a relay reports the
  limit, P1b the client presents its credential and prefers the relay's answer,
  P1c the copy fix + the D2 amendment above.
- ~~**P1 — relay-sourced figures (D2).**~~ Extend the relay to return the entitled limit alongside usage
  (⚠ it returns usage only today), check the client decoder's `ignoreUnknownKeys`, then move
  `hasStorage` / limit / gauge onto it with an explicit **unknown** state when never-yet-online.
  ⚠ **P1 also owns P0's deferred copy fix** — it inherits the obligation, so the misleading
  "Setting up your storage" state must be revisited as part of it and not quietly forgotten.
- **P2 — ⚠⚠ CANCELLED by the D2 amendment.** The record cannot be retired as a figures source,
  because `hasStorage` must stay locally derived and the record is what derives it. The anchor is
  therefore diagnostic for the *numbers* but still consulted for the gate — narrower than v0.2
  claimed. ⚠ Revisit only if `hasStorage` ever gains an offline-tolerant relay-backed form, which
  is a design in its own right and not currently worth it.
- **P3 — none.** D3 decided against building distribution. §4 stays as a sketch for whoever hits the
  revisit trigger.

⚠ Sequencing note: P0 and P1 are independent and can land in either order, but **P2 must follow
P1** — collapsing the record's figures role before the relay supplies figures would leave the UI with
no source at all.

---

## 6. Open questions

- ~~**Q1.** Is "storage: unknown" an acceptable offline state?~~ **Resolved 2026-08-07: yes** (D2).
- **Q2.** Does anything else consume the *forwarded* proof besides the relay's verifier? Current
  answer: no — it is opaque bytes. Confirm before P0 makes forwarding unconditional.
- ~~**Q3.** Does anything else depend on `Entitlement` being publisher-authentic?~~ **Audited
  2026-08-07:** three consumers — `EntitlementStorageReader` (figures; retired by P2),
  `EntitlementWriter` (idempotency check, signature-independent), and `EntitlementRecovery`
  (⚠ window check only, no signature dependency). None blocks these decisions.
- ~~**Q4.** Should the *writer* verify what it stores?~~ **Resolved by D1's verify-at-read:** the
  writer keeps writing unverified, but the reader refuses to *derive figures* from an unverifiable
  record — so a key mismatch fails immediately and locally at mint instead of drifting until the next
  pull. The asymmetry is settled by moving the check, not by adding a second one.
- **Q5 (new).** A record already dropped under today's behaviour is gone until the issuer re-mints —
  the sync cursor will not re-deliver it. Does P0 need a one-shot re-fetch for devices that dropped
  records during the §1 window, or is waiting for the renewal midpoint acceptable?

---

## 7. Threat-model deltas

Proposal-level only; land per implementing phase (House Rule #2).

- **A29** gains a note: the signing key's *public* half is a client trust anchor that, after these
  decisions, is **diagnostic rather than enforcing**. Rotation therefore no longer has a
  fleet-migration cost — but the anchor is still compiled in, so a rotation still produces spurious
  verification failures on not-yet-updated clients until they update. Record that as expected noise,
  not as an incident.
- **New residual (D1.3 + D2):** the client has stopped being a filter on entitlement records. An
  unverifiable — including a genuinely forged — record is retained and forwarded to the relay as a
  credential. ⚠ This is safe *because* `RelayEntitlementVerifier` re-verifies against its own
  configured key, so it cannot grant storage. It must be written down explicitly, because any future
  consumer of the stored record that assumes "if it is here, it verified" would be wrong.
- **New residual (D2):** displayed quota and local gating now come from the relay, so an untrusted
  or compromised relay can influence what the user sees and whether their client attempts an upload.
  Bounded — it cannot grant storage — and it replaces the equivalent record-injection surface rather
  than adding to it.
- ⚠⚠ **The invalidating condition, restated because everything above rests on it:** any paid
  capability gated *purely client-side with no relay round-trip* re-promotes this check to a revenue
  boundary, at which point D1–D3 must all be revisited. Nothing today is in that shape; this belongs
  in the threat model so it is noticed if something ever is.
