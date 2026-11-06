# Offer-Flow "Scanned" Announce — Pre-Approval SAS on Both Screens

**Status: v0.2 — ⭐ ACCEPTED 2026-08-28, A1–A4 starting.** Written as a draft on 2026-07-17 with the
decision deliberately left open; the maintainer took it on 2026-08-28 after the pairing sweep
(`docs/tech-debt.md`) re-derived the same gap from the native side and §5.2 established that the
cheap alternative protects the wrong direction.

⚠ The shipped post-hoc posture (`docs/sync/pairing-flow.md` §4.5) was never a bug — it is
post-approval **by construction** — and it stays correct until A3 lands. ⭐ §5.3 (keep the
responder's SAS on screen so the post-hoc comparison is a glance rather than a memory test) shipped
separately on 2026-08-28 and is **independent of A1–A4**: it helps whether or not the announce ever
lands, and it does not fix the timing.

**Depends on / relates to:** the graduated offer flow (`pairing-flow.md` §4; proposal record
`sync-pairing-inverted-direction.md`), threat model §8.1 **T-P-06..08**, the native offerer UI,
the SAS-timing decision + truthful responder SAS copy (`pairing-flow.md` §4.5), and the two-party
flow test `PairingVmFlowSystemTest` (`:system-test-utils`).

---

## 1. Problem

In the offer flow (new device shows `tenshu://offer`, existing device scans), the SAS ceremony is
**asymmetric by construction**: the SAS is derived from `X25519(privA, PublicKeyB)`, and the
offerer only learns `publicKeyA` from the offer-response — which the responder POSTs *when the
user approves*. So the live sequence is:

1. phone scans → phone shows the SAS approval prompt,
2. user approves (nothing on the desktop to compare against),
3. desktop pairs and shows the SAS **post-hoc**.

Two consequences, both observed in the first live desktop↔phone runs (2026-07-17):

- **UX**: the ceremony reads as broken — "the code appeared on the phone *before* the desktop,
  then on the desktop *after* I already approved; what did I just verify?" The truthful-SAS copy
  makes the prompt honest, but honesty about an awkward ceremony is still an awkward ceremony.
- **Security timing**: T-P-07 (rogue offer-response / key substitution) names the user's SAS
  comparison as part of its defense — but today that comparison can only happen **after
  `GetOfferStatus` has already decrypted and installed the received keys**. A substituted
  counterparty is *detected* (codes differ → user revokes / resets), not *prevented*. The
  tripwire works; a lock would be better.

## 2. Design

Add one pre-approval interaction: when the responder scans the offer QR, it immediately
**announces** its ephemeral public key to the rendezvous. The offerer's existing poll picks the
announce up, derives the SAS locally (it holds `privB`), and displays it *while the responder's
approval prompt is still on screen* — restoring the join flow's "compare, then approve" ceremony.

### 2.1 Wire shape (additive; reuses the existing rendezvous)

Reuse `POST /sync/pairing/offer-response` with a new status value rather than a new endpoint:

```
POST /sync/pairing/offer-response
{ ownerId, pairingSecretHash, status: "scanned", publicKeyA, responderDeviceId, hostSignature }
```

- **No `payload`** — the announce carries routing metadata + the ephemeral public key only.
  Nothing here is secret (T-P-06's posture unchanged); House Rule #1 holds trivially.
- **Signed like the approve POST**: the relay verifies `hostSignature` (Ed25519 by a registered
  realm device key over the canonical `status || hash || publicKeyA` bytes, via the existing
  `MasterDelegationVerifier` path). A passive QR observer therefore *cannot* announce — only a
  realm device can. This matters: an unauthenticated announce would let an observer plant a bogus
  SAS on the offerer's screen (a confusion primitive), which signing removes for free.
- **Does not consume the single-use hash** (`ConsumedPairingSecretsStore` untouched); the
  terminal approve/deny still consumes it exactly as today.
- `GET /sync/pairing/offer-response?hash=…` returns the announce blob until a terminal blob
  replaces it. Storage: the existing `PairingOfferStore` row gains the announce (or a sibling
  column), same TTL.

### 2.2 State machine

`OfferStatus.state` (a plain string on an existing composite — **additive, not peer-breaking**)
gains `"scanned"`, with `sas` populated (the proxy can derive it the moment `publicKeyA` is
known):

```
showing → scanned (sas) → approved (sas) | denied
        ↘ (announce never arrives — old responder) → approved | denied   ← today's path, kept
```

Forward/backward compatibility falls out of the shipped client code:

- **Old offerer, new responder**: the native offerer's poll loop treats any non-`approved`/`denied` state as
  "keep polling" — a `scanned` status is silently ignored; behavior identical to today.
- **New offerer, old responder**: no announce ever arrives; the offerer stays on the QR until
  the terminal status, i.e. exactly today's post-hoc behavior. No cliff.
- **Old relay, new responder**: the relay must accept `status: "scanned"` → **deploy the relay
  first** (same SIGNAL_ONLY-style sequencing as prior additive wire changes). Until then the
  responder's announce POST fails; the responder MUST treat announce failure as non-fatal
  (log + continue to the approval prompt) so a stale relay degrades to post-hoc, never blocks.

### 2.3 The key-equality lock (the security payoff)

The offerer pins the announced `publicKeyA`. When the terminal `approved` response arrives, the
proxy **verifies the response's `publicKeyA` equals the announced one before decrypting or
installing anything**. Mismatch ⇒ hard abort: no install, session torn down, a typed error
surfaced ("the approval came from a different device than the one that scanned — start over").

This upgrades T-P-07 from *detect-after-install* (post-hoc SAS + revoke) to *reject-before-
install* whenever an announce was seen. With no announce (old responder), the posture degrades
gracefully to today's documented tripwire.

### 2.4 UX

- **Offerer (desktop/web)**: on `scanned`, the QR is replaced by the SAS + "Code scanned —
  confirm it matches on your other device, then approve there." On `approved`, the existing
  success terminal (SAS already on screen, now confirmed).
- **Responder (phone)**: the approval prompt's copy returns to the join flow's semantics —
  "Confirm this code matches on your other device" — the current honest wording
  (`pairing_responder_match_note`) becomes the *fallback* shown only if the announce POST failed.
- **iOS responder** (`PairingView.swift`): same two-state copy; note it still carries the
  older, pre-honest-copy phrasing today — this proposal's UI phase would fix both at once.

## 3. Threat-model deltas (land with the relay phase, House Rule #2)

- **T-P-06** (unauthenticated poll): unchanged in kind; the announce blob adds `publicKeyA` to
  what a hash-holder can fetch — an ephemeral public key, no confidentiality impact. Note added.
- **T-P-07** (key substitution): defense strengthened — announced-vs-approved key equality is
  enforced *before* install when an announce exists; the SAS comparison moves pre-approval.
  Residual drops (Low → Low, tighter window; the no-announce fallback keeps today's posture).
- **New T-P-09 (announce spoofing/replay)**: an attacker replaying or forging announces to plant
  a wrong SAS. Closed by the device-signature requirement + the relay binding one announce per
  hash (a second announce from a *different* device id is rejected; re-announce by the same
  device overwrites — covers a legitimate rescan). DoS residual folds into T-P-08.

## 4. Phasing (each independently shippable)

| Phase | Scope | Notes |
|-------|-------|-------|
| **A1** | ✅ **BUILT 2026-08-28.** Relay: accept + store + serve `status:"scanned"`; one-announce-per-hash binding; T-P-07 amended | Deploy first; old clients unaffected. ⚠ **Not yet deployed** — nothing posts an announce until A2, so the relay simply gains a capability nobody exercises. As-built §7 |
| **A2** | ✅ **BUILT 2026-08-28.** `RespondToPairingOffer` POSTs the announce (non-fatal); `GetOfferStatus` surfaces `scanned`+`sas`; key-equality lock in the approved path | `OfferPairingRoundTripTest` + mismatch-abort case. As-built §8 |
| **A3** | ⚠ **PARTLY BUILT 2026-08-28** — native offerer `scanned` surface + responder copy + **iOS catch-up** done; **web NOT done** (see §9) | Copy strings per ADR 0016. As-built §9 |
| **A4** | ⚠ **PARTLY BUILT 2026-08-28** — the both-screens-before-approve assertion is in; the system-level mismatch walkthrough is **not** (covered at the proxy in A2) | The regression net for the ceremony itself. As-built §9 |

⚠⚠ **"Each independently shippable" is not true of A4** (noted 2026-08-28). Its scope is *"assert
the SAS is visible on **both** screens before approve"* — which is exactly what **A3** builds, so A4
cannot pass without it. The honest cut is **A1 + A2** (protocol and ops, no user-visible change,
safe to deploy and sit on) and then **A3 + A4 together**. ⚠ A1+A2 alone buy nothing a user can see:
the SAS becomes derivable on both sides and nothing displays it.

Rollout: A1 → redeploy relay (`relay.tenshu.app`) → A2–A4 in clients. No migration; no data
shape change; single-use semantics unchanged.

## 5. Alternatives

### 5.1 Do nothing, explicitly

Keep the shipped post-hoc posture. It is documented (§4.5), test-pinned, honest in copy, and its
tripwire catches the realistic attack after the fact at the cost of a revoke. The cost of *this*
proposal is a relay deploy + four small phases; the benefit is a ceremony users already expect
from the join flow plus a genuinely earlier T-P-07 rejection point. If the offer flow remains a
rare, in-person bootstrap path, the do-nothing option is defensible.

### 5.2 Defer key installation on the offerer until the user confirms — ⚠ **rejected, and the reason is asymmetric harm**

Raised 2026-08-28. Shape: leave the wire alone; the offerer fetches and *decrypts* the
offer-response as today but **holds the keys un-installed**, shows its SAS, and installs only after
the user confirms the codes match. No relay change, no new message — client state plus one confirm.

It is genuinely cheaper, and it does prevent one of the two substitution directions. It is still the
wrong trade, because the two directions do not cost the same:

| | what is substituted | who is harmed, and how badly | does 5.2 help? |
|---|---|---|---|
| **A** | a rogue **offer-response** reaches an honest new device | it installs *someone else's realm*. Bad, but nothing of the user's leaked | ✅ yes — the install is what it withholds |
| **B** | a rogue **offer QR** is scanned by an honest realm device | that device seals **the realm's master key blob** to the attacker and POSTs it (`payload = admitted.encryptedMasterKey`, sealed with `privA · offererPubB`) — full realm compromise | ❌ **no** |

⚠⚠ **Direction B is the expensive one and 5.2 is not present in it.** The device withholding its
install is the honest offerer, sitting uninvolved; the blob has already left the responder. §2's
announce covers B precisely because the mismatch appears on the **responder's** screen *before* it
approves — and approving is the act that sends the blob. **5.2 prevents an install; §2 prevents a
send.**

### 5.3 ⭐ Independent of the choice: stop making the user *remember* the code

Worth separating from the above, because it is nearly free and helps whichever way §2 goes. Today's
ceremony asks the user to carry a six-digit code across an approval tap and compare it on a
different screen afterwards. That is not a weak comparison so much as one that **mostly will not
happen** — which makes T-P-07's tripwire weaker in practice than "detected, not prevented" suggests:
it is detected *only if the user did something people reliably do not do*.

**Keep the responder's SAS on screen after it approves.** The post-hoc comparison then becomes
side-by-side instead of a memory test. It fixes nothing about timing — the blob is already sent —
but it makes the tripwire that exists actually usable while §2 is undecided. Copy + a little state;
no protocol involvement.

## 6. Open questions

1. **Should the approve prompt block until the offerer confirms?** No — mutual *display* is the
   goal; requiring an offerer-side ack would add a third round trip for marginal gain. (Lean: no.)
2. **Bind the hash to the announcing device at announce time** (i.e. only the announcer may later
   approve)? §2.3's equality lock already gives this end-to-end; a relay-side bind would also
   stop a *different realm device* from approving a session someone else scanned — an odd but
   conceivable household scenario. (Lean: yes, cheap at the relay, matches T-P-09 handling.)
3. **Web responder** doesn't exist (web is offerer-only today) — nothing to do, noted for parity.


---

## 7. As-built — A1 (2026-08-28)

**Relay only.** `POST /sync/pairing/offer-response` accepts `status: "scanned"` alongside
`approved|denied`, verified with the **same** signature over `status || hash || publicKeyA`
(`payload` null) by a registered realm device key — so a passive QR observer cannot announce.

⚠⚠ **The announce is stored in its own table (`pairing_offer_announce`, migration 16), and that is
the load-bearing decision.** In `pairing_offer_response` the *presence of a row IS* the single-use
consumed marker (T-P3) — the in-memory backend enforces it with `putIfAbsent` and the durable one
with a `SELECT 1` — so storing an announce there would have consumed the hash and killed every
handshake at the instant it was scanned. Separate table, separate `postAnnounce` entry point,
separate `AnnounceResult`; `post` is untouched and single-use is exactly as it was.

`fetch` serves **terminal first, announce second**, so the offerer's existing poll sees the
announce until the decision replaces it — no client change needed for that ordering.

`gcSweep` sweeps both tables. ⚠ Without that, a handshake abandoned after the scan leaks a row
forever — the same unbounded-growth shape the pairing-request mailbox had (`tech-debt.md`, the
2026-08-28 sweep).

**Pinned by four route tests**, the load-bearing one being
`scannedAnnounce_isServedToTheOffererPoll_andDoesNotConsumeTheHash`: it announces, polls, and then
posts the terminal approve and asserts it still returns **200** — if the announce ever starts
consuming, that goes red rather than pairing silently dying in the field.

⚠⚠ **A1 is capability, not protection.** Nothing posts an announce until A2 and nobody sees a SAS
earlier until A3; the key-equality lock (§2.3) is A2's. The threat model says so at T-P-07 rather
than crediting A1 with the outcome.


---

## 8. As-built — A2 (2026-08-28)

**The offerer now sees the SAS before the responder decides**, and a mismatched approval is
rejected before anything is opened.

`RespondToPairingOffer` posts `status:"scanned"` right after it derives the SAS, signed with the
device's A4 key exactly like the terminal statuses. ⚠⚠ **Non-fatal by construction** — an
un-upgraded relay 400s on the new status, and a responder that treated that as an error would
refuse to pair at all against a relay that serves the documented post-hoc flow perfectly well. It
degrades and `println`s; ⚠ *reported* rather than swallowed, because a silent failure looks
identical to an old relay and makes "why is there no code on the other screen?" undiagnosable.

`GetOfferStatus` gains a `"scanned"` branch: derive the SAS from the announced key, **pin** it on
the offer session, return `OfferStatus.SCANNED` + `sas`. ⚠ `SCANNED` is a new value of an existing
plain-string field — additive, and an older offerer treats any non-terminal state as "keep
polling", so it degrades to today's behaviour with no cliff.

⚠⚠ **The key-equality lock runs before the payload is touched.** On `approved`, if a key was pinned
and the terminal response carries a different one, the session is torn down and a typed error
thrown — *no decrypt, no install*. That is T-P-07 moving from detect-after-install to
reject-before-install.

⚠ **A null pin means "no announce was seen", never "any key is fine".** That distinction is what
lets A2 ship before A3 and before every relay is upgraded: with no announce the code falls through
to the documented post-hoc posture instead of inventing a lock it cannot enforce. `pinAnnouncedOfferKey`
also keeps the **first** announce — a client that re-pinned each poll would quietly forgive a relay
that stopped enforcing one-per-hash, and a lock must not depend on the thing it guards.

**Tests.** `scannedAnnounce_putsTheSasOnTheOffererScreenBEFORETheResponderApproves` is the payoff —
it asserts both screens hold the *same* code with no `approve()` having happened.
`anApprovalFromADifferentDeviceThanTheOneThatScanned_isRejectedBeforeAnythingIsInstalled` is the
lock, and checks the offerer is left unbound.

⚠ One existing test legitimately changed: `relayKeepsRefusingTheResponder_…` now observes `SCANNED`
rather than `PENDING`, because the responder really did scan before the relay began refusing. Its
real intent — a refused approve installs nothing — is now asserted **directly** rather than implied
by the state constant.

⚠⚠ **Still invisible to users.** Nothing renders `SCANNED` until A3; the offerer keeps showing its
QR. A2 is where the security lands, A3 is where the ceremony does.


---

## 9. As-built — A3 + A4 (2026-08-28), and what is still owed

**The ceremony now works on native and iOS.** `GetOfferStatus`'s `scanned` populates `offerSas`
while the decision is open, and the offerer replaces its QR with the code — the QR has done its job
by then, and the code is the thing the user must act on.

⭐ `offerScanned` is **derived, not stored**, in both the Kotlin `PairingWizardUiState` and its
Swift mirror: `offerSas` is set on `scanned` *and* on `approved`, and `offerPaired` is exactly what
separates them, so a stored flag could only ever disagree with those two. It also keeps a
constructor field off a shared `*UiState`, which would have owed the Swift build for no gain.

**Responder copy changed** from *"After you approve, the new device shows this same code"* to
*"Check this code matches the one on the other device before you approve."* ⚠ It deliberately does
**not** promise the other screen is already showing it: an offerer on an older build only renders
the code post-hoc (§2.2's old-offerer path), and copy that over-promised would read as broken there.

**A4's assertion is the inverse of the one it replaces.** `PairingVmFlowSystemTest` used to pin
*"no SAS on the offerer pre-approval"* — correct for its time, and written so no copy could imply a
comparison that could not happen. It now awaits `offerScanned` and asserts **both screens hold the
same code with the decision still open**. Reversed rather than deleted: what must never regress is
that the two agree.

### ⚠ Still owed

1. **Web offerer.** §2.4 names a `PairingOfferScreen` scanned state; there is **no such file** —
   web's offer surface does not exist in the shape this proposal assumed (`web/apex/src/lib/`
   has only `pairingUri.ts`). Scope it against what web actually has before writing code.
2. **System-level mismatch walkthrough.** The key-equality lock is pinned at the proxy
   (`OfferPairingRoundTripTest.anApprovalFromADifferentDeviceThanTheOneThatScanned_…`), which is
   where the lock lives. A VM-level duplicate would exercise the same branch through more
   scaffolding; worth adding when someone is already in that file, not for its own sake.
3. **Nobody has run this on two real devices.** Every assertion here is a test. ⚠ The one thing
   tests cannot tell you is whether the two codes appear close enough in time to *feel*
   simultaneous — which is the entire user-visible point of the track.
