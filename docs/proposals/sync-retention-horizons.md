# Retention horizons — the barrier's wrong instrument, and the roster behind it

**Status:** v0.6 (2026-09-08). **RH-0 + RH-1a + RH-1b all shipped** — the barrier decides on cursors,
and §2's quiet-realm deadlock is closed. Split by §3.3,
which is a decision the phasing line did not anticipate. Written from a live investigation: production quota
reached 100 % of the free tier and could not be reclaimed. v0.1 led with membership; **§2's
measurement moved that to second place**, and the concrete change is now §3.

> ⚠⚠ **Its consumer is gone (CR-2 B6, 2026-10-04, `content-plane-cr2-b6-spec.md` §7):** `ReclamationBarrier`, `RealmConvergence` and the candidate list RH-1a stamped were removed with ST-3b's sweep; the scheduler no longer records peer cursors. ⚠⚠ **And its plumbing is gone too (2026-10-29, before the production reset):** the relay no longer records or publishes cursors — `device_cursor` is dropped at relay schema v5, `peerCursors` and `PeerCursor` are off the wire — and the `stampTombstoneSeq` seam is deleted. `PushTombstone` still returns the relay's assigned seq; nothing reads it. Everything below is history; a future consumer re-adds the cursor with its reader (threat model, the 2026-10-29 amendment).

Related, and load-bearing:
[`drive-upload-stop-and-reclamation.md`](drive-upload-stop-and-reclamation.md) §4.16/§4.23/§4.24,
[`core-schema-evolution.md`](core-schema-evolution.md) §8.3,
[`sync-device-catch-up.md`](sync-device-catch-up.md) §4.B/§5.7,
[ADR 0006](../adr/0006-sync-tombstones.md) + [`sync/design.md`](../sync/design.md).

---

## 1. The measured state (2026-09-07)

- Owner `c2162a91…`: **15 747 blobs, 5.00 GiB — 100.0 % of the free tier**, every content write refused.
- The reclamation dry run: `1 candidate(s), would reclaim 0, reclaimed 0; waiting on 4 device(s)`.
- ⇒ Flipping `RECLAMATION_SWEEP_ENABLED` today reclaims **zero**. The flag was never the blocker.

§4.23 read that as an orphaned re-pair identity, and generalised it: *a realm that has ever
re-paired a device can never satisfy this barrier*. True, and not the whole story — the same report
listed a **OnePlus that was online and syncing throughout**. That one was recorded as unexplained.
§2 explains it.

---

## 2. ⚠⚠ The outbound half cannot do the job it is named for

`ReclamationBarrier`'s own KDoc concedes the weakness:

> **Outbound — `peerTipPhysicalMs`.** Has every admitted device published a tip past the tombstone?
> … ⚠ *On its own this proves only that the peer was **polling** after the delete — a tip is
> wall-clock physical time, and a peer many pages behind publishes a current one* — which is why it
> must never ship as the whole gate (D7).

Its *purpose* is sound and still needed: a peer that has applied the tombstone no longer holds the
row, so it cannot **newly copy** it into a fresh reference between the delete and its next sync.
(The inbound half, `caughtUp`, is *"the half that protects the data"*; this one closes a race.)

But the instrument does not establish that, and it has a second failure nobody had measured.

### 2.1 ⭐ Measured: an idle peer's tip does not move at all

`buildSignedTip()` publishes `HLCManager.current()`, raised only by `tick()` (a local write) or
`update()` (an ingested block) — never by wall clock. Over 15 s of a settled two-device realm
(`ReclamationBarrierPropagationTest`):

```
PROBELONG total=300 span=14ms steps=[(0, 0), (20, 14)]
```

**14 ms of travel, in one discrete step, then nothing.** An event-driven step function, not a climb.

⇒ Ingesting the tombstone lifts a peer to roughly the delete's instant — `update()` takes
`max(current, remote, now)` — which is where it must be **plus `DEFAULT_SKEW_MARGIN_MS` (30 s)**.
Nothing then moves it. **A realm that deletes a file and goes quiet sits one margin short for ever,
with every device present, online and caught up.**

⚠ Every pre-existing convergence test avoided this by stamping its tombstone in 2023 — *"well
before the peers' live clocks, so the margin is cleared"*. The comfortable case was the only one
covered.

### 2.2 So there are two defects in one gate

| | consequence |
|---|---|
| a tip does not prove the peer **consumed** the delete | the gate can pass while a peer is thousands of blocks behind |
| a tip does not move without traffic | the gate cannot pass at all in a quiet realm |

The first is a soundness hole the KDoc already names. The second is why the OnePlus never acked.

---

## 3. ⭐ The fix: compare the consumed cursor, not the clock

**The relay is already handed the right value on every poll and discards it.**

```
SyncRouting.kt      val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
SyncMailbox.kt      by `seq > since` (was: `block.hlc.physicalTime > since`)
```

`since` is a **monotonic relay sequence number** — the device's consumed cursor. The tombstone lands
at a known `seq`. "Has every admitted device consumed past it?" is then a fact rather than an
inference:

| | tip (today) | consumed cursor |
|---|---|---|
| proves the peer **applied** the delete | ✗ — only that it polled | ✓ |
| needs a skew margin | ✓ 30 s — two wall clocks compared | ✗ **no clock is involved** |
| needs traffic authored after the delete | ✓ — §2.1 | ✗ — the peer just reads past it |
| deterministic | ✗ | ✓ |

⭐ One change, three properties. And the margin disappears rather than being tuned: it exists *only*
because the comparison is between two devices' wall clocks.

### 3.1 ⚠ What it costs, honestly

§4.16 rejected the per-peer form for a stated reason:

> *The per-peer form would have needed a durable, HLC-ordered watermark per device; the global form
> needs one boolean the scheduler already computes.*

That trade reads differently now. The **client** needs no new bookkeeping and pushes nothing new —
the value is already on the wire. What is new is the **relay** recording it (one slot per
`(ownerId, deviceId)`, exactly the shape `SqliteHlcTipStore` already has) and echoing it back beside
`peerTips`. That is new relay state and one wire field, which §4.16 was right to want to avoid —
but it is a fraction of what was rejected, and it buys a property the current gate does not have.

### 3.2 ⭐ What the relay learns: nothing it is not already given

The obvious objection is House Rule #1 — does this hand the relay new information? **It does not.**

1. **`since` is already on every request.** It is how pagination works: `getChanges(ownerId, since)`.
   The relay is handed each device's consumed cursor on every poll today, and has been since the
   cursor became a `seq`.
2. **The relay already persists per-device state of exactly this shape.** `hlc_tip (owner_id,
   device_id, tip BLOB, PRIMARY KEY (owner_id, device_id))` — a cursor slot is the same table shape,
   the same keying, one column narrower.
3. **The threat model already scopes this class of metadata.** Device names and authorization live
   inside encrypted `Device` blocks; what remains relay-visible is *"device counts and timing"*. A
   log position is activity metadata of that kind, and reveals nothing about content — it is a
   position in the owner's own log.

⚠ **"Already visible" is not the same as "already stored", and the difference should be stated
rather than waved past.** The change is one of *retention*: a value the relay sees transiently
becomes a value it holds at rest, and therefore one that appears in its backups and in anything a
compromised relay yields. The marginal exposure is small — the relay already stores a per-device
**wall-clock** tip, which is arguably the more revealing of the two — but it is not zero, and it is
the honest cost of D2.

⚠⚠ **House Rule #2 applies regardless.** A stored field plus a response field is a change to the
relay's data shape and to the sync protocol, so this lands with a threat-model amendment in the same
PR even though the amendment's content is "no new information, new retention".

⚠ ~~**The cursor probably has to be signed**~~ — **settled the other way in RH-1b (Q1, closed).**
The reasoning below was right about the risk and wrong about the actor. Lying *upward* does clear the
barrier early and make another device delete bytes; but only a device's **own** cursor is forgeable
(the relay reads `(ownerId, deviceId)` from the session, not the body), and an admitted device holds
the realm keys anyway — it can delete the data directly. The revoked-device case named here fails
because revocation deletes the `Device` row, so the barrier stops reading that cursor at all. The
threat model carries the full argument and the one condition that would reopen it: a member who is
not a co-owner.

⚠ It does **not** replace the inbound half. `caughtUp` remains what protects the data; this makes
the outbound half mean what its name says.

⚠ It also does not close §4.16's irreducible residue: a device **awake and disconnected** holds
writes nobody can observe. ST-3f exists to make that survivable, and no barrier removes it.

---

### 3.3 ⚠⚠ RH-1 needs a sequence the candidate does not carry — D9

Found while building RH-1, not while writing this doc, and it is the reason §11 now splits that
phase in two.

**The units do not match.** The barrier is asked *"is every peer past this tombstone?"* and the
tombstone is identified by a **millisecond**:

```kotlin
data class Entry(val contentId: String, val tombstonedAtMs: Long)   // ContentReclamationCandidates
```

A cursor is a **relay sequence**. There is nothing to compare it against, and the relay assigns the
seq on push and then throws it away: the route answers with the literal string `call.respond("OK")`,
so RH-1a is not widening a response body — it is **introducing** one where none exists.
(`pushTombstone` is declared `TResult<Data?>` client-side, so the return type itself needs no change.)

⚠ So "the outbound half switches to the cursor" is not a swap. Three ways to close it, and the
cheapest is the one that is wrong:

| | approach | verdict |
|---|---|---|
| **a** | `/push-tombstone` returns the assigned seq; the candidate records it | **chosen** |
| **b** | stamp the candidate with the device's *own* cursor | ⚠⚠ unsafe as stated — see below |
| **c** | ask the relay "have all peers passed this tombstone?" | impossible under HR #1 |

⚠⚠ **(b) is the trap, because its safe direction is the opposite of the obvious one.** At *delete*
time a device's cursor is **below** the tombstone's seq — the block has not been pushed yet — so
using it would clear the barrier **early**, which is authority to delete bytes that a peer may still
reference. It works only as an *upper* bound taken after the push has landed, and it is not even
clear a device's cursor ever reflects its own blocks, since a device filters its own writes out of
its own pulls. A bound whose safety depends on an unverified claim about pull filtering is exactly
the shape of reasoning this track has already had to retract twice.

⚠ **(c)** would need the relay to know which seq carries a given `contentId`'s tombstone. That means
reading a manifest, which House Rule #1 forbids — the same wall that makes relay-side refcounting
impossible and put reclamation on the client in the first place.

⇒ **D9. The relay returns the assigned sequence from `/push-tombstone`, and the candidate records
it.** Chosen despite being the largest of the three because it is the only one where the compared
quantity is *the actual thing* rather than a bound whose safe direction has to be argued. The relay
already computes the value and discards it, exactly as it did with `?since=` before RH-0.

**What it costs**, stated so the phase is not understated a second time:
- the push-tombstone response gains a field — a wire change, so **HR #2** applies again;
- `ContentReclamationCandidates.Entry` gains a field, and its rows are an **encoded preference
  string** — `"contentId\u001Ftombstonedat"`. ⭐ Cheaper than it looks: `decode` splits on
  `row.indexOf(SEPARATOR)`, the **first** separator only, so a third field appends without
  disturbing how old rows parse. Old rows still need a defined seq-absent meaning, though;
- ⚠ candidates written *before* this ships have no seq and never will. They are forward-only
  already, so the honest handling is to let them keep using the tip comparison until they age out,
  rather than to invent a seq for them.

⚠ **A pattern worth naming.** This is the third phase in this track whose one-line entry understated
its work — RH-0 turned out to owe an HR #9 version bump, the iOS flag turned out to have no real
blocker, and RH-1 turns out to need a wire change. All three lines were written before anyone read
the code paths they describe. Treat the phasing below as a sketch of *intent*, never of size.


### 3.4 ⚠⚠ RH-0 recorded the wrong cursor — found while building RH-1a

§3.3 said RH-1a was "carry the seq, no behaviour change". Building it found a **second** defect in
RH-0, and this one would have been load-bearing.

RH-0 recorded `?since=` from **both** `/sync/changes` and `/sync/log` into one row upserted with
`MAX(...)`. Those routes do not carry the same quantity:

| route | cursor it receives | safe to compare against a tombstone seq? |
|---|---|---|
| `/sync/log` | `min(blockCursor, tombstoneCursor)` | ✅ conservative |
| `/sync/changes` | the **block lane's** cursor alone | ❌ over-states |

⚠⚠ The lanes share the relay's counter without sharing its **density** — measured on-device, *500
blocks spanned ~56 000 seq while 500 tombstones spanned exactly 500*. So the block cursor runs
arbitrarily far ahead of what a device has consumed in tombstones, and `MAX(...)` let it win.

That over-states consumption, which is the unsafe direction: the barrier would answer *"yes, every
peer is past it"* early and authorise deleting bytes a peer still references. ⭐ The reproduction is
in `SyncLogRoutingTest` — with RH-0's recording restored, a device that reported a merged cursor of
**100** and then paged the block lane from **99 999** is stored at 99 999.

⚠ **The client already knew this rule and the relay did not inherit it.** `SyncScheduler.mergedCursor`
carries a KDoc arguing exactly this — *"`min`, not `max` … taking `max` would silently skip every
tombstone in between"* — for the client's own pull. It applies unchanged to what the relay stores.
Monotonicity in the wrong quantity is still the wrong quantity.

**Fixed:** `/sync/log` is the sole recorder. ⚠ A device that pulls only through the two-lane fallback
now records no cursor, so **absence must read as "not caught up"** — an obligation RH-1b inherits,
and `peerCursorSnapshot()` returning a map without that device is exactly how it will surface.

### 3.5 What RH-1a shipped, and the fork it did not close

**Shipped (the relay half):** `/sync/push-tombstone` returns the seq it assigns rather than the
literal `"OK"`, through `MailboxStore.pushTombstone(...): Long`. ⭐ Compatible in both directions —
the client checks only `status.isSuccess()` and ignores the body. Plus §3.4's correction, and the
HR #2 amendment.

⚠⚠ **Not shipped — and it is a design choice, not plumbing.** A candidate still cannot record the
seq, because of an ordering the doc had not accounted for: `DeleteFileImplementation` emits the
tombstone and records the candidate **synchronously at delete time**, while the push happens later
on the sync lane. `TombstoneEmitter.emit(...)` returns `Unit`, so **nothing links a candidate to the
push that carried its tombstone.**

Three ways to close it, none free:

| | approach | cost / risk |
|---|---|---|
| **α** | `emit` returns the `blockId`; the push path reports `blockId → seq`; a stamper fills candidates in | exact; plumbing across three modules |
| **β** | stamp every unstamped candidate with the seq of the next completed push | needs a *strict* ordering argument — a candidate recorded mid-push must not take that push's seq, or it under-states and clears early |
| **γ** | record the candidate only after its push lands | restructures the delete path; a crash between delete and push loses the candidate |

⭐ **α is the recommendation**: it is the only one whose correctness is a *type*, not an argument
about interleaving. β's failure mode is precisely the one §3.3 warned about — a bound whose safe
direction has to be reasoned about — and this doc has now had to retract two of those.

### 3.6 ✅ α as built

`TombstoneEmitter.emit` returns the emitted block's `blockId`; `DeleteFileImplementation` passes it
to the recorder; `Entry` carries `tombstoneBlockId` and `relaySeq`; and the scheduler's tombstone
drain stamps the seq the push returned onto the candidates of that same delete, through a new
`ReclamationSources.stampTombstoneSeq`.

⭐ **Cheaper than §3.5 estimated, for one reason found while building**: `PushTombstoneImplementation`
already returns `(result as TResult.Success<*>).value`, so the seq rides the operation's **existing**
return channel. No operation contract changed — only what the relay client puts in the success value.

⚠⚠ **First stamp wins.** A retried push takes a *later* seq — the drain removes a block only after
its operation returns, so a crash between the two re-pushes it. Overwriting would move the barrier's
threshold forward and make the candidate wait on peers reaching a point the delete never needed,
which in a quiet realm means never.

⚠ **A short row still encodes byte-identically to the pre-α format.** An older build decodes with
`substring(indexOf(SEPARATOR) + 1)` and `toLongOrNull()`, so a four-field row parses as null and
its candidate is *skipped*. Emitting the long form only when there is something extra to say keeps a
downgrade lossless for every row that predates this.

⚠ **`relaySeq` stays null against a relay older than RH-1a**, which answers the literal `"OK"`. That
is a normal outcome, not an error: the candidate is simply not yet comparable. **RH-1b must read a
null seq as "not comparable", never as "safe to reclaim"** — the same obligation §3.4 leaves it for
an absent peer cursor.

⭐ Every guard mutation-tested: dropping the blockId pass-through yields
`expected:<[block-for-photo]> but was:<[null]>`; the encode, the first-wins stamp and the
identity-on-no-match are covered by `ContentReclamationCandidateSeqTest`.


## 4. Behind it: three mechanisms, one roster dependency

§3 fixes the instrument. It does not fix what the gate is *gated on* — every admitted, non-revoked
device, with no liveness notion. That dependency is shared:

| mechanism | waits for | what a stall costs |
|---|---|---|
| content reclamation barrier | every device past the tombstone | **storage quota** |
| tombstone GC quorum | every device to ack | log growth |
| compaction floor (E4) | every device to record a supported version ≥ F | upcasters never retire |

> ***Floor advance is roster-gated, not time-gated.*** *The floor for service S advances to F only
> when **every registered, non-revoked device** has recorded a supported version ≥ F… A device
> offline for six months therefore *holds the floor back* until it returns and updates — or is
> revoked/pruned through the existing device-management path (ADR 0007), which is precisely the
> lever the user already holds for a lost device.*

⇒ A fix inside any one mechanism leaves the other two stalled on the same dormant device. §3 makes
the barrier *correct*; it does not make a year-dormant device stop blocking it.

---

## 5. What tombstone retention actually buys

The intuitive reading — *"a returning device must learn about the delete"* — is not what the barrier
is for. `SyncProcessor` resolves by HLC, and recreation is **defined** behaviour:

> *SyncBlocks at HLC < the tombstone's HLC will be short-circuited; SyncBlocks at HLC > the
> tombstone's HLC are accepted and **treat the entity as recreated (standard CRDT tombstone
> semantics)**.*

| the returning device's write was authored… | outcome | needs the tombstone? |
|---|---|---|
| **after** the delete | recreates the entity — defined, not a failure | no |
| **before** the delete | must be rejected | **yes** |

⇒ **Tombstone retention buys exactly one property: rejecting writes *older* than the delete.** Which
turns "keep tombstones for ever" into a bound:

> **Keep a tombstone until no un-pushed write older than it can still exist.**

The only source of such a write is a device's **durable pending-block journal**. A device that has
been offline and *idle* holds none — its journal is empty and the rest is replica state.

⚠ The two retentions must stay ordered: **tombstone retention ≥ the horizon within which a device may
still push.** Otherwise a pre-delete write arrives indistinguishable from a create and the entity
resurrects — Cassandra's `gc_grace_seconds` failure exactly.

---

## 6. The snapshot is the shared enabling mechanism

A device past that horizon must not merge stale replica state; it must re-derive. That is affordable
only if current state can be handed over cheaply — which `sync-device-catch-up.md` §5.7 / C8 proposes
and which §8.3 says compaction already *is*:

> *The recovery blob is already this artifact in spirit — a current-state snapshot at one version.*

| | compaction (E4) | snapshot (§5.7) |
|---|---|---|
| walks | current state | current state |
| emits | fresh **blocks** at the current version | a sealed **blob** per slice |
| effect | the floor can advance | a joiner can skip to `asOfSeq` |

1. **A snapshot expresses deletion by absence** — a device rebuilt from one needs no tombstones to
   learn a file is gone, which is what lets tombstone GC finish.
2. **Re-bootstrap stops being expensive** — the blob at `asOfSeq`, then `seq > asOfSeq`.

⚠ **Re-bootstrap does not mean discarding local work.** Replica state is superseded; the pending
journal replays on top and pushes. v0.1 of this document said otherwise and was wrong. The one case
needing a decision is a journal entry whose target the snapshot does not contain: a **create** is
genuinely new; an **update to an absent id** is the resurrection candidate of §5. Per D15 — an
identity claim belongs to the user — that is plausibly a prompt, not a silent policy.

---

## 7. ⚠ What this does NOT propose

- **No liveness timeout on deletion.** §4.16 rejected it and was right: *"the risk is fixed at the
  delete and does not decay, so a timer expires into exactly the case that was never safe."* §3
  removes a *clock comparison*; it adds no timer.
- **No index blocks.** §4.B rejected a producer-written index chain: an immutable index *groups but
  cannot identify supersession*, and it *fights compaction*. Indexes over a log are conventionally
  derived and stored outside it (git bitmaps, Datomic).
- **No automatic revocation.** §4.24 / D15: two machines can hold one keypair (restore from backup),
  `Device` rows have no regeneration path, and a wrong self-revoke is unrecoverable.

---

## 8. Prior art

You cannot have **unbounded offline devices**, **unanimity-based GC**, and **bounded storage**.

| system | gives up | why it does not transfer |
|---|---|---|
| Dropbox / Drive / iCloud | unanimity — the **server is authoritative** | the relay is zero-trust and cannot read manifests (HR #1) |
| Cassandra / Riak | unbounded offline — `gc_grace_seconds`, then a node **must be re-bootstrapped** | closest precedent; §5 + §6 are this, with the grace derived rather than fixed |
| ZooKeeper / Kafka / Dynamo | membership permanence — a **lease**, rejoin transfers state | the shape §4 needs, but safety must come from the rebuild path, never from expiry |

---

## 8b. ⭐ What RH could not fix, and where it goes next

RH-0…RH-1b made the barrier's answer **correct**. They could not make reclamation **complete**, and
2026-09-08 measured the difference: after a 929-file delete reclaimed to the byte, the realm still
held **~14 750 orphaned blobs — 94 % of a 5 GiB quota — against 11 live files**.

⚠⚠ That is not legacy residue alone. Forward-only recording leaks structurally: the candidate list is
capped at 2 000 with overflow dropping the *new* entry, only `DeleteFile` records at all, and
recording is deliberately best-effort. A fresh relay on today's code leaks more slowly, not never.

⇒ [`content-plane-refcount.md`](content-plane-refcount.md) takes it from here. It argues the two
planes are inverted — content should be transactional and metadata eventual — and that this track's
barrier is a client-side *approximation of a refcount*, built because the relay was believed unable
to hold one. ⚠ Its §5 names the real keep-alive holders — `File.ContentId`, `SharedFile.contentId`
under the shared realm's owner, and Fit's field-less `Workout.Streams` — and closes the graph by the
schema's `OnDemand` trait at the sync layer's two seams, never by asking an operation to declare.
⚠⚠ Its §6c.1 is the finding this track should read first: under ADR 0006's recreate semantics the
barrier *was* doing a correctness job, so a refcount must re-declare on recreate.

## 9. Open questions

- ~~**Q1. Does the consumed cursor need to be signed?**~~ **CLOSED 2026-09-08, with an argument
  rather than a signature** (threat model, RH-1b amendment). A device can only report *its own*
  cursor — the relay takes `(ownerId, deviceId)` from the authenticated session, never the body — so
  the only forgeable claim is "I am further ahead than I am". And an admitted device already holds
  the realm keys (A4), so it can delete the same data outright; lying about a cursor is a weaker
  path to an outcome already available to it. §3.2's revoked-device objection does not hold:
  revocation **deletes the `Device` row**, so the barrier stops reading that device's cursor
  entirely. ⚠ The argument is scoped to co-owner membership — a read-only member would make the
  cursor a privilege boundary and would owe the signature.
- **Q2. What bounds "no un-pushed write older than this"?** A per-device journal high-water mark is
  the candidate; nothing durable records one today.
- **Q3. Does the snapshot need to be per-slice here?** §5.7 slices for joiner cost; §6 needs only
  current state.
- **Q4. The user-facing shape of a device past the horizon** — §4.24 chose a label over a self-heal.
- **Q5. Does the shared-realm case change the bound?** Membership there is cross-user (A26/A27).

---

## 10. Decisions this asks for

| | decision |
|---|---|
| **D1** | The outbound half compares the **consumed cursor**, not the HLC tip. The skew margin is then removed, not tuned. |
| **D2** | The relay records each device's `since` cursor and echoes it beside `peerTips`. New relay state is accepted for it — the information is not new (§3.2), the **retention** is. |
| **D3** | The inbound half (`caughtUp`) is unchanged and remains what protects the data. |
| **D4** | Tombstone retention and content-byte retention are separate policies with separate horizons. |
| **D5** | Tombstone retention ≥ the horizon within which a device may still push. |
| **D6** | A device past the horizon **re-bootstraps and replays its journal** — it does not discard local work. |
| **D7** | A journal entry updating an id absent from the snapshot is surfaced to the user. |
| **D8** | No timer authorises a delete. |
| **D9** | `/push-tombstone` returns the relay-assigned sequence and the candidate records it (§3.3) — not a bound derived from the deleting device's own cursor. |

---

## 11. Phasing sketch (not scheduled)

- **RH-0** ✅ **SHIPPED 2026-09-07** — the relay records `since` per device and echoes it in both
  `ChangesResponse` and `SyncLogResponse`; `RealmConvergence` records the peer cursors. Nothing
  consumes them. ⚠ Cost the phase line did not carry: `SyncService` 4 → 5 and a migration, because
  both responses are positional composites.
- **RH-1a** — ✅ **shipped 2026-09-07**, both halves: the relay returns the seq (§3.4's cursor-source
  defect fixed alongside), and α threads it back onto the candidate (§3.6). A candidate recorded from
  now on carries a `relaySeq`; nothing consumes it yet.
- **RH-1b** — ✅ **shipped 2026-09-08.** The barrier's outbound half compares peer cursors against
  the candidate's `relaySeq`. ⭐ A quiet realm converges and the §2.1 deadlock is gone.
  ⚠ `DEFAULT_SKEW_MARGIN_MS` was **not** deleted, contrary to this line: a candidate recorded before
  RH-1a α carries no seq and cannot be given one, so those keep the tip comparison rather than being
  stranded for ever. The constant is now legacy-only and deletable once no seq-less candidate can
  exist. Q1 closed with an argument (§9).
- **RH-2** — durable per-device journal high-water mark (Q2), reported, changing nothing.
- **RH-3** — current-state snapshot (§6), sequenced with E4 so the two share one traversal.
- **RH-4** — the retention bound (§5) applied to tombstone GC only.
- **RH-5** — content-byte reclamation drops unanimity, gated on RH-4 holding in practice.
- **RH-6** — the horizon's user-facing surface (Q4, D7).

⚠ RH-0/RH-1 are worth doing on their own merits: they make the gate mean what it says and unblock
the quiet-realm case, independently of every question about membership behind them.
