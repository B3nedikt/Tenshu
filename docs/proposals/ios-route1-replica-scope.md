# Route-1 replica scope — what a device should have to keep

**Status:** Draft v0.3 (2026-08-02) — design pass, **no code, no decisions taken**. Opened off the
last open item of the tech-debt entry *"iOS `FileObjectStore` hydrated the whole realm at launch"*
(now *"The iOS shared replica keeps the whole realm"*), whose store half was fixed by lazy
indexing and size tiering. ⚠ That store is deleted since 2026-11-05: the replica is SQLite
([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9), and the question here is unchanged. Sequenced against
[`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) §4, which settled the
*key*-scope question and explicitly deferred this one.

**Problem in one line:** every route-1 iOS app keeps a **full copy of the entire realm** — Drive
holds **107,055 rows**, the great majority Fit workouts and Timeline moments it cannot use — because
each app's runtime registers every service module. The store fixes bounded what a launch *reads*;
nothing bounds what a device *keeps*.

> **⚠ v0.2 corrects v0.1's framing.** v0.1 asked "what should *this app* keep" and concluded that
> filtering and a shared replica were mutually exclusive. That conclusion was an artifact of the
> per-app framing. The better question — **"what do the apps installed on this device need?"** —
> makes the two compose, and is what this version is built around (§4, O-A). Nothing was ever settled
> *against* filtering; v0.1 took no decisions and neither does this.

---

## 1. Why this exists

Measured on a real iPhone, 2026-08-02:

| store | rows | what it is |
|---|---|---|
| `app.tenshu.drive.ios` | **107,055** | the whole realm — desktop's replica of the same realm is ~106k |
| `app.tenshu.control.ios` | 22,954 | the same realm, mid-back-fill (Control cold-starts locked, so it applies nothing until unlocked) |

Drive's row census on the desktop twin: ~27.5k `dataId/string`, ~27.5k `resolvedDataId`, ~14k `hlc`,
~14k `sync/metadata`, **~10.4k `health/workout`** + ~10.4k `workoutRollupAttribution`, ~2.8k
`health/dailyActivity` — against **13 `file` rows and 4 `folder` rows**. Drive is a file browser
storing five orders of magnitude more health data than file data.

This was invisible until it wasn't: the eager store walk over that dataset blew the 20 s
`scene-create` watchdog and made Drive unlaunchable. Laziness and tiering fixed the *reading*.
**Disk, sync bandwidth, battery and back-fill time are untouched.**

⚠ It compounds per app: four first-party apps can go route-1 today, and without a shared container
that is 4 × the realm on one phone. The two measured counts differ, so the phone is demonstrably
*not* sharing a container right now (free-signing fallback).

⚠⚠ **But whether any of that actually hurts is unverified** — see §7 I1. The acute symptom is gone,
so there is no longer any reason to guess, and this document deliberately does not.

---

## 2. Ground facts (verified 2026-08-02 — cite, don't re-derive)

1. **Every route-1 app registers every service.** `FirstPartyLocalRuntime` assembles
   `listOfNotNull(buildProxyModule(), buildPlatformProxyModule(), base, appOverrides)` — the
   *production* Control module set, registering file, health, timeline, place, contact, ingest,
   notifications, map-region, logging, home-device, routine, hub-management, preference, sync,
   storage and identity. The reuse was P1's whole point; the storage consequence was never the
   question being asked.
2. **⭐ Blocks for a service a runtime does not know are already dropped.** `SchemaPullFence`'s KDoc,
   explaining why it does *not* fence such a service: *"nothing can materialize a type this runtime
   has no schema for, and blocks for unknown types are already dropped as unparseable by the ordinary
   ingest path."* The drop mechanism exists.
3. **⭐ The installed-app roster already exists and is synced.** `AppRegistration`
   (`tenshu/app/registration`) is a keyed `(appId, deviceId)` row carrying `state`
   (`Active`/`Removed`) and `verified`, with an `APP_REGISTRATIONS` relation. Removal sets
   `state = Removed` rather than deleting, so history stays intact.
4. **⭐ The app→services mapping already travels.** `AnnounceSession` carries each client's
   `VersionVector`, derived by walking its registered module graph — i.e. *precisely* the set of
   services that app knows.
5. **`SchemaRegistry.owningServiceOf(typeId)`** (`SchemaRegistry.kt:250`) maps a type to its owning
   service — the classifier an explicit filter would use.
6. **The replica directory is shared when an App Group exists.** `sharedReplicaDirectory()` returns
   the App-Group container, else app-local. Paid account ⇒ one replica for all apps.
7. **Key scope is settled and orthogonal.** §4 decision **C** — first-party apps hold the whole
   `RealmKeyMaterial` blob. This document changes what a device *keeps*, never what it can decrypt.
8. **Type ids are legible only post-decrypt** (E2c's upcaster walks them after
   `decryptAndParseCipher`).

Facts 3 and 4 are load-bearing: **the inputs to "what does this device need?" already exist**, synced,
with no registry to invent.

---

## 3. Three different things "scope" could mean

| axis | question | status |
|---|---|---|
| **Key scope** | what can this app *decrypt*? | **settled** — §4 decision C, whole blob |
| **Replica scope** | what does this device *keep on disk*? | ⬅ **this document** |
| **Read scope** | what does a process hold *in memory*? | **done** — lazy indexing + size tiering |

---

## 4. Options

**O-A — Keep-set = the union over *installed apps* (device-scoped).** ⭐ The shape this version is
built around. The device keeps rows for the services the apps registered on *this device* need
(facts 3 + 4) and drops the rest at ingest (fact 2). A Drive-only phone never stores a workout; a
Drive + Fit phone stores both; Control is covered because it is itself a registered app that needs
everything.

Why it beats v0.1's per-app framing:
- It **composes with a shared replica** instead of fighting it: one store whose contents are the
  union is consistent for every reader (this is what dissolves v0.1's "mutually exclusive").
- The keep-set changes only on a **discrete, observable event** — an app installed or removed —
  rather than on any future feature wish, which makes the cursor problem (C1) tractable.
- Cross-app lenses keep working whenever both apps are present, which is exactly when they are
  usable.

**O-B — Per-app keep-set.** Each app keeps only what *it* needs even when a sibling is installed.
Smaller on disk with per-app stores, but it reintroduces the shared-replica conflict and breaks
cross-app lenses (Timeline showing a Drive photo). Recorded as rejected-by-framing.

**O-C — One shared replica (App Group), no filtering.** Stop having N copies at all. Costs the $99
account (already a P7 prerequisite) and makes every app a co-writer of one store — which
`SharedReplicaDatabaseLock` and `rehydrateFromDisk` were built for. Solves *duplication*, not
absolute size: a Drive-only phone still stores every workout.

⚠ **O-A and O-C are complements, not alternatives** — O-C removes the ×N factor, O-A removes the
unused services. Doing both is coherent.

**O-D — Status quo.** Kept in the list because I1 may show the cost does not justify any of the
above.

---

## 5. Constraints that survive the reframe

**C1 — ⚠⚠ Cursor destructiveness (the real cost of O-A).** The pull cursor is a **single durable
high-water mark that records what was *delivered*, not what was *applied***:

```kotlin
// SyncScheduler.advancedCursor
val delivered = max(blocks.cursor, tombstones.cursor)
return max(since, minOf(delivered, ceiling))
```

A block fetched and then dropped as unparseable still counts as delivered, and the cursor keeps no
memory of what it skipped. So a device that later *widens* its keep-set — the user installs Fit —
finds those rows in neither place: not in its store (dropped) and never served again
(`since=<cursor>` is past them).

⚠⚠ **This is a known failure class, not a hypothetical.** The cursor/object-store desync (a non-zero cursor over an emptied
store skipping the backlog forever) and the `/sync/changes` page truncation are the same shape; `advancedCursor`'s
own KDoc calls it *"the silent-backlog class"* and exists to prevent it. O-A would deliberately induce
it once per declined service.

⭐ **The fence shows the non-destructive alternative.** E2d-2's pull fence faces the same "not this
data, not now" problem and is safe precisely because it **does not fetch and does not advance** — the
backlog stays on the relay and clearing the fence resumes exactly where it stopped. Filtering as
naively designed does the opposite. B3 closes the gap.

Mitigating: the trigger is a discrete event; relay history is durable for live rows
(`MailboxStore.gcSweep` is tombstone-driven only), so the data is still there to re-fetch.

**C2 — Filtering cannot save pull bandwidth.** Fact 8: a device must fetch and decrypt a block before
it can know the type. O-A saves *disk*, *apply cost* and *battery-at-rest*, not transfer. Real
transfer scoping would need the relay to serve a subset, which it cannot: it sees ciphertext only
(HR #1), and per-service routing would leak which apps a user runs — the same argument that made the
epoch vector E2E rather than relay-visible. **§6 is where that ceiling could actually move.**

**C3 — Control must stay whole.** Control is the device's proxy, roster owner and recovery surface.
Under O-A this should fall out for free (Control is a registered app needing every service) rather
than needing a special case — but it must be *verified* to fall out, not assumed (I3).

**C4 — `MeteredExempt` is the shape, not the seam.** The trait system is the idiomatic way to mark a
type's sync behaviour, and `MeteredExempt` is the precedent (fieldless marker, schema-declared,
checked per block). ⚠ But it is tested at **emit**, where the plaintext schema is in hand; the
receiver has no equivalent hook before decrypt. A trait can express policy; enforcement stays local.

---

## 6. Future track — protocol-level metadata scoping (⚠ likely over-optimising; do not build yet)

C2 caps what any local filter achieves: the bytes still cross the wire. Moving that ceiling means
changing what a *block* is, so metadata can be resolved on demand instead of streamed exhaustively.
The sketch, recorded so the idea is not lost:

- **One manifest block per folder** instead of N per-row blocks — a folder's listing becomes a single
  addressable unit, so a client fetches the tree lazily (open a folder → fetch its manifest) rather
  than replicating every row up front. The content plane already works this way (a manifest addresses
  chunks); this extends the pattern to the metadata plane.
- It would make the *unit of sync* match the *unit of use*, which is what makes on-demand fetching
  coherent rather than a per-row RPC storm.

⚠⚠ **Large and invasive**: block granularity, merge semantics (a folder manifest is a write-contention
hotspot where per-row blocks are not), tombstones and offline behaviour all move with it. ⚠ And it is
**quite possibly optimisation we never need** — a phone holding ~100k metadata rows may cost nothing
measurable, which is exactly what I1 exists to find out. Listed as a future track, gated behind the
measurements, deliberately not sized.

---

## 7. Phasing — investigate, then decide, then build

⚠ The ordering is the point. The acute symptom is fixed, so nothing here is urgent enough to skip to
a fix: **no implementation phase starts before its decision gate, and no decision is taken before its
investigation.**

### Investigation (I) — what is actually costing us?

- **I1 — Is replica size a real problem, and where?** On device, for a ~107k-row store, measure disk
  bytes vs **file count** (one flat directory — enumeration, iCloud backup size, uninstall/reinstall
  time and inode pressure may bite long before bytes do); cold-open time now that hydration is lazy;
  and the back-fill's wall-clock and battery cost. **⚠ Output: a go/no-go.** If the honest answer is
  "100k small files cost us nothing measurable", the correct outcome of this whole document is O-D
  and a note saying so.
- **I2 — What is the composition, per installed-app set?** Which services would each set exclude, and
  how many rows does that actually remove?
- **I3 — Do facts 3 + 4 hold on route-1 specifically?** Confirm `AppRegistration` rows exist per
  device on iOS (each app is its own proxy — does a satellite app register, and under which
  deviceId?), and that a keep-set derived from announces names Control's needs correctly (C3).

### Decisions (D) — each gated on its investigation

- **D1 — Do we act at all?** Gate: I1. Options O-A / O-C / both / O-D.
- **D2 — What derives the keep-set, and where is it enforced?** Gate: I2 + I3. Candidates: the module
  set per app (drop at ingest, fact 2) vs an explicit `owningServiceOf` gate at apply (fact 5 —
  reversible per launch without a re-sync, which interacts with C1).
- **D3 — What is the widening story?** Gate: D2. The C1 answer: what happens the day a user installs
  Fit on a Drive-only phone.

### Build (B) — only after D

- **B1** — the keep-set derivation + enforcement point chosen in D2.
- **B2** — O-C's shared replica, if D1 selects it (independent of B1; it is the ×N factor).
- **B3** — ⚠ the C1 widening path. **Ships with B1, not after it** — a device that silently cannot
  see its newly installed app's data is a worse bug than the one this document is about.

  **⭐ Leading candidate: a cursor per service, not one global mark.** Today the cursor is a single
  `Long` in a reserved store row (`SYNC_CURSOR_HASH`, via `ObjectStore.loadSyncCursor`/
  `storeSyncCursor`). Replacing it with a `serviceId → Long` map makes the *missing* explicit: on
  install, Fit's mark starts at 0 while every other service stays current, so widening is a targeted
  catch-up instead of a whole-device reset — and the store is never wiped, so nothing the user has is
  lost or re-downloaded on the content plane.

  ⚠⚠ **It does not save the re-fetch, and the design must not pretend otherwise.** The relay cannot
  serve per-service ranges — it sees ciphertext and has no idea which service a block belongs to
  (C2) — so the *request* must still go out at `min(cursors)`, i.e. a one-time full-history re-stream
  on widening. What the map buys is correctness and safety, not bandwidth: the device knows exactly
  what it lacks, re-application is idempotent (`processSyncBlock` / `processDeleteBlock` both are —
  the relay already re-serves tombstones for that reason), and the catch-up is nameable in the UI
  ("catching up Fit") rather than an unexplained full re-sync.

  ⚠ That widening pull is a `since=0` pull, which is exactly the path that OOM'd the relay before
  `/sync/changes` was page-capped — so it rides an already-hardened road (page cap + truncation-aware ceiling), but it must be
  tested against it rather than assumed.

  **Variant considered and folded in:** a *skip log* of the seq ranges where blocks were dropped,
  re-fetched by range. Strictly narrower in principle, but in the common case — "this device never
  had that service" — the range is `[0, now]`, i.e. identical to a per-service cursor of 0, at the
  cost of a new ranged relay read. Not worth it unless partial-service history becomes a real case.

  **Fallback if the map proves too invasive:** the blunt version — on keep-set change, reset the
  single cursor to 0 and re-pull. Correct, already the de-facto behaviour after a store wipe, and
  cheap to build; it just cannot distinguish "catching up one app" from "something went wrong".

---

## 8. Open questions

- **Q1 — Does an app's announce vector equal "services whose rows it needs"?** It is the set it can
  *parse*, a superset of what it *uses* (Drive parses `preference` rows it barely reads). Probably
  good enough — but it bounds O-A's savings by module wiring, so a service every app happens to link
  stays replicated everywhere.
- **Q2 — ⚠⚠ Can the infrastructure row families be attributed to a service?** `hlc`, `sync/metadata`,
  `resolvedDataId` and `dataId/string` are ~76% of the rows and are *infrastructure*, not
  service-owned domain data. If they cannot follow their owning service, filtering domain rows alone
  removes far less than the census suggests. **This may be the most important question in the
  document** — I2 should answer it first, because a bad answer here weakens O-A badly.
- **Q3 — Does this generalise off iOS?** Android and desktop have one proxy and one store, so the ×N
  problem is route-1-specific; the *absolute* size question is not. A device-scoped keep-set would
  work identically on Android — worth knowing before building an iOS-shaped answer.
- **Q4 — What does a per-service cursor map cost?** (B3.) It is local state only — no wire or relay
  change, since the request still goes out at `min(cursors)` — but it replaces a load-bearing
  primitive: `loadSyncCursor`/`storeSyncCursor` are default methods on `ObjectStore`, so every store
  twin inherits them, and the A1 co-location property (cursor shares the store's lifecycle, which is
  what makes desync unrepresentable) must survive the change. ⚠ Also: does `min(cursors)` interact
  badly with the shared-realm cursors, which are already tracked per realm
  (`runtime.cursor`, separate from the personal `lastSyncTimestamp`)? Two independent axes —
  per-realm × per-service — may want one mechanism rather than two.

---

## 9. Threat model

No delta expected. Nothing here changes what the relay sees (ciphertext only, HR #1), what a device
can decrypt (§4 C, unchanged), or the E2E guarantees. ⚠ If O-A ships it *incidentally reduces* data
at rest per device — a defense-in-depth improvement worth an A5 note when it lands, not an amendment
now. ⚠ §6, if ever pursued, **would** need a threat-model pass: changing block granularity changes
what the relay can infer from access patterns (fetching one folder's manifest is a narrower signal
than a full replica sweep).

---

## Version

- **Draft v0.3** — 2026-08-02. C1 rewritten around what the cursor actually records (`delivered`, not
  applied — `advancedCursor`), naming the cursor-desync / page-truncation precedent and the fence as the non-destructive
  contrast. B3 now leads with a **per-service cursor map**, with the honest note that it buys
  correctness and a nameable catch-up but **not** bandwidth (the relay cannot serve per-service
  ranges), plus the folded-in skip-log variant and the blunt-reset fallback. New Q4 on what replacing
  the single-cursor primitive costs.
- **Draft v0.2** — 2026-08-02. Reframed from per-app to per-device keep-sets (O-A), dissolving v0.1's
  shared-replica conflict; recorded that the installed-app roster and the app→services mapping
  already exist (facts 3 + 4); added the §6 protocol-level future track; restructured §7 into
  investigate → decide → build. No decisions taken; D1 is gated on I1.
- **Draft v0.1** — 2026-08-02. Initial pass, per-app framing.
