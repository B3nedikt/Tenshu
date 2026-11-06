# Split the pull cursor per lane — the dense lane must not throttle the sparse one

- **Status:** **Draft v0.5 — TRACK COMPLETE. L0 + L1 SHIPPED; L4 REJECTED; L3 SHIPPED
  (the merged lane), which carried L2 with it.** The mechanism was confirmed on device (§2.1),
  fixed by per-lane cursors (L1), and then dissolved by serving one merged log under one cursor
  (L3, §4.3) — the class of bug cannot be expressed against a single ordered log. `advancedCursor`
  is deleted. ⚠⚠ **L4 was rejected on inspection, not built** (§4.5): tombstone acks already
  shipped in May, and GC'ing tombstones is forbidden by ADR 0006 decision 6 — it would resurrect
  deleted entities on long-offline devices. Its premise came from a stale code comment, now
  corrected. ⚠⚠ **L3 turned out to owe NO version bump of any kind** — the opposite of what §4.3
  assumed; see §4.3.1. Phases in §6.
- **Date:** 2026-08-02
- **Owner:** Benedikt
- **Related:** [`sync-device-catch-up.md`](sync-device-catch-up.md) (⚠ the sequel: fixing the
  cursor made catch-up *correct*, not *fast* — a wiped device still replays the whole log, and
  that note measures why and rejects the index-manifest answer) ·
  [`core-local-store-orphan-gc.md`](core-local-store-orphan-gc.md) (compounds this —
  a shorter delivery log means fewer pages to crawl) · `docs/tech-debt.md` ·
  `memory/project_sync_cursor_store_desync` (the skip-the-backlog class the current rule exists to
  prevent — do not regress it) · [`core-schema-evolution.md`](core-schema-evolution.md) §8.9 (HR #9)

---

## 1. The bug

**A device restoring from an empty store advances its pull cursor by exactly 500 seq per tick, no
matter how far ahead the available blocks reach — and re-delivers and re-applies nearly the same
500 blocks every tick.**

Measured on an iPhone rebuilding after a fmt-19 store wipe (instrumented `SyncScheduler`):

```
SYNCDIAG pull since=15013 got=500 nextCursor=71360
SYNCDIAG pull since=15513 got=500 nextCursor=71360
SYNCDIAG pull since=16013 got=500 nextCursor=71360
...
SYNCDIAG pull since=23044 got=500 nextCursor=71391
```

17 pulls → **8,233 blocks ingested, 0 dropped**, cursor advanced 8,031 of the ~56,000 needed.
Because re-ingest is idempotent the store stops growing, so the device *looks* stalled while
burning CPU, battery and bandwidth. This is what made a perfectly healthy file look missing for
~40 minutes.

## 2. Mechanism

`SyncScheduler.advancedCursor` takes the **`min`** of the two lanes' truncation ceilings:

```kotlin
val ceiling = minOf(
    if (blocks.truncated) blocks.cursor else Long.MAX_VALUE,
    if (tombstones.truncated) tombstones.cursor else Long.MAX_VALUE,
)
val delivered = max(blocks.cursor, tombstones.cursor)
return max(since, minOf(delivered, ceiling))
```

That rule is **correct and must be preserved in spirit**: a truncated lane must not let the shared
cursor run past the last row it actually delivered, or the undelivered remainder is skipped forever
(`project_sync_cursor_store_desync`). The defect is not the ceiling — it is that **one cursor serves
two lanes with opposite densities**:

- blocks matching a single device are **sparse** — 500 of them span ~56,000 `relay_seq` units;
- tombstones are **dense** — 500 of them span exactly 500.

So `min` pins the whole sync to the tombstone lane's pace. The block lane re-fetches from a cursor
that has barely moved, and the relay hands back the same first-500-after-`since` page.

### 2.1 L0 — CONFIRMED on device (2026-08-02)

The deduction above was verified by logging both `PullOutcome`s and the verdict together. Every
element holds:

```
SYNCDIAG pull            since=29044 got=500 nextCursor=71391
SYNCDIAG pull-tombstones since=29044 got=500 nextCursor=29544
SYNCDIAG cursor since=29044 blocks=(cursor=71391 rows=500 trunc=true)
                            tombs=(cursor=29544 rows=500 trunc=true)
                            -> advanced=29544 delta=500 ceilingFrom=tombstones(both)
```

…repeating identically at 29544 → 30044 → 30544. So: the tombstone lane **is** truncated, its
cursor **is** exactly `since + 500`, and it **is** the lane whose ceiling wins. Blocks are available
out to 71,391 and are held at 29,544.

**Cost of the coupling, measured:** from 29,044 to 71,391 at +500/tick is **~85 more ticks**, each
re-delivering and re-applying ~500 blocks. With per-lane cursors the block lane would jump to 71,391
on the **next tick**.

⚠ **The tombstone lane is dense across a huge seq range**: a *full* 500-row page at every `since`
from 15,013 through 30,544, packed into exactly 500 consecutive seq units each time. That is why the
ceiling never lifts on its own while the shared cursor exists.

⚠⚠ **Corrected 2026-08-03.** This section originally blamed "tombstones are never ack'd … Phase 4,
unbuilt" and called the throttle *systemic*, citing a comment at the tombstone loop. **That comment
was stale.** Acks shipped in May and body-GC already runs on quorum + retention; and tombstones are
kept forever *by design* (ADR 0006 decision 6), so the lane being dense is correct behaviour. The
coupling — sparse lane pinned by dense lane — is the real defect, and Option A fixes it. See §4.5.

### 2.2 Two things that look like the bug and are not

Recorded because both cost real time on 2026-08-02:

- **`since=15013 → nextCursor=71360` is not a cursor skipping the backlog.** The relay computes
  `nextCursor = matched.maxOfOrNull { it.seq }` (`SyncMailbox.kt`) — the highest seq *actually
  returned* — and `relay_seq` is one counter shared across every row and every owner, so a sparse
  500-block page legitimately spans a wide range.
- **A falling object count during replay is not data loss** — it is tombstone application deleting
  entities as history replays.

## 3. Where the cursor lives

`SyncManagerImpl.lastSyncTimestamp` is **a reserved row in the object store**, not a Syncable
composite and never on the wire:

```kotlin
const val SYNC_CURSOR_HASH: Long = Long.MIN_VALUE
const val SYNC_CURSOR_TYPE: String = "tenshu/sync/cursor"   // one positional LongValue field
```

Co-locating it with the store was deliberate (Option A1) so a wipe resets it and the
cursor-over-empty-store desync became structurally impossible. **Any fix must keep that property.**

## 4. Options

### 4.1 Option A — per-lane cursors (recommended)

Give each lane its own persisted cursor; each advances to its own `nextCursor` whether truncated or
not. `advancedCursor` and the whole cross-lane ceiling **disappear** — the coupling was the only
reason it existed.

Shape: a **sibling reserved row** (`SYNC_CURSOR_HASH - 1`, type `tenshu/sync/tombstoneCursor`)
rather than a second positional field on the existing row. A sibling is additive; appending a field
to a positional row is a reshape, and even though this row never crosses the wire, the
positional-encoding rule is not worth bending for one field.

Seed the new cursor from the existing value at first read (absent ⇒ take `loadSyncCursor()`, not
0). **Do not** seed 0 and replay all tombstones: replay should be idempotent and HLC-guarded, but
"should be" is not a reason to re-run every delete on every device.

### 4.2 Option B — relay returns explicit per-lane `hasMore` — **DEFERRED 2026-08-03, with a carrier**

Replaces the `rowCount >= SYNC_CHANGES_PAGE_LIMIT` inference, which is subtly wrong when a page
happens to be exactly full.

**Re-assessed after L1 shipped, and deliberately not built now.** The value shrank when L1 deleted
the ceiling: `truncated` has exactly **one** production consumer left, `wakeSignal.wake()`. And the
inference is sound by construction — `SYNC_CHANGES_PAGE_LIMIT` lives in `RelayProtocol.kt` and
*both* the client and the relay compile against that one constant, in the file that is explicitly
about the relay contract, so a change to it is already a protocol-relevant change. The only defect
is the exactly-full-final-page false positive: **one wasted tick, roughly 1 in 500 drains.**

The cost is permanent and disproportionate: `hasMore` reshapes the relay response, so
`RELAY_PROTOCOL_VERSION` goes 1 → 2 (untouched since E2e) plus a coordinated redeploy — surface every
future client carries forever, for a wasted tick.

⚖️ **Decision: defer, and bundle with the next change that reshapes this response anyway.** Two
natural carriers exist: **long-poll on `/sync/changes`** (`sync-relay-api.md` Phase 5, explicitly NOT
STARTED) and **Option C / L3**. Either takes `hasMore` for free, on a bump it was already paying.

✅ **The free half was taken** (#TBD): the truncation rule had drifted into **two** inference sites —
`PullOutcome.truncated` and an inline `blocks.size >= SYNC_CHANGES_PAGE_LIMIT` in
`pullSharedRealms`. Both now ask `PullOutcome.isFullPage(...)`, so the rule has one home. Duplicated
rules are exactly the shape that let `advancedCursor`'s ceiling drift from what the lanes needed.

✅ **And the rest landed with L3 (#TBD), exactly as the deferral predicted.** `SyncLogResponse`
carries `hasMore` stated by the relay, so the merged lane never infers truncation from a row count
— and the exactly-full-final-page false positive is gone. ⚠ Note the deferral's *cost* reasoning
turned out not to apply to the carrier that actually arrived: L3 needed no protocol bump at all
(§4.3.1), because it added a route rather than reshaping one. The decision to wait was still right
— it just cost even less than budgeted.

### 4.3 Option C — one merged lane — **SHIPPED (L3)**

Serve blocks and tombstones from a single ordered endpoint with one cursor. The principled
end-state: the class of bug cannot exist if there is genuinely one log.

**The premise turned out to be stronger than this section assumed.** The relay was never storing
two logs — `SyncMailbox.arrivalCounter` is ONE `AtomicLong` shared by `pushBlock` and
`pushTombstone`, so a total order across both kinds has existed since the arrival-cursor design
landed. `/sync/changes` and `/sync/tombstones` were two *views* of one log, each with its own
cursor. L3 does not invent an ordering; it stops hiding one.

As built:

- **`MailboxStore.getSyncLog`** returns the first `limit` entries of the **union** above the
  cursor, and reports the max seq of exactly that prefix. This is the whole correctness property:
  because the page is a prefix of the merged order, nothing below `nextCursor` is unsent in
  *either* kind, so one cursor is sound. Two independent `take(limit)` queries could never promise
  that — each returned its own first-500, whose max seqs differ by tens of thousands.
- **Both backends implement it**, and every test runs against both: the in-memory mailbox merges
  two lists, SQLite runs `UNION ALL … ORDER BY seq LIMIT limit + 1` (the extra row *is* the
  `hasMore` answer, in one round trip).
- **`POST /sync/log`** — same auth + signed-HLC-tip contract as `/changes`, and it returns peer
  tips, so a client on it needs neither older route. The auth/tip preamble was **extracted and
  shared** rather than copied: it is where a device's claim to an identity is checked and where a
  forged tip is rejected, and two copies of a security check drift.
- **`SyncLogResponse`** carries `blocks` and `tombstones` as two lists plus `nextCursor` and an
  explicit `hasMore`. ⚠ Kind-separated rather than interleaved **deliberately** — nothing consumes
  the interleaving, because ingest is HLC-guarded in both directions (`resolveConflictAndSave`
  drops an update at or below a recorded tombstone; `processDeleteBlock` drops a tombstone older
  than its target, the L1a fix), and the client already applied a page of blocks then a page of
  tombstones. A heterogeneous union type on the wire would buy an ordering guarantee with no
  consumer.
- **Client cursor adoption is `min(blockCursor, tombstoneCursor)`, not `max`.** Only `min` is
  provably delivered in both kinds; `max` would silently skip every tombstone in between — exactly
  the `project_sync_cursor_store_desync` failure. Both lane cursors are then written in lockstep
  with the merged one, so a fallback at any tick resumes correctly with no special case.

### 4.3.1 ⚠ L3 owes no version bump — and that was not obvious

§4.3 originally read "It is a protocol change (`RELAY_PROTOCOL_VERSION`, new route, both sides)".
**That was wrong on both counts**, and the reason matters for the next slice that adds a route:

- **No `RELAY_PROTOCOL_VERSION` bump.** `RelayProtocol.kt` is explicit that *purely additive routes
  do not bump*: a client that never calls a new route cannot be broken by its existence.
  `/sync/changes` and `/sync/tombstones` are untouched and still served, so an older client is
  unaffected and an older *relay* is handled client-side (below). No coordinated redeploy, no flag
  day in either direction.
- **No `TENSHU_STORAGE_FORMAT_VERSION` bump.** A new op id and a new response composite are
  additive — nothing existing is reshaped, so no peer can misparse. This is not an argument we had
  to trust: `SchemaShapeBaselineEnforcementTest` reported exactly *"3 new type(s) are not pinned
  yet"* and zero shape changes, which is the machine-checked form of the claim.

**What replaces the bump is a client-side probe.** A newer client against an older relay gets a
bare unmatched-route 404, which `RestSyncRelay` maps to `SyncLogFetch.RelayUnsupported`; the
scheduler latches the merged lane off for the process and uses the two-lane path.

⚠⚠ **Two things can make the lane unavailable, and both must degrade to the fallback rather than
to "offline":** the relay lacking the route, *and this proxy lacking a `GetSyncLog`
implementation*. The second is not hypothetical — iOS route-1 and the JS runtime assemble their own
operation graphs, and several harnesses register ops by hand; six existing tests failed exactly
this way when L3 first landed. An unimplemented op raises a bare `IllegalStateException` from
`ExecutionContext.execute`, which caught as a generic failure flips the device **offline** and
stops the pull dead — while a perfectly good fallback exists. The scheduler therefore *asks*
`implementationFactory(GetSyncLog.ID) != null` rather than discovering it by exception; matching on
that message would have been the fragile alternative.

⚠ Reporting a working-but-older relay as "offline" is the nastiest available failure, because it
hides itself: the fallback keeps syncing while the UI blames the network.

### 4.4 Rejected — raise the page limit

Moves the constant, not the asymmetry. A denser tombstone history reproduces it at any limit.

### 4.5 ~~Option D — ack tombstones so the lane thins~~ — **REJECTED 2026-08-03, do not build**

This proposed acking tombstones and GC'ing them relay-side so the lane stops returning full pages.
**Both halves were wrong**, and it was checked before implementation rather than after.

**Acks already exist.** `MailboxStore.acknowledgementSet` derives per-device quorum from the HLC-tip
piggyback (`tip >= tombstone.hlc.physicalTime`), and `SyncMailbox.gcSweep` — driven from the relay's
`Application.kt` — already gates GC on **quorum AND the retention window**. Relay-plan §3.1 shipped
in May. The premise came from a stale comment in `SyncScheduler.pullTombstones` reading *"Tombstones
aren't ack'd in v1 … Phase 4"*; that comment has been corrected.

**GC'ing tombstones is forbidden by an accepted, implemented ADR.** ADR 0006 decision 6:

> *Tombstones themselves don't get GC'd, only their target bodies. The relay keeps `DeleteBlock`s
> indefinitely — they're the chain of provenance for any device that comes online after retention.*

`dropBodiesFor` does exactly that: it replaces each target `SyncBlock`'s payload with an
envelope-only proxy, freeing the bytes while the entry and the tombstone survive. Deleting the
tombstone would leave a long-offline device never learning of the delete and **resurrecting the
entity** — precisely the bug tombstones exist to fix, and what decision 3 depends on.

**So what did L0 actually observe?** That the relay serves every tombstone above the cursor — which
is correct behaviour, not a missing feature. Before L1 it throttled the block lane; after L1 the
lanes are independent and it converges by itself. The residual costs are a one-time replay on a
fresh device and unbounded but tiny tombstone storage (10,522 rows ≈ 2.1 MB measured). Both are
accepted consequences of decision 6.

⚠ **Lesson for this doc's other options:** the "systemic trigger" framing in §2.1 was built on the
same stale comment. Sparse-vs-dense lane coupling is real and Option A fixes it; "the tombstone lane
is *saturated and shouldn't be*" was not.

## 5. ⚠ The schema-bump trap this fix must not fall into

A new reserved row is a stored-shape addition, so HR #9 says bump. **But a `:core` bump with no
registered migration chain wipes every store** — `coreReadableFloor()` returns `currentVersion` when
no chain exists, so the recorded version falls outside `[floor, current]` and
`SqliteObjectStore`/`FileObjectStore` delete everything. That is exactly what fmt 19 did on
2026-08-02: an hour of desktop re-sync, repeated iOS launch-watchdog kills, and this investigation.

**Register an empty `schemaMigration(CORE_SERVICE_ID, fromVersion = <previous>)` step with the
bump.** Then `froms = {previous}`, the floor walks back to `previous`, the recorded version is
reachable, and the store *migrates* instead of wiping. This is cheap and should arguably be the
default for every core bump that does not actually reshape existing rows.

## 6. Phasing

| Phase | Scope | Gate |
|---|---|---|
| ~~**L0**~~ **DONE** | Log the tombstone lane + the `advancedCursor` verdict together | ✅ §2.1 — `ceilingFrom=tombstones(both)`, `tombs.cursor == since + 500`, blocks held at 71,391 |
| **L1** | Option A: sibling cursor row, seeded from the existing cursor; delete `advancedCursor` and the cross-lane ceiling; bump core **with an empty core migration step** (§5) | a restoring device advances by its blocks' `nextCursor` per tick, not +500; no store wipe on upgrade |
| ~~**L2**~~ **DELIVERED BY L3** | Option B: explicit per-lane `hasMore`. Deferred on its own (§4.2) as not worth a protocol bump for one wasted tick per ~500 drains — then L3 reshaped the response anyway and took it for free. `SyncLogResponse.hasMore` is stated by the relay, exact even for an exactly-full final page | ✅ `hasMoreIsExact_evenWhenTheFinalPageIsExactlyFull`, run against **both** mailbox backends |
| **L3** **SHIPPED** | Option C: one merged log, one cursor — `MailboxStore.getSyncLog` (both backends) + additive `POST /sync/log` + `GetSyncLog`/`SyncLogResponse` + client adoption at `min(blockCursor, tombstoneCursor)` with a latched fallback. ⚠ **No version bump of any kind** (§4.3.1) | ✅ a 40-tombstone/5-block log — the on-device density asymmetry — pages straight through in ceil(n/limit) ticks; an older relay AND an unwired op each fall back **without** reporting the device offline |
| ~~**L4**~~ **REJECTED** | ~~ack tombstones + relay GC~~ | ✅ §4.5 — acks already exist (`acknowledgementSet` + `gcSweep`, quorum AND retention); GC'ing tombstones is forbidden by ADR 0006 decision 6 and would resurrect deleted entities on long-offline devices |

L0 was small and non-negotiable — the whole design rested on one deduced number. L1 was the fix;
L3 is the end-state that makes the bug unrepresentable, and it absorbed L2 on the way past. The
sequencing was worth it in retrospect for a reason worth recording: **L1 first meant the urgent
data-visible bug was fixed under a client-only change**, and only then did the protocol-shaped
work happen — with the fallback path it needs already built, tested, and known-good, because it is
literally the code L1 shipped.

**Regression guard for L1:** the skip-the-backlog case must stay closed. A unit test that a
truncated lane never advances its own cursor past its last delivered row, and an integration test
that a device with a multi-page backlog in *one* lane still receives every row of the other.

## 7. House rules

- **HR #1 unaffected** throughout. L1 was client-only; L3 adds a relay route that serves the same
  ciphertext envelopes the two it merges already served — the relay still cannot read a byte of it.
- ⚠ **L3 needed neither version bump** (§4.3.1), correcting this section's earlier claim that it
  would take `RELAY_PROTOCOL_VERSION` and a coordinated redeploy. A purely additive route does not
  bump (`RelayProtocol.kt`, normative), and additive schema types reshape nothing — confirmed by
  `SchemaShapeBaselineEnforcementTest` naming 3 new types and 0 changed shapes. **A relay redeploy
  is still needed for the route to exist**, but it is uncoordinated: old clients keep working
  against a new relay, and new clients keep working against an old one.
- **HR #9:** L1 adds a reserved store row → core bump **plus the empty core migration step** (§5),
  which is what keeps it from wiping every device.
- **No threat-model delta** — cursors are local metadata; the relay already sees every `since`.
