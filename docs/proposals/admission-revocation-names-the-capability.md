# Admission revocation should name the capability, not the device-at-an-epoch

**Status**: v0.1 — design, no code. Written 2026-08-18 out of the incident where a re-admitted device was born revoked.
**Owner**: unassigned. **Gates**: threat-model amendment (House Rule #2), a wire change to a
master-signed message.
⚠ **This header is stale about everything below §7b** — the F-phases' as-built notes live in
§7c–§7g and several landed. Read those, not this line, for what is built.
⭐ **§7d (length prefixes) and F5b (no keyless revocation) SHIPPED 2026-10-28** with the
protocol-1 re-cut of the complete production reset — as-built in §7d.2, §7d.3 (B1: the pairing/realm family, same hole) and §7g.6 (incl. S1/S2).
⭐ **§7i (workstream B) SHIPPED 2026-10-29** on the same re-cut: a device id is derived from its key.
⚠ **Unstarted: §7h (F6)** — the F2′ refusal is a dead end for the honest user, and the
ledger's real benefit turns out to be *telling the approver*, not blocking. Added 2026-08-28.

---

## 0. The incident that produced this

A desktop, freshly re-paired and syncing perfectly — hundreds of `200 OK: POST /sync/log`, pushes
accepted — answered **every** attempt to add a device with `403 Admission capability has been
revoked`. Permanently: no retry, re-pair or restart helped.

`AdmissionChainVerifier.isRevoked` supersedes a capability when

```kotlin
revocation.epoch >= cert.mintedAtEpoch
```

and both sides read `currentRealmEpoch`, which is **the count of `Device` tombstones**. Revoking
advances that count the *first* time by adding one; re-revoking a device that already has a tombstone
adds none. So revoke-then-readmit inside one epoch mints a capability that is **superseded at the
instant it is signed** — `N >= N`.

The shipped fix treats the symptom by seeding the epoch past the revocation it just wrote. That works, and it
leaves the underlying shape intact: a security decision ordered by a counter that is not monotonic
with respect to the events it separates.

---

## 1. The conflation at the root

"Revoke" currently means two things at once:

1. **This key is compromised** — kill *this* capability. Inherently **key-scoped**.
2. **This device is out of the realm** — and don't let a delegated admitter hand it straight back.
   Inherently **device-scoped** and forward-looking.

The comparison is doing job 2 while the data shape is doing job 1.

⚠⚠ **This is why `>=` cannot simply become `>`.** With `>`, any valid admitter could re-mint at the
revoked epoch and return the capability, defeating A1's removal. The `>=` is not protecting *ordering*
— it is protecting a **policy** ("a delegated admitter must not undo a revocation"), using a counter
as a proxy. The born-revoked bug is the cost of that proxy.

State the policy directly and the ordering requirement disappears.

## 2. What the data already has

```kotlin
AdmissionCapabilityCert(ownerId, deviceA33PublicKey, deviceId, mintedAtEpoch, signature)
  message = "tenshu-admit-cap-v1" ‖ ownerId ‖ deviceA33PublicKey ‖ deviceId ‖ mintedAtEpoch

AdmissionRevocation(ownerId, deviceId, epoch, revokedAt, signature)
  message = "tenshu-admit-revoke-v1" ‖ ownerId ‖ deviceId ‖ epoch ‖ revokedAt
```

⚠ These are the layouts this analysis started from. As built (§7d) both are length-prefixed
and re-tagged, `tenshu-admit-cap-v2` and `tenshu-admit-revoke-v3`, and the revocation also names the
A33 key — see `SignedMessageEncoding.kt` and `AdmissionCapability.kt`.

The cert **already** carries `deviceA33PublicKey`, and `PairingAdmission` mints a **fresh A33 key pair
per admission**. A per-capability identity therefore already exists and is already unique. The
revocation simply does not reference it.

Naming it turns `isRevoked` into set membership — no ordering, no clock, no counter. A re-admission
mints a new key, which is trivially not in the revoked set.

## 3. The constraint that shapes every option

⚠⚠ **The revoker does not currently know the key it would be naming.**
`RevokeDeviceImplementation` walks an `admissionSubtree` of **deviceIds**. The A33 public key of
another device is available only from `AdmissionKeyAnnouncement`.

### 3.1 ⚠⚠ R1's measurement — and it is worse than "the announcement may not have synced"

**Measured on real devices 2026-08-18** (R1 probe, iPhone as the A1 holder, revoking a two-device
subtree):

| device | admitted by | `a33KeyResolvable` |
|---|---|---|
| desktop `7a38e6fd…` | **the A1 holder itself** | **false** |
| emulator `eff23d8c…` | the desktop | **true** |

⚠ This **inverts** the expectation this doc was written with. The device the revoker *personally
admitted* could not be named; the one admitted by a third device could.

**The mechanism is structural, not timing** — `AdmissionMigrationObserver` skips announcing when:

```kotlin
if (syncManager.currentMasterKeyPair != null ||
    syncManager.getRecoveryPhrase() != null ||
    syncManager.getAdmissionCertChain().size == 1
) { announcedThisSession = true; return }
```

So an announcement exists **only** for a capability-less device (the P4 migration case) or one holding
a chain **deeper than 1** (the re-root request). ⚠⚠ **A device admitted directly by the A1 holder has
a depth-1 chain and therefore never publishes its key at all** — and in any small realm that is most
devices.

⚠ `AdmissionCertGrant` carries the chain (hence the key) but is published by the same duty on
observing an announcement, so it covers the **same population**. It is not an alternative directory.

⚠ **The probe measures *resolvable*, not *current*.** A row's key is the device's self-minted one; a
device re-admitted through pairing is minted a **fresh** A33 key by its admitter. For a depth-1
device there is no row to go stale, but any key-scoped design must verify it is naming the *live*
capability, not merely finding *a* record. R1 as implemented does not answer that.

### 3.2 ⭐ The key is known at admission — and discarded

`PairingAdmission` **mints** the child's A33 keypair (`generateAdmissionKeyPair()`), puts the public
half into the cert it signs and the private half into the pairing blob — and records the public key in
**no synced row**. The admitter holds exactly the value a key-scoped revocation needs, at the moment
of admission, and throws it away.

That reframes the whole problem: the gap is not *lookup*, it is *capture*.

---

## 4. Options

### A — add the key, keep the epoch rule as a fallback  ⚠⚠ **undermined by R1**

⚠⚠ **R1 (§3.1) largely kills this as written.** Its fallback was supposed to be the rare case; the
measurement says the *lookup itself* fails for every device admitted directly by the A1 holder,
because those never announce. On a small realm the fallback would be the **normal** path, and A would
be the current behaviour wearing a new field. Read the option below it (A′) instead.

`AdmissionRevocation` v2 carries `revokedA33PublicKey`; new domain tag `tenshu-admit-revoke-v2`.
`isRevoked` matches on key equality when the revocation names one, and falls back to today's epoch
comparison when it does not (a v1 record, or an unresolvable key).

* ✅ Precise where it matters; no over-revocation; backward compatible; no ordering in the common path.
* ⚠ Two rules coexist permanently.
* ⚠⚠ A revoker that has not synced the announcement **silently degrades** to the coarse rule. That is
  a guard that exists and does not fire — the exact failure mode that produced five defects on
  2026-08-17/18. **The degradation must be logged and surfaced, or A is not worth shipping.**

### A′ — capture the key at admission *(new, from R1)*

The admitter already mints the child's A33 keypair, so it records the public half when it admits —
on the `Device` row it authors anyway, or in a small synced record beside it. A revocation then names
a key the realm has always had.

* ✅ **No announcement dependency, no sync race, no fallback branch** — the value is present at the
  moment the admitting device writes the row.
* ✅ Works for the depth-1 devices that §3.1 shows are exactly the unserved case today.
* ⚠ Schema change to a synced type ⇒ House Rule #9: bump the owning service and ship the
  `SchemaMigration` in the same PR.
* ⚠ **Backfill**: devices admitted *before* this ships have no recorded key. They keep the epoch rule
  until re-admitted — forward-only, like the `admitted_hlc` stamp. That is acceptable only
  because it degrades to today's behaviour rather than to a hole.
* ⚠ Does not by itself remove the ordering rule; it makes the key-scoped path *reachable*, which A
  assumed and R1 disproved.
* ⚠⚠ **It must record something signature-bearing — not a bare `a33PublicKey` field.** Synced rows
  are realm-encrypted, so *any realm member* can author one; an unsigned key field inherits
  **T-DA-06** ("forged admission-key announcement", actors *AD2–AD3 holding any realm device"),
  which is exactly why `AdmissionKeyAnnouncement` is A4-signed. Record the **cert** (already
  master- or A33-signed, and it contains the key), or the key plus its signature. ⚠ The analogy
  comes from the scanned-announce proposal's own reasoning — *an unauthenticated announce would let
  an observer plant a bogus key* — which is the same failure in a different flow.
* ⚠ **Unrelated to the scanned announce of `sync-pairing-scanned-announce.md`**, despite the name:
  that announces the *ephemeral pairing* key to the rendezvous for early SAS + key-equality pinning,
  one ceremony long. This records the *A33 admission* key in the realm, for the life of the
  capability. No shared plumbing and no ordering conflict — A′ transmits nothing, it records.

### B — key only, no fallback

* ✅ One rule; the epoch loses all security weight; simplest verifier.
* ⚠⚠ **Rejected.** If the announcement has not synced, the device **cannot be revoked at all**. "I
  can't remove this device because I haven't seen its key" is a security regression, not a trade-off.

### C — revoke a set of keys

The revocation lists every announced key seen for that device.

* ✅ No ordering; re-admission unaffected by construction.
* ⚠ Unbounded message size.
* ⚠⚠ Still misses a key that has not synced — B's hole, narrower but present.

### D — keep device scope, fix the ordering with HLCs

Add `revokedAtHlc` to the revocation and `mintedAtHlc` to the cert; compare those, epoch as tiebreak.
Reuses the shipped `HlcStamp` machinery (the `admitted_hlc` stamp).

* ✅ No dependency on announcement sync; device semantics preserved; machinery exists.
* ⚠ A **client-asserted** clock inside a security decision — it may only ever break ties, never
  override a strictly higher epoch (same constraint the `admitted_hlc` amendment records).
* ⚠ Two clocks in one rule.
* ⚠⚠ **Over-revocation remains**: a revocation still cannot say "this key, not the next one".

### E — separate the two concerns  *(considered, not chosen — §4.3)*

1. Revocations name the **capability** (key-scoped, exact) — option A's data change.
2. A separate, explicit rule governs **re-admission after revocation**: a device with a revocation on
   record may only be re-admitted by a chain **rooted at A1**, never by a delegated admitter.

* ✅ Each rule says what it means. No ordering anywhere. The property `>=` was protecting becomes
  explicit and checkable.
* ✅ **Enforceable where it belongs**: the relay already reads the whole ledger at admit time
  (`revocations(ownerId)` in the admit path), so it can apply rule 2 itself.
* ⚠ Largest change; needs the threat-model amendment.

### F — an identity never returns  ⭐ **CHOSEN (§4.3)**

Removal is **final for that identity**. There is no re-admission rule because there is no
re-admission: a device that comes back mints a **new** identity and is admitted like a stranger.
This is Signal's and Matrix's model.

* ✅ The ordering problem becomes **unrepresentable**, not solved — no epoch, no HLC, no key capture.
  Nothing in the ledger has to be compared against anything.
* ✅ Adds **no new authority**: unlike E, it does not require an A1-rooted approver at re-admission,
  so it does not lean on owner devices — which §5b.9 shows cannot themselves be removed.
* ⚠⚠ Needs a **reaper for orphaned rows**, which we do not have: every remove→return cycle leaves a
  permanent dead entry. Android already carries two such ghosts in the live realm.
* ⚠⚠ Discards everything keyed to the device identity (§4.1).

---

## 4.1 The live pair — E and F, and why the rest are out

**A, A′, B, C and D are ruled out as endpoints.** A/A′ and B are key-scoping changes that E
*contains* (E is A′ plus the policy), so they are steps on the way to E rather than alternatives to
it; C never had a caller that wanted set semantics; D fixes the ordering rather than removing the
need for it, and leaves over-revocation in place. ⚠ D is the tempting reach because we built
`HlcStamp` the same morning.

That leaves the two that actually differ in what they *mean*: **E** — a revoked identity may return,
but only when a holder of A1 deliberately re-admits it; and **F** — a revoked identity never returns.

### ⭐ They are security-equivalent; they differ only in what they cost the honest user

This is the finding that decides it, and it is not obvious:

* **An attacker without A1** is blocked by both. E requires an A1-rooted approver to re-admit; F
  admits no one.
* **An attacker with A1** is stopped by neither — and F's extra strictness buys nothing, because
  such an attacker has no need to re-admit the old identity at all. They can enrol a **fresh**
  identity from the same hardware, which both options permit. §5b.9 compounds this: a device
  holding A1 cannot be removed, so the realm is already theirs.

So F's hard guarantee **binds only the honest user**. It does not bind the attacker E fails to stop.

⚠ The one real asymmetry is **approval phishing**, not cryptography: under E a thief holding the
removed device can try to talk the user through an approval, and the returning device arrives with
its *old name and old row*, which reads as familiar. Under F a returning device is visibly new and
unnamed. E owes its confirmation copy that job (§5b.6).

## 4.2 Where E is better for the user — the two intents behind one button

F's cost is concrete, because a device identity is not just a credential: **`Device.NAME` is
user-set** (*"Rename device"*, `sync/design.md` §"Actions"), and the dashboard keys
`deviceSyncPolicies`, `deviceAdmittedBy` and `deviceLastSeen` off `deviceId`
(`SyncDashboardUiState`). Under F all of it resets, next to a ghost row of the device's former self.

The cases where that matters are the ordinary ones:

1. **The mistaken removal.** The user removes the wrong row — the single likeliest real use. Under E
   an owner re-admits and the device returns *as itself*. Under F it returns as a stranger to be
   re-named and re-configured.
2. **The precautionary removal.** *"I think I left my laptop on the train."* It turns up. E makes
   that a reversible act; F made it a one-way door at the moment the user was least sure.
3. **Removal reached for as a temporary state.** Lending a device for a week. Remove is the only
   lever we offer, so users will use it that way whatever we intend.
4. **The list stays truthful.** Under F every cycle adds a permanent dead row and no reaper exists,
   so the list degrades into *"which Office Desktop is mine?"* — the exact confusion §5b.2 set out to
   remove.

Against that, F is better in one case: removal that is **meant** to be final — sold, stolen,
decommissioned — where the user wants the button to mean *never again*.

### ⭐ But E can express F's meaning; F cannot express E's

Those are two different intents behind **one** button, and today's UI cannot tell them apart — so
whichever we adopt globally is wrong for the other intent. E is the more expressive of the two: a
removal that is *meant* to be final can be honoured by making re-admission a deliberate, owner-only,
warned act (*"This device was removed on 12 March. Re-admit it anyway?"*), which is E with the intent
surfaced rather than a second mechanism. F cannot go the other way — it has no re-admission to make
convenient.

⚠ An unoverridable *final* flag is **not** the answer: a user who marks final by accident could never
recover the device even with the recovery phrase, and — per the equivalence above — it would buy no
security. The intent belongs in the confirmation copy and the re-admission warning, not in a new
ledger state.

## 4.3 ⭐ DECISION: **F**, taken 2026-08-19

**Chosen: F — a revoked identity never returns.** The reasoning is §4.1's equivalence: E buys no
security over F, so E's extra machinery is bought entirely with convenience — and F is markedly
simpler. It needs no key capture (A′), no A1-rooted approver rule, no dependence on the owner-device
concept (§5b) landing, and it removes the epoch as a security input rather than repairing it. The
ordering bug that produced this document becomes **unrepresentable**.

The cost F accepts is §4.2: a mistaken removal cannot be undone by anyone, the recovery-phrase
holder included. F therefore trades *cure* for *prevention*, and the prevention has to be real.

### What the confirmation covers — and what it does not

**Built (2026-08-19).** The revoke dialog gained a checkbox — *"I understand this cannot be
undone"* — which gates the confirm button (`RevokeDeviceConfirmDialog`, `SyncDashboardScreen.kt`).
Its guard is `revokingADevice_needsTheAcknowledgement_notJustTheConfirmTap`, written to go red if
`enabled = acknowledged` is deleted; verified by removing the gate and re-running.

⚠⚠ The body copy was **wrong for F** and was rewritten. It promised *"to use it again you'll have to
add it back from the device that has your recovery phrase"* — E's story. Under F there is no adding
back, and copy that implies recovery strands precisely the user who believed it. It now says the
device can be set up again but **joins as a new device**, without its name or settings.

The checkbox closes case 1 of §4.2 (the mis-tap) and nothing else. Two costs remain open, and
choosing F means accepting or funding them:

* ⚠ **The precautionary and temporary removals (§4.2 cases 2–3) stay one-way doors.** Those users did
  not mis-tap — they removed deliberately and changed their mind — so a confirmation confirms a
  deliberate act and helps them not at all. This is accepted, on the basis that honest copy at the
  moment of removal is the whole of what we owe them.
* ⚠⚠ **CORRECTED AGAIN 2026-08-19 (§7b.2) — the "needs no key capture (A′)" claim below is WRONG.**
  A′ is F's prerequisite too: without it, F1's set membership makes Q3 subtree revocation permanent
  and destroys the re-mint path. Read §7b before this bullet.
* ⚠⚠ **CORRECTED 2026-08-19 — F does *not* need an orphan reaper.** This entry previously called one
  a prerequisite, on the claim that every remove→return cycle leaves a dead row. **That claim was
  false.** `RevokeDeviceImplementation` does `removeFromArray(DEVICES)` **and**
  `executionContext.deleteData(deviceDataId)` — a revocation *deletes* the row, so a returning device
  simply gets a new one. The two ghosts in the live Android realm come from the unrelated
  *identity-destroyed-while-store-survives* path, whose two halves have shipped (self-revoke on
  wipe + `DeviceLastSeen`); they are orthogonal to F and are not made worse by it. ⚠⚠ And an
  automatic reaper would have been the wrong shape anyway: `tech-debt.md` rules it out explicitly —
  *"an offline device and a dead identity are indistinguishable from the outside"*, and ADR 0007 has
  an absent device hold the compaction floor rather than be presumed gone.
* ⚠⚠ **F's actual prerequisite is the iOS reinstall gate** ([`ios-identity-reinstall-gate.md`](ios-identity-reinstall-gate.md)
  RG-1), and it is a real one. On iOS the Keychain outlives the app, so a revoked device keeps its
  identity through an uninstall and presents **the same `deviceId`** when it comes back — which is
  exactly what F refuses, permanently. Today that reinstall silently self-heals into the same row;
  under F it becomes a permanent refusal instead. ⓘ Not a brick: `LockedView` already carries a
  "Forget this device" escape hatch that sheds the custody bucket, so the user *can* get out. But
  they arrive at a locked screen that does not say why, and the remedy is labelled for a different
  situation. RG-1's fork is what names it. ⚠ iOS-only: `wipeDeviceState` has no Android or desktop
  twin, and on those platforms clearing app data takes the identity with it.

⚠ E is **not** kept as a fallback. It was live only while §4.1's equivalence was unproven; with F
chosen, §5b's owner device stands or falls on its own merits (it still explains what A1 is and why
new hardware needs the phrase), and no longer has a re-admission rule depending on it.

---

## 5. The lifecycle question this answers — and the two axes people collapse

The natural intuition is *"deliberate removal should stick; an accidental reinstall should not cost
the user anything."* That is right, but it is **two independent axes**, and only one is a policy
question:

|  | identity survives | identity gone |
|---|---|---|
| **not revoked** | reinstall resumes the same member — *no policy question, the device never left* | a **new** device; ordinary pairing |
| **revoked** | ⚠⚠ **the stuck cell** — 401s forever, and under E only an A1 holder can re-admit | a **new** device; ordinary pairing, no revocation names it |

⚠ **An app *update* is a non-event on both platforms** — it preserves app data and app-scoped
Keystore/Keychain entries. Only delete-and-reinstall moves the second axis.

⚠⚠ **"identity gone" does NOT mean "device removed", and the difference is a gap.** Destroying the
local identity destroys the device's ability to *be* that device — it does not remove the *record* of
it. The only thing that deletes a synced `Device` row or a relay registration is the explicit
`RevokeDevice` op. So an Android uninstall leaves behind a phantom row in the user's device list and
a live relay registration for a key that no longer exists anywhere, with **no cleanup path**. The
reinstalled app then pairs as a separate device beside its own ghost.

⚠⚠ **And the second axis is platform-asymmetric**, which is the part most easily got wrong:

* **iOS** — the Keychain identity **survives** uninstall, so a reinstall silently resumes the old
  identity ([`ios-identity-reinstall-gate.md`](ios-identity-reinstall-gate.md)).
* **Android** — the identity does **not** survive: `pm clear` / uninstall takes the sandbox *and* the
  app-scoped Keystore entries with it, so a reinstall is a genuinely new device. ⚠ "Unaffected" is how
  [`ios-identity-reinstall-gate.md`](ios-identity-reinstall-gate.md) puts it, and that is right about
  the *gate* — but not about the lifecycle: Android trades the iOS problem for the orphan above.
* **Desktop** — already closed: the reset script deletes the macOS login-Keychain wrap key explicitly.

So the reinstall gate is an **iOS** concern, not a shared one.

### Why the gate and this policy need each other

In the stuck cell, `ios-identity-reinstall-gate.md`'s **"start fresh"** is the escape hatch: it mints a
*new* device identity, hence a new A33 key, which no revocation names. Pairing then works normally.

⚠⚠ **Under E that is not a loophole**, and this is the load-bearing point: getting back in still
requires an authorised admitter, and under rule 2 an A1 holder. The revoked **capability** stays dead
forever; the **person** may enrol the device again as a new member. That separation is the whole
argument of this doc — *revocation is about keys, not about people* — and it is what makes "start
fresh" safe to offer in the UI.

Conversely, without the gate the stuck cell has no in-product exit on iOS: the app resumes a revoked
identity and reports a permanent failure with no explanation. **The policy is only humane if the gate
exists.**

### ⚠⚠ The two platforms fail in opposite directions

| | what outlives what | consequence |
|---|---|---|
| **iOS** | the **identity** outlives the app | reinstall silently resumes it → needs the reinstall gate |
| **Android** | the **record** outlives the identity | orphaned `Device` row + stale relay registration, no cleanup |

Neither is clean. ⚠ **Open, and out of this doc's scope**: nothing reaps a `Device` row whose device
can no longer exist. It is not a revocation — the user never asked for one — so `RevokeDevice` is the
wrong instrument, and a device cannot reap its own row *after* it has lost the identity that would
authorise the write. Wants its own design; noted here because §5's "identity gone" column reads
harmless and is not.

---

## 5b. The "owner device" — a UX name for holding the master identity

⚠ **Written while option E was live** (§4.3 chose F, which needs no A1-rooted approver). The
section survives because it still explains what A1 is, why new hardware needs the phrase, and why an
owner device cannot be removed — none of which depended on E. Option E routed an
ordinary decision — *re-admit a device I removed* — to whoever holds A1. Today a user can end up with
**no device that does**, and nothing in the product ever told them that was possible.

### 5b.1 What A1 actually is

- `ownerId = sha256(masterPublicKey)` — **the realm *is* the master public key**. It cannot be
  eliminated or replaced: rotating it produces a *different realm*, breaking every registration and
  every cert chain. ⚠⚠ **A1 is unrotatable, so a leak is permanent and unrecoverable.**
- `deriveMasterKeyPair(recoveryPhrase)` is deterministic, so **the master identity is the phrase**,
  not a device. Any device that derives it holds A1.
- A pairing joiner **never receives it**: `PairingKeyBlob.pack` carries `sharedIdentityKey`,
  `sharedVolumeKey`, the realm encryption keypair and an A33 admission keypair — no master key. (⚠ The
  field is named `encryptedMasterKey` on the wire; that name is a misnomer for a pairing blob.)

**Consequence, and the scenario that motivates this:** realm started on device A, device B joins, A is
lost and removed ⇒ **the realm now has no A1 holder at all**. Not "B is promoted", not "there were two
masters". The master identity still exists — it is the phrase — but no device derives it.

### 5b.2 The concept

> **An owner device is one that holds the realm's master identity.** It can add and remove devices,
> repair its own registration (and, under the since-rejected option E, re-admit a removed device).

**DECIDED (2026-08-19): the term is "owner device", not "primary device".** ⚠⚠ The reason is
load-bearing rather than stylistic: **there can be more than one.** Any device that derives A1 from
the phrase holds it, and nothing can prevent that — derivation is deterministic and offline. A label
implying a singleton would eventually render two "Primary" badges and read as a bug; "owner" carries
plurality without a caveat. **Do not build an enforcement that pretends there is exactly one** — it
cannot be enforced, only misrepresented.

### 5b.3 Eligibility — what may become an owner device

Only a device with **durable, OS-backed key custody of its own**:

| eligible | not eligible | why |
|---|---|---|
| Control on Android (installed) | the **web/browser** device | session-scoped, no durable custody — a transient holder of an unrotatable key is a leak waiting to happen |
| Control on iOS (installed) | any **EPC-pattern** client | it does not hold realm custody; it proxies through Control, so "this device holds A1" would be a claim about a *different* process |
| Control on desktop (installed) | — | |

⚠ The rule follows from A1 being unrotatable: a key that can never be rotated must never sit anywhere
its lifetime is shorter than the realm's, or borrowed from another process.

### 5b.4 How a device becomes an owner device

Two routes, both **deliberate**, never automatic:

1. **Enter the recovery phrase** on the device. The existing recovery entry already derives A1 — this
   is the same operation, reached from a different place and with different copy (*make this device an
   owner*, not *recover your realm*).
2. **An intentional transfer during pairing** — an explicit extra step, off by default.

⚠⚠ **Never automatic, and never bundled into "approve" — and the warning must be much stronger than
"added exposure".** Per §5b.9, **an owner device can never be removed from the realm**. The step is
not a convenience toggle; it is closer to a "do you understand what this means" moment, and the copy
has to say the irreversible part out loud.

### 5b.5 Where it lives in the flow *(suggestion)*

The pairing flow already ends with the joiner unlocking; the recovery-phrase entry already exists as a
screen. Suggested integration:

```
… pair → keys installed → joiner unlocked
                              ↓
                    [ optional, explicit ]
        "Make this an owner device too?"           ← default: NO
                              ↓ user opts in
        reuse the existing recovery-phrase entry
        (relabelled: "owner", not "recover")
                              ↓
              device derives A1 → primary
```

⚠ Reuse the screen, **not** its framing. Today every phrase surface is worded as disaster recovery —
*"if you've lost access to all your devices"*, *"the only way to recover your data"* — so a user who
still has working devices reads the prompt as something that has gone wrong.

### 5b.6 What the UI owes, beyond the step

- ⭐ **An "Owner" badge on the device list.** Which devices hold A1 is currently invisible, and it
  decides who can add, remove, self-repair — and, per §5b.9, who cannot be removed at all. The badge
  is not decoration: it is the only place a user can see that a device is unevictable. ⚠ It must
  tolerate several (§5b.2).
- ⚠⚠ **An honest empty state: "no owner device".** `masterIdentity()` already returns null in exactly
  that case, so this is checkable state, not a guess. Copy along the lines of *this device can sync,
  but adding or removing devices needs your recovery phrase* — the realm is in a materially different
  state and today nothing says so.
- **Re-framed phrase copy.** `recovery_phrase_warning` currently says only *"the only way to recover
  your data if you lose access to all your devices"*. It should also say what the phrase **is**: what
  proves you own this account, and what makes a device able to add and remove devices. ⚠ The single
  string that connects phrase → master key today is `recovery_entry_slow_note`, an aside explaining
  why a spinner is slow.

### 5b.7 New hardware does **not** inherit ownership — and that is deliberate

The natural expectation is *"my phone was restored from backup, so it is still my phone."* It is not,
and the copy has to say so before the user is standing in front of a fresh device.

Every Keychain item Tenshu writes is **`…ThisDeviceOnly`**:

| where | attribute |
|---|---|
| `SecurityFrameworkKeychain.kt` (iOS) | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `…WhenPasscodeSetThisDeviceOnly` |
| `ControlSessionProvisioner.kt` (iOS EPC) | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| `KeychainHelper/main.swift` (macOS) | `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` |

`ThisDeviceOnly` items are excluded from iCloud Keychain sync **and** from encrypted backups, and
nothing sets `kSecAttrSynchronizable` — the flag that would opt an item into iCloud sync — anywhere.
So the two cases behave very differently:

| | outcome |
|---|---|
| **same device, app reinstalled** | the item survives the uninstall, the identity resumes — this is the case [`ios-identity-reinstall-gate.md`](ios-identity-reinstall-gate.md) exists for |
| **new device, restored from backup** | the identity does **not** come across; Tenshu sees a brand-new device that must pair in |

⚠⚠ **Do not "fix" this by making the items synchronizable.** That would place the realm's
**unrotatable** master identity in Apple's custody and land it on every device signed into that
account — a hole no amount of relay-side zero-trust compensates for, and unrecoverable rather than
merely bad, because A1 cannot be rotated (§5b.1).

**So the intended route on new hardware is the recovery phrase**, exactly as in §5b.4 — the user
enters it and that device derives A1 and becomes an owner. Nothing is transferred; it is re-derived.

⭐ This is also the clearest statement of what the phrase is *for*, and the UI does not currently make
it: **the phrase is not a backup of your data — it is the only thing that survives hardware.** A user
who understands that will keep it properly; one who reads today's copy (*"the only way to recover your
data if you lose access to all your devices"*) files it with disaster documents and is surprised twice
— once on a new phone, and once when they are told a working realm needs it to add a device.

### 5b.9 ⚠⚠ An owner device cannot be removed — and that is the concept's real cost

An owner device never meets the born-revoked problem, because **it never needs re-admitting**. A1 is
exactly what separates *"can re-assert its own relay registration"* from *"must be re-admitted by
another device"* (`RealmRegistrar`): `reregisterThisDevice` signs with A1, and an A1-rooted admission
presents **no cert chain** (`presentedChain` is null when a master key signs) — so there is no chain
for the revocation ledger to gate.

⚠ Consistent with what was observed 2026-08-18: the desktop was revoked and could *not* self-repair,
precisely because it is not an owner.

**The corollary is the important half.** "Remove device" on an owner is theatre: the row is dropped and
the device puts it back on its next tick. Therefore:

- making a device an owner is **effectively irreversible**;
- a lost or stolen owner device (unlocked) is a **realm compromise with no eviction available**;
- the only remedy is **T-K-04 identity rotation** — *"generate a new master identity, re-pair all
  devices under it, revoke the old one, and re-encrypt data"* — which is abandoning the realm and
  rebuilding it, since `ownerId = sha256(masterPublicKey)`.

⚠ Reasoned from the code paths and `RealmRegistrar`'s contract; **not yet tested** by revoking an
actual owner device.

#### Why Signal and Matrix are not in this position

Both have a **server-side account that outranks every device**, so eviction is always available:

| | identity root | evicting a stolen device |
|---|---|---|
| **Signal** | phone number + PIN | re-registering on new hardware **deregisters** the old install |
| **Matrix** | account + password | log the device out from another session; its access token dies |
| **Tenshu** | a key (A1), no account | ⚠⚠ **nothing outranks an A1 holder** |

That is the price of the zero-trust, no-account design, not an oversight — but it means **rotation is
our substitute for the account-level eviction they get for free**, and today we do not have it.

#### What the app-lock does and does not buy

⚠⚠ **"Require unlock" is a UI curtain, not a key gate.** It is on by default, but its own contract is
explicit: it gates *viewing Control's UI, never keys*, and background sync is unaffected. The hard
gate — **"Encrypt on unlock only"**, which wraps the recovery phrase behind a passphrase so a locked
cold start actually seals the realm — is a **child setting and off by default**. So in the default
configuration A1 is headless, and a stolen *unlocked* device yields the realm.

⭐ **Recommended follow-on, out of scope here:** an explicit **identity-rotation wizard** — a
deliberate, checkbox-gated flow that mints a new master identity, re-pairs the surviving devices, and
carries the data forward, together with rotation of the account credentials on the commerce side.
Until it exists, "my owner device was stolen" has no answer, and §5b.4's warning is the only thing
standing between a user and that state.

### 5b.10 What this is *not*

- ⚠ **Not capability sharing, and not sharing A1 by default.** Delegable powers already have a
  mechanism (A33). This is about the powers deliberately kept at the root.
- ⚠⚠ **Not a case for delegating revocation.** A delegate that can revoke can evict everyone —
  including the devices that would remove it — which is precisely the bound the conservative-subtree
  rule exists to keep.
- ⚠ Not a new cryptographic construct: multi-holder A1 works today, unchanged. **This is a product and
  copy change, not a protocol change** — which is why it can ship before any of R1–R5.

### 5b.11 ⚠⚠ A1 lives in the **phrase**, not in devices — so never guard it with a headcount

Written 2026-08-21 after a realm on this project lost its master identity. The sequence is worth
recording because three successive diagnoses were wrong, and each was wrong in a way the design
invites.

#### What actually happened

The realm's A1 holder was an iPhone. RG-1's "Start fresh" discarded it — with a confirmation that
correctly said the device would not get its name or settings back, and could not say the one thing
that mattered: **it was the only device that could ever admit another.** The desktop that remained
is a joiner (`sync_joined_funded_realm=true`), and ⭐ **a joiner never receives the phrase** —
`PairingKeyBlob` carries derived keys, the realm encryption keypair and an A33 capability, and no
master key. So nothing deleted anything; the phrase was never there.

⚠ Two wrong turns worth naming, because both are natural:
* *"Something deleted the desktop's phrase."* It never had one. `discardSupersededRealm()` looked
  like a culprit — it does remove *"own-genesis A1 material"* — but its guard is
  `priorOwnerId != ownerId`, and this device never held a prior realm.
* *"Then archive the phrase instead of deleting it."* Solving a problem that did not occur, at the
  cost of hoarding full-compromise material for realms the user deliberately left, plus an at-rest
  A1 change owing a threat-model amendment.

#### ⚠⚠ And do not replace it with "is another A1 holder alive?"

The obvious repair — have the relay count A1 holders and warn on the last one — **fails in the
dangerous direction.** A device that vanishes without telling anyone (a lost phone) stays registered
forever, so the count is an **overcount**, and the warning is suppressed *precisely* when the user is
down to their last reachable device: they discard it, the relay reports another holder that is at the
bottom of a canal, and the realm dies silently.

⭐ This is the constraint `tech-debt.md` already states for reaping orphaned rows, reached from the
other side: *"an offline device and a dead identity are indistinguishable from the outside."* Any
design that asks the relay to distinguish them inherits the ambiguity. `DeviceLastSeen` (that path's B
half) makes it **visible**, which is the most that signal can honestly do; it does not resolve it.

#### ⭐ The reframe: devices are caches of the phrase

A1 is not something devices *hold* — it lives in the phrase, and `deriveMasterKeyPair(phrase)`
regenerates it on any device forever, while Argon2id being one-way means the reverse never works
(threat model A9: *"the phrase cannot be reconstructed from the stored keys"*). A lost phone holding
A1 is worthless once the phrase is gone; the phrase alone suffices even if every device burns.

So the question worth asking is not *"does another device still hold A1?"* — unanswerable, and its
answer is a lie exactly when it matters. It is **"does the user have the phrase, outside the device
fleet?"**

#### What to build

1. ⭐ **The Owner badge (§5b.6) stays** — "this device holds the master identity" is *locally* knowable
   and true, needs no relay round trip, and no inference about other devices.
2. ⭐⭐ **Gate destruction on the phrase reveal, not a headcount.** Any flow that destroys A1 material
   — RG-1's "Start fresh", "Forget this device", a future identity rotation — shows the phrase at
   that moment and requires an explicit acknowledgement that it is saved. This is **RG-2**, and this
   incident is the argument that it is not optional.
   ⚠ It cannot verify the user wrote it down; nothing can. What it does is put the one recoverable
   thing in front of them at the last moment it exists, which a count can never do.
3. ⚠ **Drop the relay-side last-holder check.** It was answering an unanswerable question.
4. ⚠ **The copy in `recovery_phrase_warning` is part of the failure.** *"the only way to recover your
   data if you lose access to all your devices"* files the phrase with disaster documents; §5b.7's
   framing — *not a backup of your data, the only thing that survives hardware* — is what makes a
   user save it. ⭐ And the screen itself was silently broken until the recovery-phrase screen's dead controls were fixed: on a device without a
   phrase the reveal button did nothing at all, so a user could not distinguish "no phrase here" from
   "broken feature", and stopped looking.

⚠⚠ **None of this recovers a realm that has already lost its phrase.** A1 is unrecoverable by
construction, which is the point of the design; what is fixable is only whether the next user is
told before the last copy goes.
### 5b.12 ⭐⭐ Making "a realm with no A1" unreachable — four links, and the safety net has a false premise

§5b.11 records how a realm lost its master identity. This is what to build so a real user cannot
reach that state. ⚠ The distinction that matters throughout: **a device with no A1 is normal** —
every joiner has none, by design. The failure is a *realm* where no device has it.

⚠⚠ And note where the harm lands. "Start fresh" is **safe for the device performing it** — it gets a
clean slate and can create a new realm. The damage falls on the devices **left behind**: joiners in a
realm that can now never admit anything. The dialog offers a device-local action whose realm-wide
blast radius is invisible.

#### 5b.12.1 ⚠⚠ The root cause: the recovery banner switches off when a second device is paired

`RecoveryAffordanceViewModel` (`onboarding-low-friction.md` §7.2, Level 0) shows its
"your data is at risk" banner only when the roster holds **exactly one device**, reasoning:

> *"multi-device realms have a second copy → the recoverability story is already covered"*

⭐⭐ **That premise is false.** A joiner never receives the phrase — `PairingKeyBlob` carries derived
keys, the realm encryption keypair and an A33 capability, and **no master key**. A two-device realm
whose second device was paired in has **one** copy of A1, not two.

So the banner turns itself off at exactly the moment it wrongly concludes the user is safe, and it
offers *"Add another device"* as a **resolving action for a risk that action does not resolve**. That
is the whole failure in one line, and it is reachable by every user who pairs a second device before
saving their phrase — which the low-friction onboarding deliberately encourages them to defer.

**Fix:** key the affordance on *"has the user saved the phrase?"* — local, unfalsifiable, and
unaffected by pairing. Device count answers a different question. ⚠ "Add another device" may remain
as an action; it must not dismiss the recoverability state.

⚠ **Shipped, then finished 2026-10-30.** The trigger moved but the *copy* did not: the banner kept
saying *"exists only on this device and isn't synced anywhere"* and kept *"Add another device"* as its
primary button, on realms that synced. The action is now removed and the wording follows the realm's
synced `hasStorage` (`onboarding-low-friction.md` §7.2 as-built note).

#### 5b.12.2 ⚠ Route destruction through the reveal — do **not** block it

An earlier draft of this section said to *block* destroying the last A1 while the phrase is unsaved.
**That is wrong**, and the counter-case is ordinary: a user with a broken or sold phone who *must*
wipe it would be stuck with no way out, and a refusal teaches nothing.

⭐ Instead, when the device holds the realm's **only** A1, the destruction flow routes through the
phrase reveal. The user can always continue — they simply cannot do it *unknowingly*, and being shown
the phrase **resolves** the risk rather than deferring it. Applies to RG-1's "Start fresh", iOS's
"Forget this device", and any future identity rotation.

⚠ The copy must name the realm-wide consequence, not just the device-local one: *"this is the only
device that can add new devices to your realm"*.

#### 5b.12.3 The four links, and what breaks each

| # | Link in the chain | Break it with |
|---|---|---|
| 1 | Onboarding lets the phrase go unsaved | Keep the deferral (friction is deliberate) — but see 2 |
| 2 | The banner stops nagging once a 2nd device is paired | §5b.12.1 — key on *phrase saved*, not device count |
| 3 | Destroying the last A1 says nothing realm-wide | §5b.12.2 — route through the reveal (RG-2) |
| 4 | Desktop has no "Forget this device" at all | Give it one — `wipeDeviceState` is iOS-only, so the desktop escape hatch is `rm -rf` on a data directory |

⭐ What is deliberately **not** on that list: any headcount of A1 holders, or any relay-side check.
§5b.11 established those are unanswerable — a vanished device stays registered forever, so the count
over-reports precisely when it matters. Every break above uses only what a device knows locally:
*do I hold A1*, and *has the user confirmed saving the phrase*.

#### 5b.12.4 ⚠ Adjacent finding: a fresh realm needs a fresh entitlement

Measured 2026-08-21 on the live relay: **`ENTITLEMENT_ENFORCE=true`**. `/sync/log` requires a valid
entitlement for the authenticated `ownerId` (`SyncRouting` §`requireEntitlement`), so a realm created
with a new `ownerId` **402s on push** until commerce mints an entitlement for it — the existing
`tenshu/identity/entitlement` row is bound to the old owner.

⚠⚠ This matters for the *recovery* story, not only for billing: a user told to "start a new realm"
after losing their phrase lands in a realm that pairs fine and silently syncs nothing, which reads as
a bug rather than an unfinished setup. Whatever flow tells a user to start over owes them this step.

**⭐ The re-mint mechanism EXISTS and is reachable** (traced 2026-08-21). `CommerceStore.bindOwner`
is re-bindable by design, and its KDoc names this exact case: *"This is what makes purchase recovery
after recovery-code loss work (the old `ownerId` is dead; the new one inherits the entitlement)."*
The reachable path is the OTP sign-in — `POST /commerce/account/verify` carries `{email, code,
ownerId}`, and `AccountRouting` then *"(re-)bind[s] the owner (supersede, §3.4), grant[s] the Free
entitlement, open[s] a session."* The client sends its current owner (`CommercePublisherClient`), so
signing in after creating a new realm re-points the account to it.

⚠ **But there is no discovery path.** The rebind is a *side effect* of signing in; nothing anywhere
tells a stranded user "sign in again and your purchase follows you". A mechanism no user can find is
not a recovery story — and the user in §5b.11 would have had no reason to believe it existed.

#### ⚠⚠ 5b.12.5 The paid tier reverts the recovery at the next renewal

Code-traced 2026-08-21, **not yet observed against a live renewal** — treat as a claim to verify, but
the path is unambiguous.

`client_reference_id` / `metadata[owner_id]` are written **once, at checkout**
(`StripeClient` §`append("client_reference_id", request.ownerId)`). **Nothing ever updates them** —
there is no update-metadata call anywhere in `:commerce-service`. So a subscription keeps its
checkout-time owner forever.

After an OTP rebind to a new realm, the next renewal webhook runs:

```kotlin
(obj.clientReferenceId ?: obj.subscriptionAwareMetadata("owner_id"))
    ?.takeIf { it != store.liveOwner(account.id) }      // stale old ≠ new  ⇒ TRUE
    ?.let { store.bindOwner(account.id, it, nowMs) }    // rebinds BACK to the dead owner
```

⭐⭐ …and **silently reverts the recovery**. The account re-binds to the realm the user can no longer
use, their working realm starts answering `402`, and the trigger is a billing cycle rather than
anything they did — so the failure arrives days or weeks detached from its cause.

⚠ The `takeIf { it != liveOwner }` guard was added to stop *renewal churn* in the A30 history. It is
correct for that purpose and is exactly what makes this case fire: a genuine re-bind and a stale
webhook are indistinguishable at that line, because both are "different from the live owner".

**Fix direction (not decided here):** either update the Stripe subscription's metadata when
`bindOwner` supersedes a live binding, or make the webhook path bind only when the account has no
live owner — i.e. treat checkout metadata as *initial*, never *authoritative*. ⚖️ The second is
cheaper and needs no Stripe write, but changes what a legitimate owner change at checkout means.

⭐ Free-tier recovery is unaffected: the OTP path grants `ProductCatalog.FREE` directly and no webhook
contradicts it.

## 6. Migration and compatibility

* New domain tag `tenshu-admit-revoke-v2`; ⚠ **never reuse a tag** — a v1 signature must not verify as
  v2.
* Both revocation forms verify forever; v1 records keep the epoch rule.
* Relay `admission_revocation` gains a nullable `revoked_a33_pubkey` column (a migration; additive,
  so no `RELAY_PROTOCOL_VERSION` bump — the envelope does not change).
* ⚠ `MembershipChainVerifier.isRevoked` carries the **identical** `revocation.epoch >=
  capability.epoch` predicate for shared-realm membership. Any fix must cover both or the two
  verifiers drift — see the tech-debt entry.
* House Rule #2: A33 semantics change ⇒ threat-model amendment in the same PR.

## 7. Phasing  *(rewritten for F, 2026-08-19)*

⚠ The v0.2 phasing was E-shaped — A′ key capture, then the A1-rooted re-admission rule. **None of it
is needed under F**, which is most of why F was chosen. Kept here only as the record of what F
avoids buying.

1. **R1** — *done, and it is what falsified A.* At revoke time, can the revoker resolve the target's
   `AdmissionKeyAnnouncement`? Measured on device; §3.1 has the result.
2. **F1 — delete the ordering.** Replace `revocation.epoch >= cert.mintedAtEpoch` with **set
   membership**: a cert whose subject `deviceId` appears in the revocation ledger is invalid, full
   stop. This is sound *only* under F — the comparison exists to distinguish "minted before the
   revocation" from "minted after a re-admission", and F abolishes the second case. `isRevoked`,
   `AdmissionChainVerifier`.
3. **F2 — the relay refuses re-admission outright.** An admit naming a `deviceId` present in
   `revocations(ownerId)` is rejected, with no epoch arithmetic. **Threat-model amendment here**
   (House Rule #2).
4. **F3 — ⚠ withdrawn.** This was "the orphan reaper"; §4.3 records why it was never needed. The
   prerequisite is **RG-1**, which sequences *before* F1 rather than after — see the ordering note.
5. **F4 — ⚠ SUPERSEDED by §7f (audited + landed 2026-08-21).** The mirror was already there: membership revocations were *already* key-scoped, because a fresh A26 is minted per grant. F2′ must **not** be mirrored (un-share is not identity resurrection). What the audit found underneath was a **capability signature that verified as a revocation** — closed with domain tags, `RELAY_PROTOCOL_VERSION` 1 → 2, threat model **T-GR-07**.
6. **F5 — ⚠ SUPERSEDED by §7e (audited 2026-08-21): the seed CANNOT be deleted.** It feeds
   `Device.admittedAtEpoch`, which T-K-02 enforces at **merge** on every peer — a client-side
   control F1′/F2′ do not replace. F5 becomes F5a (persist the seed) / F5b (retire the fallback) /
   F5c (⚖️ move the merge check off the epoch). Original text kept below for the record:
   ⭐ Once F1/F2 are in, check whether `seededRealmEpoch` can be **deleted rather than repaired**: it exists to make the
   post-revocation epoch visible to later admissions, and its known defect is that it is in-memory
   and lost on restart. If nothing security-relevant reads the epoch any more, that open bug
   dissolves instead of needing a durability fix. ⚠ Claim to verify, not a finding — the epoch has
   other readers and each needs checking.

⚠⚠ **RG-1 ships before F1**, not before F2 only. Both halves of F refuse a returning identity — F1
on the client's cert check, F2 at the relay — so either one alone is enough to turn an iOS reinstall
from "self-heals" into "refused with no explanation". The gate that names the situation has to exist
first.

⚠ F1 and F2 are one semantic change on two sides of the wire and should land together or
version-gated; a relay that still compares epochs while clients have stopped writing them is the
kind of skew House Rule #9 exists for.

⚠ **CORRECTED by §7c.4 — the opposite is true.** F2′ makes the ordering guard *more* load-bearing,
because a stale retirement would write a permanent removal-ledger entry for a live device. The
paragraph below is retained as the record of the wrong call.

⭐ ~~F also retires the 2026-08-18 relay-side ordering guard~~ (`tech-debt.md`, the stale-retirement
incident). That guard exists because a queued `Device` tombstone could land on a device that had
since been *re-admitted under the same identity*. Under F that re-admission cannot happen, so the
stale instruction can only ever name an identity that is already permanently gone. ⚠ Verify before
deleting anything — the guard is cheap and the analysis above is a claim, not a measurement.

## 7b. ⚠⚠ F1 is mis-specified — and the epoch broke production twice while we found out

Two things happened on hardware on 2026-08-19, in the same hour. Together they say the phasing in
§7 is wrong in a way that would have shipped a regression.

### 7b.1 The live 403 — the epoch is not durable, and a restart is enough to lose it

Pairing an iPhone into the realm failed on the desktop with **403 "Admission capability has been
revoked"**, immediately after a routine app restart.

`RevokeDevice` seeds the realm epoch to `epoch + 1` so later certs are minted *above* any revocation.
⚠⚠ That seed is `private var seededRealmEpoch: Long = 0L` — **in memory only**:

```kotlin
override val currentRealmEpoch: Long
    get() = maxOf(realmEpochProvider(), seededRealmEpoch)
```

A restart drops it, `realmEpochProvider()` falls back to the tombstone-derived count, the device
mints its A33 cert *below* the revocation naming it, and `revocation.epoch >= cert.mintedAtEpoch`
matches — **born revoked**, again. Nothing warns; the only symptom is a 403 at the moment a user
tries to pair.

⚠ Two plausible explanations were ruled out by evidence before landing on this one: the desktop is
**not** in the wiped device's lineage (its `Device` row carries no `admittedByDeviceId` — it is the
realm root), and the wipe's own `RevokeDevice[R1]` log line shows the computed subtree was a single
member, the iPhone itself.

⭐ This is the third time in one day the realm epoch has broken something real, which is the case for
removing it as a security input rather than making the seed durable.

### 7b.2 ⚠⚠ But F1 as written would break the re-mint path — so **F does need A′ after all**

§4.3 says F "needs no key capture (A′)". **That is wrong**, and §7's F1 inherits the error.

F1 replaces `revocation.epoch >= cert.mintedAtEpoch` with set membership on `deviceId`. That is
sound for *device removal*, which is what F reasons about. It is **not** sound for the other thing
this same predicate governs: **Q3 conservative subtree revocation**, where removing a device also
strips the admission capability of everything it transitively admitted —
*"Subtree members are admission-revoked but **not** removed: they stay visible (with lineage) in
Manage devices"* (`RevokeDeviceImplementation`).

Those devices are still realm members, and the way they recover is an A1-holder re-mint, which works
**precisely because a fresher epoch clears the old revocation**:

```kotlin
// AdmissionMigrationObserver: adopt only improvements: first chain, a flatter chain (re-root),
// or a fresher leaf epoch (post-revocation re-mint).
chain.first().mintedAtEpoch > own.first().mintedAtEpoch
```

⚠⚠ Under set membership that re-mint can never clear the revocation: the new cert names the same
`deviceId`, so a member conservatively revoked for someone else's removal would lose admission
authority **permanently**, with no path back short of becoming a new device. That is a regression, not
F's intent — F is about *removed identities*, and these devices were never removed.

⭐ **The fix is the one option A′ already proposed: name the capability, not the device.** A revocation
that names the A33 **key** is cleared by a re-mint for free, because a re-mint produces a new key the
old revocation does not name — no epoch comparison, and no permanence where none was meant.

### 7b.3 Corrected sequence

- **F0 = A′** — capture the admitted device's A33 key at admission (schema bump + migration, House
  Rule #9). ⚠ It is F's prerequisite too, not only E's.
- **F1′** — match revocations on the **capability key**, not `(deviceId, epoch)`. Deletes the epoch
  comparison *and* keeps re-mint working. ⚠ Devices admitted before A′ shipped have no captured key,
  so the epoch rule stays as a fallback for them (§4's option A) — this is the one place the two
  rules must coexist, and §7 F5 is where the fallback finally goes.
- **F2′** — the relay refuses re-admission by consulting a durable **removal ledger**, never
  `revocations(ownerId)`. ⚠ Added §7c: that list mixes removed devices with Q3 subtree members who
  were *not* removed, and today a removal is a hard `DELETE` leaving nothing to key on. Carries the
  threat-model amendment.
- **F4 / F5** — unchanged.

⚠ Do **not** implement §7's F1 as written. It is retained above as the record of what was wrong.

## 7c. ⚠⚠ F2 has the same defect as F1 — two revocation kinds share one ledger

Found 2026-08-19 while checking whether the end state composes. It does not, yet.

### 7c.1 `revocations(ownerId)` mixes two populations that must be treated oppositely

`RevokeDevice` POSTs **one `AdmissionRevocation` per subtree member**, and the subtree is *"the
removed device **plus** everything it transitively admitted"*. The record is:

```kotlin
class AdmissionRevocation(ownerId, deviceId, epoch, revokedAt, signature)
```

⚠⚠ **Nothing in it says which kind it is.** So one list holds both:

| population | correct treatment |
|---|---|
| the **removed** device | never re-admitted — F's whole point |
| a **Q3 subtree member**, never removed, still listed in Manage devices | capability **re-mintable** |

§7's F2 — *"refuse an admit naming a `deviceId` present in `revocations(ownerId)`"* — would bar the
second population permanently. ⭐ That is precisely the regression §7b.2 caught in F1, one phase
later and from the same root cause: **a device-scoped name standing in for two different facts.**

### 7c.2 The obvious discriminator does not exist — removal is a hard delete

The natural way to tell them apart is device removal itself. The relay cannot: `KnownDevicesStore`
/ `SqliteDeviceRegistry.revoke` runs

```sql
DELETE FROM device WHERE owner_id = ? AND device_id = ?
```

so after a removal there is **no record that the identity ever existed**. The relay cannot answer
*"has this deviceId been removed before?"* — which is exactly the question F's never-return rule
asks at admit time.

### 7c.3 F2′ — record the removal instead of discarding it

⭐ **This is much smaller than a new protocol.** The removal signal already arrives at the relay and
is already correctly ordered: `DeleteBlock.revokedDeviceId` → `revokeIfNotStale(ownerId, deviceId,
authoredAt)`. Nothing new has to be signed, sent, or agreed between client and relay. The only
change is that the relay currently **throws the fact away**.

F2′ adds a durable **removal ledger** — one row per removal, written where `revoke` deletes today —
and the admit path consults *that*, never `revocations(ownerId)`:

* **removal ledger** → F's "this identity never returns"
* **capability key** (F1′) → "this capability is dead; a re-mint issues a new one"

⚠ House Rule #1 is untouched: the rows are `(ownerId, deviceId, removedAt)`, both ids the relay
already stores in `device`. No user data, no decryption. It still owes a threat-model amendment
under House Rule #2 — it is a new durable identity record with a new retention story.

### 7c.4 ⚠⚠ Two consequences, and one correction to §7

* **The ledger can never be garbage-collected.** Deleting a row re-admits that identity, which is
  the one thing F promises not to do. Growth is one row per removal — trivial in size, permanent in
  principle, and it should be *documented* as unbounded rather than discovered later.
* ⚠⚠ **§7's ⭐ note is too fast.** It says F "retires the 2026-08-18 relay-side ordering guard". It
  does not — **F2′ needs that guard**, because the ledger write is exactly where a stale retirement
  does permanent damage: a tombstone delayed by hours (401/402) would otherwise bar a device that
  was legitimately re-admitted in the meantime, and under F that bar is forever. The guard becomes
  *more* load-bearing under F, not less. ⚠ Devices admitted before F ships are precisely the
  population this protects.

### 7c.5 Sequence

**F2′ replaces F2.** It stays after F1′ and still carries the threat-model amendment.

### ✅ 7c.6 As-built (2026-08-21)

* **Table** `removed_device (owner_id, device_id, removed_at)`, relay migration 15.
* **Written from `revokeIfNotStale` only** — an applied `Device` tombstone. ⚠⚠ Deliberately **not**
  from `revoke`, which `MembershipGrantRouting` calls for shared-realm *member* removal. Putting
  both in one ledger is the conflation this section exists to avoid, and it was one line away from
  happening again — pinned by
  `RemovalLedgerTest.aDirectRevokeDoesNotRecord_becauseMemberRemovalIsNotDeviceRemoval`.
* **Enforced on BOTH admit paths.** ⚠ The join flow and the offer flow each register a device, and
  the offer flow is precisely the route a returning device uses. A rule enforced on one of two
  equivalent routes is not enforced.
* **A refused (stale) tombstone records nothing** — §7c.4's requirement, pinned by
  `aStaleTombstoneRecordsNothing`. Under F a wrongly-written bar never lifts.
* **Threat model**: T-K-08 (House Rule #2).
* ⚠ **Observed while testing: a refused re-pair leaves its pending request behind.** The relay
  rejects at *registration*, after the request was posted, so the host keeps showing a pending
  device that can never be approved. Not chased here — the request expires — but it is a rough edge
  the refusal creates, and worth a line of copy or a sweep before this reaches users.
* ⚠⚠ **`/sync/admit-device` is deliberately NOT guarded.** It requires a master delegation, so only
  an A1 holder can use it — which is §5b.9's documented asymmetry (an owner device is unevictable),
  not a hole F2′ opens. Guarding it would change owner-device evictability, and that is a separate
  decision, not something a ledger should do silently.

⚠ Still outstanding for the track: **F5b** (retire the epoch fallback on the admission path) and
**F5c** (⚖️ move T-K-02's merge check off the epoch). F4 is closed by §7f; F5a has landed, and
§7e is why the seed had to be repaired rather than deleted.

## 7d. ⚠ Hardening found while building F1′ — the signed messages have no length prefixes

`AdmissionRevocation.message`, `keyScopedMessage` and `AdmissionCapabilityCert.message` are bare
concatenations:

```
DOMAIN_TAG || ownerId || deviceId || epoch || revokedAt [|| a33PublicKey]
```

⚠⚠ With variable-length `ownerId`/`deviceId` and no length prefixes, **field boundaries are
ambiguous**: different field values can produce identical bytes. F1′ made this concrete — under a
*shared* domain tag, a v1 message for one device collides exactly with a v2 message for another,
and `deviceId` is chosen by the joining device. `KeyScopedRevocationTest` constructs that collision;
it is red if the tags are equalised.

⭐ **F1′'s distinct `tenshu-admit-revoke-v2` tag closes the v1↔v2 case**, which is the one that
matters today. It does **not** close the class: any future message added to this file inherits the
same ambiguity, and two shapes sharing a tag would collide again.

⚠ Not fixed here, deliberately — length-prefixing changes the signed bytes of an **existing**
master-signed message, so every stored cert and revocation would have to be re-verified under the
new encoding or grandfathered. That is its own slice with its own compatibility story, not a
drive-by. In practice today's ids are fixed-width (`ownerId` is a sha256 hex, `deviceId` a UUID),
which is why this has never bitten — but nothing *enforces* that, and the safety currently rests on
a convention rather than the encoding.

⭐ Discovered by mutation-testing, not review: the first two tests written for the domain tag both
passed with the tag removed. A guard that cannot fail proves nothing, and the third attempt is what
turned an assumption into a demonstrated property.

### 7d.1 ⚠⚠ Sequencing: this cannot ship to a realm that has lost A1 (traced 2026-08-22)

Length-prefixing changes the bytes of every **master-signed** record, because `signedMessage()`
recomputes them from the stored fields. Two populations are invalidated, and the second is fatal:

* **Relay revocations** go inert — `isRevoked` needs the signature to verify, so they are ignored.
  That **fails open**, the same direction F5b is blocked on (§7g). Production holds 7. Repairable by
  deleting the rows, which is a repair only available while the fleet is one person's.
* **Stored admission certs** stop verifying, and `SyncRouting.kt`'s admit path has exactly **two**
  authorities: a cert chain rooting in A1, or a direct A1 signature. Nothing else. So on a realm with
  no A1 holder the second path is *already* dead, the first is the only one left, and invalidating
  the certs closes it. **That realm can then never admit another device.**

⚠ Re-minting a cert needs its parent authority — A1 for the root. A realm that has lost A1 cannot
re-mint, so this is not "expensive", it is one-way. Exactly the shape that made F5b undeployable, one
level earlier in the same file.

**Therefore: §7d ships only to a realm whose A1 is held.** On this project's realm that means *after*
the fresh-realm rebuild, never before.

⭐ Worth separating from §7d entirely: the trace above shows this realm's device admission **already**
rests solely on the delegated cert chain, because the direct-A1 path died with A1. That is a live
single point of failure, independent of any encoding change.

### ✅ 7d.2 As-built (2026-10-28) — shipped with the production reset

§7d.1's precondition was met the only way it could be: the complete production reset wiped the
relay and every client store, so every realm is fresh and its A1 is held. Nothing stored had to be
re-verified or grandfathered.

**Encoding** — one function, `lengthPrefixedSignedMessage` (`SignedMessageEncoding.kt`):

```
LP(x)   = u32be(|x|) ‖ x
message = LP(tag) ‖ LP(field₁) ‖ … ‖ LP(fieldₙ)        — strings UTF-8, Longs 8-byte BE
```

Every field is prefixed, the fixed-width ones too: a rule with no exceptions has nothing to get wrong,
and the 4 bytes per `Long` are free. The tag is prefixed as well, so no tag can be a byte-prefix of
another message.

| Message | Before | After |
|---|---|---|
| A33 cert | `"tenshu-admit-cap-v1" ‖ ownerId ‖ a33Pub ‖ deviceId ‖ mintedAtEpoch` | `LP("tenshu-admit-cap-v2") ‖ LP(ownerId) ‖ LP(a33Pub) ‖ LP(deviceId) ‖ LP(mintedAtEpoch)` |
| A33 revocation | `"…-revoke-v1" ‖ ownerId ‖ deviceId ‖ epoch ‖ revokedAt` **or** `"…-revoke-v2" ‖ … ‖ a33Pub` | `LP("tenshu-admit-revoke-v3") ‖ LP(ownerId) ‖ LP(deviceId) ‖ LP(epoch) ‖ LP(revokedAt) ‖ LP(a33Pub)` — one form (F5b) |
| Announcement (A4) | `"tenshu-admit-announce-v1" ‖ deviceId ‖ a33Pub` | `LP("tenshu-admit-announce-v2") ‖ LP(deviceId) ‖ LP(a33Pub)` |
| Member capability | `"tenshu-member-cap-v1" ‖ realmId ‖ authPub ‖ epoch ‖ issuedAt` | `LP("tenshu-member-cap-v2") ‖ LP(realmId) ‖ LP(authPub) ‖ LP(epoch) ‖ LP(issuedAt)` |
| Member binding | `"tenshu-member-bind-v1" ‖ realmId ‖ deviceId′ ‖ devicePub ‖ boundAt` | `LP("tenshu-member-bind-v2") ‖ LP(realmId) ‖ LP(deviceId′) ‖ LP(devicePub) ‖ LP(boundAt)` |
| Member revocation | `"tenshu-member-revoke-v1" ‖ realmId ‖ authPub ‖ epoch ‖ revokedAt` | `LP("tenshu-member-revoke-v2") ‖ LP(realmId) ‖ LP(authPub) ‖ LP(epoch) ‖ LP(revokedAt)` |

**Decision — the tag suffixes moved.** On a fresh realm keeping `-v1` would have been safe in
practice (a prefixed message cannot parse as an unprefixed one with a hex/UUID first field), but a
tag names one byte layout, and reusing it for another makes "which encoding is this?" depend on
parse luck. Bumping costs nothing here and turns a stale build's signature into a plain mismatch.
`tenshu-admit-revoke-v1`/`-v2` are both retired, hence `-v3`. Pinned by
`SignedMessageEncodingTest.everyTagIsDistinctAndNoRetiredTagIsReused`.

**Tests** (`SignedMessageEncodingTest`, `commonTest`, so JVM in the gate and iOS under
`iosTestCheck`): exact message-byte vectors for all six; real-backend Ed25519 signature vectors
(`cryptoBackendsModule()` — BouncyCastle on JVM, libsodium on iOS — never the bootstrap stub), JVM-
generated, which iOS must reproduce; and `aShiftedFieldBoundaryNoLongerVerifies`, which for six
adjacent-field pairs first asserts the *unprefixed* tuples concatenate identically (so it cannot pass
vacuously) and then that a signature over one does not verify over the other. Mutation-checked:
making `lengthPrefixedSignedMessage` a bare concatenation turns it red.

### ✅ 7d.3 B1 — the pairing/realm family was the same hole, one level up (2026-10-28)

Security review of the §7d change found the messages it had left out were the **blocker**, not a
residual. The new-device delegation `K ‖ deviceId ‖ epoch(8)` was untagged and unprefixed, and signed
by A1, an admitter's A33 or a shared-realm master — the very keys that sign `AdmissionCapabilityCert`
and `MembershipCapability`, which also end in an 8-byte field:

* **Reverse (trivial).** Split any A1-signed root cert C as `K = C[0:|C|−12]`,
  `deviceId = "\u0000\u0000\u0000\b"` (the 4-byte length prefix of C's last field), `epoch = C[−8:]`, and
  the old delegation bytes **were** C. Replayed at the unauthenticated `POST /sync/admit-device` it
  registered a junk device — which stalls the realm's tombstone GC (its ack never comes), and which the
  owner cannot remove (no `wasRealmMember` evidence). In a shared realm any member holds a cert.
* **Forward.** A joiner grinding its A33 key to valid UTF-8 (~2^26.6 keygens) could steer the host's
  approved delegation into a valid A1 signature over a cert it chose — an unrevocable capability.
  `ReadmitDevice` and `SharedRealmBootstrapper.admitKnownDevices` needed no grinding: A1 signed over a
  `Device.publicKey` of any length that any realm device can write.

**Fix — every message an A1 / A33 / A4 / shared-master key signs now starts with LP(a unique tag):**

| Message | Signer | Before | After |
|---|---|---|---|
| New-device delegation | A1 / A33 / shared master | `K ‖ deviceId ‖ epoch(8)` | `LP("tenshu-admit-delegation-v1") ‖ LP(K) ‖ LP(deviceId) ‖ LP(epoch)` |
| Realm bootstrap | A1 / shared master | `ownerId ‖ masterPub ‖ firstDevicePub ‖ firstDeviceId` | `LP("tenshu-realm-bootstrap-v1") ‖ LP(…)×4` |
| Recovery claim | A1 | `devicePub ‖ deviceId ‖ challenge` | `LP("tenshu-recovery-claim-v1") ‖ LP(…)×3` |
| Offer response | A4 | `status ‖ hash ‖ publicKeyA ‖ payload` | `LP("tenshu-offer-response-v1") ‖ LP(…)×4` (absent payload = empty field) |
| HLC tip | A4 | `ownerId ‖ deviceId ‖ physical(8) ‖ logical(8) ‖ hlcDeviceId` | `LP("tenshu-hlc-tip-v1") ‖ LP(…)×5` |

One builder each in `SignedMessageEncoding.kt`; the relay's `MasterDelegationVerifier.*Message` are thin
aliases, and the five client copies (`PairingAdmission`, `RealmRegistrar` ×3, `SharedRealmDeviceAdmitter`,
`SharedRealmBootstrapper`, `RecoveryClaimImplementation`) and both `longToBigEndianBytes` copies are
gone. The other things an A4 key signs start with a printable byte, which no LP message can: JWS inputs
(`eyJ…`) and the hub offer (`tenshu/pairing/hub-offer/v1` ‖ LP fields) — pinned by `A4SignedFamiliesTest`.

Two further walls, so no single one is load-bearing:

* `pairingDelegationMessage` **requires a 32-byte key** — no path can be asked to sign a delegation
  over an arbitrary blob — and `ReadmitDevice` / `admitKnownDevices` refuse a `Device.publicKey` that
  is not 32 bytes before getting that far.
* Every relay route that registers a device or names a key (`/pairing/request`, `/pairing/response`,
  the approved offer-response, `/admit-device`, `/realm`, `/recovery-claim`, `/realm-member/admit`,
  `/admission/revoke`) refuses keys that are not 32 bytes and `deviceId`s that are not 1–256 characters from
  `[A-Za-z0-9_-]` (`RegistrationInputRules`; every real id is a UUID or 64-hex, pinned by
  `RegistrationInputRulesTest`).

**Tests:** `CrossFamilySignatureTest` (a signature over each of ten families verifies as none of the
other nine; each starts with LP(its own tag); the cert-as-delegation split is reconstructed under the
old encoding and refused under the new; JVM signature vectors iOS must reproduce),
`AdmitDeviceRoutingTest.aCertsSignature_replayedAsADelegation_registersNothing`, 400s for 31-byte keys
and control-character ids on the routes, and
`ReadmitDeviceImplementationTest.aRowWhoseKeyIsNotAnEd25519Key_isRefused_andNothingIsSigned`.
⭐ Mutation-checked in layers: with the tag, the key-length rule *and* the id rule all removed, the
replay test registers a device (the attack, reproduced end to end); restoring the tag alone makes it
green again.

**F5c** (`admittedAtEpoch` inside the delegation) was **not** changed: still ⚖️ open (§7e.3). The
delegation's `epoch` is now simply one more prefixed field.

## 7e. ⚠⚠ F5 audited — `seededRealmEpoch` cannot be deleted, and the reason is not admission

§7's F5 reads: *"once F1/F2 are in, check whether `seededRealmEpoch` can be **deleted rather than
repaired**."* Audited 2026-08-21. **It cannot**, and the blocker is a reader the phase never
considered.

### 7e.1 The seed has two effects, and only one of them F1′ retired

`PairingAdmission` reads `syncManager.currentRealmEpoch` **once** and spends it twice:

| Effect | Status after F1′ |
|---|---|
| `cert.mintedAtEpoch` — what a revocation is compared against | ⭐ **Retired.** `AdmissionChainVerifier.strips` compares *keys* and ignores the epoch entirely when both sides carry one. |
| `Device.admittedAtEpoch` — the stamp on the new device's row | ⚠⚠ **Still load-bearing**, and not at the relay. |

### 7e.2 The blocker: T-K-02 is enforced at **merge**, on every peer

`SyncProcessor.isStaleDeviceBlock` drops a `Device` entity block whose `admittedAtEpoch` is older
than *the receiving device's* `currentRealmEpoch`. That is a **client-side, relay-independent**
control — F1′ and F2′ operate on the relay's admit path and replace none of it.

⚠⚠ So deleting the seed would silently weaken T-K-02: a device admitted **after** a revocation would
carry a *lower* `admittedAtEpoch` than peers who have seen the tombstone, and its own `Device` row
would become droppable on merge. The failure would look like a device that pairs, syncs, and then
intermittently vanishes from other devices' rosters — the hardest possible shape to diagnose.

⭐ The seed exists precisely to stop that: it makes the post-revocation epoch visible to the *next*
admission so the new row is stamped above the tombstone.

### 7e.3 What F5 actually becomes

Not a deletion. Three separable pieces, in order:

1. **F5a — make the seed durable.** The epoch-seed defect stands: `seededRealmEpoch` is in-memory and a
   restart drops it (§7b.1's live 403). Since it cannot be deleted, it must be **persisted** — which
   is what the incomplete fix should have done. ⭐ Note the 403 itself is already gone for post-F0
   devices via F1′; what durability now protects is the *T-K-02 stamp*, not revocation matching.
2. **F5b — ⚠⚠ BLOCKED by §7g (audited 2026-08-21).** Not a cutover — *undeployable on this realm*.
   All 7 production revocations are keyless and 3 of 4 live devices carry one, so removing the
   fallback fails **open** (T-K-01 restored) while the set-membership alternative fails **closed**
   (those devices could never admit again). Re-minting them needs A1, which this realm has lost.
   Shipped as guards pinning both directions; no behaviour change.
3. **F5c — ⚖️ decide whether T-K-02's merge check should key on something else.** The epoch is a
   coarse proxy for "this row predates a rotation the realm has seen". If it moved to the HLC
   ordering every other replica already respects, the epoch would have no readers left. That is a
   design question, not a cleanup.

⚠ **The `admittedAtEpoch` value is also inside the signed delegation message**
(`newDeviceSigningPublicKey || newDeviceId || epoch`), so removing the concept is a wire change to a
master-signed message — the same compatibility story as §7d's length prefixes.

⭐ Recorded because the phase as written would have been implemented as a deletion, and the audit is
the only thing standing between that and a merge-layer regression nobody would have traced back.

## 7f. F4 audited — the mirror was already there, and the real hole was underneath it

*2026-08-21. Landed with the F4 slice.*

F4 was one line of §7 — *"mirror F1/F2 into `MembershipChainVerifier` for shared realms"* — written
when F1/F2 still meant **set membership**: a revoked id is dead, full stop. §7b then replaced that
with F1′ (key-scoped matching) and §7c with F2′ (a durable removal ledger). F4's one line was never
revisited, so it instructed the next reader to mirror a spec that no longer existed. Auditing it
first, the way §7e audited F5, is what turned it up.

### 7f.1 F1′ was already satisfied — by an independent route

`MembershipChainVerifier.isRevoked` already matched a revocation to a capability on an **exact
`memberAuthorityPublicKey` match**. It reached the F1′ rule without F1′, because GR made a different
call for a different reason: `SharedRealmGrantManager.grant()` mints a **fresh A26 per grant**, and
its own comment says why — *"revocations are keyed per authority key, so a fresh A26 per grant never
collides with a prior member's revocation."*

So the thing F1′ had to engineer for devices — a way back after revocation — membership got for
free, because re-sharing issues a new key rather than reusing an old one.

### 7f.2 F2′ must **not** be mirrored — and that is a decision, not an omission

F2′ makes the relay refuse a removed **device identity** forever. Mirrored here it would make
un-sharing permanent: a member is a *different user*, and un-share-then-re-share is an ordinary
thing to do, not identity resurrection. ⚠⚠ The two tracks look symmetric and are not. F's "never
returns" is scoped to *my own device in my own realm*; nothing in it generalises to a second party.
Pinned by `MembershipChainVerifierTest.aFreshAuthorityKeyIsAdmittedDespiteAPriorRevocation`.

### 7f.3 The epoch conjunct was inert, and inert is not harmless

`revocation.epoch >= capability.epoch` sat beside the key match. Both sides are pinned to
`SharedRealmGrantManager.CAP_EPOCH`/`REVOKE_EPOCH` **= 0**, so it evaluated `0 >= 0` on every real
call: always true, never discriminating. Two ways that hurts, neither of which needs it to ever
fire:

* it reads as a second line of defence that does not exist, and
* `MembershipGrant.kt`'s own KDoc claimed the field carried *"the existing realm-state epoch"* —
  a live invitation to "finish" the wiring. ⚠⚠ Doing so would import §7b.1 wholesale: a client
  whose epoch counter lagged would mint *below* an existing revocation and be evicted permanently
  and silently. F5a had just finished making that same counter durable on the admission path.

Removed, with the KDoc corrected to say the field is reserved and unread. Pinned by
`theEpochDoesNotAffectTheVerdict`, which fails on the `0L` and `4L` cases if the comparison returns.

### 7f.4 ⚠⚠ The actual finding: a capability signature *was* a revocation signature

Under the inert epoch, the two messages were byte-identical:

```
capability = realmId ‖ memberAuthorityPub ‖ epoch(8) ‖ issuedAt(8)    — Ed25519(A1)
revocation = realmId ‖ memberAuthorityPub ‖ epoch(8) ‖ revokedAt(8)   — Ed25519(A1)
```

Same fields, same order, same signer, no domain tag on either — membership had **zero** tags where
admission has four. A capability with `issuedAt = T` is the same bytes as a revocation with
`revokedAt = T`, so its master signature verifies as a revocation of its own holder. No collision
search, no attacker-chosen field: a relabelling.

⚠⚠ The sharpest holder of a capability is the **relay** — it receives one in the body of every
`POST /sync/realm-member/admit`. So it could evict any member of any realm from material it
legitimately holds, which inverts the House Rule #1 posture for this surface: the relay verifies
master signatures, it never authors one. Threat model **T-GR-07**; T-GR-02's *"the relay cannot
forge a revocation"* was false until this slice and is corrected in place.

⚠ Bounded honestly: eviction (denial), not disclosure and not admission. And **not** cross-member —
`MemberAuthorityKey` carries the A26 *private* key, so it syncs across one member's own devices,
never to another's.

Fixed with domain tags on all three messages (`tenshu-member-{cap,bind,revoke}-v1`), pinned by
`MembershipMessageConfusionTest` — written in its final form first, watched fail on both
assertions, then made green.

### 7f.5 Cutover — measured, not assumed

Changing signed bytes on relay-verified routes is an auth-contract change, so
**`RELAY_PROTOCOL_VERSION` 1 → 2** (min stays **1**: v1 clients must keep doing ordinary sync, and
cutting them off over routes they have never successfully called would be a bigger break than the
one being fixed).

⚠⚠ **No dual-accept window for the untagged form** — that is the point, not an oversight;
continuing to accept untagged bytes is the confusion. Safe as a hard cutover because it was
*measured* so: production held **0** `member_revocation`, **0** `member_admission` and **0**
`grant_mailbox` rows. Shared-realm membership has never been exercised in production, so there is
no live signature to honour.

⚠ No schema bump: the tag lives inside the signed bytes, so `MemberAuthorityKey.capability`
(`epoch ‖ issuedAt ‖ signature`) changes *value*, never shape — `SchemaShapeBaseline` is untouched.

### 7f.6 What F4 leaves open

* ⚖️ **The relay owes a redeploy** before any shared-realm grant is attempted — a v1 relay rejects
  v2 signatures. Nothing else forces it, since no membership traffic exists.
* §7d's underlying weakness — **unprefixed concatenation** — is still untouched across both files.
  Tags make the *shapes* distinct; they do not make the *fields* unambiguous. Still a hardening
  item, now with one more file in scope.

## 7g. F5b audited — the fallback cannot be retired, and the reason is in production data

*2026-08-21. Landed with the F5b slice (guards only — no behaviour change).*

§7e sized F5b as *"retire the epoch fallback in `strips()`. Possible only once every device in a
realm carries a captured key (F0). ⚠ There is no way to know that from inside a realm, so this needs
a deliberate cutover, not a condition."*

That framing is right about the mechanism and wrong about the difficulty. There **is** a way to
know — the relay's `admission_revocation` table answers it directly — and the answer makes F5b
undeployable rather than merely delicate.

### 7g.1 The measurement

Production, 2026-08-21, one realm:

| | |
|---|---|
| `admission_revocation` rows | **7** |
| …carrying a `device_a33_public_key` | **0** |
| …**keyless** (the fallback's population) | **7** |
| Live `device` rows | 4 |
| …that are named by a keyless revocation | **3** |

⚠⚠ **F0 captures a key at *admission*. It does not retroactively key the revocations already
written.** So the keyless population does not drain as devices roll over — it is a permanent
sediment of every revocation minted before F0, and only a realm created *after* F0 has none.

### 7g.2 Both ways of retiring it are unsafe, and they fail in opposite directions

`strips()` reads the epoch only when a key is missing on either side. Removing that branch forces a
choice, and production data rules out both:

* **Require a key match always → fails OPEN.** All 7 revocations stop stripping anything. A pre-F0
  revoked device that still holds its old cert and A33 private key can act as an admitter again —
  T-K-01, restored. ⚠ One such device is already in the data: `e2fc0e92…` is revoked with no
  surviving `device` row.
* **Treat a keyless revocation as stripping every cert for its `deviceId` → fails CLOSED.** This is
  the original F1 "set membership" spec (§7 item 2), and on live data it is the worse of the two:
  **3 of 4 devices** carry a keyless revocation, so none of them could ever admit a new device
  again. Pairing would survive only through the single unrevoked device.

⚠ Note what the second bullet does *not* say: those three devices keep syncing either way. An
already-registered device authenticates with its device-key JWT and never re-verifies its admission
cert, so the blast radius is *admitting*, not *operating*. Worth stating precisely, because "3 of 4
devices bricked" would be the wrong summary.

### 7g.3 Why this realm can never satisfy the precondition

Re-minting the 7 revocations with keys would need **A1** — and this realm's master identity is
unrecoverable (§5b.11). Even with A1 it would need each target's A33 public key, which **R1 measured
as unresolvable** for a device already gone (§3.1) — the finding that falsified option A in the
first place. So the precondition is not merely unmet here; it is unreachable.

### 7g.4 What shipped instead: the deletion is now fixed from both sides

No behaviour change. Two guards in `KeyScopedRevocationTest`, one per failure direction:

* `aLegacyRevocationStillStripsAKeyedCertByEpoch` — the **live** shape (keyless revocation, keyed
  cert), which had no test at all. Goes red on the fail-open deletion.
* `aLegacyRevocationDoesNotStripAFresherKeyedCert` — goes red on the fail-closed one.

Both were mutation-tested against the two candidate rewrites. ⚠ The pre-existing
`aLegacyRevocationStillStripsAPreF0CertByEpoch` covered only keyless-revocation *and* keyless-cert
— a shape that no longer occurs, since every cert minted since F0 carries a key. The case that is
actually 100 % of production was the untested one.

### 7g.5 Status

**F5b is BLOCKED, not scheduled.** It becomes available only for a realm with zero keyless
revocations, which today means a realm created after F0. Should that ever be worth acting on, the
readiness check is one query — `SELECT COUNT(*) FROM admission_revocation WHERE
device_a33_public_key IS NULL` — and it must be per-owner, because the answer differs by realm.

⚠ A cheaper prize sits next to it: the fallback is *narrow*, not merely tolerated. It fires only
when a key is absent on one side, both branches are pinned, and nothing else in the verifier reads
the epoch. The remaining epoch reader of consequence is **T-K-02's merge check**, which is F5c and a
design question, not a cleanup.

### ✅ 7g.6 As-built (2026-10-28) — F5b shipped on the reset

The readiness query above returns 0 for every realm, trivially: the complete production reset wiped
`admission_revocation` along with everything else. F5b therefore shipped with the protocol-1 re-cut,
alongside §7d:

* `AdmissionRevocation.deviceA33PublicKey: ByteArray` — non-null; the keyless constructor and the
  `-v1` device-scoped message are deleted. `SyncRelay.revokeAdmission` takes the key as required.
* Relay: `RevokeAdmissionRequest.deviceA33PublicKey` has no default (a keyless body fails to parse →
  **400**), an empty key is a 400, `RevocationRecord.deviceA33PublicKey` is non-null, and
  `admission_revocation.device_a33_public_key` is `NOT NULL` in the squashed v1 migration.
* `AdmissionChainVerifier.strips` is one predicate: same `deviceId`, non-empty key, keys equal. The
  §7g.4 two-direction guards are retired with the branch they guarded; in their place
  `KeyScopedRevocationTest.aKeylessRevocationStripsNothing` (empty key strips neither a keyed cert nor
  an empty-key one) and `theRevocationEpochDoesNotAffectTheVerdict`, plus the relay's
  `keylessRevocation_returns400_andIsNotAppended` and `theDurableLedgerRefusesAKeylessRow`.
* ⚠ **Client: a member with no known key is skipped, not revoked keyless.** `RevokeDeviceImplementation`
  now names *every* A33 key it can find for each subtree member — the `Device` row's captured key
  (F0), the `AdmissionKeyAnnouncement` key, and the `AdmissionCertGrant` leaf key, distinct — one
  revocation each. The second source is what covers the realm's **first** device, whose row never
  carries a captured key (nobody admitted it) but whose cert was minted from its announcement (P4).
  Naming extra keys cannot over-revoke: every revocation is `deviceId`-scoped and key-exact. With no
  key at all the strip is skipped with a log line; the tombstone and the F2′ ledger still remove the
  device. The R1 `a33KeyResolvable` measurement log is deleted with the fallback it measured for.
* ⚠⚠ **Found by retiring the fallback: F0's captured key never survived pairing.** The joiner
  self-publishes its own `Device` row (`PairingKeyInstaller`) and that save carries the whole
  property set — without `admissionPublicKey` — so it replaced the admitter's captured copy
  realm-wide. Every blob-paired device (no announcement, no grant) was therefore keyless on the
  revoker, and its revocations had silently been taking the epoch fallback all along.
  `DelegatedAdmissionSystemTest` went red the moment the fallback went. Fixed at the source: every
  self-written own-device row (`PairingKeyInstaller`, `SyncProxyInitialization`'s self-heal,
  `CreateRealmImplementation`, `RecoveryClaimImplementation`) now carries the device's own A33
  public key when it holds one.
* ⚠⚠ **S1 — the key-scoped rule has a blind spot, and the removal ledger closes it.** "A member with
  no known key is skipped" means *known to the revoker*, whose view comes from rows a device writes
  about itself (whole-row LWW, no authorship check). A malicious device can blank its own
  `admissionPublicKey`; under F5b nothing could then strip it, and the "sleepers" it admitted would
  keep admitting through chains that run through it — which the epoch rule used to kill. Fixed
  key-independently: the relay passes `knownDevices::wasRemoved` (the F2′ ledger) into
  `AdmissionChainVerifier.verifyChain`/`verifyAdmission`, and any chain with a removed link is
  `RejectRemovedLink` (→ 403) — at post time and again at delivery. Queued registrations carry their
  encoded chain (`PendingRegistration.admissionCertChain`), and `queuedChainStillAdmits` re-runs the
  whole `verifyChain` at the join peek and the offer fetch — revocations as well as removals, since a
  revoker strips by key at once while the removal tombstone is pushed later, and a Q3 subtree member is
  revoked without being removed. F2′ guarantees a removed id never returns. ⚠⚠ It strips chains **still running through** the
  removed device only: a sleeper already re-rooted by P4 (`[S ← A1]`) survives until removed
  individually — threat model residual, `tech-debt.md` *"Re-rooted sleepers survive their admitter's
  removal"*. Pinned by
  `AdmissionChainVerifierTest.aChainThroughARemovedDevice_isRefused_evenWithNoRevocation` and
  `PairingResponseAdmissionChainTest.aChainThroughARemovedAdmitter_returns403_withoutAnyRevocation`.
* ⚠⚠ **S2 — `strips()`'s `deviceId` match is load-bearing**, not the redundancy its comment used to
  call it: a device can copy a victim's A33 key into its own row, and revoking the attacker then names
  the victim's key. Pinned by `KeyScopedRevocationTest.aRevocationNamingAnotherDevicesKeyDoesNotStripIt`.
* ⚠ The relay SQL baseline marker moved to **`2026-10-reset-b`**: v1 was edited in place, so a database
  cut by the 2026-10 reset's baseline (`2026-10-reset`, nullable key column) is refused, not silently kept. Change
  the marker again whenever v1 is edited in place.
* ⚠ The revocation's `epoch` and the cert's `mintedAtEpoch` are **still signed, now unread** —
  reserved, the §7f.3 posture. `RevokeDeviceImplementation`'s `seedCurrentRealmEpoch(epoch + 1)`
  stays, for T-K-02's `admittedAtEpoch` stamp (§7e.2), not for admission. Retiring the epoch from
  these messages is F5c's question and would be a wire change.

## 7h. F6 — the refusal is a dead end, and the ledger's real benefit is the telling

**Written 2026-08-28** from a maintainer question: *"we of course don't want a pairing to happen by
a stolen device — but the 403 gets displayed when trying to pair a device that has been added
before."*

### 7h.1 Two claims tested, and one of mine was wrong

The obvious defence of F2′ is *"if a refused device could just mint a new identity, the ledger
would stop meaning anything."* ⚠⚠ **That is false, and the reason is `ios-identity-reinstall-gate.md`
D2:** "start fresh" is gated by **(a), a plain destructive confirm** — no credential — and option
(c), requiring the recovery phrase, was *"rejected outright, not deferred: it is unenforceable for a
joiner, which holds no phrase at all."* So **anyone holding the device is one confirm dialog away
from a new identity.** The ledger cannot stop re-entry and never could.

⭐ **What the ledger actually buys is that a removed device cannot *resume*.** It returns a
stranger: fresh admission at the current epoch, old row still tombstoned, and — since D3 — no local
realm data. Re-entry itself is gated by the approver, exactly as §4.1 said when it found E and F
security-equivalent.

⭐⭐ **And that reframes the benefit: the ledger's security value is the *telling*.** The 403 reaches
the **approver** (it is their `POST /sync/pairing/response` that fails), so it is the one moment the
system volunteers *"this is a device you removed"* to the person deciding. Not a control — an
informed opt-in.

### 7h.2 ⚠⚠ The two obvious improvements cancel each other out

The natural fix for the dead end is to offer **Start fresh at the point of refusal**. But the
signal in §7h.1 only fires while the device still carries its **old** identity. Route the user
straight to Start fresh and the retry arrives under a **new** id, so the approver sees an ordinary
pairing request and learns nothing. **Making the dead end easier to escape deletes the only reason
the dead end was worth having.**

⇒ **Sequence it: the approver is told first, and the joiner is sent to Start fresh only after.**
1. Joiner pairs with its old identity → relay refuses.
2. The **approver** is shown what happened, with the removal date, and acknowledges.
3. Only then is the joiner told to start fresh, and the pair retried.

⚠ This orders the *honest* path. A malicious holder simply starts fresh first and is never seen —
and that is **fine**, because it was never the control; approval is. Do not document, name or build
on this as an attack control (§7h.4).

### 7h.3 Should the approver get a checkbox?

**Recommended: yes, for symmetry — but as an acknowledgement, not a second gate.** Revoking already
requires *"I understand this cannot be undone"* (built 2026-08-19, guarded by
`revokingADevice_needsTheAcknowledgement_notJustTheConfirmTap`), and an action that undoes a removal
in effect should not be quieter than the removal was.

Two constraints on the copy, both of which this project has already got wrong once elsewhere:
- ⚠ **It states a fact, never a reassurance.** *"You removed this device on 12 August. Adding it
  back gives it access to your realm again."* Not *"this is safe"* — the client cannot know that.
- ⚠⚠ **Checkbox inflation is a real cost.** The revoke acknowledgement works because it is rare. A
  second one is affordable; a third needs an argument. If only one of the line and the checkbox can
  ship, ship **the line** — the information is the benefit, the tick is the ceremony.

### 7h.4 What F6 must not claim

⚠⚠ **F6 is a usability and disclosure change. It adds no security.** A holder who starts fresh
before pairing is invisible to every part of it. Writing it down here because the failure mode is
live on this project: a banner that *asserted a cause the client could not know*, and a tech-debt
entry that recorded a surface as *"partially closed"* from the plumbing rather than the screen.

### 7h.4b ⭐ The web track reached this independently, and specified the wrong order

`drive-web-parity.md` **W10b** landed 2026-08-28, hours before this section, from the browser side:
*"after F2′ the identity is permanently dead for that realm… So mark the rejection, and on it offer
'start fresh'."* Same diagnosis, and it names a concrete gap this section did not — **F2′ has no
rejection marker**, where the `401` has `UNAUTHORIZED_REJECTION_MARKER` and the `402` its own, so
the web client cannot even tell a terminal refusal from a transient one. That is F6-2's twin; build
them together.

⚠⚠ **But W10b as written specifies exactly the ordering §7h.2 says must not ship first** — offer
"start fresh" *on* the rejection, with no approver step. Not a disagreement, an omission: the web
doc reasons from the joiner, where the approver's signal is invisible. Whichever track builds
first owes the other the sequencing.

### 7h.5 Slice

- **F6-1 — the relay returns the date.** `removed_device.removed_at` is already stored
  (`SqliteDeviceRegistry.recordRemoval`); the route discards it. Return it with the refusal.
- **F6-2 — a typed refusal.** The 403 reaches the pairing screen as raw text today. It becomes a
  typed outcome carrying `removedAt`, rendered from the catalogue — the *"code plus an argument"*
  case in [`core-error-copy-catalogue.md`](core-error-copy-catalogue.md) §5.2, and its first real
  customer.
- **F6-3 — the approver's disclosure** (§7h.3), on the existing approval surface.
- **F6-4 — Start fresh reachable from the failure**, on the joiner, **after** F6-3 — the ordering
  in §7h.2 is the whole point, and shipping F6-4 first would be a regression dressed as a fix.

⚠ **Not started.** No code, and the open question below decides F6-3's shape.

### 7h.6 Open question

**Where does the approver's disclosure live on each platform?** The approval surface differs
(Control's pairing wizard on Android/desktop, `control-ios` SwiftUI, and the web has no pairing
surface at all). ⚠ A shared `*UiState` change owes the Swift build, so a single flag threaded
through `PairingWizardUiState` is not free — and `hostDeviceUnrecognized` is already a dead field
of exactly that kind (`tech-debt.md`), which is the argument for wiring the consumer in the same
slice or not adding the field.

## 7i. ✅ Workstream B — the device id is derived from the A4 key (2026-10-29)

F (§4.3) made the removal ledger the guarantee that *an identity never returns*, and §7d/B1 made
every signed message injective. Both still keyed on a **`deviceId` nobody checked**: a UUID the device
minted for itself, bound to its A4 key only by the admitter's signature over the pair. So the
admitter — A1, *any* A33 holder in a chain, a shared-realm master — chose the id, and:

* **an id could be squatted.** The relay's registry is `(ownerId, deviceId) → key`, *register or
  replace*. A compromised admitter, or a device whose self-written `Device` row A1 re-admits from
  (`ReadmitDevice`, `admitKnownDevices`), could register its own key under a victim's id — replacing
  the victim's key, and inheriting everything keyed on the id: S1's removed-link refusal, S2's
  `strips()` conjunct, F2′ itself, block authorship.
* **"never returns" was about a string.** The same holder could come back under any other id, and a
  different key could take a removed id's name.

### 7i.1 The rule

```
deviceId = lowerhex( SHA-256( LP("tenshu-device-id-v1") ‖ LP(publicKey) ) )[0 : 32]
```

`deviceIdForPublicKey` / `deviceIdMatchesKey` in `:sync-service-common`
(`DeviceIdDerivation.kt`), one definition for every client target and the relay. `publicKey` is
the key the id is registered with — a device's A4, or on a shared realm joined as a member the
per-realm device key (those ids were already `hex(SHA-256(key))`, untagged and 64 characters; they
now use this rule, and stay unlinkable because the key is fresh per realm).

**Why this encoding** (vs base64url, vs the full digest): lower-case hex has no separator a tokenizer
splits on (`HlcStamp`'s `:`, the offer store's `,`) and no case to fold away on a case-insensitive
file system; 128 bits makes a squat a second-preimage search (2^128) — a collision between two keys
an attacker made himself (2^64) gains him nothing; 32 characters is shorter than the 36-character
UUID it replaces, so no HLC, row or log grows. The tag versions the scheme and keeps the digest apart
from any other `SHA-256(key)` (the old per-realm ids were exactly that).

### 7i.2 Where it is enforced

* **Relay, every route that registers a device** — `/sync/realm`, `/sync/admit-device`,
  `/sync/pairing/request`, `/sync/pairing/response`, the approved `/sync/pairing/offer-response`,
  `/sync/recovery-claim`, `/sync/realm-member/admit` — via `RegistrationInputRules.isKeyDerivedDeviceId`,
  **before** the admission / master signature is verified, the realm looked up, the recovery
  challenge spent or anything written (caller authentication — the JWT on `/pairing/response`, the
  responder's A4 signature on `/offer-response` — may come first; it has no side effect). A mismatch is
  `400 device-id-not-key-derived: …` (`RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED`). The pair cannot
  change between post and delivery, so the queued join/offer registrations need no second check.
* **Clients, before signing** — `PairingAdmission.admit` (join + offer flows; also mints the A33
  cert naming the id), `ReadmitDeviceImplementation`, `SharedRealmBootstrapper.admitKnownDevices`,
  and `AdmissionMigrationObserver.maybeMint` (the P4 re-root, which reads the pair off a synced
  `Device` row — found in review). A1 and A33 never vouch for a mismatched pair, whatever the relay
  or a synced row says.
* **Minting** — `SyncManagerImpl.currentDeviceId` is derived (lazily) from
  `getDeviceSigningKeyPair()`; `Keys.DEVICE_ID` survives only as a plaintext cache for the readers
  that run before a `SyncManager` exists (store-migration context, iOS route-1 hand-off), overwritten
  whenever it disagrees. All platforms — Android/desktop/iOS Control, iOS route-1 apps (they adopt
  Control's A4), the web runtime and the hub — mint through that one class.

### 7i.3 Rotation

There is none (`tech-debt.md` *"Public-Key Rotation Without Re-Pairing — Deferred"*), so the id is
simply stable for the key's life. **The id changes with the key**: an unreadable stored blob makes
`getDeviceSigningKeyPair` mint a new key, and that device returns as a stranger to be admitted again —
the honest outcome — ⚠⚠ but the **old id is orphaned** (before, the persisted id was reused and the
relay's register-or-replace overwrote the key): it stays in the relay registry and the synced
`Device` relation, counts in the tombstone-GC quorum and the reclamation barrier, and stalls both
until the stale device is removed. Any future rotation must re-admit under the new id **and retire
the old one**; `docs/web/architecture.md` §9's
optional "rotate, same identifier" note is withdrawn accordingly. F2′ interplay is unchanged in shape:
a reinstall that keeps the Keychain keeps the key and so the id (and is refused if removed);
*Start fresh* forgets custody, mints a key and so a new id.
⚠ **The id now sits behind the secret bucket.** It used to be read from the plain one; now its first
read on a process — the first HLC stamp — goes through `getSecret`. On iOS, once the secret bucket is
gated behind Face ID (C2, `gateSecretBucket = true`), a cancelled prompt that reads as `null` would
mint a new key and so a new device, the orphaning above. That failure already existed for the key; it
now sits on far more call sites. C2 must make a cancelled read **fail**, never read as "no key".
⚠ **Pair the key with its own id, never a carried copy.** The iOS route-1 hand-off carries a separate
`RealmKeyMaterial.deviceId`, written to its own Keychain item; a partial publish can leave it beside a
newer key. The hand-off primes the cache, and the relay probes build their JWTs, from the handed key's
derivation (`keyDerivedDeviceId`), not from that copy.

### 7i.4 Not done

Link ids inside an A33 chain are not re-derived (a link carries its A33 key, not its A4), and synced
`Device` rows are not checked on merge. Both are written only by admitted devices, and every
registration they could feed is checked at the relay. No wire field was added or removed and
`RELAY_PROTOCOL_VERSION` stays 1: clients and relay ship together on the reset.

**Tests:** `DeviceIdDerivationTest` (vectors computed outside Kotlin; JVM + iOS) and its browser copy
`DeviceIdDerivationJsTest` (`:tenshu-sdk-js`),
one mismatch-refusal test per relay route (each mutation-checked: the guard removed, the test red),
`AdmissionKeyCaptureTest.anIdThatIsNotTheKeys_isRefused_beforeAnythingIsSigned`,
`ReadmitDeviceImplementationTest.aRowWhoseIdIsNotItsKeys_isRefused_andNothingIsSigned`, and end to end
`PairingSystemTest` (every id the relay holds after a real pairing is its key's; an A1-signed
admission of the joiner's key under the host's id is refused and the host's key is untouched).
`MockSyncRelay` refuses an underived id on every registering method, so a client path pairing an id
with the wrong key fails in unit tests too (it caught two fixtures); `SharedRealmAdmitOnBootstrapTest`
pins that a forged row never reaches it. iOS: `DriveLocalRuntimeTest` pins that an adopting app's id is
the handed key's derivation — ⚠ only `iosTestCheck` runs it, not `run-gate.sh`, which is how it shipped
red with workstream B (§7i).

## 8. What this does not do

* Does not change what a revocation *means* to the user.
* Does not remove `RevokeDevice`'s epoch seeding — that stays correct until R5 retires the comparison.
* ⚠ Does not address whether a revoked device should be able to *read* what it already has; scope is
  the admission capability only.
