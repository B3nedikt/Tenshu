# Stopping an upload, and reclaiming what it leaves behind

**Status:** v0.26 — **ST-6 + ST-7 shipped** (§4.20: an over-quota `507` no longer storms, and "full" now means what it says; §4.21: the pre-flight counts the queue in front of a new upload). **ST-3f is LIVE** (§4.18); **ST-3b is LIVE on desktop and Android** (§4.25, §4.26) — §4.23's *do not flip* verdict is spent, the barrier converged, and 4 634 B were actually reclaimed. iOS stays observe-only pending one two-app device pass. ⚠ The instrument is still the wrong one (an idle peer's tip does not move, so a quiet realm never clears the margin); both platforms are on *despite* that. §4.19 designs the honest empty state. ST-1 (the relay `.tmp` leak) is **shipped and
deployed** (relay runs `c4772fea`), ST-0b/ST-0c/ST-0d shipped, and **ST-2a** — the reported bug —
shipped with v0.4 and **ST-2b** with v0.5/v0.6; ST-3 and ST-4 remain, and ST-3 owes a maintainer decision before any code.

> ⚠⚠ **ST-3b is REMOVED (CR-2 B6, 2026-10-04, `content-plane-cr2-b6-spec.md` §2, §7):** the sweep, its policy, barrier, candidate list, recorder and the `RECLAMATION_SWEEP_ENABLED` switches are gone, and so is `POST /sync/content/delete`. Content is freed only by the relay's own sweep (CR-2 B5), deployed `armed`. ST-3f (repair) stays. The status above is the record up to B6.

v0.25 adds **§4.24** — ST-8. Control now *names* an orphaned identity in the device list. ⭐ The
manual repair already worked: every revocation path keys on `deviceId`, not the shared keypair, and
the orphan's lineage subtree is empty. ⚠⚠ **D15** — the automatic version is unsafe: two machines can
hold one keypair (backup restore), and `Device` rows have no regeneration path.

v0.24 adds **§4.23** — the dry run's first real answer, and it is a **negative**: one candidate,
would-reclaim **0**, waiting on all five peers, unchanged across 100 reports in 13 minutes. ⚠⚠ One of
the five is the desktop's own **orphaned identity** — two device ids sharing one keypair — so it can
never ack and the barrier can never clear. ⚠⚠ Corrects §4.22's "no device is provably dead":
authoring a write is a fact about the past, the barrier needs a future ack. **D14.**

v0.23 renumbers the §4.22 decision **D11 → D13**, records §4.22 here, and corrects two live
statements that **TC-2b is "still open"** — it has been *built but not device-verified* since
2026-08-30 (`sync-hlc-tip-device-identity.md` §6), so the work in front of the ST-3b flip is a
**device pass, not an implementation**. ⚠ The renumber and the missing entry are a rebase
window's doing: the reclamation dry run and the full-realm stop / queue-counting pre-flight landed 30 minutes apart, each appending a decision to this
doc, and neither saw the other. ⚠⚠ Two different D11s stood for ~40 minutes, cited by *adjacent rows
of the same phase table* — §4.20's "the relay is the only authority on whether a write fits" and
§4.22's flip gate. A decision number that means two things is worse than an unnumbered one.

v0.22 corrects a freshness claim §4.20/§4.21 and five KDocs shared: usage is no longer frozen for
the life of the process but re-fetched on a **60 s backstop**. ⚠ The conclusions are
unchanged — chunk-size blindness is independent of freshness, and a second device can spend the room
inside any remaining window — so D11 stands and only the relay's answer closes the gap.

v0.21 adds **§4.21** — ST-7. The pre-flight measured a new upload against room the queue in front of
it had already claimed. ⚠⚠ The single-file gate and the folder-upload pre-check are the same question
at two scales and now share one helper. ⚠ The negative is what matters: the journal is transiently
non-empty on every upload, so a gate keyed on the queue *existing* would refuse everything.

v0.20 adds **§4.20** — ST-6. A full realm was retrying 30 doomed `PUT`s/second because `isFull` asked
`remainingBytes <= 0` (836 bytes free reads as *not* full) and a `507` was tagged as transient. ⚠⚠ The
client cannot answer "does the next write fit" from two numbers — D11 makes the relay the authority.
⭐ One predicate now serves the banner, the backup gate and the share gate.

v0.19 adds **§4.19** — the honest empty state is a **protocol** question, not UI work: three of the
four causes of a missing manifest resolve themselves, so "gone" is not observable and no elapsed-time
rule can make it so (D9). The signal should travel through the sync log, not the relay (D10).
⭐ Part of the fix is unblocked today — the current screen tells a *format* story about a *content*
problem.

v0.18 adds **§4.18** — ST-3f built: a repair is a re-emit under the same `contentId`, because the id
is a stable value on the entity and `manifestContentRef` is a pure function of it. ⭐ One guard —
*this device holds the plaintext* — makes it additive **and** unable to race a live upload. ⚠ The
honest empty state and an end-to-end demonstration are not built; both gate the flip.

v0.17 completes **§4.17** — `contentChunkRefs` is the last seam, the sweep rides the sync tick
(the only placement where it reads the snapshot it acts on), composition lives in DI because
`:sync-service-proxy` must not reach into `:file-service-proxy`, and the whole thing is off behind
`RECLAMATION_SWEEP_ENABLED` until TC-2b closes.

v0.16 adds **§4.17** — ST-3b as built on §4.16's barrier: the policy no longer holds a clock, the
inbound half went *global* ("have I consumed the whole log?") and so needs no new persistence, wire
field or endpoint, and three `?: emptyX()` lines that read as defensive are the dangerous ones.
⚠⚠ The sweep is still not constructed — `chunkRefsOf` has no public seam and nothing schedules it.

v0.15 adds **§4.16** — the 7-day settle window is the wrong *shape*, not the wrong number: the
risk is fixed at the delete and does not decay, so a timer expires into the one case that was never
safe. ⚠⚠ The convergence barrier the policy KDoc denies exists (`acknowledgementSet` + the `peerTips`
already on every response), ⚠ its inbound/outbound split, and **ST-3f** — dangling-reference repair
as the prerequisite for ever offering "reclaim anyway". Decisions D5–D8.

v0.14 adds **§4.15** — ST-3b is forward-only and frees nothing already stranded; §4.8's "zero
orphans" answered the relay's question, not the client's; and a backfill (**ST-3e**) is a different,
more dangerous mechanism that owes shared-realm enumeration first.

v0.13 adds **§4.14** — the client `deleteChunks`, and a precise statement of the two DI seams left:
the recorder, and the live-set provider whose `children` seam is where the safety property can be
switched off silently.

v0.12 adds **§4.13** — the sweep that joins every ST-3b piece, and the maintainer's decision to
scope it to all files rather than large ones (with what that trades).

v0.11 adds **§4.12** — the delete site records its orphaned contentIds, and the one remaining gap
is joining the built pieces rather than building more.

v0.10 adds **§4.11** — the durable candidate record and the realm live set, plus the derive-instead
alternative that fails on an uninvertible hash.

v0.9 adds **§4.10** — ST-3b's client-side decision as built, the manifest-by-contentId mechanism
that makes it possible, and the two missing pieces of plumbing that are the real work.

v0.8 adds **§4.9** — ST-3b's relay endpoint as built, and why the client half is held back: a
copy shares one `contentId`, so orphanhood is a whole-realm question with a race that can destroy
content the relay holds the only copy of.

v0.7 adds **§4.8** — the ST-3a census, which **falsifies §3.2's prediction**: the relay holds
**zero** orphaned blobs, so the fill was live content rather than waste, and a time-based sweep has
nothing to collect.

v0.6 adds **§3.8** — Stop on the Files-screen banner, offered only when a backup drain feeds it:
a hand-started upload renders the identical state and has nothing to pause.

v0.5 adds **§3.7** — Stop/Resume as built, and the `scope.async` mistake it nearly shipped:
a failing drain would have killed the trigger loop for the process lifetime, invisibly in
production because its scope carries a `SupervisorJob`.

v0.4 adds **§3.6** — ST-2a as built, and ⚠⚠ why §3.5's step 1 would **not** have fixed the reported
bug on its own: the gate never reads the per-folder selection, which is the switch the report was
filed against.

v0.3 added **§4.6** — deploying ST-1 hit a *second* full disk, the droplet's **root** disk, filled by
9.1 GB of unrotated container logs that the *first* failure's error storm had produced. Two coupled
capacity failures, neither alerting. Fixed durably across all three compose files.

v0.2 added what the incident itself taught: **§4.1's correction** — entitlement enforcement is *on*, and the quota failed to bind because one tier grant exceeds the whole disk; **§4.4** — per-file reclamation is not a missing flag but
forbidden by House Rule #1, and the only granularity that would work is a *client-driven* delete
that does not exist; and **§4.5** — the volume grow as actually run, including the Terraform drift
that would have destroyed the droplet had it been applied untargeted.

**Trigger (2026-08-14), two hours apart and — it turns out — the same problem:**

1. The relay began answering **500 `internal_error`** to every write. Its 9.8 GB volume was
   **100 % full**: 9.7 GB of blobs accumulated Aug 8–14, and nothing has ever reclaimed them.
2. *"We cannot actually stop the upload. When I switch the toggle in Synced Folders of Camera back
   to off, I would expect the upload to stop, it does not. Also the badge itself does not allow me
   to stop the upload."*

Companions: [`drive-synced-folders.md`](drive-synced-folders.md) (the backup loop this controls),
[`retention-quota-model.md`](retention-quota-model.md) (P3, the reclamation that is built and
inert), [`content-plane-transfer-concurrency.md`](content-plane-transfer-concurrency.md) (the
chunked upload path), [`file-content-addressing.md`](file-content-addressing.md) (⚠ its Ph4 GC is
**superseded** — reclamation belongs to `retention-quota-model.md`).

---

## 1. One spine: the write path has no brakes

Three defects that look unrelated share a shape. **You cannot stop a write, a failed write leaks,
and nothing reclaims.** The outage was all three at once: Drive uploading into a relay that had
filled, with no way to stop it, leaving partial writes that nothing will ever collect.

| | defect | where |
|---|---|---|
| **ST-1** | a failed blob write leaks its `.tmp` forever | `sync-relay-server` `FileBlobStore.put` |
| **ST-2** | "off" does not stop an upload, and the badge offers no stop | `SyncedFolderController` + Drive UI |
| **ST-3** | nothing reclaims relay content | `retention-quota-model.md` P3, then inert (live since 2026-10-24, closed accounts only) |

---

## 2. ST-1 — a failed write leaks its temp file

`FileBlobStore.put` writes `<name>.tmp` and renames, which is the right shape for atomicity. But
there is **no cleanup on the throw path**: when `tmp.writeBytes(bytes)` fails — as every write did
once the disk was full — the empty `.tmp` stays. Clients retry forever, so the leak is one file per
attempt: **8,682 stale `.tmp` files** had accumulated in under two hours (15:26 → 17:25).

⚠ They were only 740 KB, so they did **not** cause the fill — the fill was 9.7 GB of real blobs.
But on an inode-constrained filesystem this is its own outage, and a store that leaks under the
exact condition it is most likely to meet is worth fixing regardless. A `try/finally` is the whole
change.

---

## 3. ST-2 — "off" means "don't start the next drain"

### 3.1 What actually happens

`drainAllSources` evaluates `evaluateBackupGate(settings, storage, network, charging)` **once per
drain**, before looping over sources. `drainSource`'s per-item loop calls
`currentCoroutineContext().ensureActive()` — so it honours *coroutine* cancellation — but it never
re-reads `settings.enabled`.

Flipping the toggle writes a `DEVICE`-scoped preference, which emits into
`settings.collect { triggers.trySend(Unit) }`. That only **queues another drain**. The in-flight one
holds `drainMutex` and runs to completion, uploading every remaining file.

The badge renders `BackupState` and offers no action at all — even though each source drain already
runs as **one `BackgroundTask`** (`core-task-composite.md` TS-5) and the platform has a real
cancellation plane (`CancellableRemoteTaskExecutor.cancel(taskId)`, `ProxyGateway.cancel`). The
wiring simply was never exposed.

### 3.2 ⚠⚠ What aborting costs *today*, which decides the design

A 200 MB video is ~100 chunks at `FileContentChunkSizeBytes` = **2 MB**, four in flight
(`chunkTransferConcurrency`). Aborting it costs two things, and the second is the surprise:

- **All progress.** There is no resume. `SyncObserver` mints a **fresh CEK per emit**, and its own
  comment says so: *"a re-emit of the same content re-keys + re-PUTs its chunks, orphaning the old
  blobs."* A retried 200 MB upload starts from zero.
- **Permanent relay garbage.** Those orphaned chunks are collected by nothing: Ph4 GC is superseded
  and P3 was inert (live since 2026-10-24, but for closed accounts only). **Every abort of a large file leaks up to that file's size onto the relay,
  forever** — on the relay that just filled up.

So "abort in-flight" is the right *instinct* and currently the expensive *implementation*.

### 3.3 How the comparable products do it

⚠ General industry knowledge, not a citation — worth verifying before it is quoted as a fact.

OneDrive, Dropbox and Google Drive all present **Pause** rather than Cancel as the primary control
(OneDrive offers 2/8/24 hours; Dropbox pauses until resumed), with automatic resume. A destructive
per-file cancel is rare; the "stop forever" gesture is **removing the folder from the sync set** —
which is exactly what the Synced Folders toggle already is.

⭐ **They can afford an instant abort because uploads are resumable sessions.** The server holds
partial state keyed by a session, the client resumes from the last committed offset, and — the part
that matters here — **the server expires incomplete sessions on its own**. Their responsive stop and
their storage hygiene are the *same* feature. We have neither, which is why our stop is slow *and*
our relay filled.

### 3.4 Decisions

- **D1 — Stop is a pause, not a cancel.** Matches every comparable product, and removes the
  "accidental click destroys a 200 MB upload with no resume" risk without a confirmation dialog.
  ⚠ A dialog was considered and rejected: it puts the cost on every deliberate stop to protect
  against the rare accidental one, and it is the wrong instrument when the underlying action is
  cheap to undo. **Forgetting the queue** stays where it already lives — untick the folder.
- **D2 — Stop takes effect at the next chunk boundary, not mid-chunk.** At most `concurrency ×
  chunk` = **8 MB** is in flight, so the wait is bounded and small (~1–6 s on a typical uplink)
  without any new abort machinery. ⚠ Mid-chunk abort buys a second or two and cannot reduce the
  orphan cost at all — the already-PUT chunks leak either way.
- **D3 — ⚠⚠ A truly instant, cheap abort is gated on resumability, not on UI work.** Keeping the
  emit's CEK and its committed chunk list so a retry resumes is what makes stopping free. Until
  then, **every stop of a large file is a storage cost the user cannot see and cannot recover** —
  which is why ST-3 is not optional garnish on this track.
- **D4 — The grace window is rejected.** A "finish what you started, stop the rest" timer helps a
  queue of small files and does nothing for the 200 MB case that motivated the question, while
  adding a timer to reason about. D2 gets the same responsiveness from a bound that already exists.

### 3.5 Shape of the change

1. Re-read the gate **inside** `drainSource`'s per-item loop and break out on
   `Disabled`/`Paused`, setting the state so the badge reflects it. Stops at a **file** boundary —
   small, testable, and the whole fix for a queue of photos.
2. Thread the drain's `BackgroundTask` id to the UI and expose **Stop** on the badge, cancelling
   that task. With D2 this stops at a **chunk** boundary.
3. `BackupState` gains a paused-by-user state distinct from `Paused(reason)` — "you stopped this"
   and "Wi-Fi is missing" must not read the same.

⚠ **Not in scope:** changing what the toggle *means*. Untick still means "this folder is not backed
up", and D1 keeps the destructive reading there rather than on a Stop button.

### 3.6 ✅ ST-2a as built — ⚠⚠ and why step 1 above was not the fix

Shipped 2026-08-15. **Step 1, on its own, would have left the reported symptom exactly as it was.**

The report was filed against the **per-folder** toggle — *"when I switch the toggle in Synced Folders
of Camera back to off"*. `evaluateBackupGate` reads the **master** switch, storage, network and
charging. It never reads the bucket **selection**. Unticking one folder writes
`selectedBuckets`, which the drain snapshots once via `readSelectedBuckets()` before its loop — so a
gate re-read would have re-confirmed "backup is still on, storage fine, Wi-Fi fine" and uploaded
every remaining photo, exactly as before. ⚠ The design said "re-read the gate" because the gate is
what §3.1 had analysed; nothing had checked that the gate covers the switch the user actually
flipped.

So the per-item loop re-reads **two** things:

| Re-read | Covers | On stop |
|---|---|---|
| `currentGate(...)` | master switch, storage, network, charging | `return` — the whole source stops, `setStable` publishes `Disabled`/`Paused(reason)` |
| `bucketId in readSelectedBuckets()` | ⭐ the per-folder toggle — **the reported bug** | `break` — that folder stops, other selected folders keep going |

⚠⚠ **The gate reads the preferences directly (`readSettings()`), not the cached `latestSettings`.**
The cache is written by a collector coroutine, so trusting it makes "did the user just turn this
off?" depend on whether that coroutine was scheduled between two uploads — true in production only
because a real upload suspends on I/O. A stop must not be contingent on someone else getting a
timeslice. It costs three preference reads per file: milliseconds against an upload's seconds.

⚠ Stopping abandons files the run had already counted, so `run.total` gives them back — otherwise the
surviving folders count toward a denominator including work nobody will do, which is the honesty
`drive-upload-progress.md` §14.3 established.

**Four guards, all verified red without the change** (`SyncedFolderControllerTest`): unticking one
folder mid-drain, the master switch mid-drain (asserting the badge reads `Disabled`, not a later
"Backed up N"), dropping to metered mid-drain (asserting the badge explains *why*), and one folder
unticked while a second keeps uploading.

⚠ **Not fixed here: the badge still offers no Stop button** — that is ST-2b, and it is a different
mechanism (cancelling the drain's `BackgroundTask`, stopping at a chunk boundary per D2) rather than
a bigger version of this one.

### 3.7 ✅ ST-2b as built — Stop, and the one-line mistake it hid

Shipped 2026-08-15. `BackupState.PausedByUser` + `BackupGate.PausedByUser`, a persisted
`drive.backup.pausedByUser` DEVICE preference, `SyncedFolderController.stop()`/`resume()`, and a
Stop / Resume control in the Synced Folders status row.

⭐ **Stop reaches the file being uploaded, not just the queue.** `uploadForBackup` awaits through
`awaitOrWireCancel`, which on cancellation sends a **wire cancel** for the task — so cancelling the
drain stops the current upload at a chunk boundary (D2, ≤ `chunkTransferConcurrency × 2 MB` in
flight) rather than after the whole file. On a 200 MB video that is the difference between Stop
feeling instant and Stop meaning "in a few minutes". ⚠ The mechanism already existed; nothing had
been wired to it.

**Order matters in `stop()`**: persist the pause *first*, then cancel. Cancelling first races the
trigger loop into starting the next drain before the gate can see the pause.

⚠ **A user pause outranks the environment pauses in the gate, and `Disabled` outranks it.** Both a
pause and "no network" can be true; only one has a remedy the user can act on, and telling someone
who pressed Stop that the network is missing is the phone blaming the world for their decision.
Conversely there is nothing to resume when backup is off.

⚠⚠ **`PausedByUser` is deliberately not a `BackupPauseReason`.** Every member of that enum is a
condition the device is in and the user can only wait out. Folding Stop in would have rendered
"Paused — you stopped backup" beside "Paused — no network" as if the remedy were the same, and the
compiler would never have asked. Being a separate state is what made the exhaustive `when`s list
every site that had to decide — notification, registry status, aggregation, screen.

⚠⚠ **The one that nearly shipped:** running the drain as `scope.async` so `stop()` could cancel it
looked equivalent to a child job and is not. A failing `async` cancels its parent scope unless that
scope carries a `SupervisorJob`, so a throwing environment read would have killed the trigger loop
**for the process lifetime** — backup silently dead until restart. Production's scope does carry a
supervisor, so this would have looked fine; the existing
`drainSurvivesThrowingEnvironment_doesNotCrashOrKillLoop` caught it because the *test* scope does
not. The shipped form is a child of `coroutineScope`: cancelling a child does not cancel its parent,
but a child that fails does — exactly the split needed.

✅ The **Files-screen sync banner** now carries Stop too — §3.8.

### 3.8 ✅ Stop on the banner — and why it is not offered for every upload

Shipped 2026-08-16, closing the report's *"the badge itself does not allow me to stop the upload"*
where the badge actually is: the Files-screen sync banner.

`SyncBannerState.Syncing` gains **`stoppableBackup`**, true only when the banner's figures came from
a drain (`BackupProgress != null`). The composable renders **Stop** only then.

⚠⚠ **The flag exists because a hand-started upload produces the identical `Syncing` state.** That
upload has no pause switch and no queue to resume, so a Stop beside it would either do nothing or
mean something different from what the same word means five minutes earlier during a backup — one
control, two meanings, which is how a control stops being trustworthy. Keying on "is the banner
showing a transfer" would have shipped exactly that; keying on "is a drain feeding it" does not.

⚠ Set at **all three** `Syncing` construction sites — a pass in flight, files queued with no pass
reported yet, and a drain whose queue is momentarily empty. Missing one would have made Stop
flicker in and out during a single backup as the branches alternate. Guarded per branch.

⭐ Three existing banner tests went red on the field alone, which is the sealed-shape property doing
its job: they assert whole-state equality, so a new dimension cannot be added silently.

Resume stays in Synced Folders. The banner is a status surface that appears while something is
happening; a paused backup has nothing to narrate, so a Resume there would need the banner to
persist in a state whose whole point is that nothing is transferring.

---

## 4. ST-3 — nothing reclaims, and that is a decision not a bug

`retention-quota-model.md`'s P3 was **BUILT and INERT** (until 2026-10-24) — both flags off, deliberately, because
deletion "needs a deploy-time decision not a code review". The consequence arrived on schedule:
9.7 GB of blobs from six days, a full volume, and every write failing.

### 4.1 ⭐ The quota is built, correct, and switched off

An earlier draft of this section said `relay.env` sets "no quota knobs". True, and it understated
the case: **the per-owner quota path is fully built and one flag away.** `PUT /sync/content/{ref}`
already does exactly the free-vs-paid shape:

- `resolveEntitlement(...)` carries **the tier's `quotaBytes`** through on the allow path;
- `putWithinQuota` sums the owner's stored bytes **under the write lock** (so no concurrent PUT/GC
  can race the total) and rejects **before writing anything**;
- over quota → **507 Insufficient Storage** with `over_quota`, deliberately distinct from 413 (one
  oversized blob) and 402 (no entitlement); a re-PUT of an existing ref never re-counts, so a
  retry by an at-quota owner still succeeds.

**⚠⚠ Why none of it bound — corrected 2026-08-15, and v0.1 had this wrong.** v0.1 said
enforcement was off and *"every owner is currently unmetered"*. **It is on.** The container's own
environment says `ENTITLEMENT_ENFORCE=true`, with an issuer pubkey set. The earlier claim came from
grepping a `relay.env` that **does not exist** at that path and reading empty output as "not set" —
the relay's real configuration lives in `/opt/tenshu/deploy.env` on the droplet, hand-maintained and
outside version control, which is why nothing in this repo reveals it.

**So the quota was enforced and the disk filled anyway.** The reason is arithmetic, not
configuration:

| tier | grant |
|---|---|
| `FREE` | **5 GiB** |
| `BASIC` | **50 GiB** |
| `PREMIUM` | **2 TiB** |

The volume was **10 GB**. ⭐ **A single BASIC owner is entitled to five times the disk**, and two
FREE owners already exceed it. The per-owner quota did exactly what it promises — it rations each
*account* — while nothing related it to the *hardware*. After the grow the volume is 50 GB, which is
precisely one BASIC entitlement.

⚠⚠ **So the real gap is capacity planning against Σ(entitlements), not a flag.** The relay is
either sized for the sum of what it has promised, or deliberately oversubscribed **with monitoring
and a policy for what happens when the bet loses** — which is what happened here, unmonitored. That
is a different question from ST-3's reclamation and does not go away when reclamation lands.

⚠ Free-vs-paid tiering is therefore **already live**: an unentitled owner gets 402 and an over-quota
one gets 507 today.

### 4.2 Capacity: growing is a parameter, not a redeploy

`data_volume_size_gb` is a Terraform variable (default **10**), and the volume is deliberately
independent of the droplet — *"a droplet rebuild/resize never loses the data"*. Raising it and
re-applying grows the block volume **in place, with the droplet running**.

Two caveats the runbook does not currently state, and should:

- DO expands the **device**; the **filesystem** still needs extending (`resize2fs /dev/sda`).
- Block volumes **only grow**. There is no shrink, so oversizing is a permanent cost.

⚠⚠ **There is no alerting of any kind** — no disk monitor, nothing that would have said "90 %"
before writes began failing. The first symptom was a tray badge reading *Offline*. A threshold
alert is the cheapest item on this whole track and it converts every future instance from an
outage into a scheduled decision.

### 4.3 What ST-3 owes, in order

1. **What are the 28,285 blobs?** Orphans from re-emits (§3.2 predicts many), live content, or
   both. Nobody should enable reclamation without that answer.
2. **Then** two decisions that are the maintainer's, not a code review's: turning on
   `entitlementEnforce` (§4.1 — it rations per owner) and P3's flags (it reclaims). ⚖️ P3 may want
   counsel; the doc says deletion is a deploy-time decision.

⚠ *"Why did `putWithinQuota` not constrain the fill?"* was the second question here in v0.1 and
§4.1 now answers it: enforcement is off, so `quotaBytes` is null and every owner stores unmetered.
Left visible rather than deleted — the answer is more useful than the question.

⚠⚠ **Growing the volume is the unblock, not the fix.** It buys time proportional to the same
unreclaimed growth rate, and ST-2's aborts add to it.

---

### 4.4 ⚠⚠ Per-file reclamation is not a missing flag — it is forbidden by House Rule #1

Raised 2026-08-15: *"I just deleted the huge folder, but I guess this does not propagate?"* It does
not, and the reason is structural rather than a gap someone forgot to fill.

The delete **did** propagate — as tombstones on the sync plane, so the references are gone and other
devices show the folder gone. The relay's bytes stayed, and **no per-file deletion can free them**.
`ContentBlobRegistry` says why:

> *"The relay is content-blind (House Rule #1): manifests are ciphertext and a delete tombstone
> exposes only an opaque entity hash, so **it can never learn which blobs a file orphans**."*

That is the same reason `file-content-addressing.md`'s Ph4 refcounting GC is recorded as
HR#1-impossible — of a relay-*derived* count, which stands; the client-*declared* refcount that
supersedes this mechanism shipped 2026-09-09 (`content-plane-refcount.md`). Measured after the
delete: **0 bytes freed**, blob count still climbing.

⚠⚠ **ST-3b's `/sync/content/delete` is scheduled for deletion by that track's CR-2**, which held
2026-09-09 on a device pass rather than on code: ST-3b resolves a candidate's refs by fetching its
manifest, so it reaches the pre-CR-4 corpus the declared refcount structurally cannot. Removing it
before each device has described its live content strands, permanently, any pre-CR-4 file deleted
in that window (`content-plane-refcount.md` §9.10).

**The three granularities that exist, and the one that does not:**

| granularity | state |
|---|---|
| **time-based** `gcSweep` | returns 0 in production — `CONTENT_RETENTION_WINDOW_MS` is `Long.MAX_VALUE`, deliberately, as the **durable-content data-loss fix** (`retention-quota-model.md` §2) |
| **whole-account** `reclaimOwner(ownerId)` | built, but it is account *closure*: ⚠⚠ *"irreversible, and for most owners the relay holds the only durable copy"* |
| **per-file** | ⛔ **does not exist** — no DELETE route on the content plane, and nothing client-side calls one |

⭐ **The missing option is client-driven, and it does not breach HR #1.** The *client* knows exactly
which refs a delete orphaned, because it holds the manifests in plaintext. It can authenticate and
tell the relay "drop these refs"; the relay stays blind and merely obeys its owner. That is the only
granularity that makes *"delete a folder frees space"* true, and it needs an endpoint that does not
exist rather than a flag that is off.

⚠⚠ **So "just enable P3" is the more dangerous of the two paths, not the easy one.** Turning the
time-based sweep on is precisely the shape of the durable-content data-loss bug — it deletes by age, with no idea whether the
content is still referenced. ST-3 weighs the client-driven delete (**ST-3b**) *before* the time-based sweep (**ST-3d**).

### 4.5 As-run: the volume was grown 10 → 50 GB (2026-08-15)

Done during the incident, and recorded because two parts of it are traps.

`terraform apply -target=digitalocean_volume.relay_data` → `1 changed, 0 destroyed`, then
`resize2fs /dev/sda` on the droplet. No restart, no downtime: **50 G, 37 G free**, and the backlog
drained immediately (blobs 28,290 → 37,041 within minutes, Control's health row flipping `offline` →
`online` on its own).

⚠⚠ **The `-target` was not caution for its own sake — a plain apply would have destroyed the
droplet:**

```
Plan: 2 to add, 2 to change, 2 to destroy.
  # digitalocean_droplet.relay must be replaced
      ~ user_data = (sensitive value) # forces replacement
```

The rendered cloud-init has drifted from what the running droplet was built with, and **any**
`user_data` change forces replacement in the DO provider. The drift is still there: the next
`terraform apply` run for any reason rebuilds production unless it is `-target`ed or the drift is
resolved first. Survivable by design (the droplet is cattle, data lives on the volume) but it is an
unplanned outage and a fresh clone-and-build on first boot. **Worth resolving deliberately.**

⚠ **The new size is not in version control.** `terraform.tfvars` and `*.tfstate` are both
gitignored (the former holds `do_token`), so 50 GB lives only on the operator's machine and in DO.
A fresh clone still reads the `variables.tf` default of **10**.

⚠ Three weeks, not a fix: ~1.6 GB/day of unreclaimed growth, and DO block volumes **only grow**.

### 4.6 ⚠⚠ As-run: deploying ST-1 revealed a *second* disk, also full (2026-08-15)

ST-1 is deployed — the relay runs `tenshu-relay:c4772fea`, `POST /sync/push` and `/sync/log`
answering 200. But the first attempt to ship it failed, and what it failed on is the finding.

```
failed to ingest "blobs/sha256/07bb…": failed to open writer:
mkdir /var/lib/containerd/…/ingest/…: no space left on device
```

**This was the root disk, not the data volume** — `/dev/vda1`, 24 G, **100 % full**, while
`/mnt/relay_data` still had 37 G free from §4.5's grow. `/var/lib/docker/containers` held
**9.1 GB of unrotated JSON logs** (6.7 G relay + 2.4 G Caddy), plus eight stale relay images at
~600 MB each. There was no `logging:` block in any of the three `docker-compose.yml` files and no
`/etc/docker/daemon.json`: **Docker's json-file driver is unbounded by default**, and the relay
logs one INFO line per request.

⚠⚠ **The two failures are causally linked, and that is the part worth remembering.** The data
volume filling (§4.5) made the relay answer 500 to every write; the resulting error storm is what
inflated the logs; the logs filled the root disk. **One capacity failure fed the other**, and the
second surfaced only because a deploy needed scratch space — nothing was alerting on either. The
relay stayed *up* the whole time (reads fine, `HTTP 200` on `/`), so from the outside neither disk
looked like an outage.

**Fixed durably**, not just on the box: an `x-logging` anchor (`50m` × 5 = 250 MB/container) now
applies to all five services across `sync-relay-server/`, `commerce-service/` and `web/apex/` —
all three had the identical defect. Runbook: `deploy-relay-digitalocean.md` §"Disk: there are two
of them".

⚠⚠ **A logging policy binds at container *creation*.** The containers running today were created
before the anchor landed, so they keep the unbounded config until `up -d --force-recreate`. The
on-box truncation done during the incident bought headroom (0 → 9.1 G free, 61 %), **it did not
change the policy** — without a recreate the logs simply grow back.

**As-run, later the same day:** all three deployments were pulled to `e9603f00` and recreated, and the
policy is confirmed *bound* (`docker inspect … LogConfig`) on relay, Caddy ×2, commerce and apex —
recreating is the step that counts, not the merge. The relay's 15 stale images were removed by
explicit tag (keeping `590b9ca4` as a rollback), taking the root disk **62 % → 49 %**; commerce and
apex had nothing worth reclaiming (0 B / 9.5 MB), which fits — the relay is the only box that gets
frequent manual image ships.

⚠⚠ **One container is still uncapped and cannot be fixed from this repo:** commerce's Postgres is
declared only in the on-box `commerce.override.yml`, and **a YAML anchor does not cross compose
files**. Harmless today (18 MB on a 77 G disk) but it is the same defect —
[`deploy-commerce-digitalocean.md`](../how-to/deploy-commerce-digitalocean.md) §"The Postgres sidecar
lives outside the repo".

⚠⚠ **Restarting Postgres left the app pool holding dead sockets, and its own stats hid it.** After
`db` was recreated, Hikari reported `total=10, idle=10` while `pg_stat_activity` showed **zero**
connections from the app — it validates on borrow, so until something borrows, the pool describes
sockets that no longer exist. `restart commerce` rebuilt it. Verify from the database's side, never
the pool's:
`select client_addr, state, count(*) from pg_stat_activity where datname = 'commerce' group by 1,2;`

⚠⚠ **And the 200 that had been reassuring all along was meaningless:** commerce's `GET /` is a static
string, so it cannot fail while the process lives. **ST-0c** adds `/health`, which round-trips the
store, so *"the process is up"* and *"the service can serve"* stop being the same signal. ⚠ The relay
has the identical shape — `GET /` is `"Tenshu Sync Relay is running!"` — which is why it answered 200
throughout the original outage while failing every write. **ST-0d** is its probe, and §4.7 is why it
could not be the same one.

### 4.7 ST-0d — the relay's probe had to be a *write*, and had to skip the database

Two shape decisions, both forced by what actually failed.

⭐ **It writes.** The relay's outage was a full blob volume: every `POST /sync/push` returned 500
while reads served perfectly. **Nothing that only reads could have distinguished that from health**,
so commerce's `SELECT 1` is the wrong instrument here. `/health` writes a few bytes to the blob root
and deletes them — literally the `writeBytes` call that threw. The probe carries ST-1's own lesson in
a `finally`: a check that leaked its file on the one condition it exists to detect would make a full
disk fuller, once per poll.

⚠⚠ **It does not touch the database, and that is deliberate.** `RelayDatabase` is a *single*
connection behind a `ReentrantLock`, so every caller serialises on it. A `SELECT 1` would queue
behind whatever sync request holds the lock, which means a **busy** relay would report as **down** —
health coupled to load is worse than no health check, because the false alarm arrives exactly when
someone is looking. It costs nothing: a full volume fails the SQLite write too, so probing the disk
covers both.

⚠ **In-memory mode answers `{"status":"ok","durable":false}`** rather than guessing. A relay with no
`DATABASE_URL` has no volume to probe — and in production that flag is itself the alarm, since such a
relay wipes its registrations on restart (the lost-registrations-on-restart bug).

⭐ **This retires the "just watch the volume" version of ST-0.** Alerting has to cover *both*
disks, and a full root disk should be read as a possible symptom of the data volume rather than an
independent event.

### 4.8 ✅ ST-3a — the census, and it falsifies this document's own prediction

Run against production 2026-08-16 (read-only; the WAL was snapshotted rather than read under the
writer). §4.3's first question was *"what are the 28,285 blobs — orphans from re-emits (§3.2
predicts many), live content, or both?"*

**Neither "both" nor "many". There are no orphans.**

| | |
|---|---|
| `content_blob` index rows | **34,577** (one owner) |
| blobs on disk (excl. `.tmp`) | **34,581** — 10.92 GB |
| claimed by an index row | **34,580** |
| ⭐ **orphans — a file no index row claims** | **1**, of **0 bytes** |
| index rows with no file on disk | **0** |

⚠⚠ **So §3.2's "a re-emit orphans the old blobs — permanent relay garbage" does not describe this
volume.** The mechanism is real (a fresh CEK per emit re-PUTs every chunk), but it has not produced
measurable garbage here: every byte on that disk is indexed, attributable and *live as far as the
relay can tell*. The fill was not waste. It was content.

⚠ That is **not** the same as "every byte is still referenced by a file the user has". The relay
cannot know that — which is §4.4 restated with numbers: an index row survives a client-side delete,
because nothing tells the relay. The reclaimable set is defined entirely on the client.

**Where the bytes are**, which decides what reclamation is worth:

| Cohort | Files | Bytes |
|---|---|---|
| > 1 MB | 5,067 (14.7 %) | **9.09 GB — 83.3 %** |
| ≤ 64 KB | 22,076 (63.8 %) | 0.17 GB — 1.57 % |

The modal size is **2,097,257 B** (3,785 files) — a 2 MiB content-plane chunk plus 105 bytes of AEAD
overhead. ⭐ **83 % of the volume is a few thousand large-file chunks**, so per-file reclamation is
worth far more than per-file effort suggests: deleting a handful of videos returns more than every
small blob combined.

**What this does to the phases:**

- ⚠⚠ **ST-3d is worse than "dangerous" — it has nothing to collect.** A time-based sweep over
  `last_referenced_at` was weighed as risky because it deletes by age without reference knowledge.
  The census removes the upside from the trade: there is **no garbage on that disk for it to
  reclaim**, so every byte it deleted would be live indexed content. It is not a reclamation
  mechanism here; it is a deletion mechanism.
- ⭐ **ST-3b is the only path with anything to reclaim**, and 83 % of the value sits in large files.
- ⚠ **ST-3c is now trivial to answer: there is exactly one owner.** "Capacity vs Σ(entitlements)"
  is not a fleet question yet — it is one account's tier against a 50 GB volume, which is the
  §4.1 mismatch stated concretely rather than hypothetically.

✅ **Also cleared: the 8,687 leaked `.tmp` files were still there.** ST-1 stopped the leak on
2026-08-15 but nobody removed what had already leaked — the fix and the cleanup are different
actions, and shipping the first reads like doing both. All were 35–39 h old (the incident window,
none in flight), so deleting those older than two hours was safe: **43,268 → 34,581 files**, 8,687
inodes returned. ⚠ Only 736 KB, so this was never about bytes.

⚠ Noticed in passing: production runs `c4772fea`, built **before** ST-0c/ST-0d, so `GET /health`
answers 404 there. The probe exists in master and is not deployed.

---

### 4.9 ST-3b — the relay half is built; the client half is where the danger is

Shipped 2026-08-16: **`POST /sync/content/delete`**, owner-authed, idempotent, capped at 512 refs
per call, returning what it actually freed. `ContentBlobRegistry.dropRefs(ownerId, refs)` in both
backends. Threat model amended in the same change (surface 3 + **T-R-13**), wire contract in
`sync-relay-api.md` §3.7.

⚠⚠ **`ownerId` is the verified JWT's; the request body has no owner field at all.** That is the
whole authorisation. The guard was verified by making the implementation trust a body-supplied
owner and watching `delete_cannotReachAnotherOwnersContent` go red — a cross-tenant delete
primitive is the single worst thing this endpoint could become, so it is pinned rather than
assumed. A ref belonging to another owner is *skipped*, not rejected, so the response is not an
existence oracle either.

⚠ No `RELAY_PROTOCOL_VERSION` bump: no existing route's semantics, envelope, auth or blob-ref
format changes, and no client calls it yet. An older relay answers 404, which a client must read as
"reclamation unavailable" rather than as a failure.

#### ⚠⚠ Why the client half is not in this change

The endpoint is inert until something computes *which* refs are orphaned, and that computation is
the data-loss-critical part. It is not "list the deleted file's chunks":

**A copy shares one `contentId`.** `file-content-addressing.md` makes move/rename/copy pure
metadata — two `File` rows, one `contentId`, one set of blobs — and says so plainly: *"once a
`contentId` can be referenced by >1 `File` (move, copy, dedup), blob deletion needs refcounting/GC"*
(§8.7). So the rule is **not** "this file was deleted, drop its refs". It is *"no remaining
`File` in the realm carries this `contentId`"* — a whole-realm question, answered over plaintext
the client has and the relay never will.

⚠⚠ **And that answer has a race.** Device A computes "nothing references this" from its synced
view; device B, offline or mid-sync, has just made a copy. A deletes; B's file is now unreadable,
and for most owners there is no other durable copy. This is the durable-content data-loss shape wearing different
clothes — an inference about references, made from an incomplete view.

The mitigation is a **convergence** condition rather than an age one, and the distinction matters:
reclaim only content whose last referencing `File` was tombstoned longer ago than a settle window,
so a concurrent copy from any device has had time to arrive. ⚠ That is a bound on *metadata*
convergence, not the content-plane age gate ST-3d proposes — it asks "has the realm agreed?", never
"is this old?".

⭐ **Sequencing follows from §4.8**: 83 % of the bytes are in 5,067 large-file chunks, so a first
client slice that only reclaims content from *explicitly deleted large files* captures most of the
value at a fraction of the risk of a general sweep.

---

### 4.10 ST-3b client half — the decision is built; the plumbing it needs does not exist yet

`ContentReclamationPolicy.plan(candidates, liveContentIds, nowMs, settleWindow)` — pure, total, in
the idiom of `evaluateBackupGate`. Three outcomes, because the two "no" answers mean opposite
things: `stillReferenced` is **final** (forget it, the content is alive again) and `notSettled` is
**ask later**. Collapsing them either resurrects settled candidates forever or discards young ones.

⭐ **The mechanism that makes this possible at all was already there and nobody had connected it:**
`SyncObserver` stores each manifest as an ordinary content blob at `manifestContentRef(contentId)` —
`blob-sha256("manifest:<contentId>")`. So the chunk refs of any content are recoverable *from the
contentId alone*, without the `File` row that named it. Without that, a client could not name the
refs of content whose file it had already deleted, and ST-3b would have needed a new durable index.

⚠⚠ **What is deliberately NOT built, and why it is the hard part.** Two pieces of plumbing are
missing, and both are more than wiring:

1. **A durable candidate record.** `TombstoneRegistry` is in-memory and keyed by *entity id hash*,
   not `contentId`, so nothing today survives a restart or answers "which content did this delete
   orphan?". The mapping is only cheaply known at delete time — after the row is gone, nothing local
   relates entity to content.
2. **A whole-realm live set.** `liveContentIds` must be *every* `contentId` any remaining `File`
   carries, realm-wide. ⚠⚠ An empty set is a **claim, not a default**: it reads as "nothing is
   referenced" and reclaims everything settled. A caller that cannot enumerate the realm must not
   call at all — pinned by a test that spells this out where a reader will meet it.

⚠ **A correction worth keeping.** The first version of this policy claimed the liveness-before-age
branch order was a data-loss guard, with a test named for it. It is not: `reclaim` is the `else` of
an exhaustive `when`, so a live candidate cannot reach it in *either* order. That was found by
swapping the branches and watching every test stay green — the discipline in
`tech-debt.md` ("delete the guard and re-run") catching an overclaim rather than a bug. What the
order actually decides is how a *young and live* candidate is reported, which the rewritten test now
pins.

⚠ The settle window (7 days) **bounds the race; it does not close it.** A device offline longer than
the window can still hold a copy nobody has seen, and no finite window fixes that — the realm has no
"everyone has caught up" barrier to wait on. That residual risk is why the sweep should start with
§4.8's finding: 83 % of the bytes are in 5,067 large-file chunks, so reclaiming only explicitly
deleted **large** files captures most of the value with far fewer decisions to get wrong.

---

### 4.11 The two missing pieces, built — and the persistence fork behind them

`ContentReclamationCandidates` (durable record) and `LiveContentIds` (realm live set), the two
things §4.10 named as missing. Both are pure and unit-tested; neither is wired to a sweep yet.

**Where candidates live, and the alternative that failed.** The obvious design — derive orphans by
diffing the relay's ref listing against live content, needing no durable record at all — was worked
through and **does not close**: a manifest's ref is `sha256("manifest:" + contentId)`, and sha256
cannot be inverted, so a client looking at a listing cannot tell a dead manifest from a chunk. It
would have to fetch and trial-parse every unknown ref. Recorded because it is the first idea anyone
has, and it looks obviously better right up to the hash.

So candidates are written down, as a `StringList` DEVICE preference of `contentId<U+001F>millis` —
the `BackupCursors` precedent exactly, which means **no new composite, no schema version bump, no
migration** (House Rule #9 untouched).

⚠⚠ **The list is capped at 2,000, and overflow drops the NEW candidate rather than evicting an old
one.** Both halves are deliberate. The cap exists because this rides a *synced* device row, and an
uncapped list would re-sync in full on every delete — the "derived projection broadcast" traffic
problem this project has already paid for once. Dropping the newest makes a full list degrade to
**today's behaviour** (that content is simply never reclaimed); evicting the oldest would instead
abandon a candidate that was about to become reclaimable, trading a leak for a bigger one.
⭐ **Overflow is therefore a missed opportunity, never a wrong deletion.**

⚠ A re-recorded candidate keeps its **original** timestamp. Refreshing it would push the settle
window out every time anything re-deleted the same content, so repeatedly-deleted content could
never age into reclaimability.

**The live set returns a sealed `Complete`/`Incomplete`, not a `Set`.** ⚠⚠ This is the input whose
incompleteness destroys data: the policy reads an absent id as "nothing references this", so a set
that is merely *mostly* right reclaims the bytes of every file it missed. `Incomplete` deliberately
carries **no partial set** — handing one back invites a caller to use it, which is the single thing
the type exists to prevent. An unreadable folder fails the whole walk rather than reading as an
empty one.

⚠ Still not wired: nothing records candidates at the delete site yet, and nothing runs the sweep.
The delete path (`DeleteFileImplementation.deleteRecursively`) is where the `contentId` is still
readable, and §4.8 says to start narrow — explicitly deleted **large** files are 83 % of the bytes.

⚠ A snag worth its own line: the first draft used a literal `U+001F` in the Kotlin source and its
test used `U+0000`, so the two disagreed invisibly and the round-trip test failed for a reason no
diff would show. The separator is now written as the escape `'\u001F'`. Invisible bytes do not
belong in source.

---

### 4.12 The delete site records — and what deliberately still does not run

`DeleteFileImplementation` now collects each deleted file's `contentId` during its recursive walk
and hands the batch to a `ContentReclamationRecorder` once the delete has **succeeded**. Threaded
through `fileServiceProxyImplementationFactory` and `createFileProxy`; **null by default**, so every
existing caller behaves exactly as before. ⚠ Reclamation must be something you switch on, never
something a library upgrade starts doing to your files.

⚠⚠ **Recorded after the delete, and never able to fail it.** The call is wrapped: a delete must not
fail because a note about future disk cleanup could not be written. The user asked for the file to
go, and it has; losing the note costs only that the relay keeps those bytes — exactly the status quo
before ST-3b, and never data loss. Recording *before* would be worse still: a failed delete would
leave a candidate claiming content is unreferenced when its file is still there.

⭐ The walk is the only possible place. After it, the rows are gone and nothing local relates an
entity to its content — which is why `TombstoneRegistry`, keyed by entity hash and in-memory, could
never have served.

⚠ **A test that passed for the wrong reason, caught before merge.** The first version of these tests
deleted a *folder* — which has no `contentId` — so the recorder was never called at all, and the
"a failing recorder cannot fail the delete" case passed without the recorder ever running. Rewritten
over a `File` entity carrying a real `contentId`; both now fail if the capture line is removed.

**Still not wired, and this is the whole remaining gap:** no production `ContentReclamationRecorder`
implementation exists, so nothing writes to `ContentReclamationCandidates`, and no sweep reads it.
The pieces are: recorder → candidates → `LiveContentIds` + `ContentReclamationPolicy` →
`POST /sync/content/delete`. Every link is built and tested; nothing joins them yet, by choice —
the first thing to join them should follow §4.8 and reclaim only explicitly deleted **large** files.

---

### 4.13 ST-3b joined — `ContentReclamationSweep`, and the scope decision behind it

The chain is now one class:

```
candidates → live set → policy → manifest → refs → POST /sync/content/delete → forget candidate
```

⚠⚠ **This is the only code in the project that can delete a user's file bytes.** Every branch is
written to fail *towards keeping* them:

| Situation | What the sweep does |
|---|---|
| live set `Incomplete` | **deletes nothing**, keeps every candidate, reports why |
| content came back to life | no relay call; candidate forgotten (settled, not pending) |
| candidate too young | kept, untouched, re-asked next sweep |
| relay delete failed | candidate kept — and the next sweep **re-plans from scratch**, so liveness is re-checked rather than a stale verdict retried |
| manifest unresolvable | candidate dropped; those bytes leak, exactly as every byte leaked before ST-3b |

⚠ The manifest is read **before** anything is deleted — it is the only thing that names a content's
chunks — and its own blob goes last, in the same batch.

⭐ **A duplicate type was collapsed on the way**: the policy had declared its own `Candidate`
identical to `ContentReclamationCandidates.Entry`. One concept with two types is how the two drift,
so the policy now takes the type the durable record actually stores. `:sync-service-proxy` gained
`:file-service` in `commonMain` for it — acyclic, and it was already a `commonTest` dependency.

#### ⚠⚠ The scope decision: all files, not just large ones

§4.8 recommended starting with explicitly deleted **large** files (83 % of the bytes, far fewer
decisions to get wrong). The maintainer chose **all files**, on the reasoning that *"in the worst
case nothing important is lost"*.

⚠ Recorded plainly because the worst case is not quite that: the failure mode is a device that held
the only copy of content this sweep deleted, and for `OnDemand` content the relay's copy is
generally the only durable one. What makes the call reasonable **today** is the census (§4.8) —
exactly **one owner**, pre-release, with the maintainer's own data — not that the mechanism is
harmless. ⚠⚠ That justification expires the moment a second owner exists, and the large-file-first
narrowing should be reconsidered then rather than rediscovered.

**Still not switched on**: no production `ContentReclamationRecorder` writes candidates and nothing
schedules the sweep, so the chain remains inert until it is wired into DI. Everything above is built
and unit-tested against fakes.

---

### 4.14 The client can now speak the delete — and what DI still owes

`ContentBlobStore.deleteChunks(refs)` with a `RestContentBlobStore` implementation:
`POST /sync/content/delete`, device JWT, **no owner field on the wire** (the relay takes the owner
from the verified token, so there is no field an attacker could aim at another tenant — asserted).

⚠ **Authorization only; deliberately no entitlement header.** Reclaiming *frees* an owner's quota
rather than spending it, so gating it on a live entitlement would leave an expired account unable to
shrink its own footprint — the one action such an account most needs.

⚠⚠ **Every non-success is `false`, including an older relay's 404.** The caller keeps its candidate
and re-plans; reading a failure as "probably deleted" would drop the candidate and leak those bytes
forever. There is no partial-success shape to misinterpret, on purpose.

#### ⚠⚠ What is still not wired, and why this is the honest stopping point

Two seams remain unfilled, and both are the kind that must be got right rather than finished:

1. **A production `ContentReclamationRecorder`** over the preference service, so deletes actually
   write candidates.
2. **A live-set provider** that walks the realm through `ExecutionContext` — and *this* is the one
   that decides whether the sweep is safe. `LiveContentIds` makes an incomplete walk unmistakable,
   but only if its `children(folder)` seam reports failure as `null` rather than as an empty folder.
   A provider that swallows a read error into an empty list turns the whole safety property off,
   silently, and every test in this track would still pass.

Plus the schedule itself: nothing decides *when* a sweep runs.

⭐ **The precedent for the last step is in this repo already.** `retention-quota-model.md` P3 is
"BUILT but INERT — both flags off, so deletion needs a deploy-time decision, not a code review".
ST-3b should land the same way: wired, off, switched on deliberately once a device pass has watched
it reclaim something real and leave everything else alone.

---

### 4.15 ⚠⚠ ST-3b is forward-only — it will never free what is already unreachable

Asked directly, and the answer is no. **ST-3b's chain begins at the delete site.** A candidate is
written when `DeleteFileImplementation` witnesses a delete; content orphaned by deletes that already
happened has no candidate and never will, because nothing enumerates *"content the relay holds that
no live file references"*. Wiring ST-3b changes what happens from then on and frees nothing that is
already stranded.

⚠⚠ **§4.8's "zero orphans" does not contradict this, and it is an easy line to misread.** That
census asked the *relay's* question — is there a file on disk with no `content_blob` row? — and the
answer was 1 blob of 0 bytes. But an index row **survives a client-side delete**, which is §4.4's
entire point: the relay is never told. So content whose file the user deleted last week still has
its row, still counts as "claimed", and is invisible to both the census and to ST-3b.

**How much of the 10.92 GB is already unreachable? Nobody knows, and there is currently no cheap way
to find out.** Attempted: `last_referenced_at` as a staleness hint. It separates nothing here —

| age | blobs | bytes |
|---|---|---|
| 0–1 d | 2,907 | 0.37 GB |
| 1–7 d | 23,693 | 9.73 GB |
| 7–30 d | 7,977 | 0.81 GB |
| 30 d+ | 0 | 0 |

— because the whole store was written inside a month (the Aug 8–14 backup burst). ⭐ Which is also a
third independent strike against **ST-3d**: a time-based sweep has nothing old to find, on top of
having no reference knowledge and no garbage to collect.

#### What a backfill would actually take

Computable, but it is a **different and more dangerous mechanism** than ST-3b, and should not be
mistaken for a bigger version of it. ST-3b deletes what it *watched* become unreferenced; a backfill
deletes what it *cannot prove* is referenced — an argument from absence, over a set it built itself.

1. A relay endpoint listing an owner's refs (**does not exist**).
2. The client building the complete reachable set: for **every** live `contentId`, fetch its
   manifest and read its chunk refs. One fetch per live file — no `manifestContentRef` shortcut,
   because chunk refs are only inside the manifest (§4.11's uninvertible hash again).
3. Delete the difference.

⚠⚠ **And the hazard ST-3b does not have: shared realms.** The live-set walk covers the personal file
tree. Content pulled in by a `SharedFile` / `Placement` is referenced by rows that walk does not
visit, so a naive difference would delete content that is very much in use — and a share is exactly
the case where a *second* party loses data. Any backfill owes that enumeration before it owes an
endpoint.

⭐ **The cheap alternative worth weighing first:** none of this is reclaimable-by-argument if the
account can simply be re-uploaded from. For a single pre-release owner, deleting the realm's relay
content wholesale and letting devices re-emit what they still hold is a far simpler operation with a
far clearer failure mode — `reclaimOwner` already exists for account lifecycle. Not free (it costs a
full re-upload, and any content **only** the relay still holds is gone), which is why it is a
decision rather than a fix, but it needs no new mechanism at all.

### 4.16 ⚠⚠ The settle window is the wrong instrument — waiting buys nothing after the first round

**Asked 2026-09-05:** seven days is a long time to hold bytes the user has just told you to delete.
Should the window be shorter — or should an explicit delete simply authorise reclaiming at once and
let the other devices catch up with the delete afterwards?

Neither, and the reasoning changes ST-3b's *gate* rather than its constant.

#### What the window actually guards, and what it does not

Not the user changing their mind, and not a device with a download in flight. It guards a **second
`File` row carrying the deleted file's `contentId`** (`ContentReclamationPolicy` KDoc, §4.10):
content-addressing made move and copy pure metadata, so one set of blobs can back several files.
⚠ The device that loses data is therefore not the one that deleted anything — it is a device whose
*kept* copy now points at blobs that are gone. Which is exactly why "the user authorised this" does
not reach the case: they authorised the original, not the copy they deliberately kept elsewhere.

⚠⚠ **Eventual consistency does not rescue it**, which is the tempting reading of "let them catch up
later". A late-arriving copy row is not a stale read that converges — it is a dangling reference,
and nothing can re-derive the bytes. Copy is metadata-only over `OnDemand` content, so the device
holding that row may never have downloaded the content, and after reclamation no replica need hold
the plaintext at all.

⭐ The direction split is the useful part: **outbound may be eventual, inbound may not.** Peers
learning of the delete late costs nothing — the row disappears whenever they next sync. What cannot
lag is *this* device's knowledge of what the peers already did, because deleting blobs is a local
replica asserting a **global** fact ("nothing anywhere references C") from a partial view.

⚠⚠ **Superseded in direction by `content-plane-refcount.md` §6d (D3, 2026-09-16):** the one case this
barrier exists for — a peer-only row written while offline — is fenced at the operation and verified
from the device holding the bytes, so P3 removes the barrier instead of replacing it. The reasoning
below is why no *timer* works, and it still holds.

⚠⚠ **What replaces it for the PHYSICAL free is decided at `content-plane-refcount.md` §9.14
(2026-09-22): option 1.** Removing the barrier removes the *wait*, not the safety argument — a delete
**credits** immediately, but the **bytes** go only behind a **device progress quorum** plus a
**lease that fences local merges** through the delete, because a conflict-born holder can reference
content *after* this device has answered. The delete also **retains rather than destroys** the local
plaintext, so a restore has a source — ⚠ in the **delete path itself**, since
`DeleteFileImplementation` drops every leaf's bytes directly and no guard on the *eviction* path
intercepts that — and held until any restoration is durably complete, ⚠ never on a timer, since a
non-answering or permanently-departed owner device can delay the free indefinitely. ⇒ do not read
"P3 removes the barrier" as "P3 frees on a local decision".

#### ⭐⭐ The load-bearing correction: the risk is fixed at the delete and does not decay

The set of references that exist anywhere was fixed the moment the delete happened — a sleeping
peer creates no new ones, because it is asleep. So "did anything else reference C?" has a constant
answer from T onward. Waiting does not improve that answer; it only buys the chance to *hear* it.

Two states, and a clock helps with neither:

- every peer was current before the delete → confirmable in about a minute, and reclaiming is safe;
- some peer was not → it stays unsafe until that peer reconnects, be that tomorrow or in six weeks.

⚠⚠ **A timer implies risk decays with elapsed time, and it does not.** `DEFAULT_SETTLE_WINDOW_MS`
therefore fires precisely when we have *not* heard from a device — the only case in which it
mattered — while making a single-device realm wait a week to be protected from a peer that does not
exist. It is simultaneously too slow for the common case and unsound for the case it names. The
window's own KDoc half-says this ("it bounds the race, it does not close it"); the point here is
that no value of it closes anything, so the shape is wrong rather than the number.

#### ⚠⚠ There *is* a convergence barrier — the KDoc claiming otherwise is false

`ContentReclamationPolicy`'s "the realm has no 'everyone has caught up' barrier to wait on" was
wrong when written. The relay computes one: `MailboxStore.acknowledgementSet` — every realm
device whose signed HLC tip has passed the tombstone — and the tombstone-body
GC has used it all along. Better, the client needs no new endpoint to evaluate it: every
`POST /sync/changes` and `POST /sync/log` already returns `peerTips`, each other device's last signed
tip (both handlers in `SyncRouting`), and every poll records the caller's own tip via
`authenticateAndRecordTip`. So a device that merely *pulls* acks; it need not write.

Two corrections to the naive form of that gate, both found while specifying it:

1. ⚠⚠ **A tip is wall-clock physical time, so `tip >= T` proves the peer was *polling* after T — not
   that it ingested the tombstone.** A peer many pages behind (`hasMore`) publishes a current tip
   while still owing itself thousands of blocks. For dropping a tombstone *body* that is a bounded
   risk; for deleting file bytes it is the difference between safe and not. This is the **outbound**
   half only: it establishes that the peer can no longer newly copy the doomed row.
2. ⭐ **The half that protects the data is inbound** — has this replica ingested a write *authored
   by* that peer after T? Per-device log ordering then covers every earlier write by that peer, the
   copy included. The fact is free at the HLC seam: `DeviceLastSeenRecorder` already reads the
   author's `deviceId` and physical time off `block.hlc` on every ingest.

⚠ **But not through `DeviceLastSeen` as it stands.** That row's KDoc rules itself out twice — it
inherits the author's clock skew and is "a human-facing 'last seen' and nothing more", and absence
means "never heard from", not "gone", so it "must never drive automatic removal". A reclamation gate
needs its own observation of the same fact: HLC-ordered rather than wall-clock, and **absence read
as not-safe** rather than as no obstacle. Same ingest seam, different trust level.

#### How fast the barrier actually settles

Not one poll cycle — three legs, because a tip rides the *request* and so cannot reflect that same
response's ingest (`SyncScheduler` calls `buildSignedTip()` before `executeAsync`). With A deleting
and B a peer: A pushes immediately (the `wakeSignal` shortcut on a user-originated enqueue); B's
next poll ingests the
tombstone but publishes a pre-ingest tip; B's poll after that publishes the ack; A's next poll reads
it in `peerTips`. So **2 × B's interval + 1 × A's**, against `SyncScheduler`'s
`pollingIntervalMs = 30_000` / `hotPollingIntervalMs = 1_500`, and the *slowest* peer sets the pace.

⚠ **Corrected 2026-09-06, after §4.17 built the skew margin this section did not yet have.** "About
five seconds on the hot cadence" was wrong: the barrier wants `tip ≥ stamp + skewMarginMs`, so the
**margin is the floor and the poll rate only adds to it**. Any realm with a peer takes at least
`DEFAULT_SKEW_MARGIN_MS` (30 s) however fast the polling; 60–90 s is the honest idle-online figure.
⭐ A realm of one is unaffected — with no peer there is no tip to compare and no margin to clear.

| Realm | Safe to reclaim |
|---|---|
| One device, or every peer revoked | **immediately** — no peer to have missed anything |
| Several devices, all online | **60–90 s**, and never under the skew margin — see below |
| A peer asleep or offline | not knowable — see below |

⭐ In practice the sweep's own cadence will dominate both, and nothing picks it yet (§4.14).

#### An unreachable peer is a decision, not a timer

Stalling indefinitely is not acceptable UX — a peer can sleep for days — but neither is expiring a
timer into the one case that was never safe. The resolution is to put the judgement where the
knowledge is:

> *"3.1 GB frees up once 'Old iPad' syncs — last seen 6 weeks ago."*

with two user-owned resolutions: reclaim anyway, or **revoke the device** — which drops it from
`knownDevices` and therefore out of the barrier, and is the honest action for a phone that is gone.
⭐ The user knows whether that iPad still exists; a scheduler never will.

#### ⚠⚠ The prerequisite that makes "reclaim anyway" a choice rather than a gamble

Today a dangling reference is a permanently broken file, which is why every ST-3b branch fails
towards keeping bytes. It need not stay that way, and this is the missing piece of the track:

- on ingesting a `File` whose `contentId` resolves to no blobs, a replica that still holds the
  plaintext **re-emits** it. Content-addressing makes the repair land under the same `contentId`, so
  every other device's reference simply starts working again. ⚠ Not a byte-identical restore: a
  re-emit mints a fresh CEK, so the chunk blobs are new and the old ones leak —
  `file-content-addressing.md` §3.4 slice 3-1's orphan shape, already accepted there;
- when **no** replica holds the plaintext, the file surfaces as "content no longer available"
  rather than a broken thumbnail.

With that in place the residual failure narrows to a conjunction: a peer **awake and disconnected**
between its last sync and the delete, which created a *second reference* to that specific content in
that window, and which holds no local plaintext. Without it, "reclaim anyway" is an unrecoverable
bet and should not be offered at all.

#### ⚠⚠ What tip-gating inherits

Gating byte deletion on HLC tips adopts the TC-2 hazard verbatim: a watermark shared more widely
than the store it describes **forges the ack** (`sync-hlc-tip-device-identity.md`; the tip is a
*process* clock on iOS route-1 and web, where several runtimes share one `deviceId`). Today a forged
ack drops a tombstone body, which is survivable. Under reclamation it deletes file bytes, which is
not. ⚠ TC-2b (the iOS shared container) is **built but not device-verified**
(`sync-hlc-tip-device-identity.md` §6, since 2026-08-30 — this paragraph predates that and read
"still open" until v0.23), so this raises the severity of a live bug rather than meeting a settled
one — sequence ST-3b's barrier after it, or hold a small conservative floor (hours, not days)
underneath purely to absorb tip flapping.

#### Decisions

- **D5 — the gate is convergence, not elapsed time.** ST-3b reclaims when the content is
  unreferenced in the local view **and** the inbound barrier holds for every admitted device.
  `DEFAULT_SETTLE_WINDOW_MS` stops being the primary gate. ⭐ The same instinct as **D4**: this
  document has already once rejected a timer in favour of a bound that exists anyway.
- **D6 — no blind timer fallback.** An unconfirmable peer stalls *visibly*, with revoke as the
  clean exit. A timer that expires into the unsafe case is worse than the stall it replaces.
- **D7 — the inbound half is the safety property.** The outbound (peer-tip) half only closes the
  window against *new* copies; it must never be shipped as the whole gate.
- **D8 — "reclaim anyway" ships only behind the repair path (ST-3f).** Until a dangling reference
  can self-heal, there is no honest way to offer the override.

### 4.17 ✅ ST-3b as built on the barrier — and the one seam still open

Built 2026-09-06 against §4.16's decisions. The chain is no longer "every link built, nothing
joining them": the two seams §4.14 called *"the kind that must be got right rather than finished"*
now exist as production code, the barrier replaced the settle window, and a delete on desktop
actually writes a durable candidate.

#### What the gate became

`ReclamationBarrier` (`:sync-service-proxy`), pure and total in `ContentReclamationPolicy`'s idiom:

| Check | Source | Verdict when it fails |
|---|---|---|
| this replica has consumed the whole log | the scheduler's own tick | `Waiting`, and it names **us**, not a peer |
| the device roster is readable | local `Device` rows | `Waiting` — see the ⚠⚠ below |
| every admitted peer's tip is past `stamp + margin` | `peerTips`, already on every response | `Waiting`, naming the peers |

⭐ **`ContentReclamationPolicy` no longer takes a clock at all** — no `nowMs`, no
`settleWindowMs`, no `DEFAULT_SETTLE_WINDOW_MS`. It answers only "is anything still referencing
this?"; the barrier answers "has the realm agreed?". Splitting them is what stops a future reader
reaching for a timer: the two questions fail differently, one finally and one only ever *"not yet"*.

⭐ **No new persistence, no new wire field, no new endpoint**, which is the payoff for expressing
the inbound half *globally*. §4.16 framed it per peer — *"have I ingested a write authored by every
admitted peer after the tombstone?"* — and building it showed the global form (*"have I consumed
the whole log?"*) is both strictly stronger and free: if the log is fully consumed then every
reference anyone has **pushed** is already in the live set, peer by peer reasoning included. The
per-peer form would have needed a durable, HLC-ordered watermark per device; the global form needs
one boolean the scheduler already computes.

⚠ It also sidesteps a hazard §4.16 did not name. `DeviceLastSeen` — the obvious per-peer source —
is throttled to five-minute granularity and its KDoc scopes it to display; wiring a byte deletion to
a row a UI refactor may coarsen is the shape of a defect nobody would see coming.

#### ⚠⚠ Three places where the safe-looking line is the dangerous one

Each of these is an `?: emptyX()` that reads as defensive and is the opposite. They are called out
together because they are the same mistake wearing three coats:

1. **An unreadable roster returning an empty set** reads as *a realm of one*, which converges
   immediately. `RealmConvergence.admittedDeviceIds` returns `null`, and the barrier blocks on
   `rosterKnown = false`. The one store failure that could *widen* the gate now closes it.
2. **An unreadable folder returning an empty list** reads as *this subtree holds no content*, and
   every file underneath it looks unreferenced — §4.14's exact warning. `reclamationChildren`
   returns `null` on a row that will not load. ⭐ Verified by deleting the guard and re-running:
   exactly one test fails, and it is the one that names the property.
3. **A failed pull leaving the previous tick's `caughtUp = true`** reads as *the live set is
   complete* at precisely the moment we have failed to read the log. `onPullOutcome(true)` is
   therefore called *before* the pull as well as after it, so a throw collapses to the safe answer
   by construction rather than by remembering to catch.

⚠ The boundary that is *not* a failure: `FILES` absent or not list-shaped is a genuinely empty
folder. `ExecutionContext.resolveList` cannot tell those apart — its contract says `null` means
"not list-shaped, callers treat it as empty **or fail per their own contract**" — so the
distinction is drawn one level down, at "a row we were pointed at would not load". Reading
`resolveList`'s `null` as failure would abort on every empty folder and stall reclamation for ever.

#### The stamp is an HLC, not a wall clock

`ContentReclamationCandidates.Entry.tombstonedAtMs` is now the recording device's **HLC physical
time**. It has to be: the barrier compares it against peers' *signed HLC tips*, and an HLC that has
absorbed a future-dated peer block runs ahead of its own wall clock by design. ⚠ The two coincide on
an undisturbed device, so the wrong one would have passed every test that did not look for it.

⭐ No encoding change fell out of this — the field already held a millisecond stamp, so the fix was
to the *contract* and to who supplies the value, not to the durable format.

#### As-built inventory

| Piece | Where | State |
|---|---|---|
| `ReclamationBarrier` + `Snapshot`/`Verdict` | `:sync-service-proxy` | new, 11 tests |
| `ContentReclamationPolicy` on the barrier | `:sync-service-proxy` | rewired; the window is gone |
| `ContentReclamationSweep` asks the barrier first | `:sync-service-proxy` | rewired; skips the realm walk when not caught up |
| `RealmConvergence` | `:sync-service-proxy` | new — peer tips + caught-up, wired into `SyncProxyInitialization` on **every** platform |
| `SyncScheduler.onPullOutcome` | `:sync-service-proxy` | new seam, defaults to a no-op |
| `ReclamationBarrierPropagationTest` | `:sync-service-proxy` | two proxies over one relay; the only cover for the roster↔tip **join** |
| `ProxyContentReclamationRecorder` + `contentReclamationRecorder` | `:file-service-proxy` | new — §4.14's first seam |
| `readReclamationCandidates` / `writeReclamationCandidates` | `:file-service-proxy` | new — the `DeviceSetting` row, read proxy-side rather than through the preference client |
| `reclamationChildren` / `liveContentIds` | `:file-service-proxy` | new — §4.14's second seam |
| `reclamationRecorderFactory` | `:file-service-proxy` | the one place the clock/identity policy is decided |
| Recorder wired into DI | `control-shared-proxy` **desktop** | live — one argument; deletes now write candidates |

⚠ `DeleteFileImplementation` takes a **factory** over the operation's own `ExecutionContext`, not a
ready-made recorder. Candidates live in the realm's own store, and a recorder built once at DI time
would capture whichever context the graph held — writing a shared realm's candidates into the
personal store, or the reverse. The delete knows which realm it is deleting from; the graph does not.

#### ⚠⚠ The clock is a policy decision, and Ph4 is its trigger

The first wiring put that decision in desktop DI as a lambda — and so reintroduced, one level over,
the mistake the factory above exists to avoid: `deviceId` and the HLC were bound at graph-build time
from a singleton. Moved to `reclamationRecorderFactory(syncManager)` in `:file-service-proxy`, so
every platform passes one argument and the reasoning sits beside the recorder.

⚠⚠ **What that reasoning is.** `SyncManager.hlcManager` is the **personal realm's** clock; each
shared realm gets its own (`HLCManager(realm.deviceId, …)`, and `SyncProcessor` resolves
`realmHlcManager ?: syncManager.hlcManager`). The stamp is compared against peers' signed tips, and
those are per realm. Today that cannot bite — `DeleteFile` walks only the personal tree, shared
realms have their own operations and **no delete op at all**, which is
`drive-shared-realm-file-model.md`'s unbuilt Ph4. ⚠⚠ **The day Ph4 lands this must resolve that
realm's clock**, or a candidate is stamped in one domain and measured in another: a failure that is
rare and silent, because the two clocks agree until one absorbs a future-dated block — exactly when
the barrier decides whether to delete bytes.

⚠ It cannot be recovered from the `ExecutionContext` either: shared realms are built with
`executionContext = this` and so *share* one, differing in their processor. The realm is not a
property of the context, which is why the factory takes a `SyncManager`.

#### ⭐ What the two-proxy test found on its first run

Every other barrier test feeds a hand-built `Snapshot`, so they prove the decision and nothing about
the inputs. Standing two proxies up against one relay and asserting through a **real**
`RealmConvergence.snapshot()` turned up two things immediately:

1. ⚠⚠ **`caughtUp` was false almost always.** The first wiring reported pessimistically *before*
   each pull so that a throw could not leave a stale `true` — which made the flag false for the whole
   duration of every tick. Safe, and useless: a sweep sampling at an arbitrary moment would have
   found "not caught up" and never converged. Now only an *unfinished* pull reports pessimistically
   (a `finally` guarded by a `pullReported` flag), and the flag's momentary nature is documented
   where a caller will meet it.
2. ⭐ **The empty-roster hazard is not reachable on a live proxy.** `admittedDeviceIds` cannot tell
   *empty* from *not yet learned*, and empty reads as a realm of one — but a proxy that has run
   `initIdentityService` always holds its own `Device` row, so a real snapshot's roster is never
   empty. ⚠ The reassurance is empirical, not structural, so the coupling is pinned by test rather
   than assumed.

⚠ A test-design note worth keeping: `caughtUp` must be read from *the snapshot that was tested*,
never re-read afterwards. Waiting for a condition and then taking a fresh snapshot samples mid-tick
and is how three of these four cases first failed.

#### ✅ Joined and scheduled — and switched OFF

Both remaining pieces landed 2026-09-06.

**`SyncProcessor.contentChunkRefs(contentId)`** is the last seam: the first half of
`resolveContentManifest` — fetch, decrypt, parse — stopping short of the `apply` that writes bytes,
because the sweep wants the chunk names and not the file. ⚠⚠ `null` means *cannot name them*, never
*there are none*; the sweep drops what it cannot resolve rather than deleting on a guess. ⚠ The
ordering it must preserve is unchanged: the manifest is read **before** anything is deleted, and its
own blob goes last, because it is the only thing that names the chunks.

**The schedule is the sync tick, not a cadence of its own.** The sweep runs once each tick, after the
pull has settled. Three reasons, in order of how much they matter:

- ⭐ It reads its snapshot **at the moment it acts**. `caughtUp` is momentary — false for the duration
  of every pull — so anything that waits for a green light and then re-reads is sampling a different
  tick. Riding the tick is the only placement where that cannot happen.
- The barrier settles in 60–90 s on an idle-online realm and immediately on a realm of one, so a
  separate interval would become the thing the user waits on rather than convergence.
- It is free when idle: no candidates ⇒ the first read returns empty and the sweep stops.

**Composition lives in the DI graph**, because `:sync-service-proxy` cannot reach the candidate store
or the realm walk in `:file-service-proxy` and should not grow an edge to do so. `initSyncService`
takes a `ReclamationSources` holder; ⚠⚠ **null — the default, and what every platform passes — builds
no sweep object at all.** Inert by absence rather than by a flag to be found and trusted.

⚠⚠ **And it is off.** Desktop DI composes the sources behind `RECLAMATION_SWEEP_ENABLED = false`, so
the wiring is type-checked and one boolean from live. It stays false because the barrier leans on
peers' signed HLC tips and **TC-2b is built but not device-verified** — a device can publish a tip
speaking for a runtime it does not represent, and here that clears a byte delete rather than a
tombstone body. ⚠ The remaining work is therefore a *pass*, not an implementation: reading this as
"the iOS fix is not written yet" (which "still open" invited until v0.23) puts imaginary work in
front of the flip. Sequence the flip after TC-2b's two-app device pass and after a dry run has
reported (D13).

⚠ Android and iOS DI pass no recorder and no sources — one line each, deliberately deferred so this
does not pull in HR #8's emulator gate for a path that cannot execute.

⭐ **A test claim corrected rather than defended.** `ReclamationSweepWiringTest` was written asserting
that the `try/catch` around the hook keeps sync alive. Deleting that guard and re-running left it
green: the tick loop's own outer catch already survives any throw. The guard's real job is narrower —
keeping the *rest of the tick* (shared-realm sync runs after the hook) from being skipped — and the
test now says so instead of claiming cover it does not have.

⚠ Also still true, and unchanged by any of this: **ST-3b is forward-only** (§4.15). Everything above
changes what happens from the day it is switched on and frees nothing already stranded.

⚠ Android and iOS DI still pass no recorder — a one-line change each, deliberately deferred because
touching `androidMain` pulls in HR #8's emulator smoke gate for a change that cannot execute yet.

⚖️ And **ST-3f remains the gate on any user-facing "reclaim anyway"** (D8): until a dangling
reference can self-heal, the override is an unrecoverable bet rather than a choice.

### 4.18 ✅ ST-3f — putting content back, and the three facts that make it cheap

Built 2026-09-06, wired and off. A dangling reference — a live `File` whose `contentId` resolves to
nothing — is today a permanently broken file, which is what makes a wrong reclaim unrecoverable and
therefore what stands between us and D8's "reclaim anyway". This is the net.

#### ⚠⚠ A future dedup would reintroduce the failure ST-3f exists to survive

`file-content-addressing.md` §4.3 promises within-realm dedup: look `contentHash → contentId` up in
a local index and reuse the id rather than uploading again. It is **not built** — every upload mints
a fresh id (`val contentId = generateId()`), so today two `File` rows share one only through move or
copy, which is why `LiveContentIds` walks the whole realm before anything is reclaimed.

⭐ **A lookup against live rows cannot collide with reclamation**, and that is the useful half to
know. Both mechanisms that exist today derive from live `File` rows — the `contentHash` predicate is
a linear walk, `ContentIndex` is built by walking them — so a hit implies a live row still carries
that `contentId`, which implies the live-set walk protected its blobs.

⚠⚠ **A stale one can, and `ContentIndex` is explicitly stale-able.** Its KDoc says so: *"a value
taken at a moment, with no subscription… nothing invalidates it for you."* A backup drain loads one,
another device deletes that file mid-run, the barrier clears and this sweep reclaims — and the drain
dedups a later asset onto a `contentId` whose blobs are gone, minting a `File` born broken. ⚠ No new
index required: just a drain outliving ~a minute, which a photo backup does easily.

⚠ ST-3f cannot cover that one: the repair fires only on a device holding the plaintext, and the
uploader holds the bytes but never resolves (so never notices) while every peer resolves but holds no
bytes (so cannot help). Broken for everyone except its author.

⭐ So the mitigation is not "check before reusing a persistent id" — there is no persistent id store.
It is that `ContentIndex`'s **reload-per-run becomes a safety property** once this sweep is live, and
that a dedup hit should confirm the manifest still resolves before reusing an id. Recorded in §4.3
too, where whoever builds dedup will actually be reading.

⭐ **The first id reuse to ship follows that rule** (2026-09-15). An overwrite with unchanged
bytes keeps its `contentId` and calls `FileContentSyncEmitter.ensureContentOnRelay`, which skips the
upload only when the manifest **and every chunk it names** are present, and otherwise re-emits under
the same id through the journal. ⚠ Manifest presence alone would miss the partial delete this sweep
can leave, since chunks go before the manifest. ⚠ Unknown (unreachable relay, locked realm) hands off. That makes it the one
**holder-side** trigger for this repair: the device holding the bytes asks, instead of waiting for a
resolve it never makes.

#### ⭐ Why the repair is a re-emit and nothing more

Three facts line up, none visible from outside the emit path, and together they turn what sounds
like a resurrection problem into an ordinary upload:

1. **`contentId` is a stable random id carried on the entity**, not a hash of the bytes —
   `SyncObserver` reads it back through `resolveContentId` rather than deriving it. A repair reuses
   the id the row already has.
2. **`manifestContentRef(contentId)` is a pure function of that id** —
   `"blob-" + sha256("manifest:$contentId")` — not of the manifest's ciphertext. A rebuilt manifest
   therefore lands at exactly the address every other device is already asking for.
3. **The refs it needs are free**, precisely because they were reclaimed. The repair writes into the
   hole the delete left: nothing to overwrite, no conflict to resolve.

⇒ **no entity write, no sync block, nothing for peers to merge.** They were already asking for this
content; afterwards the answer exists. ⚠ Not a byte-identical restore — a re-emit mints a fresh CEK,
so the chunks are new blobs and any that survived are orphaned (§3.4's accepted shape).

#### ⚠⚠ One guard carries both safety properties

The repair acts **only when this device holds the plaintext**. That single condition does two jobs:

- **Additive by construction.** Every path uploads or does nothing. There is no branch that deletes
  and no input that makes one appear — which is why this can be switched on *ahead* of the sweep
  rather than with it. A net is worth having before the fall.
- **It cannot race a live upload.** An absent manifest also describes an upload still in flight, and
  the repair does not try to tell the two apart — it does not need to. Only the uploading device
  holds bytes, and its own emit journal already owns that retry; every other device declines at the
  guard. ⭐ The ambiguity that looked like the hard part is excluded for free.

⚠ Verified the way this track has learned to: deleting the local-bytes guard turns exactly one test
red, and it is the one that names the property.

#### As built

| Piece | Where |
|---|---|
| `ContentRepair` + `localBytesContentRepair` | `:sync-service-proxy` — the decision, named and testable |
| `ContentRepairHolder` | late binding: the repair emits through `SyncObserver`, built *after* the processor that calls it |
| `SyncProcessor.resolveContentManifest(entityId, contentId, propertyId)` | the detection point — an absent manifest *is* the dangling reference |
| `repairDanglingContent` on `initSyncService` | `false` by default |
| `CONTENT_REPAIR_ENABLED` | desktop DI, `false` |

⚠ `propertyId` is threaded from `OnDemandResolutionImpl`, the only place holding it beside the
entity, which is why the repair hangs off the resolve rather than off ingest.

⚠ The decision lives in a named function rather than the DI lambda it started as — the same
correction the recorder's clock needed. What files a device may put back is policy, and policy in
wiring is untestable and unread.

#### What is deliberately not here

- **The honest empty state.** "Content no longer available" for the case where *no* replica holds the
  plaintext is not built; such a file still simply fails to surface. ⚠ That is the half a user
  actually sees, and D8's override should not ship without it.
- **Repair on demand.** Only an `OnDemand` resolve triggers a repair, so content nobody opens stays
  broken until someone does. Sufficient for a net, wrong for a sweep that reclaimed in bulk.
- ✅ **Demonstrated 2026-09-06** — `ContentRepairReEmitTest`. Content is emitted, its manifest and
  every chunk it named are dropped exactly as the sweep drops them, and the repair puts it back:
  found through `fetchManifestCiphertext` (the reader's own path, not the test's arithmetic), with
  the `contentId` and whole-file hash unchanged and every chunk the rebuilt manifest names present.
  A device without the plaintext declines and leaves the hole alone.
  - ⚠⚠ **One property has no test, and cannot have an honest one here.** That a re-emit is *not*
    byte-identical — fresh CEK ⇒ new chunk refs ⇒ the old blobs orphaned — is unobservable in this
    harness: `SyncCryptoManager`'s stub returns a **deterministic** "random" key, so every emit
    reproduces identical ciphertext and reuses the refs. The obvious test fails, for a reason that
    has nothing to do with the code under test. Left to the real crypto and stated here instead.

⭐ **ON since 2026-09-06**, ahead of the sweep as intended — the net before the fall. While the sweep
stays inert it fires rarely: only for content whose manifest never landed, from a device still
holding the plaintext.

#### ⚠⚠ The defect the flip turned up first

Flipping it revealed a hole that would have caused real damage. Every thumbnail class is
`Syncable(OnDemand)`, so a *thumbnail* resolve reaches the repair carrying its own property and its
own derived `contentId` — while the repair answers with the **file's** path. It would have re-emitted
a full-size image under a thumbnail's content id, in the most ordinary state there is: the file
downloaded, the thumbnail not.

⚠ Fixed with `REPAIRABLE_PROPERTY_IDS`, a **whitelist** of the two properties whose bytes actually
live at the file's path. A blacklist would silently admit the next `OnDemand` property somebody adds.
⚠ Declining a thumbnail is the right answer rather than a gap: a thumbnail is derived, degrades to
the next class or a category icon, and is regenerable from the file — which the file is not.

⭐ The demonstration test caught its own version of this on the way: it had been passing a
hand-written `"tenshu/file/file/data"`, borrowed from a test whose entities are synthetic. Against
the real whitelist it failed, which is exactly what a constant is for.

### 4.19 The honest empty state — a protocol question wearing UI clothes

D8 says "reclaim anyway" ships only behind ST-3f, and §4.18 lists the honest empty state as the half
that is missing: *"content no longer available"* for the case where **no** replica holds the
plaintext. This is what that would take, and why it is not the afternoon of Compose it looks like.

#### ⚠⚠ Today the user is told the wrong thing, not nothing

⚠ **Corrected 2026-09-06 — the first version of this section named the wrong screen.** It said a file
with missing bytes lands on `UnsupportedPreviewScreen` and is told a *format* story. It does not: the
`Destination.FilePreview` router branches on category and platform capability *before* any content is
fetched, so a previewable file goes to `ImagePreviewScreen` / `PdfPreviewScreen` / `VideoPreviewScreen`
regardless of whether its bytes exist.

What actually happened is worse in a different way. Those viewers render a typed error, and
`TenshuFilesRepository.getRemoteFile` answered absent bytes with `ErrorCopyKey.UNEXPECTED` — *"Something
went wrong. Please try again."* So the ordinary state of `OnDemand` content that has not arrived was
reported as an application fault. ⭐ Its own code comment claimed both branches "said the same thing
to a user (nothing — the payload was never rendered)", which stopped being true when the preview
screens started rendering `error.driveLabel()`.

⭐ Fixed 2026-09-06 by `ErrorCopyKey.CONTENT_UNAVAILABLE`: *"Couldn't load this file's contents. They
may still be syncing."* ⚠⚠ Deliberately **not** "no longer available" — three of the four causes below
resolve themselves, and telling a user their file is gone when it is merely late is the failure this
key exists to prevent. The permanent wording still waits on D10.

#### ⚠⚠ "The content is gone" is not observable

A missing manifest means *"not here"*. That is equally:

| what actually happened | how it looks | resolves itself? |
|---|---|---|
| reclaimed by the sweep | manifest 404 | **no** |
| never uploaded — an emit that has not landed | manifest 404 | yes |
| uploaded by a peer that has not pushed yet | manifest 404 | yes |
| transient relay failure | manifest 404 | yes |

Three of the four come back on their own. The client cannot tell which it is looking at, and **the
relay cannot help**: it deletes blobs and keeps no record that a ref *was* reclaimed. So an honest
empty state needs a signal that does not exist — which is why this has stayed unbuilt while looking
like UI work.

⚠ And the trap to avoid naming itself: *"failed N times over M days ⇒ call it gone"*. That is §4.16's
settle window again, inferring a durable fact from elapsed time, and it errs in the direction that
matters — a file that would have come back is labelled dead, while a genuinely dead one is labelled
"still trying" for M days.

#### The three shapes a real signal could take

- **(a) The sweep remembers what it reclaimed.** It already keeps a candidate list and currently
  *forgets* an entry on success; it could keep a "reclaimed" note instead. ⚠⚠ Device-local, so only
  the reclaiming device knows — and the device that sees the broken file is a **peer**. Solves it for
  the one machine that does not need solving.
- **(b) The relay distinguishes *reclaimed* from *absent*.** A per-`(ownerId, ref)` marker. ⚠ It is
  metadata, not content, so HR #1 survives — but it is a durable record of an owner's deletion
  history, which is an oracle the relay does not otherwise hold, and it grows without a cap.
- **(c) ⭐ A synced record.** The reclaiming device writes a small `Syncable` row naming the
  reclaimed `contentId`s. Peers learn it through the log they already read; it is end-to-end
  encrypted like everything else, so no new oracle; no relay change at all. ⚠ Costs a row per
  reclaimed content, so it wants the same cap-and-age treatment as the candidate lists.

⭐ **(c) is the recommendation**, and it composes with ST-3f rather than duplicating it: a device
that sees the record *and* holds the plaintext repairs (§4.18); one that sees it and holds nothing
shows the empty state. Same fact, two readers, opposite correct responses.

#### Decisions

- **D9 — the empty state is gated on a signal, not on UI work.** Anything that infers "gone" from
  repeated failure or elapsed time is out; three of the four causes resolve themselves.
- **D10 — the signal travels through the sync log, not the relay.** A reclaimed-content record is
  E2E-encrypted realm data, which keeps HR #1 intact and adds no deletion-history oracle to a server
  that has no business holding one.

#### ⚠ Two parts, and only one of them is blocked

1. ✅ **Done 2026-09-06:** absent bytes now report `CONTENT_UNAVAILABLE` rather than the generic
   fault sentence. Honest whether or not the loss is permanent, and it needed no new signal — only a
   copy key distinct from "something went wrong".
2. **Blocked on D10's record:** the permanent wording, and anything that invites the user to act
   (delete the row, stop waiting). Saying "no longer available" about content that is merely
   *late* is the same class of error as the mislabel it replaces.

⚠⚠ Sequence it before `RECLAMATION_SWEEP_ENABLED` goes true, not before ST-3f. The repair is on and
covers the case where somebody has the bytes; the sweep is what starts creating the case where
nobody does.

---

### 4.20 ⚠⚠ ST-6 — a full realm was not a *stop*, it was a 30/second retry storm

Reported 2026-09-06 as *"the phone is stuck at Uploading… forever, counting randomly, always
31.8 MB left"*. It is the third occurrence of one failure mode this codebase has already fixed
twice, and it reached production because the fix had been written twice as a *special case* rather
than as a rule.

**The device.** `tenshu/storage/status` on the phone decoded to `usedBytes = 5,368,708,284`,
`limitBytes = 5,368,709,120` — **836 bytes free** on the 5 GiB free tier. 606 journalled content
emits over 217 entities, ~30 rejected chunk `PUT`s per second since 16:46, Control at **106% CPU**,
and 176,000 identical log lines that had evicted every other Tenshu line from logcat.

**Three defects, and the order matters.**

1. **`isFull` asked the wrong question.** `StorageStatusModel.isFull` was `remainingBytes <= 0`.
   With 836 bytes free that is *false*, so `SyncBanner` skipped `StorageFull` and fell through to
   `Syncing` ("Uploading 297 of 606 · 31.8 Mb left"), and `evaluateBackupGate` skipped
   `Paused(OUT_OF_SPACE)` and kept enqueuing. ⚠⚠ **The client cannot answer this question from two
   numbers.** Chunks are 2 MiB, so the smallest write that must fit is not derivable from
   `used`/`limit`; and those figures lag — usage re-fetches on a 60 s backstop — so
   bytes spent by *another of the owner's devices* are invisible for up to that long. Raising the
   test to a headroom constant only moves the boundary and starts refusing uploads that would have
   worked.

   ⚠ **Shortening the refresh interval is not the fix, and it is worth being explicit about why.**
   The 60 s backstop narrowed this window from "the life of the process" to a minute, which is a real
   improvement to the *gauge* — but neither that nor any smaller interval makes the arithmetic
   correct, because the chunk-size blindness above is independent of freshness and a second device
   can always spend the room inside whatever window remains. D11 stands: only the relay's answer
   closes it.
2. **A `507` did not look doomed.** `RestContentBlobStore.putChunk` tagged `402` with
   `ENTITLEMENT_REJECTION_MARKER` and let everything else fall to a generic failure — which the
   scheduler reads as *transient* and retries at tick cadence. `RejectionBackoff`'s own KDoc already
   documented this exact storm for `402` (~6/sec) and `401` (~8k/hour); nothing generalised it.
3. **The reason was thrown away before anyone could classify it.** `ChunkPutFailed` was built from
   a message naming the file and chunk and *not* the `TResult.Failure` behind it, then caught and
   `println`ed. So even a tagged 507 was stripped at the throw site. This is why the phone could log
   the same line 176,000 times without once recording that it was a quota refusal — the storm
   destroyed the evidence for itself *and* for anything else wrong with the device.

**Shipped (ST-6).** The relay's refusal becomes a first-class signal and the client stops guessing:

- `STORAGE_REJECTION_MARKER` (`storage-full-507`) beside the 402 one, applied by `putChunk`;
  `ChunkPutFailed` carries `reason`, so the marker survives to a caller.
- `SyncObserver.drainDeferredContent` **ends its pass** at the first 507 — the rest of the queue
  meets the same quota check, and the relay reads the whole body before refusing, so every further
  attempt is a chunk uploaded to be discarded. Nothing is dropped: entries stay journalled.
- A third `RejectionBackoff` (`storageBackoff`) in `SyncScheduler`. ⚠ The per-pass stop bounds one
  pass; only the backoff bounds the *sequence* of passes. Both halves are needed.
- `SyncHealthStatus.CONNECTIVITY_STORAGE_FULL` — a **new value** of the existing `connectivity`
  string, so the positional composite is unchanged, no version bump is owed, and a skewed Drive
  degrades to no banner. (The same escape hatch `CONNECTIVITY_DEVICE_UNRECOGNIZED` used.)
- `StorageStatusModel.relayRejected`, joined into `storageStatus()` from the health row, so
  **`isFull` now means "bytes cannot land"** — from the figures *or* the relay's answer. ⭐ That one
  predicate fixes the banner, `evaluateBackupGate` and `StorageGate` together, which is the point:
  three consumers asking the same question must not each own a different answer to it.
- `StorageCapacityHolder.relayRejected`, so `ensureWithinQuota` refuses a **manual** upload at the
  moment the user picks the file — the parallel-device case, where the arithmetic still says it
  fits. Wired from Control DI in all three platform modules; `:sync-service-proxy` has no edge to
  `:storage-service-proxy`, hence a lambda rather than the holder.

**D11.** *The relay is the only authority on whether a write fits.* Client-side capacity arithmetic
is an optimisation that avoids a doomed round trip — it is **not** the gate, and it may not be the
only thing a UI keys on. Any future consumer of `used`/`limit` that decides whether work can proceed
owes a path for the relay's refusal to reach it.

**D12.** *Every status the relay can refuse with owns a marker and a test.* Three occurrences with
identical shape is enough: an unmarked refusal is retried forever and surfaces nowhere.
`RestContentBlobStoreRejectionTest` pins 402/507 and — the load-bearing negative — that a `500` is
marked *neither*, so ordinary transient failures still retry.

**Measured on the reporting device, before → after** (same phone, same over-quota realm):

| | before | after |
|---|---|---|
| rejected chunk `PUT`s | ~1,800/min, indefinitely | 8 in the first 18 s, then **silent** — the backoff reaches its 2 min cap |
| files attempted per pass | 217 | 1 |
| Control CPU | 106 % (a pinned core) | idle |
| the log line | `chunk PUT failed … aborting manifest emit` ×176,000 | same, plus `— storage-full-507: content PUT rejected (507)` |
| `SyncHealthStatus.connectivity` | `online` | `storage_full` |
| Drive's banner | "Uploading 297 of 606 · 31.8 Mb left" | "Storage full — 5 Gb of 5 Gb used. Your data stays on this device and nothing is deleted, but it can't sync" + **Manage storage** |

⚠⚠ **The scheduler's backoff alone was not enough, and the device is what said so.** With only
`storageBackoff` in place the storm was gone but ~10 doomed `PUT`s/minute remained — each preceded
in logcat by a `tenshu-scheduled-op` WorkManager job. Those are Synced Folders uploads going through
the **inline** `emitFileContent` path, which the scheduler never sees. Hence the latch is *sticky*
(cleared only by an accepted write, not at pass start) and the inline path consults it. A fix
verified only against the drain would have shipped looking complete.

⚠ **`contentStorageRejected` is per-`SyncObserver`, and every session wires its own** (see
`SyncProxyComponents.copyForSession`), so N sessions probe independently — visible as pairs of
rejections ~0.5 s apart on a device running Drive and Control. Bounded by session count rather than
by queue length, so it does not reintroduce the storm, but it is why the floor is a pair per window
rather than a single probe.

⚠ **Not fixed here.** The pre-flight still does not count **already-journalled** bytes: with 40 MB
free and 31.8 MB queued, a new 30 MB file passes `ensureWithinQuota` and joins the queue. It now
fails safely (the banner says "Storage full" and the drain backs off rather than storming), but the
honest answer is to check `used + queued + required`, which needs the journal's figures at the
upload op. Tracked as **ST-7**.

---

### 4.21 ✅ ST-7 — the pre-flight was measuring against room the queue had already claimed

ST-6 left this open and named it. `ensureWithinQuota` asked `used + required > limit`, where `used`
is **what the relay holds** — so a queue waiting to go up was invisible to it. With 40 MB free and
31.8 MB already journalled, a new 30 MB file passed the check, was accepted, was journalled, and
then never landed. The honest question is `used + queued + required`.

⚠⚠ **Two surfaces asked it, and only fixing one would have been worse than fixing neither.** The
Tier-2 op gate (`ensureWithinQuota`, single file) and the Tier-3 pre-flight (`CheckCapacity`,
the folder/multi-file "will this job work" call `TenshuFilesRepository.uploadFolder` makes once
before a long run) are the same question at two scales. They now share `availableAfterQueue(...)`,
because a single-file refusal and a folder-upload green light that disagree is a worse bug than
either being wrong alone.

**Where the figure comes from.** `SyncObserver.deferredContentFigures().bytes` — already computed
every tick for the banner — is published to `StorageCapacityHolder.queuedContentBytes` through a
new `onQueuedContentBytes` lambda, the same seam ST-6's `relayRejected` uses.

⚠ **Per tick, not live, and that is forced rather than chosen**: `CheckCapacity` is a
`SyncOperationImplementation` and `ensureWithinQuota` a non-suspend extension, so neither can await
the journal read. Staleness **under**-counts the queue, which is the permissive direction and
matches this gate's standing optimistic-on-unknown posture — allowing an upload that is later
refused beats refusing one that would have fitted.

⚠ **It does not double-count, and this was checked rather than assumed.** Each journal entry is
keyed `(entityId, propertyId)` and stores its *own* blob path — a file's thumbnails go to
`FileBytesPropertyWriter.thumbnailPath(...)`, not the file's path — so summing `fileSize` per entry
sums distinct blobs. (This is why the reporting device showed 606 entries over 217 entities at a
54 KB average: previews and thumbnails, each counted once.) And `ensureWithinQuota` runs *before*
`UploadFile` journals the current file, so a file can never be measured against itself.

⚠ **Plaintext vs ciphertext**: the queue is summed in plaintext bytes while the relay meters
ciphertext (~28 bytes of AEAD overhead per 2 MiB chunk). A projection, not an audit — and the error
is again on the permissive side.

⚠⚠ **The negative is the load-bearing test.** In normal operation the journal is transiently
non-empty on *every* upload (`emitFileContent` journals, uploads, then clears), so a gate that keyed
on the queue's **existence** rather than its **size** would refuse everything. Both
`withinQuota_aQueueThatStillLeavesRoom_doesNotRefuse` and the `CheckCapacity` twin pin that, and
`anEmptyJournalPublishesZero_...` pins the release half — a figure that only grew would
permanently shrink the headroom every future upload is measured against.

---

### 4.22 ✅ The dry run — because the gate's inputs are invisible from outside the process

§4.17 left the sweep one boolean from live and said the flip wanted "TC-2b + a device pass". Trying
to schedule that pass surfaced the problem: **nothing about the gate is observable.** You cannot
look at a device and find out whether reclamation would work there.

#### ⚠⚠ Why a store dump cannot answer it

Every input the barrier turns on is either momentary or in-memory:

| input | where it lives | can you read it off the device? |
|---|---|---|
| `caughtUp` | a field on `RealmConvergence`, false for the duration of every pull | **no** |
| peer HLC tips | a map on `RealmConvergence`, never persisted | **no** |
| the candidate list | a `DeviceSetting` preference row | yes |
| the live set | walked from the file tree on demand | recomputable |

So the two facts that decide the verdict are exactly the two that vanish with the process. ⭐ And
`deviceLastSeen` is **not** a substitute: it records when this replica last *ingested a write by* a
device, while the barrier reads what the relay serves as that device's tip — which a device updates
by **polling**, without writing anything. A device can be silent in one and current in the other.

#### What shipped

`ContentReclamationSweep` gains an observe-only mode: it plans every pass in full — candidates,
barrier, live-set walk, policy — and then stops before the first write. It reports candidate count,
how many it *would* have reclaimed, and **which devices the barrier is waiting on**. Desktop DI runs
it (`RECLAMATION_DRY_RUN_ENABLED = true`), silent until there is a candidate to talk about.

⚠⚠ **A dry run cannot be inert by absence**, which is how §4.17 kept the real sweep safe ("no sweep
object exists"). It is a sweep that runs, so the property is rebuilt out of two independent parts —
and they turned out to protect different things, which only deleting each and re-running showed:

- it returns before any write, which is what makes it **stateless**; and
- `initSyncService` hands it a `deleteRefs` that is **not the blob store**, which is what keeps it
  **byte-safe** if that return is ever lost.

Neither substitutes for the other: remove the return and the bytes survive but the candidate list is
pruned; remove the `deleteRefs` branch and the return still covers everything.

#### ⚠ What the first read of it found

Nothing — literally. The desktop's candidate row does not exist, so the recorder (wired
unconditionally since §4.14, *not* behind the sweep flag) has recorded no delete. Forward-only plus
desktop-only means the flip would have been a no-op until a **fresh delete made from the desktop**.
That is worth knowing before, not after, switching on the only code in the project that can delete a
user's file bytes.

⭐ And the roster read that prompted this corrected two guesses at once: no device in the realm is
provably dead (every one has authored a write within ~9 days), and the row with *no* `deviceLastSeen`
entry is **this device** — `DeviceLastSeenRecorder` skips its own writes, so for self, absence is the
design. Reading that absence as a dead duplicate would have revoked the machine doing the reading.

⇒ **D13 — the flip is gated on a dry run that has reported, not on a device pass that watched.**
"Watch it reclaim something real and leave everything else alone" (§4.17) presumes you can tell what
it is about to do. The dry run is how you tell.

---


### 4.23 ⚠⚠ The dry run's first real answer: the barrier waits on a device that cannot ack

§4.22 shipped the dry run so the flip could become a decision instead of a leap. Run for the first
time against the live realm on 2026-09-06, it answers — and the answer is **do not flip**.

#### What was run

Desktop Control from source (so its stdout is readable) plus Drive **packaged and signed**, then one
file deleted from Drive's UI. ⚠ `./gradlew :drive-desktop:run` cannot be the client half: a bare
Gradle JVM has no bundle identity, so Control's UDS peer-credential gate rejects it with
`no team may vouch for claimed identifier 'net.java.openjdk.java'` and every op returns
*"IPC Client -> not connected"*. Package, sign, and run the binary out of the build output.

#### The report, 100 consecutive times over 13 minutes, unchanged

```
reclamation (dry run) -> 1 candidate(s), would reclaim 0, reclaimed 0;
  dry run: planned 0, deleted nothing, waiting on 5 device(s): <all five peers>
```

⭐ The recorder works: the delete produced exactly one candidate, which is the forward-only path
§4.22 predicted would stay empty until a fresh desktop delete. **The barrier is what does not move.**

| device | last authored a write | in the waiting list |
|---|---|---|
| OnePlus CPH2581 | 2.4 hours | yes |
| iPhone | 4.3 days | yes |
| `Desktop (benedikt on Mac OS X)` — **old id** | 4.4 days | yes |
| Claude W7 verify | 5.2 days | yes |
| This Browser | 9.3 days | yes |

#### ⚠⚠ One of those five cannot ever ack, and it is provable

The realm holds **two `Device` rows named `Desktop (benedikt on Mac OS X)`**, and they share **one
public key** (`535bf625…`) under two different device ids. `local-store.properties` says this Mac
now runs as `2a093cd4`; the other, `6396a36d`, is the *same machine's* older identity, left behind by
a re-pair. Nothing runs as it, nothing ever will, and `Device` rows have no regeneration path — so
its tip is frozen and the barrier waits on it for ever.

That is enough on its own: **a realm that has ever re-paired a device can never satisfy this
barrier.** "This Browser" (9.3 days) and "Claude W7 verify" (a verification device from an earlier
session) are very likely the same story without the proof.

#### ⚠⚠ This corrects §4.22's own roster read

§4.22 concluded *"no device in the realm is provably dead — every one has authored a write within
~9 days."* Both halves are true and the conclusion does not follow:

- **Authoring a write is a fact about the past; the barrier needs a *future* ack.** A device that
  wrote nine days ago and no longer exists satisfies the first and never the second. `deviceLastSeen`
  can only ever say "was alive", which is why §4.22 was right that it is not a substitute for the
  tip — and the same argument applies to using it as a liveness test at all.
- **The duplicate keypair is the evidence `deviceLastSeen` cannot give.** Two ids, one keypair, one
  machine: no elapsed-time reading of either row shows that, and it is conclusive.

⇒ **D14 — the barrier needs a way to stop waiting on a device that cannot ack, and until it has one
the flip is blocked on *every* realm, not on this one's luck.** Candidates: revoke the orphaned id
(⚠ `Device` rows have no regeneration path — revoking the wrong twin takes out the live machine), or
an identity-dedup repair that folds ids sharing a keypair. Neither exists. ⚠ A liveness *timeout*
is the one answer §4.16 already rejected, for the reason it gave: the risk is fixed at the delete and
does not decay, so a timer expires into exactly the case that was never safe.

⚠ **Open, and not answered by this pass:** the OnePlus is online and syncing, and it did not ack
either within 13 minutes. Whether a *live* peer advances its tip past a tombstone on a useful
timescale is a separate question this run does not settle — and it has to be yes before the barrier
can work even with the dead ids removed.

---

### 4.24 ✅ Surfacing an orphaned identity — and why the obvious automatic fix is unsafe

§4.23 found the barrier waiting on a device id that can never ack, and D14 asked for a way to stop
waiting on it. **The mechanism to remove one already exists** — this slice makes the condition
*visible*, and deliberately stops there.

#### The manual repair works today, and it is safe — checked, not assumed

Revoking the orphan is the existing `RevokeDevice` op, and all three of its effects key on
**`deviceId`**, never on the keypair the twins share:

| effect | keyed on |
|---|---|
| relay device registration | `KnownDevicesStore.revoke(ownerId, deviceId)`; auth reads `getPublicKey(ownerId, deviceId)` |
| admission capability | the signed record was `"tenshu-admit-revoke-v1" ‖ ownerId ‖ deviceId ‖ epoch ‖ revokedAt`; now it is `LP("tenshu-admit-revoke-v3") ‖ LP(ownerId) ‖ LP(deviceId) ‖ LP(epoch) ‖ LP(revokedAt) ‖ LP(a33Pub)`, so it also names the A33 key, still matched together with `deviceId` (S2) |
| local roster | deletes the `Device` entity by id, plus a tombstone |

⭐ So the live machine keeps its own registration under its own id even though the orphan carries the
same public key. On the measured realm the orphan's lineage subtree is also **empty** — nothing names
it as its admitter, and the live desktop's `admittedByDeviceId` is absent — so the subtree revocation
strips nothing. And `RealmConvergence.admittedDeviceIds()` reads local `Device` rows, which
revocation deletes, so the orphan drops out of the barrier's waiting list.

#### ⚠⚠ Why this ships as a *label*, not a self-heal

The tempting version is `reconcileOwnDeviceRow` growing a second job: the keypair's owner silently
revokes every id that is not its current one. It already runs at startup, holds the signing key, and
has the realm-membership guard — it looks free.

It is not safe, because **two machines can hold one keypair**. A restore from backup carries the
secrets, and this codebase handles that case explicitly (`RealmRegistrar.reregisterThisDevice`,
`SyncHealthFigures.deviceUnrecognized`, `sync/design.md`). The restored machine would revoke the
original — and `Device` rows have **no regeneration path**, so that is unrecoverable. A person tells
"my old desktop entry" from "my other laptop" instantly; this code cannot.

⇒ **D15 — an identity claim about *which twin is live* belongs to the user, not to the device.**
`previousIdentitiesOfThisDevice` reports; the existing `RevokeDeviceConfirmDialog` asks.

#### What shipped

`previousIdentitiesOfThisDevice(devices, currentDeviceId)` in `:control-shared-logic` — rows sharing
the current device's public key under a different id — rendered in Control's device list as a badge
plus a sentence naming the consequence (it holds up storage cleanup for every device).

⚠ It reports **only** the group containing the current device. Two *peers* sharing a key is the same
shape but a different claim — this replica cannot know which of those is live — so saying anything
would be a guess presented as fact.

⚠⚠ The negatives are the load-bearing tests, because a false positive invites an unrecoverable
revoke: a keyless row must match **nothing** rather than every other keyless row (verified by
deleting the guard: it then returns every one of them), the current device is never flagged against
itself, a healthy roster produces nothing, and an unresolved `currentDeviceId` produces nothing.

---

### 4.25 ✅ The flip — §4.23's verdict is spent, and the barrier converged

§4.23 answered *do not flip*, and the reason was specific: the barrier was waiting on a device id
that could never ack. That reason no longer holds, and the steps were taken in order with each one
observed rather than assumed.

**1. The orphans were revoked** (by hand, via the §4.24 label + `RevokeDeviceConfirmDialog`). Roster
**6 rows → 3**: `OnePlus CPH2581`, `iPhone`, `Desktop … 2a093cd4`. The live desktop was confirmed
first from `local-store.properties` (`sync_device_id=2a093cd4…`) — §4.24's warning that revoking the
wrong twin takes out the live machine is not theoretical, and the two rows are identical in the UI
apart from the badge. Removed: `6396a36d` (the re-pair twin), `30377517` ("Claude W7 verify"),
`23e47545` ("This Browser"). Tombstones 68 → 71.

**2. The remaining two cleared themselves, and how they did is the finding.**

| device | why it cleared |
|---|---|
| OnePlus `0a80ef70` | it had been busy — **243 relay writes in the preceding hour** — so its tip had advanced past `stamp + margin` |
| iPhone `e4a23211` | cleared the moment Drive was **opened on it**; metadata sync is not entitlement-gated, so no rebuild was needed |

⭐ Between those two steps the report went `waiting on 4` → `waiting on 1` → **converged**:

```
1 candidate(s), would reclaim 1, reclaimed 0; dry run: planned 1, deleted nothing
```

Zero occurrences of "waiting on". **The first time this realm's barrier has converged**, and the
first time the dry run has planned anything.

⚠⚠ **This confirms `sync-retention-horizons.md` §2 rather than refuting it.** The barrier cleared
*because all three devices happened to be active*. An idle peer's tip does not move
(`ReclamationBarrierPropagationTest`, measured: 14 ms in 15 s), so a realm that deletes and then goes
quiet still stalls. What §4.25 shows is that the happy path exists — not that the instrument is right.

**3. Both preconditions for the flip were verified before it:** ST-3f is on
(`CONTENT_REPAIR_ENABLED = true`), and the single candidate is the **deliberate test delete from
§4.23's own exercise** — contentId `b308d293…`, stamped 2026-09-06 18:42:56 Z, three minutes before
that session's Control spawn. Not user data.

⇒ `RECLAMATION_SWEEP_ENABLED = true` on desktop. Android and iOS stay false pending a desktop pass.

⚠ **What this does NOT recover.** Reclamation is forward-only: deletes that predate the recorder
wrote no candidate and never will. The realm is at **15 748 blobs / 5.00 GiB**, essentially all
historical, and the flip reclaims **one** candidate. Getting the rest back needs something that
enumerates the relay's blobs against the live set, which nothing does today.

---

### 4.26 ✅ Android follows desktop — and why the iOS blocker did not survive checking

The desktop pass §4.25 asked for happened and passed: one candidate reclaimed, **4 634 B** actually
freed on the relay, the candidate consumed and forgotten, and the sweep silent on every pass after.

⚠ One measurement needed interpreting rather than reporting: the relay's blob **count** went
15 748 → 15 817 across the reclaim. That is not the sweep — the realm is at quota, refused uploads
still register a ref, and there are 277 zero-byte `content_blob` rows in total. The **bytes** are the
measure that moved, and they moved down by exactly what was reclaimed while another device was busy.

⇒ `RECLAMATION_SWEEP_ENABLED = true` on **Android**.

#### ⚠⚠⚠ RETRACTED — iOS is not blocked by the barrier's replica accounting

**This subsection originally argued a structural blocker, and it was wrong.** It is kept rather than
deleted because the mistake is instructive and because two other artifacts cited it.

The argument was: [ReclamationBarrier] computes `admittedDeviceIds - ownDeviceId`, which assumes one
`deviceId` is one replica; route-1 shares one Keychain `deviceId` across first-party apps; therefore
a sweep in Drive-iOS would subtract that id as "self" and silently drop Timeline-iOS and Control-iOS
from the peers it must wait for.

⚠⚠ **The second premise is false in the shipped configuration.** Route-1 apps do not hold separate
replicas — they open the *same* object store:

```kotlin
fun sharedReplicaDirectory(subdirectory: String): String =
    appGroupContainerDirectory(TENSHU_APP_GROUP, subdirectory) ?: applicationSupportDirectory(subdirectory)
```

`group.app.tenshu.shared` is declared in every shipped iOS app's `.entitlements` — the files say so
in as many words (*"so `MapsLocalRuntime` reads the SAME `objects`"*) — and co-resident writers are
serialised by a cross-process flock (§P1b/P2b: *"Co-resident Control + Drive writing the shared
replica… `FileAdvisoryLock` serialises writers"*). **One store.** A row a sibling writes is already
in the store this sweep walks, so excluding the shared id genuinely *is* excluding self, and the
"unseen second reference" it was supposed to guard against cannot be hidden there.

⚠ The `?:` fallback — no entitlement ⇒ app-local `applicationSupportDirectory` — *would* behave as
feared, because then the apps really do hold separate stores under one `deviceId`. That is a
degraded/dev provisioning path rather than what ships, and it is the only configuration in which the
retracted argument holds. Anyone reviving it must first say which branch of that `?:` they are in.

⇒ **iOS stays `false`, but not for TC-2b either — that line was also stale.**
`sync-hlc-tip-device-identity.md` records **TC-2b as BUILT**: `buildSignedTip()` merges through an
`HlcWatermarkStore` before signing, and iOS binds `IosSharedHlcWatermarkStore` in production DI,
co-located with the shared replica — so a runtime cannot publish below what a sibling already
published, and the N-clocks-over-one-tip-slot divergence is closed by construction. ⭐ The
verification gap this paragraph named — TC-2b never exercised on a device — **closed on
2026-09-07**: the two-app pass ran on hardware with three runtimes and passed (§6b of that doc).
So all three blockers stated here over time (replica accounting, TC-2b being open, its device
pass) are spent, and each of the first two was argued confidently before checking. ⚠⚠ What iOS
still lacks is the pass desktop and Android each got before their own flip: someone watching a
sweep reclaim something real and leave everything else alone. Do not inherit a fourth blocker
without testing it — and do not read the absence of one as a green light.

⭐ **What the mistake cost, recorded because it is the reusable part.** The claim was argued
confidently from two documents (§256 of the tip doc and Decision C) without checking the one fact it
turned on — whether siblings share a store. It then propagated into a flag KDoc, this section, two PR
descriptions and a whole design doc (`sync-replica-identity.md`, withdrawn) before a question about
the *web* case prompted the check. Two true premises and an unverified bridge between them read
exactly like a finding.

⚠ Everything §4.25 said about the instrument still stands on both platforms that are now on: an idle
peer's tip does not move, so a quiet realm never clears the margin
(`sync-retention-horizons.md` §2–§3). Android and desktop are enabled *despite* that, not because it
was resolved.

---

### 4.27 ⚠⚠ The flag was on and the pipeline was not — reclamation recorded nothing on Android

Found by the stale-comment sweep and confirmed against the source before acting.

**§4.26 flipped Android's flag and stopped one call short of the feature.** The sweep,
its sources and `reclamationDryRun = false` were all wired; the *recorder* was not. Android's call
into `fileServiceProxyImplementationFactory` passed eight positional arguments and stopped before
the trailing `reclamationRecorder`, which defaults to `null` — so `DeleteFileImplementation` ran
`runCatching { null?.invoke(…) }` on every delete. **No candidate was ever recorded on Android, the
list could only be empty, and the live sweep could not have reclaimed a byte if it tried.**

⚠⚠ **The failure mode is silence, not error.** A pass with no candidates returns before reporting,
so an inert pipeline and a realm with nothing to reclaim emit *exactly the same thing*. Nothing in
the flag, the KDoc, the logs or §4.26 distinguished them.

⚠ **§4.25's evidence is desktop's.** One candidate reclaimed, 4 634 B freed — measured on desktop,
then quoted in Android's flag KDoc as though it justified that platform. It does not, and the KDoc
now says so. **Android has still had no evidence pass of its own.** The wiring is the precondition
for one, not a substitute.

⭐ **The same audit found it a second time, on iOS.** iOS keeps `RECLAMATION_SWEEP_ENABLED = false`
but `RECLAMATION_DRY_RUN_ENABLED = true`, which is enough to build the sources — with no recorder.
So the dry run that its own KDoc calls *"the whole point of wiring this platform"* could only ever
plan over an empty list. That is the more dangerous half: **a dry run reporting "nothing to
reclaim" is exactly the evidence a maintainer would read before flipping the real flag.**

**Fixed on both.** iOS's wiring enables *observation, not deletion* — its sweep flag stays false, so
`reclamationDryRun` stays true and the sweep still stops before its first write.
⚠ Accepted on route 1: N runtimes share one deviceId and therefore one candidate row, and recording
is a read-modify-write, so two sibling apps deleting at once can lose an append. That fails toward
**keeping** bytes — a missed reclamation, never a premature delete.

⭐ **The invariant now has a test.** It was already written down, in prose, on the desktop module —
*"the recorder and the sweep are one feature … sweeping without recording finds nothing"* — and
enforced by nothing. `ReclamationRecorderWiringTest` reads all three `PlatformProxyModule`s and
fails any platform that builds a sweep (**either** flag — a dry run needs a recorder too) without
calling `reclamationRecorderFactory`. It is a source-text audit because no DI or runtime test can
see an absent argument at a call site; the module graph only exists inside a live proxy process.
⚠ It asserts it found all three files first, so a path change turns it red rather than vacuum-green.

⚠ **The general lesson, and the third instance this week.** A boolean named `…_ENABLED` describes
*intent*, never reach: `docs/tech-debt.md` records the same shape twice in this module's own build
file (CA-5c wired a service in one source set and declared it in another). Flipping a flag is not
evidence the path behind it is complete — and where the path reports success by staying silent,
only reading it end to end, or a test that does, will tell you.

## 5. Phases

| Phase | What | Gated on |
|---|---|---|
| **ST-0** | **Disk alerting** at ~75/90 % on **both** disks (§4.6), not just the data volume. ⭐ Smallest item here, and the one that turns the next outage into a scheduled decision. ✅ The runbook half is done — `resize2fs`, image prune and the two-disk table are in `deploy-relay-digitalocean.md` | — do first |
| **ST-0b** | ✅ **DONE** — `x-logging` (250 MB/container) across all three compose files (§4.6), plus the commerce Postgres via its cloud-init template. ⚠ Binds at container creation; all three boxes recreated | — |
| **ST-0c** | ✅ **DONE** — commerce `GET /health` round-trips the store, so a dead pool reads as 503 instead of a cheerful 200 (§4.6) | — |
| **ST-0d** | ✅ **DONE** — the relay's `GET /health` writes to the blob volume and deletes it, so a full disk reads as 503. ⚠⚠ Does **not** probe the DB: one lock over one SQLite connection would make a *busy* relay report as *down* (§4.7) | — |
| **ST-1** | ✅ **DONE and DEPLOYED** 2026-08-15 — `try/finally` around the temp write in `FileBlobStore.put`; relay runs `c4772fea` (§4.6) | — |
| **ST-2a** | ✅ **DONE** 2026-08-15 — the per-item loop re-reads the gate **and** the bucket selection, so both switches stop an in-flight drain at a file boundary. ⚠⚠ The gate alone would not have fixed the reported bug: it never reads the per-folder selection (§3.6) | — |
| **ST-2b** | ✅ **DONE** 2026-08-15 — Stop/Resume in Synced Folders, a persisted `PausedByUser` state, and a wire cancel that stops the **current file** at a chunk boundary (§3.7), and on the Files-screen banner (§3.8) | — |
| **ST-3a** | ✅ **DONE** 2026-08-16 — **zero orphans**: every byte is indexed live content, falsifying §3.2's prediction. 83 % of bytes sit in 5,067 large-file chunks; one owner (§4.8) | — |
| **ST-3b** | ✅ **LIVE on desktop + Android 2026-09-07 (§4.25, §4.26)**; previously complete/inert/observable (§4.17, §4.22): barrier, policy, sweep, recorder, candidate persistence, live-set walk, convergence observer, `contentChunkRefs`, and a schedule on the sync tick. Desktop and Android run with `RECLAMATION_SWEEP_ENABLED = true` and delete relay bytes; iOS passes sources observe-only. ⚠⚠ The observe-only mode is derived from `!RECLAMATION_SWEEP_ENABLED`, **not** from `RECLAMATION_DRY_RUN_ENABLED`, which now only wires the sources — its name no longer matches what it does. ⚠ The Android recorder gap this row used to name was **fixed** — all three platforms wire `reclamationRecorder` now. | — iOS wants a pass watching a sweep reclaim something real; TC-2b's device pass is closed |
| **ST-3c** | ⚠ **Capacity vs Σ(entitlements)** (§4.1). ⭐ The census makes this concrete: there is exactly **one owner**, so the sum *is* one BASIC tier — 50 GiB entitled on a 50 GB volume | ⚖️ maintainer |
| **ST-3d** | The P3 time-based sweep. ⚠⚠ **Three strikes**: no reference knowledge, no garbage to collect (§4.8), and nothing old to find — every blob on the volume is under 30 days (§4.15) | ⚖️ maintainer — very weak case |
| **ST-3e** | ⚠⚠ **Backfill** — free what was already unreachable before ST-3b existed. A *different* mechanism: it argues from absence rather than witnessing a delete, needs a relay ref-listing endpoint and one manifest fetch per live file, and ⚠⚠ must enumerate shared-realm references or it deletes a second party's content (§4.15) | ⚖️ maintainer — weigh against simply re-uploading |
| **ST-3f** | ✅ **LIVE 2026-09-06 (§4.18)** — `CONTENT_REPAIR_ENABLED = true` on desktop, the first flag in this track to go on. A re-emit under the same `contentId`, gated on this device holding the plaintext and on `REPAIRABLE_PROPERTY_IDS` (⚠⚠ without which a *thumbnail* resolve re-emits the full-size file). Demonstrated by `ContentRepairReEmitTest` | — |
| **ST-3g** | ⚠⚠ **The honest empty state** (§4.19). Not UI work: three of the four causes of a missing manifest resolve themselves, so "gone" is unobservable and no elapsed-time rule fixes that (D9); the signal wants to travel through the sync log (D10). ⭐ Part is unblocked — today's screen tells a *format* story about a *content* problem | ⚖️ before `RECLAMATION_SWEEP_ENABLED`, and D8 owes it |
| **ST-6** | ✅ **DONE** 2026-09-06 (§4.20) — a `507` is a marked, backed-off, user-visible *stop* instead of a ~30/sec retry storm. `isFull` now means "bytes cannot land" (figures **or** the relay's refusal), so the banner, the backup gate and the share gate agree. ⚠⚠ D11: the relay is the only authority on whether a write fits | — |
| **ST-7** | ✅ **DONE** 2026-09-06 (§4.21) — both pre-flights count **already-queued** bytes (`used + queued + required`) and share `availableAfterQueue(...)`, so the single-file gate and the folder-upload pre-check cannot disagree. ⚠ Per-tick figure: staleness under-counts, the permissive direction | — |
| **ST-8** | ✅ **DONE** 2026-09-07 (§4.24) — Control's device list names a row that is **this device's own earlier identity** (same key, older id), the condition §4.23 found blocking the barrier. ⚠⚠ D15: reports only; a backup-restore clone makes an automatic revoke unrecoverable | — |
| **ST-4** | Resumable emits (keep the CEK + committed chunk list), which makes abort cheap (D3) | ST-3a |
| **ST-5** | Resolve the Terraform `user_data` drift, so `apply` stops meaning "rebuild production" (§4.5) | — ops |

⚠ **Verification note:** none of ST-2 is provable by a Kotlin test alone — "the upload stopped" is a
statement about a running drain against a real relay. `SyncedFolderControllerTest` can pin the gate
re-read and the state transition; the rest wants a device pass with the relay reachable.
