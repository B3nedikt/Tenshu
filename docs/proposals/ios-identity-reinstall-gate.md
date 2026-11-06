# iOS identity reinstall gate — "this device already has an identity"

**Status:** v0.2 — **RG-1 BUILT 2026-08-19**, D2 decided (a). Written 2026-08-17 out of a live
incident (§0). ⚠⚠ **No longer independent**: it is the prerequisite for option F
([`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)
§4.3) and sequences before F1, because under F a returning identity is refused permanently rather
than silently self-healing.

⚠⚠ **D2's "plain destructive confirm" has a consequence outside this doc.** Because "start fresh"
needs no credential, the F2′ removal ledger cannot stop a device re-entering a realm — only stop it
*resuming*. That is analysed, with what should change, in
[`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md) §7h
(F6). ⚠ F6-4 moves where "Start fresh" is offered, and **deliberately sequences it after** the
approver is told — routing a refused user straight here would delete the only signal the refusal
produced.

**Problem in one line:** on iOS the Keychain outlives the app, so a "fresh install" can silently
resume an identity — and the app never says so, never offers the alternative, and comes up locked
with no explanation.

---

## 0. Why — the incident

Deleting an iOS app does **not** clear its Keychain items — shared-access-group items survive as long
as any group member is installed (`docs/how-to/reset-and-commerce-test.md` §"iOS is two halves, and
uninstalling is only one of them"; `scripts/reset-local-device-state.sh` header). Control-iOS
therefore comes back bound to the identity it had before, which produced three distinct problems in
one evening:

1. **A stale instruction executed against a live device.** A *"retire this device"* tombstone
   authored during a half-finished reset survived in `PENDING_TOMBSTONES`, outlived a successful
   re-pairing hours later, and revoked the device the moment an entitlement made pushing possible —
   because the surviving Keychain identity meant the id it named was still the id in use
   (`docs/tech-debt.md` §"A queued Device tombstone can revoke a device that has since been
   re-admitted").
2. **A reinstall presents as a malfunction.** Control comes up **locked** (the `UNLOCK_WRAPPER`
   footgun — a reinstall has no unlock wrapper seated, so the realm is sealed; see
   `docs/tech-debt.md` on how `setUpLocalUnlock` wraps the phrase at `Keys.UNLOCK_WRAPPER`); a sealed
   realm can neither run a pairing session nor
   repair its own relay registration, so the app looks broken while behaving correctly.
3. **Nothing offers the other choice.** A user who deletes the app *intending* to start over gets the
   old identity back with no visible way to decline it. The production escape hatch exists — "Forget
   this device" on `LockedView` → `ControlSurface.wipeDeviceState()` — but it is reachable only from
   the locked screen, and it is not offered *because* a reinstall was detected.

⚠ The **desktop already closed this gap**: `reset-local-device-state.sh` deletes the macOS
login-Keychain wrap key explicitly and calls leaving it behind "the classic broken half-state". iOS
never got equivalent treatment, because its wipe route needs the app installed. **Android is
unaffected** — `pm clear` / uninstall takes the sandbox with it.

## 1. The detector is trivial and reliable

`UserDefaults` is removed with the app; the Keychain is not. So:

> **a missing first-run sentinel + populated custody bucket ⇒ this install is a reinstall**

is exact, needs no new storage, and cannot false-positive on a genuinely new device (no Keychain
data) or a normal relaunch (sentinel present). It is the standard iOS idiom for precisely this
footgun.

## 2. ⚠⚠ Why Signal's remedy does NOT transfer

The usual remedy is *clear the leftover Keychain state on first launch*. Signal-shaped apps can
afford that: the **account lives server-side**, the message database dies with the app container, and
leftover key material without a database is useless — re-registration is the intended path (peers
observe the identity-key rotation as a changed safety number). WhatsApp is the same shape: re-register
the number, restore from backup.

**Tenshu inverts the authority.** The recovery phrase *is* the account (A1), and on a genesis device
the Keychain may hold its **only** copy — `wipeDeviceState` purges `RECOVERY_PHRASE` along with the
device keys. A blanket clear-on-reinstall would therefore destroy a realm outright for any user who
never wrote the phrase down: a class of data loss Signal structurally cannot have, because for them
the server can always re-issue what the device lost.

⚠ Consequence for the design: **detect-and-clear is out. Detect-and-ask, with *keep* as the default.**

## 3. Proposed flow

On a detected reinstall, before the lock screen, Control-iOS presents one deliberate fork:

| Choice | Effect |
|---|---|
| **Continue as this device** (default) | Proceed to unlock. Nothing is destroyed. Names the situation, which is what turns problem (2) from a malfunction into an expected step. |
| **Reveal my recovery phrase** | Show the phrase *before* anything is destroyed. It is sitting in the Keychain and this is the last moment it exists — offering it is the difference between a safe discard and an irreversible one. |
| **Start fresh on this device** | Destructive. Requires explicit acknowledgement; see §4 for what it must purge and D2 for how hard to gate it. |

⚠ The fork is shown **once per install** (the sentinel is written after the choice), not on every
launch — a nag would train users to dismiss it.

## 4. What "start fresh" must purge — and today it doesn't

`wipeDeviceState()` clears the custody bucket (device id, A4 signing key, recovery phrase, unlock +
biometric wrappers, owner id, relay latches) and **deliberately not** the `FileObjectStore`. That
asymmetry is exactly how the incident in §0.1 happened: the identity went, the **queues stayed**, and
a queued `Device` tombstone executed weeks of state later.

⚠ *History: that was the file-store era. Since 2026-11-05 the iOS replica is the shared SQLite
store and `wipeDeviceState()` wipes it through the worker before it purges custody
(`ControlSharedReplica.wipeThenPurgeCustody`, [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)
B.14 and B.22); `FileObjectStore` is deleted (B.22.9).*

So a reinstall-time discard must also drop **the outbound queues** (`PENDING_BLOCKS`,
`PENDING_TOMBSTONES`, the deferred-content journal). ⚠⚠ Nothing in a queue
authored by an identity you are discarding can be meaningful afterwards — it is addressed to a realm
this device is leaving.

⚠ Whether it should also drop the **store itself** is D3: the store is the device's local copy of
realm data, and on a re-pair it would be re-fetched. Dropping it is cleaner and slower; keeping it
risks the "old files came straight back" surprise: `devicectl install` **preserves** the data
container, so only *uninstalling* drops the app-local store (`reset-and-commerce-test.md` §"iOS is two
halves").

⚠⚠ **D3 is now decided — drop it — and the deciding argument was none of the above.** Keeping the
store does not merely risk a *surprise*; it leaves the app **incoherent**, because the identity and
the store disagree about which realm this device is in. Observed on desktop 2026-08-23, minutes after
"Forget this device" shipped there:

1. the retirement worked — device `7a38e6fd` left the realm and landed in the relay's removal ledger;
2. `SyncManagerImpl.forgetThisDevice()` cleared the full custody bucket, `DEVICE_ID` included;
3. the process kept running, so `currentDeviceId` — a `val` that mints a fresh UUID when the key is
   missing — **generated a new device id and persisted it**;
4. that id was registered with no realm, so every push returned `401 — Invalid token`, and the
   outbound queue simply grew (13 blocks when it was caught);
5. meanwhile the **old realm's store was still there and still readable**, so Settings kept showing
   the account and the Services card kept deriving figures from an entitlement belonging to a realm
   this device had already left.

None of that is recoverable in-app: the device is in no realm, and cannot re-onboard because the
store makes it look like it already is one. The only exit was deleting the data directory by hand.

⭐ That is the *half-reset* failure this repo already names elsewhere — a partial reset being worse
than none. Keeping the store is not the cheap option; it is the option that produces a device which
is neither in a realm nor out of one.

## 5. Decisions

- **D1 — the detector is a `UserDefaults` sentinel, not a Keychain flag.** A Keychain flag would
  survive deletion and defeat the purpose. *Decided.*
- **D2 — how hard to gate "start fresh". DECIDED 2026-08-19: (a), a plain destructive confirm.**
  Chosen for simplicity, with the phrase reveal deferred to RG-2 rather than dropped. ⚠ The cost is
  explicit: a user who discards without holding their phrase and has no other signed-in device loses
  the realm, and (a) does nothing to catch that — which is exactly what (b) would add. Accepted
  because the gate's *first* job is to stop the silent resumption, and shipping (a) does that now
  rather than after the reveal is built. ⚠⚠ (c) stays rejected outright, not deferred: it is
  unenforceable for a joiner, which holds no phrase at all.
- **D3 — whether discard drops the object store as well as the queues. DECIDED 2026-08-23: drop
  it.** ⚠⚠ Not decided on the re-fetch/surprise trade-off §4 originally framed, but on a failure
  observed in the field: keeping it leaves the device with an identity and a store that disagree
  about which realm it is in — a fresh device id registered nowhere, `401` on every push, a growing
  outbound queue, and a Services card still reading the *previous* realm's entitlement. Unrecoverable
  in-app. §4 carries the full sequence. ⚠ The cost stands and is accepted: a re-pair re-fetches the
  realm, which is slower. That is strictly better than a device which is neither in a realm nor out
  of one.
- **D4 — the gate lives in Control only.** Drive-iOS has no wipe route and rides Control's shared
  `app.tenshu.shared` Keychain group; the group's owner is the only honest place to offer a discard.
  *Decided.*
- **D5 — "Continue" is the default and the whole fork is skippable in one tap.** A reinstall is a
  normal, safe event; the flow must not imply something is wrong. *Decided.*

## 6. Threat-model interaction

Two directions, and the second is a **security gain** rather than a cost:

- **A reinstall-time discard is a local key-destruction path.** It destroys A1/A4 material on this
  device. It grants no new authority to anyone and reaches no server, so it adds no relay surface
  (House Rule #1 intact) — but it is availability-relevant: the loss is unrecoverable without the
  phrase, which is why D2 exists. Relates to **T-K-04** (master-key compromise ⇒ rotation) only in
  that both make the phrase the thing that matters.
- ⚠⚠ **Today's behaviour is itself a privacy weakness.** Deleting the app leaves a usable realm
  identity resident on the device *indefinitely*. A phone that is resold, handed on, or repaired
  carries it, and a reinstall silently adopts it. Offering discard at the moment a reinstall is
  detected is the first point at which the user can act on that. Whether this warrants a threat-model
  row (an "abandoned identity on a transferred device" case) is worth deciding with the amendment.

Per House Rule #2, the implementing PR amends `docs/sync/threat-model.md` in the same change — the
discard path and, if accepted, the abandoned-identity row.

## 7. Phasing

- **RG-1 — ✅ BUILT 2026-08-19.** The detector (`installSentinel` in `UserDefaults`, D1) +
  `ReinstallGateView` with **Continue** and **Start fresh**; the discard purges the custody bucket
  *and* the outbound queues. As-built notes in §10.
- **RG-2 — ⭐⭐ PROMOTED 2026-08-21: no longer optional.** The phrase reveal (D2 (b)) in the fork.
  A realm on this project lost its master identity to exactly this gap: "Start fresh" discarded the
  only A1 holder, and the confirmation could not say so because nothing showed the phrase at the
  moment it still existed. ⚠ D2 (a) shipped for simplicity and its recorded cost — *"a user who
  discards without holding their phrase and has no other signed-in device loses the realm"* — is
  precisely what then happened. See `admission-revocation-names-the-capability.md` §5b.11, which
  also explains why the tempting alternative (have the relay count surviving A1 holders) **fails in
  the dangerous direction**: a vanished device stays registered forever, so the count over-reports
  and suppresses the warning exactly when it is needed.
- **RG-3** — implement D3 (now decided: drop the store), plus the "only A1 holder" refinement of D2
  if that is ever computable (it needs realm state this device may not have).

  ⚠⚠ **Scope note, measured 2026-08-23.** `ObjectStore` has **65 references** and no wipe operation,
  so widening the interface outright is not the move — it would force ~60 test doubles to implement
  a method they have no use for, and this repo has already been bitten three times in one day by
  widening an interface without compiling every implementor. There are only **four** real
  implementations: `SqliteObjectStore` (jvm), `FileObjectStore` (ios), `IndexedDbObjectStore` (js),
  `InMemoryObjectStore`. (⚠ *Since 2026-11-05 the iOS one is the SQLite `SharedReplicaStore`;
  `FileObjectStore` is deleted.*)

  So the shape is a **narrow optional capability**, not an interface widening:

  ```kotlin
  /** A store that can drop everything it holds — the D3 discard path. */
  interface WipeableObjectStore { fun wipeAllObjects(): Int }
  ```

  implemented on those four and consumed as `(store as? WipeableObjectStore)?.wipeAllObjects()`.

  ⚠⚠ An optional capability is silently inert wherever it is not implemented, which is the exact trap
  this track keeps falling into. Mitigate the same way `ForgetThisDeviceImplementation` already
  handles its other two steps: fold the outcome into the returned human-readable string — *"store
  dropped (N rows)"* vs *"⚠ store NOT dropped"* — so a store that cannot do it says so instead of
  quietly leaving a half reset behind. Both call sites (desktop/Android `forgetThisDevice`, iOS
  `wipeDeviceState`) owe the same treatment; they already drift-check against each other by comment.

## 7b. ⚖️ Not this feature: "leave this realm and join another" *(raised 2026-08-23)*

D3 makes "Forget this device" a **full** reset — identity, queues and store — consistent across
Android, iOS and desktop. That is the right shape for what the button says it does.

⚠ It is *not* the same as the thing a user often actually wants: **detach from this realm and attach
to a different one**, keeping the device set up. Today that is only expressible as forget-then-onboard,
which throws away the local copy and re-fetches everything.

⚖️ Deliberately **not designed here**, because the hard part is not the mechanism — it is making the
consequences legible. A partial reset has to answer, on screen, before the user commits:

* what happens to data in the realm being left (it stays there; this device loses access);
* whether anything is *carried* to the new realm (nothing is — realms do not share keys);
* why the feature exists at all, i.e. what it saves over forget-then-onboard.

Without those answers it is a second destructive path whose blast radius the user cannot predict —
which is precisely the class of defect this document exists to close. It wants its own concept before
any code, and the existing partial reset is the cautionary case: `admission-revocation-names-the-capability.md`
records a **half** reset (identity gone, queues kept) executing weeks later.

## 8. Alternatives considered

- **Clear the Keychain unconditionally on reinstall** (the Signal-shaped remedy) — rejected, §2: it
  destroys the only copy of A1 for a user who never saved the phrase.
- **Do nothing; document it.** The status quo, and it is what produced §0. The documentation now
  exists (`reset-and-commerce-test.md` §8) and is necessary but plainly insufficient: it protects
  maintainers running resets, not users reinstalling an app.
- **Detect and auto-purge only the queues, silently.** Fixes §0.1 without a UI, and is tempting as a
  first slice — but it leaves §0.2 and §0.3 (the unexplained lock, the missing choice) untouched, and
  silently discarding user work is precisely the class of behaviour this project keeps having to undo.

## 9. Implementation notes for Claude Code

- **Detector**: a `UserDefaults` bool (suggest `app.tenshu.control.installSentinel`) written *after*
  the fork resolves. Read it in `ControlIosApp.init` alongside the existing argv debug routes; the
  populated-custody test can reuse the standalone `IosSecureStorageDelegate` that
  `ControlSurface.wipeDeviceState` already builds before any runtime exists.
- **Fork UI**: a new gate ahead of `LockedView`, mounted where `ControlIosApp` currently decides
  between onboarding and locked. ⚠ `PairingWizardUiState`-style shared state is not needed — this is
  device-local and iOS-only, so it should not grow the shared UiState (and therefore owes no Swift/
  Kotlin cross-build beyond `control-ios`).
- **Discard**: extend `ControlSurface.wipeDeviceState()` with the queue purge (§4) rather than adding
  a second wipe route — one destructive path, one place to audit. ⚠ Its KDoc explicitly documents
  that it does *not* touch the store; update that KDoc in the same change or the next reader will
  trust the old sentence.
- **Tests**: the detector's three cases (fresh device / normal relaunch / reinstall) are pure and
  belong in `control-ios`' Swift tests if any exist, else assert the Kotlin half
  (`wipeDeviceState` purges the queues) in `:sync-service-proxy`, where the queue relations live.
- ⚠ Verify on a **real device**, not a simulator: `simctl erase` clears the Keychain, so a simulator
  cannot reproduce the very condition this gate detects.

---

**Cross-references:** `docs/tech-debt.md` §"A queued Device tombstone can revoke a device that has
since been re-admitted"; `docs/how-to/reset-and-commerce-test.md` §2b (the reset ordering this makes
less necessary); `commerce-accounts-and-sessions.md` §9.4e (the other iOS surface that dead-ends).

---

## 10. As-built (RG-1, 2026-08-19)

- **Detector** — `ControlRootView.probe()` compares the `UserDefaults` sentinel against
  `landingStateKey()`. ⭐ Checked against the *landing state* rather than the Keychain directly, so
  the two can never disagree: `"first_time"` is precisely "no identity to resume", so a genuinely
  fresh device writes the sentinel and falls through to onboarding, and only an identity this
  install has never seen routes to the gate.
- **Fork** — `ReinstallGateView` (`control-ios/Sources/`), a new `Phase.reinstallGate` ahead of
  `.locked`. Device-local and iOS-only, so it adds nothing to any shared `UiState` and owes no
  cross-platform build.
- **Discard** — `ControlSurface.wipeDeviceState()` now also calls `purgeOutboundQueues()`.
  ⚠⚠ **Ordered after `retireThisDeviceRow()`, and that ordering is load-bearing**: the retirement
  works by leaving its own tombstone in `PENDING_TOMBSTONES` and waiting for the push, so purging
  first would delete the very retirement the wipe just waited for.
  ⚠ The purge needs a runtime (the queues live in the object store); with none installed — the DEBUG
  `-wipeDeviceState` route calls the wipe *instead* of installing one — it is skipped and the return
  string says so, rather than failing the wipe.
- **What the purge does not touch**, and why the distinction is "addressed outward" rather than "is a
  queue": `RECEIVED_TOMBSTONES` is inbound and the store survives the discard (D3), so dropping it
  would resurrect deleted rows on the next ingest; `PENDING_REKEY_MIGRATIONS` is a local repair
  journal, and dropping a marker mid-migration leaves the retained store half-migrated with nothing
  left to finish it.
- **Tests** — `OutboundQueuePurgeTest` (`:sync-service-proxy`), three cases: every outbound queue
  emptied *and its rows deleted*, an empty device as a no-op, and received tombstones surviving.
  ⚠⚠ The row assertions are the load-bearing ones — `removeFromArray` alone leaves an immortal row
  (99.87 % of measured `sync/block` rows were exactly that), so a dequeue-only purge would pass a
  queue-length check and still be the bug. Verified by mutation: dropping `deleteRetiredBlock` turns
  that test red and only that test.
- **Threat model** — **T-K-07** added (House Rule #2). ⭐ The gate's real security content is that it
  surfaces a *pre-existing* weakness: deleting the app leaves a usable identity resident
  indefinitely, and a resold or repaired phone carries it. ⚠ It mitigates rather than closes — nobody
  is present during the delete-and-never-reinstall window.

### ⚠ Not yet verified on hardware

Per §9, a simulator **cannot** reproduce this: `simctl erase` clears the Keychain, so the very
condition the detector keys on does not arise. The on-device pass — delete Control, reinstall, and
confirm the fork appears once and each branch behaves — is outstanding.
