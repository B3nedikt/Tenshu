# Metered Gating — Plane Split

**Status:** Draft v0.3 (2026-08-02). **P1 + P1b SHIPPED** (as-built below); **P2 SHIPPED
2026-10-29 (deleted, §10)**; P3–P4 remain. Opened by an
on-device report: Drive's *"Waiting for Wi-Fi"* banner stays up on a phone whose files have all
uploaded, and its *"Sync now over cellular"* button appears to do nothing.

Corrects the **placement** of the metered gate. Sibling of
[`sync-metered-deferred-status.md`](sync-metered-deferred-status.md), which built the
*surfacing* of the deferral (the banner, the override, the `MeteredExempt` trait) on the
assumption that the gate itself sat in the right place. It does not. That doc's §0 premise
is superseded here; its shipped machinery is reused, not discarded.

---

## 0. Why

The `WifiOnly` policy is enforced at exactly one place: a single conjunct wrapping the
**whole** scheduler tick.

```kotlin
// SyncScheduler.kt:450
if (!syncGate.shouldSync() && !forced) { updateDrainLiveness(false); reportSyncHealth(); return }
```

Everything that reaches the relay through the *scheduler* is therefore withheld on a metered
network, and everything that reaches it by any other route is not. The two sets are close to
the exact inverse of what the policy is for:

| On cellular, with the default `WifiOnly = true` | Today |
|---|---|
| File **bytes** (megabytes) — `UploadFile` → `emitFileContent` → `putChunk` | **upload immediately, ungated** |
| Metadata `SyncBlock` (kilobytes) | **withheld** |
| Inbound pull (metadata + tombstones) | **withheld entirely, silently** |

`ControlPreferences.WifiOnly.default = true`, so this is the out-of-the-box posture for every
user on cellular, not an edge case.

Three user-visible consequences, all reported or provable:

1. **The banner lies about what is waiting.** `deferred = !syncGate.shouldSync() && pending > 0`
   (`SyncScheduler.kt:647`), where `pending` is the **realm-wide** `PENDING_BLOCKS` queue
   (`SyncScheduler.kt:659`) — every `Syncable` write from every app (Timeline moments, Fit
   workouts, Maps places, preferences, device rows), not Drive files. It renders as
   *"…%d items saved on this device, ready to sync"* (`strings.xml:200`), which reads as files.
   A Drive user whose files have all uploaded still sees a non-zero count, and always will:
   the queue is structurally guaranteed non-empty on a device running more than one app.
2. **The expensive traffic is the traffic that is not gated.** The policy's entire purpose is
   to protect a data plan, and the megabytes go out regardless.
3. **Inbound is stopped with no signal.** `drainAndPull()` is never reached, so a device on
   cellular receives nothing from its peers. If the outbound queue happens to be empty,
   `deferred` is false and there is **no banner at all** — a silently stale file list. This is
   worse than the false banner, and nothing reports it.

The button is a fourth consequence rather than a separate bug: the path is intact end to end
(`SyncNow` registered in common `:250`, client Ro `:335`, proxy impl
`SyncImplementationFactory.kt:258`, Android holder `PlatformProxyModule.kt:770/897`), but a
successful tap is unobservable — the op returns as soon as the flag is set, and the banner only
clears when `pendingBlockCount` drops, which a *metadata* drain may not visibly achieve. Once
the count means "files waiting", the override becomes legible for free.

---

## 1. The invariant

> **The metadata plane is never metered-gated. The content plane is.**

Metadata is the file listing, names, folder structure, sharing state, tombstones — the thing
that must converge for the app to be coherent, and which costs kilobytes. Content is file
bytes — the thing worth protecting a data plan from.

This is what every mainstream client does (§7). It is also what makes the user-facing story
truthful: on cellular your Drive is *up to date but not fully downloaded/uploaded*, rather
than *frozen and lying about it*.

---

## 2. Ground facts (verified 2026-08-01)

Load-bearing; each was checked in source because the design turns on it.

- **Content upload is inline and ungated.** `UploadFileImplementation.kt:308` calls
  `emitFileContent` synchronously inside the op; it encrypts and `PUT`s every chunk right there
  (`SyncObserver.kt:842`). A failed chunk `PUT` aborts the whole emit (`:843`) and the op's
  caller retries. **There is no content upload queue.** Building one is the bulk of this work.
- **Inbound pull does *not* fetch bytes.** `FileContentManifestApplier` is invoked only on
  demand, fetches one file's chunks by opaque ref, and its own KDoc pins the split: *"the eager
  drain (small + bounded); only the bytes are lazy"* (`:69`). So ungating the pull cannot cause
  a cellular byte storm. ✔ This is the fact that makes P1 safe.
- **The proactive read path is already correct.** `OfflineKeeper` is registered as a
  **periodic, unmetered-only** `HeadlessSafe` op (`OfflineKeeperSchedule.kt:19`). Background
  prefetch already honours the policy through `ConstraintSpec`, independently of `syncGate`.
- **A deferred-content mechanism already exists, in miniature.** When the realm is locked,
  `emitFileContent` parks an *intent* — `PendingContent(entityId, propertyId, filePath)` —
  on a channel (`SyncObserver.kt:756/165`) and replays it on the locked→enabled edge via
  `reemitPendingLockedWork()` (`:317`), driven by the scheduler's `onEnabled` hook. The shape
  we need is this one, generalised and made durable.
- **Not all metadata is small.** `File.Thumbnail` (≤64 KiB, `MAX_THUMBNAIL_BYTES`) and
  `File.ThumbnailLarge` (≤48 KiB, `MAX_LARGE_THUMBNAIL_BYTES`) are stored value properties that
  ride File's **eager entity block** (`FileServiceCommonModule.kt:569/573`). Up to **112 KiB of
  "metadata" per image file**, in both directions. 500 photos ≈ 55 MiB. This is the one place
  the clean invariant leaks, and §6 sizes it honestly.
- **`MeteredExempt` has no live producer.** One production type carries it (`DeviceSyncStatus`),
  and its publisher is still gated off — `publishDeviceSyncStatus: Boolean = false`
  (`SyncProxyInitialization.kt:280`), never set true in any DI. Reshaping it costs nothing.

---

## 3. Gate placement

| Traffic | Gate today | Gate after |
|---|---|---|
| Metadata push (`drainPendingBlocks`, `drainPendingTombstones`) | `syncGate` | **none** |
| Inbound pull (`pullChanges`, `pullTombstones`, shared realms) | `syncGate` | **none** |
| Content **upload** (`emitFileContent` chunk `PUT`s) | none | **`contentGate`** |
| Content **interactive read** (open a file, stream a video) | none | **none** (D4) |
| Content **proactive prefetch** (`OfflineKeeper`) | `ConstraintSpec` | unchanged ✔ |
| `MeteredExempt` exempt queue | bypasses `syncGate` | **moot** — nothing left to bypass |

`contentGate` is the **same `ConstraintSyncGate` instance** the scheduler holds today, injected
into `SyncObserver` instead of wrapping the tick. One user policy, one gate object, moved from
the wrong seam to the right one — no new preference, no new UI, no second source of truth.

The `!syncGate.shouldSync()` conjunct at `SyncScheduler.kt:450` is deleted outright. The other
two gates at that seam (`storageGate`, `entitlementBackoff`) are untouched — they answer
different questions and are correctly placed.

---

## 4. The deferred-content queue

The only genuinely new machinery.

**D1 — defer an *intent*, re-emit from source at drain time. Do not stage ciphertext.**

At `emitFileContent`, if `contentGate` is closed, append `(entityId, propertyId, filePath)` to a
**durable** pending-content journal and return without `PUT`ting. When the gate opens (or the
one-shot override fires), the scheduler drains the journal by calling the *existing*
`emitFileContent`, which reads the bytes back from `FileDatabase` and runs exactly as it does
today.

Why intent rather than ciphertext:

- **No new at-rest exposure.** The bytes are already in `FileDatabase`; re-reading them at drain
  time adds nothing. Staging ciphertext would create a second on-disk copy of user content and
  owe a threat-model A5 amendment (§9).
- **No double storage.** A 2 GB import would otherwise need 4 GB.
- **The code already exists** — this is the locked-realm path (§2) with a different trigger.
- Content is content-addressed and the CEK is minted fresh per emit
  (`file-content-addressing.md` §3.4), so a deferred emit is not a *retry* of a partial upload;
  nothing was `PUT`, so nothing is orphaned.

**Durability is the new part.** `pendingContent` is an in-memory `Channel` (`SyncObserver.kt:165`),
which is fine for an unlock that happens in-session but not for a Wi-Fi wait spanning days and
reboots. The journal follows the established local-only-durable pattern —
`PENDING_LANE_DELTAS` (task lanes) and `PENDING_REKEY_MIGRATIONS` (rekey markers): a local-only
composite with a `DataIdProvider`, in a durable array, never on the wire.

**D5 — coalesce last-wins per `(entityId, propertyId)`.** Re-uploading the same file before the
drain must not queue two emits; the newest intent wins. Same shape as the lane's
`LinkedHashMap` coalescing (`SyncObserver.kt:172`).

**D6 — resolve against current state at drain time.** The file may have changed or gone:
- deleted → drop the intent (its tombstone already went out on the ungated metadata plane);
- modified → a fresh `UploadFile` re-stamps `contentId`/`contentHash` and re-queues, and D5
  collapses the pair. The drain always reads *current* bytes, so a stale intent cannot resurrect
  old content.

**Ordering.** The entity-before-bytes invariant (`UploadFileImplementation.kt:296–304`) holds by
construction at replay: the entity block was emitted at upload time on the ungated metadata
plane, so the deferred manifest block necessarily carries a later HLC.

**Liveness.** `updateDrainLiveness` (`SyncScheduler.kt:496`) must count the content backlog too,
or a long deferred upload loses its FGS protection mid-pass and strands — the §4.6 hazard the
existing liveness signal was built for.

**The override moves with it.** `forceDrainOnce()` currently buys one tick past the metadata
gate. It becomes a one-shot **content**-gate override, consumed by the content drain. Same seam,
same one-shot semantics, same `SyncNow` op — the only change is what it unblocks. Because the
banner's count will then mean "files waiting", a successful tap becomes visible for free, which
is the actual fix for "the button does nothing".

---

## 5. The read side

**D4 — do not gate interactive reads.** Opening a file, scrubbing a video (`drive-streaming-preview.md`),
or reading a shared file is a direct response to a user action on the thing they just tapped.
Blocking it makes the app feel broken, and OneDrive/Dropbox both allow it (they *prompt* rather
than refuse). Proactive prefetch is the case worth gating, and it already is (§2).

An optional per-item confirmation for large files on metered ("This will use ~40 MB of mobile
data") is deferred to P4 — it is a UX addition, not part of the correction.

---

## 6. The fat-metadata leak, and what it costs

Thumbnails are the one place the invariant is not clean: up to 112 KiB per image file rides the
eager entity block in both directions (§2). After P1, pulling a peer's 500-photo import on
cellular downloads ~55 MiB of "metadata".

**This is not a reason to keep the current gate** — today that same pull is blocked *along with
the file listing*, which is the bug. But it must be stated rather than discovered later.

**`MeteredExempt` cannot express the fix, in either polarity.** The trait is tested per-type and
per-property (`SyncObserver.kt:399/406`), but the per-property branch only fires when the
property's own schema carries `Syncable`; thumbnails are plain `valueProperty` registrations
that ride *inline* in File's entity block. The fat payload is **fused to the row that must
always sync**. Gating it means gating the listing.

Separating them is structural, not a trait: thumbnails become their own `Syncable` property
blocks, or move to the content plane. That is the same move already made for `Workout.Streams`
(`fit-workout-streams-content-plane.md`). Only *after* that separation does a per-type marker
have anything to bite on — and then the right marker is the **inverse** of `MeteredExempt`
(`MeteredHeavy`: default free, mark the few bulk carriers), because a default of "free" fails
safe when someone forgets to annotate, and a default of "gated" fails silent. Sized as P3;
independently shippable; not a P1 blocker.

---

## 7. How others solve it

| | Metadata / listing | File bytes |
|---|---|---|
| Google Drive, Google Photos | always syncs, any connection | "Back up over Wi-Fi only" + separate daily mobile-data cap |
| Dropbox | always | "Sync only over Wi-Fi" gates transfers; per-row *Waiting to sync* badge |
| OneDrive | always (Files On-Demand) | per-item "Download over cellular?" prompt |
| iCloud Photos | always | "Cellular Data → Unlimited Updates" affects bytes only |

Three conventions worth copying, all of which fall out of §1: metadata is never gated; the
metered state appears **per row that is actually waiting** plus a passive global hint, never an
alarming persistent banner; and the escape hatch is **contextual** rather than a global
fire-and-forget.

---

## 8. Status signal and banner

**D3 — the content backlog rides a NEW single-row local relation, not a new field.**
`SyncHealthStatus` is a positional composite whose KDoc reserves its fields precisely because a
version-skewed Drive↔Control pair would mis-parse an added one. The precedent for exactly this
situation is `SchemaFenceStatus` (`core-schema-evolution.md` §7.7): a locally-derived,
**not-`Syncable`** single-row signal in its own relation. Same here — a `ContentTransferStatus`
row carrying `pendingFileCount` / `pendingBytes` / `deferred`.

⚠ A *new relation* is still additive-and-peer-breaking against a stale Control (the silent-empty
class). Clients move in lockstep (HR #9) and `scripts/android-smoke.sh` asserts it; call it out
in the PR.

Banner changes (`SyncBanner.kt`):

- `Deferred(pendingCount)` re-keys onto the **content** backlog. Copy becomes *"Waiting for
  Wi-Fi — N files waiting to upload"* — true, user-recognisable, and **zero when Drive is done**.
- Its priority drops **below `Offline`**. Today `Deferred` outranks `Offline` because a closed
  gate suppressed all relay contact, making `isOnline` meaningless. After the split the metadata
  plane keeps talking to the relay, so `isOnline` is trustworthy and "offline" is the better
  truth when both hold.
- The metadata-backlog reading of `deferred` disappears entirely — there is no such state left.

---

## 9. Threat model

**No amendment expected**, and that is a consequence of D1 rather than luck:

- The relay sees the same ciphertext, in the same shapes, over the same routes (HR #1 intact).
- Deferring intents rather than ciphertext adds **no** new at-rest user content — the bytes are
  already in `FileDatabase` under A5's existing treatment. *Had* we staged ciphertext (rejected,
  §4), A5 would need amending.
- Metadata now reaching the relay on cellular is a **timing** change, not a disclosure change:
  the same blocks, sooner. The relay could already observe them once the device saw Wi-Fi.

The one thing to re-read before P1 lands: A5's transient-plaintext notes, to confirm the journal
holds only `(entityId, propertyId, filePath)` and never bytes. If P3 moves thumbnails to the
content plane, that slice carries its own review (new blob refs on the content plane).

---

## 9b. As-built — P1 (2026-08-02)

Shipped as designed; the deltas worth knowing:

- **`PendingContentEmit`** (`:sync-service-common`) + `PENDING_CONTENT_EMITS`, following the
  `RekeyMigrationMarker` pattern exactly (composite + `DataIdProvider` trait + Dto + provider impl,
  registered in `syncServiceCommonModule`). Fields: `emitKey` / `targetEntityId` / `propertyId` /
  `filePath` — **no bytes**, which is what keeps §9's "no threat-model amendment" true.
- ⚠ **`addToArray` appends; the `DataIdProvider` key alone does not upsert.** D5's last-wins needed
  an explicit remove-then-add inside one transaction (the `initSyncHealth` idiom). Caught by
  `reEmittingTheSameFileCoalescesToOneIntent`, which saw 3 entries where the design assumed 1.
- **`emitFileContent` split in two**: the public method checks the gate and journals; a private
  `emitFileContentNow` holds the body. Without the split a *forced* replay would be re-refused by
  the very gate the override exists to bypass.
- **Drop-if-gone is asked, not inferred**: a `false` return means either "transient failure" or
  "file is gone", so the drain tests `fileDatabase.fileSize == null` — the same question the emit
  itself asks — instead of guessing from the boolean. One dead entry never blocks a live one.
  ⚠⚠ **Superseded 2026-09-22 (`content-plane-refcount.md` §6d.1):** the journal is keyed on
  the **content** it publishes, not on `(entity, property)`, and absence at the journalled path no
  longer drops an entry while a live row still names that content — a move had made "no bytes here"
  mean "moved", and a peer's move had made the retired row answer "no content id", which the emit
  then reported as done.
- **Liveness** gained `contentDrainable()` (gate open **or** override pending), so a released
  backlog keeps its FGS while gate-held content still never pins the process.
- ⚠ **fmt v18 → v19.** Nothing crosses the wire and no existing composite reshaped, so no
  `ServiceSchema` version moves and no migration step is registered; the bump is owed purely for
  the new stored collection (HR #9's "when in doubt"). No relay redeploy.
- **Six `SyncSchedulerGateTest` tests asserted the old inverted contract** and were rewritten —
  they are the ones that pinned "a blocking gate skips the whole cycle". The two that replace them
  (`closedMeteredGate_stillDrainsMetadata`, `closedMeteredGate_stillPulls`) are the regression
  guards; content behaviour moved to the new `MeteredContentGateTest`.
- **Drive-by:** `-Dtenshu.schemaBaseline.regenerate=true` never reached the test JVM (nothing
  forwarded it past the Gradle daemon), so the regenerate command in `SchemaShapeBaselineEnforcementTest`'s
  own KDoc silently did nothing — it just re-reported "not pinned yet". Forwarded in
  `system-test-utils/build.gradle.kts`.

## 9c. As-built — P1b (2026-08-02)

Closes P1's known wart (the verdict was content-driven but the figure was still the block backlog).

- **`ContentTransferStatus`** (`:sync-service`) + `Relations.CONTENT_TRANSFER`, mirroring
  `SchemaFenceStatus` exactly: composite + Ro + Dto + `DataIdProvider` in `:sync-service-common`,
  single row keyed on `SINGLETON_KEY`, non-`Syncable`, IPC-local. Carries `deferred` /
  `pendingFileCount` / `pendingBytes`.
- **`pendingBytes` is populated from v1**, not reserved-and-empty: the field could not be added
  later without breaking a version-skewed pair, and "12 files (340 MB)" is the obvious next copy.
  Summed from `FileDatabase.fileSize` per journal entry — the same lookup the drain's gone-check
  makes, so a vanished file contributes 0 rather than a stale size.
- **No `SyncService` version bump**, for the reason `SchemaFenceStatus` recorded verbatim: the
  per-service version is the *data* version that fences peers, and raising it over a purely local
  status row would make the epoch writer pull-fence every not-yet-updated device.
- ⚠ **`initContentTransferStatus` writes only on change** (`distinctUntilChanged` over the
  projected triple), unlike `initSyncHealth`. The holder ticks every scheduler pass (1.5s while a
  stream is hot) and each write is a store transaction that wakes every observing client; sync
  health genuinely moves most ticks, a deferred queue does not.
- **Banner:** `Deferred` keys on the content row; **`Offline` now outranks it** (§8); copy is
  *"Waiting for Wi-Fi — %d files still to upload. Everything else is up to date."* and the action
  reads *Upload over cellular*. The second sentence is only truthful **because** of P1 — metadata
  really does keep syncing now.
- **Tests:** `deferredCountIgnoresTheBlockBacklog` pins the reported bug directly (42 blocks queued,
  1 file waiting → the banner must say 1); `offline_outranksDeferred` pins the flip;
  `deferredContentFiguresReachTheHealthHolder` pins files *and* bytes.
- Baseline regenerated (two added lines: the composite and its provider trait; no reshapes).

## 9d. On-device pass — CPH2581, 2026-08-02

Ran on real hardware by marking the live Wi-Fi metered (`cmd netpolicy set metered-network`), which
exercises the same `NOT_METERED`-absent path as cellular without cutting connectivity.

**Confirmed — the whole lifecycle:**

```
SyncScheduler -> metered-deferred: content gate closed (WifiOnly on a metered network)
with 1 file(s) waiting to upload. Metadata push + pull keep running; only file content is held.
SyncScheduler -> pending-blocks push recovered: queue drained.
SyncScheduler -> inbound pull recovered; relay reachable again.
```

- Content deferred, `deferred=true pendingFileCount=2 pendingBytes=15716` (exactly 2 × 7 858) —
  files *and* bytes correct.
- Metadata pushed **and pulled** throughout: both uploads appeared in the listing while their bytes
  stayed on the device. §0's inversion is fixed in the place it was reported.
- Restoring unmetered Wi-Fi uploaded both files, cleared the journal and dropped the banner within
  ~12 s.

**⚠ Found: the §8 priority flip was unsafe as argued, and is now underpinned.** Throughout the
metered window the banner read *"Offline"*, never *"Waiting for Wi-Fi"* — `isOnline` was `false`
while the relay answered a ping in 13 ms. `Offline` outranks `Deferred`, so an unfounded offline
verdict **masks the state this whole slice exists to show**.

Root cause is that `SyncHealthFigures.online` was "the outcome of the last relay call we happened
to make", carried forward with no notion of *when*: `online = tickOnline ?: it.online`. A tick that
attempts nothing (`tickOnline == null` — idle, unregistered realm, schema fence) leaves the last
verdict standing indefinitely, so one failure reports Offline for hours. The same latch also made
the banner reappear on healthy unmetered Wi-Fi later in the session.

Fixed by [`RelayReachability`]: report offline only on a failure that is **recent** (evidence
expires after 90 s, so a device that stopped attempting stops claiming) **and sustained** (nothing
succeeded within 20 s, so a single bad tick among good ones is noise). An actively-offline device
retries every tick, so its evidence stays fresh and it is still named after one grace window.
⚠ The *raw* outcome still drives the reconnect re-drive — that must fire on the true transition,
not on this deliberately-laggy verdict.

Re-verified on device: the stale Offline banner is gone.

## 9e. Second on-device pass — the button, 2026-08-02

**✅ The button works, verified end to end.** One tap produced the whole chain:

```
Drive -> syncNow (Sync now over cellular) signalled to the proxy
SyncScheduler: forceDrainOnce() requested — one drain will run past the metered gate
SyncScheduler: consuming the forceDrainOnce override — uploading deferred content over the metered network
SyncScheduler -> metered-deferred cleared (gateOpen=false, waiting=0).
```

Note `gateOpen=false` on the clearing line: the gate was **still closed** and the file uploaded
anyway — the one-shot override doing exactly its job. The banner went *"Waiting for Wi-Fi"* →
*"Syncing your changes…"*. ~9 s from tap to consumption (one tick).

**⚠⚠ Root cause of the banner flapping — one scheduler PER CLIENT SESSION.** `initSyncService`
runs inside each client session (`PlatformProxyModule`, `scope = sessionScope`), so every
connected app gets its **own `SyncScheduler` + `SyncHealthHolder`** — and all of them write the
same single-row `SyncHealthStatus` / `ContentTransferStatus` relations. Proof: `DeferredStateLog`
prints once per instance per transition, and 3–5 identical "metered-deferred" lines appeared
within half a second, with no intervening "cleared".

The row is therefore last-writer-wins across N writers, several of which are freshly constructed
with **default figures** (`online=true, deferred=false`) that overwrite a correct verdict until
their first tick. That is why the banner flickered and why the deferred state kept vanishing.
Isolating to a single client (force-stopping the other six apps) made it immediately stable — and
is how the button test was finally run.

⚠ **Pre-existing and out of this slice**, but every single-row derived signal inherits it
(`SyncHealthStatus`, `SchemaFenceStatus`, and now `ContentTransferStatus`). Worth its
own tech-debt entry: either one scheduler per proxy process, or these rows must be owned by a
single writer.

**Two copy bugs found and fixed on the strings this slice touched:**

- ⚠ **A bare `%d` is never substituted** — the banner rendered a literal `"%d"`. CMP's formatter
  only handles **positional** specifiers (`%1$d`), which is what the rest of `strings.xml`
  uses. The pre-split string had the same latent defect. ⚠ `move_picker_title` /
  `move_picker_title_multi` still use bare `%s` / `%d` and are presumably broken the same way —
  not touched here.
- `"1 files"` → a singular string picked in code. Deliberately **not** a `<plurals>` resource: the
  project has neither `<plurals>` nor `pluralStringResource` anywhere, and ADR 0016 defers the ICU
  catalogue at n=1, so introducing that mechanism is not this slice's call.

**Priority reverted: `Deferred` outranks `Offline` again.** P1b's flip assumed the split made
`isOnline` trustworthy. Across both metered windows the banner only ever read "Offline" and the
deferred state was unreachable — the session churn above means `isOnline` flaps between writers
regardless of how well it is derived. It is also simply the better message: both are true of an
offline device with queued uploads, but "Waiting for Wi-Fi — N files" names the cause and carries
an action. [`RelayReachability`] is kept — it is correct on its own terms and fixed a real stale
banner on unmetered Wi-Fi.

**Remaining from the proposal:** P3 (separate the fat thumbnail metadata), P4 (per-item UX). P2
shipped 2026-10-29 as a deletion (§10).

## 10. Phases

Each independently shippable; P1 alone fixes the reported bug.

- **P1 — move the gate.** Delete the `syncGate` conjunct at `SyncScheduler.kt:450`; add
  `contentGate` to `SyncObserver.emitFileContent`; durable pending-content journal + drain step
  in `drainAndPull`; `forceDrainOnce` re-pointed at the content gate; liveness counts content.
  ⚠ Bumps `TENSHU_STORAGE_FORMAT_VERSION` (new durable stored collection — HR #9; when in doubt,
  bump). **No relay redeploy** — the relay gates on `RELAY_PROTOCOL_VERSION` only (HR #9 note,
  `core-schema-evolution.md` §7.5/§7.8).
- **P1b — the banner.** `ContentTransferStatus` row + `SyncBanner` re-key + copy + priority
  (§8). Additive relation ⇒ lockstep ⇒ smoke gate.
- **P2 — retire the dead machinery.** With nothing left to bypass, `MeteredExempt` and
  `PENDING_EXEMPT_BLOCKS` have no purpose. **Decided and shipped 2026-10-29: deleted**, with
  their only carrier `DeviceSyncStatus` and its never-enabled publisher, before the production
  reset (every schema version was still 1, so dropping a registered synced type was a free
  in-place reshape). Exempt saves were already none; every save now takes the ordinary personal
  queue. Nothing is re-polarised: P3's `MeteredHeavy` is a **new** trait, not this one renamed.
- **P3 — separate the fat metadata** (§6): thumbnails become their own property blocks or move to
  the content plane; then a `MeteredHeavy` marker becomes expressible. Own proposal if it grows.
- **P4 — per-item UX** (optional): per-row *waiting to upload* badge; large-file cellular
  confirmation on open (§5).

## 11. Rejected alternatives

- **Mark every `Syncable` type `MeteredExempt`.** Same end state as deleting the gate, reached
  type by type across every service, with a permanent obligation on every new type — and missing
  one silently stops that app syncing on cellular. Wrong default (§6), and push-only: it cannot
  touch the pull side at all, because inbound blocks are opaque ciphertext with nothing to test a
  trait against until they have already been fetched. It would also route the main flow through
  `drainExemptBlocks` (`SyncScheduler.kt:755`), which has no drain-failure logging, no
  orphan-quarantine reporting, does not own the entitlement-backoff reset, and does not drain
  tombstones — deletes would stay stuck on cellular.
- **Stage ciphertext for deferred uploads** — double storage + a new at-rest content copy + an A5
  amendment, for no gain over D1.
- **Add a field to `SyncHealthStatus`** — positional composite; peer-breaking (§8).
- **Gate interactive reads** — makes the app feel broken; no mainstream client does it (§5, §7).
- **A separate "sync metadata on cellular" user setting** — the user should not have to
  understand our plane split. `WifiOnly` keeps its plain meaning: *don't spend my data plan on
  file transfers*.

## 12. Gates

- `./gradlew desktopTest jvmTest test` (the real gate — `test` alone skips every KMP module).
- New coverage: gate-closed upload journals rather than `PUT`s; journal survives a simulated
  process restart; drain re-emits and clears; deleted-file intent dropped; last-wins coalescing;
  pull is **not** gated on metered (the regression that would re-introduce silent staleness).
- `scripts/android-smoke.sh` — touches `androidMain` + `:control` (HR #8), and P1b's additive
  relation needs the lockstep assertion.
- On-device, the only real proof: a phone on cellular must show a **current** file list from a
  peer, an accurate *"N files waiting"* count that reaches **zero**, and an override tap that
  visibly drains it.
