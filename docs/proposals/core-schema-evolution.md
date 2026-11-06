# Schema evolution — versioned data, announce-gated sessions, epoch-fenced realms

- **Status:** Draft v1.0 — design settled 2026-07-24, refined through 2026-07-25.
  **E0 IMPLEMENTED 2026-07-25 (as-built §5.2). E1 COMPLETE 2026-07-25
  (as-built §6.9 + §6.10): per-service versions, full-vector stamp,
  `AnnounceSession` op, client announce-as-session-preflight (connect +
  reconnect), reject-if-silent gate, smoke-gate assertion. **E2a+E2b
  IMPLEMENTED 2026-07-25 (as-built §8.5 + §8.6): the `SchemaMigration`
  artifact/builder/registry/codec + the JVM store-open seam (the wipe becomes
  the chain; iOS/JS twins keep the wipe). E2c IMPLEMENTED 2026-07-26
  (as-built §8.7): lazy history upcast in the decode funnel —
  `PayloadUpcaster`, stamp-keyed overlay decode + uniform by-type-id walk,
  drop-by-decision. E2d IMPLEMENTED 2026-07-26 (as-built §7.6 + §7.7): the
  frozen `RealmSchemaEpoch` row + MAX-merge + write-triggered bump, and the pull
  fence at one conjunct in `SyncScheduler.drainAndPull` + typed
  `SchemaFenceStatus`. **E2e IMPLEMENTED 2026-07-26 (as-built §7.8): the
  relay-gate split — 426 re-keyed onto a new `RELAY_PROTOCOL_VERSION` accepting a
  `[min, current]` range, payload versions gone from the relay ⇒ a
  `TENSHU_STORAGE_FORMAT_VERSION` bump no longer owes a relay redeploy. E2 is
  COMPLETE.** ⚠⚠ **E2j OPENED 2026-07-30 (§8.9): the store-migration path has
  never done real work — the `user_version` gate wipes before the migrator runs
  and HR #9 mandates that bump on every reshape, so §11's two-part heuristic
  cancels itself. **E2j-1 + E2j-2 + E2j-3 IMPLEMENTED 2026-07-30** (as built §8.9 —
  308 composite shapes pinned so a reshape without a bump fails a test; the JVM
  store gate migrates when the recorded core version is still reachable instead
  of wiping unconditionally, HR #9 rewritten to match; and ⚠⚠ E2j-3 turned out
  to be a SAFETY fix — E2j-2's policy move had opened a silent-garbling hole on
  the iOS/JS stores, which gated on the format version alone — closed by one
  shared `StoreVersionGate`. ⚠ both call sites wired since, and the twins now MIGRATE
  rather than only wipe — one shared `InMemoryStoreMigration` (load-migrate-persist)
  running the same untouched `StoreSchemaMigrator`, fail-closed to a wipe. JS
  COMPILES — which caught a JVM-only `toSortedMap()` E2j-1 had shipped into
  `commonMain`, breaking the web build — but `jsTest` (karma fetch 403) and all of
  iOS (no Xcode) stay unverified; §8.9 carries the on-device checklist). Also L1 of the `Device`-regeneration tech-debt entry.** ⚠ E3 and E4 are **built**, not "not started" as this line
  said until 2026-08-11 — E3-1 is the typed `UnsupportedOperationError` (§9.3.1) and E4-1…E4-5 are
  §8.3.1–§8.3.7 (`DeviceSchemaVersion`, `CompactionReceipt`, `SchemaCompactor`, `FloorRetirement`,
  `CompactSchema`). ⚠⚠ **What is genuinely outstanding is narrower and easy to miss: compaction has
  no production DI line**, so no `ScheduleItem` is registered and the pass never runs (§8.3.7);
  the JS persist path is unverified on its own platform; and §8.9.1 does not repair the
  743/823 `File` rows C7 already stranded.** ⚠⚠ *Since 2026-11-05 the iOS twin is not
  `FileObjectStore` (deleted) but the SQLite `SharedReplicaStore`, and ⭐ **since B.24 of
  [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) (2026-11-05) its binding registers the
  schema registry's chains** (`RegistryReplicaMigrationSteps`: each step's `typeIds` from its
  `CompositeMigration`s and `droppedProperties`, its row transform `StoreRowMigrationCodec.migrateRow`
  plus the strip) and floors each service where its chain reaches. An iOS store behind a build is
  **migrated** by the chunked migrator (§5.3 there), verified on the simulator with a real bump of a
  test service and of `:core`; it is wiped only below the floor. The iOS persist path is the SQLite
  replica's, not `InMemoryStoreMigration` (which only JS uses now); the E2j-3 iOS path below is the
  deleted file store's. ⚠ On the JVM a `:core` step lowers the floor but its `CompositeMigration`s
  never run (`StoreSchemaMigrator` walks `serviceSchemas()` only; `tech-debt.md`): iOS runs them.*
  Changelog: v0.2 interface sketches (§6.8, §8.4) · v0.3 per-type
  `CompositeMigration`s (from+to snapshots, chain check, nested ripple) · v0.4
  field-mapping model (auto-map by id, forced `drop`/`derive`/`convert`/`rename`
  accounting, **no default values**) · v0.5 rename `SchemaUpcast` → `SchemaMigration`
  · v0.6 open questions resolved (compaction: no election/quorum, receipt-row dedup,
  roster-gated floor, §8.3; per-service JS SDK, §9.5) · v0.7 service dependencies
  (§4.1: **block stamp = full `VersionVector`**, epoch bump follows contained types)
  · v0.8 `requires` **derived from `include(...)` edges** · v0.9 version lives **on
  the `ServiceSchema`** (`service(id, version = …)`, never-serialized metadata), floor
  **derived from the migration chain** (cut-off = deleting steps), §4.2 future track:
  the module system's descriptive half graduates to composites · v1.0 §7.5 **relay gate
  split**: 426 survives for the relay's *own* contract on a new `RELAY_PROTOCOL_VERSION`
  (range-accepted, not exact-match), payload versions leave the relay entirely — ending
  per-schema-change redeploys.
- **Date:** 2026-07-24 (v1.0: 2026-07-25)
- **Owner:** Benedikt
- **Related:** [`schema-dsl.md`](../schema-dsl.md) · `core-common/.../StorageFormat.kt` (the
  mechanism this proposal grows up from) · [`sync/design.md`](../sync/design.md) ·
  [`sync/threat-model.md`](../sync/threat-model.md) ·
  [`core-client-resubscription.md`](core-client-resubscription.md) (the reconnect seam the
  announce op rides) · [`file-content-addressing.md`](file-content-addressing.md) (the
  `DataIdMigrator` intent-journal pattern; the content plane that makes migrations
  metadata-only) · House Rules #1, #2, #8, #9

---

## 1. Problem

Tenshu currently has **no schema evolution story**. The entire compatibility posture is:

- One global `TENSHU_STORAGE_FORMAT_VERSION` (`core-common/.../StorageFormat.kt`; 11
  when this was drafted on 2026-07-24, 12 a day later — the churn rate is itself part of
  the argument).
- **Local stores wipe on mismatch.** Every persistent store records the version it was
  written under and, at open, deletes everything and rehydrates from the relay if it
  differs (`SqliteObjectStore.kt` `PRAGMA user_version` check; iOS sidecar file; JS
  IndexedDB equivalent).
- **The relay hard-rejects on mismatch.** `POST /sync/realm` carries `formatVersion` in the
  body; the relay 426s anything that isn't an *exact* match (`RealmRouting.kt`
  `formatVersionOk()`). Deployed relays move in lockstep with a bump.
- **Apps on one device must move in lockstep.** The Android smoke gate asserts every
  installed app reports the same version (House Rule #9), because a skewed pair fails as a
  cryptic `IPC Client -> Failure … error/internal` mid-parse, not as a clean error.

This is the right posture *pre-release*: a spurious wipe+resync is cheap, and the
alternative is migration machinery nobody needs yet. It cannot survive contact with real
users: a wipe stops being cheap the moment re-hydration means re-downloading a 40 GB Drive;
"update every device and the relay simultaneously" stops being possible the moment app
stores control rollout timing; and third-party apps (EPC) can never be forced into
lockstep at all.

This proposal is the replacement. It splits into two problems that are usually conflated:

- **Data migrations** — add/remove/reshape composites, fields, relations. A
  **cross-device** problem: rows cross the relay as E2E ciphertext, so the relay can never
  migrate anything (House Rule #1); migration is always client-side, and the hard case is
  two of the user's own devices at different app versions sharing a realm.
- **Operation migrations** — polyfill old operations into new versions; cut off versions we
  no longer want to support (insecure, unmaintained). A **same-device, cross-app** problem:
  operations never persist and never cross the relay — they are live IPC between a client
  (Drive v12) and a proxy (Control v11) on one device, plus the published `@tenshu/sdk`
  npm surface and future third-party EPC apps. That is compatibility *negotiation*, not
  migration.

Treating these as one framework would over-build both. They share exactly one artifact —
the per-service version vector (§4) — and are otherwise separate mechanisms.

---

## 2. Ground facts (verified 2026-07-24)

The design below is shaped by what the wire actually looks like today. These facts are
load-bearing; each is a file reference, not a recollection.

1. **Composite serialization is positional with no version marker, no field tags, and no
   field count.** `LinearSerializer.kt` writes `schema.values` in order;
   `BinaryParser.parseComposite` reads positionally; `CompositeDto` reassembles constructor
   values to field ids **by index**. The only header is an optional 16-byte type-id tag —
   and it is omitted entirely when the reader already expects the concrete type. This is
   *why* even additive field changes are peer- and store-breaking (`StorageFormat.kt` KDoc,
   v7–v9 entries): a peer with a different field list reads every subsequent field at the
   wrong offset and gets garbage, not an error.
2. **`SyncBlock` carries no format version** — fields are exactly `blockId`, `ownerId`,
   `hlc`, `encryptedPayload`, `targetEntityIdHash`. Neither does `DeltaBatchPayload`. The
   version travels only per HTTP request, in the `/sync/realm` body. **Given a blob of
   block bytes, no code can determine which schema version produced it.** Every migration
   strategy is dead on arrival until this is fixed (§5).
3. **The IPC envelope exchanges no version, at connect or per call.** AIDL is
   `byte[] call(byte[] request)`; the request is serialized callstack bytes; operations
   resolve purely by string id through the schema provider (`EvaluateCallstack.kt`,
   `roFactory` registration by composite id). EPC has a *transport-crypto* version byte
   (hard fail, no negotiation) — distinct from any schema version.
4. **Callstack params contain composites serialized by the client under the client's
   schema.** So on the IPC surface, ops and data shapes travel together: a client one field
   ahead of its proxy produces garbled positional bytes mid-parse, not an "unknown
   operation" error. This is the failure class House Rule #9's smoke gate exists to catch.
5. **Schema fields have stable string ids but the id is never serialized** — only ordinal
   position. And `schema.values` merges parent fields (`parent.values + this.fields`), so
   a field change on a `:core` parent composite shifts the positional layout of **every
   descendant across all services**.
6. **Migration machinery already exists in embryo.** `DataIdMigrator`
   (`:sync-service-common`) is a crash-safe, intent-journaled, idempotently-resumable
   local migration executor (`RekeyMigrationMarker` journal + `resumeIncompleteMigrations()`
   at init). It migrates data *identity*; the same shape generalizes to migrating data
   *format*.
7. **Content never migrates.** File bytes live on the content plane as CEK-sealed chunks
   addressed by `contentId` ([`file-content-addressing.md`](file-content-addressing.md)).
   Schema migrations touch **metadata only** — which is what makes eager local migration
   affordable (§8).

---

## 3. Design overview

> **One per-service version vector, enforced at two boundaries.**
>
> - At the **IPC boundary** (same device): a client *announces* its expected vector at
>   session start; the proxy admits, adapts, or rejects with a typed error. (§6)
> - At the **realm boundary** (cross device): the realm carries a schema **epoch vector**;
>   a device that cannot meet it is *fenced* from pulling until updated, but may still
>   push. (§7)
>
> The compatibility contract at both boundaries is asymmetric:
> **new code reads old formats down to a floor; old code never has to read new — it gets
> fenced (or the session refused) instead.**

Supporting mechanisms:

- Every emitted block is **stamped** with the producing format version inside the
  ciphertext (§5) — the enabler for everything else.
- Local stores migrate **eagerly at open** (replacing the wipe); relay history is upcast
  **lazily at read**; history is **never rewritten** (§8).
- **Compaction** re-emits current state at the current version, which is what lets the
  readable floor advance — and advancing the floor *is* the cut-off mechanism for data
  (§8.3).
- Operation cut-off is a **dispatcher policy** in the proxy (deregister the old id → typed
  error); polyfills are per-session adapters enabled by the announced vector (§9).

---

## 4. Per-service schema versions

Today there is one global integer. This proposal introduces a **version per service**
(file-service, identity-service, fit, timeline, …), plus a **core version** that acts as
a global floor. The version is declared **on the `ServiceSchema` itself**, in the schema
DSL (v0.9 — supersedes the earlier separate-registration shape):

```kotlin
val Schema: ServiceSchema = service(
    id = ID,
    version = 3,          // ← versions THIS schema; bumped in the same file as the change
    relations = listOf(…),
)
```

Rationale: the version *describes the schemas*, so it lives where they are declared —
the schema Δ and the bump land in the same file, turning the "same PR" discipline (§11)
into "same screenful". Mechanically this is code-side schema metadata that is **never
serialized** (the `@defer` flag precedent: positional composites make added fields
peer-breaking, so schema metadata stays off the wire) — it reaches the wire only through
the frozen `VersionVector` envelope. `:core`, which registers no single `ServiceSchema`,
keeps its version as the `StorageFormat` successor constant.

The **floor is not declared anywhere**: it is *derived* as the lowest contiguous `from`
in the service's registered migration chain (§8.4). Cutting off old formats therefore
means **deleting migration steps** — the floor can never drift from what the code can
actually read, because it *is* what the code can actually read.

Why per-service (and not finer or coarser):

- **Per-interface is too fine.** Versions exist to gate *compatibility windows*, and the
  natural release unit is the service module — a Fit release bumps Fit once, regardless of
  how many composites moved.
- **Per-realm is too coarse.** It would drag a Drive-only user through Fit migrations they
  can never need.
- **Per-service is nearly self-scoping for free**, because of the epoch trigger (§7.2): the
  realm's Fit epoch only ever bumps when a device first *writes* new-format Fit data. A
  user who never runs Fit never sees a Fit fence. Nobody migrates data they don't have.
- Service cadences genuinely differ: `:core` is stable by design; Fit reshapes whenever
  Google/Apple move; file-service sits between.

**Caveat, stated loudly:** because positional layouts include inherited parent fields
(fact 5), a `:core` composite change is inherently a **global fence** no matter what
granularity we pick — it shifts every descendant in every service. "Per-service" really
means *"per-service, floored by a global core version."* That is acceptable precisely
because core is the stable layer; the proposal makes it explicit so nobody is surprised
when a core bump fences everything once a year.

The wire/type shape: `ServiceVersionVector = List<(serviceId: String, version: Int)>` plus
`coreVersion: Int`. This one type is shared by the announce op (§6) and the epoch record
(§7) — its own schema is **frozen forever** (§6.2).

### 4.1 Service dependencies (added v0.7)

Per-service versions are **not independent**: the module DAG is real (Fit rides
file-service's content plane; everything rides `:core`), and — the sharpest case —
**schemas embed foreign-service types**. The precedent that forces this: preference-
service's `genericData` field stores an arbitrary registered `Data` value, so a
`RealmSetting` row can positionally embed, say, a sync-service `SyncPolicy` composite. A
"preference row" is therefore not decodable with preference-service knowledge alone. The
core floor (§4's caveat) is just the vertical special case of this; the horizontal case
needs handling too. Three consequences, all mechanical:

1. **Dependencies are derived from the module DSL, versions from the schemas — nothing
   new is declared** (refined v0.8/v0.9). The `tModule { }` block already carries both
   halves: the dependency DAG as `include(...)` edges (`fileServiceCommonModule`
   already opens with `include(notificationServiceCommonModule)` today), and the
   version via the registered `ServiceSchema` (§4 — `service(id, version = 3, …)`):

   ```kotlin
   val fitServiceCommonModule = tModule {
       include(fileServiceCommonModule)   // ← the dependency DAG, already declared today
       service(FitService::class, FitService.Schema)   // ← the version rides the Schema (§4)
       // composite(...) / operation(...) / schemaMigration(...) as usual
   }
   ```

   The `requires` relation is then **derived, not declared**: `include(B)` is a
   compile-time reference to B's module `val`, so the B version you were authored
   against *is* the version B's `ServiceSchema` declares in the same build — a
   hand-maintained `requires = mapOf(...)` could only ever drift from it. An explicit
   `requires` override exists only where modules genuinely version independently at
   **runtime**, which today means exactly one place: the per-service npm packages
   (§9.5), where the peer-dependency range is that declaration and the runtime
   registration check compares the actually-loaded versions. Everything still fails
   fast at init: a module graph whose derived closure doesn't hold is unconstructible —
   which is why announce admission stays entry-wise (§6.3), and why the announce
   vector itself is simply **assembled by walking the included module graph**, on
   every platform alike.

2. **The block stamp is the full vector, not one version** (revises §5). Because a
   payload can embed foreign-service types, decoding needs a *coherent multi-service
   snapshot* — the emitting device's whole vector, which is exactly what the frozen
   `VersionVector` composite already expresses; reuse it as the stamp inside the
   ciphertext envelope. Cost: a few dozen bytes per block, and a block batches many
   deltas; if it ever matters, a realm-level vector dictionary (stamp = index) is the
   compression escape hatch — not needed day one. The migration overlay (§8.4) is then
   keyed by the stamped vector: each embedded type decodes under *its own* service's
   snapshot at that vector. Cross-service decode consistency by construction, with
   migrations still authored strictly per service.

3. **The epoch bump follows contained types, not the owning entity** (refines §7.2).
   Writing an entity bumps the realm epoch entry for every service whose types appear
   in the written value — the entity's own service *and* any embedded composite's; the
   schema walk makes this mechanical. Self-scoping survives: a realm with no Fit data
   still never sees a Fit epoch, while a preference row embedding a sync-service value
   correctly bumps the *sync* entry — so a lagging reader is fenced for the service
   whose bytes it would actually garble, not for the entity's nominal owner.

### 4.2 Future track: the module system graduates (in part) to composites (noted v0.9)

Should `tModule` itself eventually be expressed in the platform's own machinery —
modules described as Tenshu composites? **Yes, for its descriptive half; never for its
behavioral half.** The clean boundary:

- **The nouns can graduate.** A module's *description* — id, version, include edges,
  the schemas and operations it contributes — is data, and the platform already trends
  this way: `AppService`'s `AppRegistration`/`AppGrant` rows are composites describing
  code structure per `(appId, serviceId, operationId)`; the Realm Data track is
  schema-side metamodeling; and the frozen `VersionVector` **is the first shipped
  module-as-data artifact** — the announce payload is literally a (minimal) module
  description. A fuller `ModuleDescriptor` composite would let Control walk an app's
  declared surface with the ordinary chain DSL, and it is likely *forced* eventually:
  a third-party EPC app must describe its module to Control **over a wire**, and on a
  wire that description can only be data. §9.2's per-session routing wants the same
  artifact.
- **The verbs cannot.** `dtoFactory`/`roFactory`/trait factories are function
  references — the module system *constructs* the machinery composites run on, so its
  behavioral registrations are irreducibly code. No bootstrap paradox: the descriptor
  describes; the factories remain Kotlin.
- **Freeze discipline caps the wire surface.** Only the announce envelope is frozen
  forever (§6.2). A richer `ModuleDescriptor` must *not* join the frozen set — it is
  exchanged only post-admission, so it versions like any ordinary schema.

Sequencing: nothing here blocks E1 — the `VersionVector` ships as designed, and the
`ModuleDescriptor` graduates when its first real consumer exists (third-party EPC
registration being the natural forcing function).

---

## 5. E0 — stamp every block with its producing version

**The single most urgent change, and the only irreversible debt accruing today.**

Add one varint to the encrypted payload of every emitted block: the
`TENSHU_STORAGE_FORMAT_VERSION` (later: the emitting service's version — see below) it was
serialized under. A block is produced atomically under one schema version, so per-block is
exactly the right granularity — per-composite stamps would be redundant within a block.

- Local stores do **not** need per-row stamps: they are already wholesale-versioned
  (`PRAGMA user_version`) and are migrated wholesale at open (§8.1). It is the **relay
  backlog** that mixes versions — a new device replays months of immutable history, and
  each block must self-describe.
- The stamp goes **inside the ciphertext** (with the per-delta hashes, TS-1 precedent) —
  the relay has no business reading it (House Rule #1). The plaintext-visible global
  `formatVersion` on `/sync/realm` was the coarse 426 backstop through E0/E1, and was
  retired from the relay at E2e in favour of a separate `RELAY_PROTOCOL_VERSION`
  (§7.5, as built §7.8) — this stamp is now the only carrier of "which schema produced
  these bytes".
- Shape: version prefix on the plaintext-payload envelope before the composite bytes, both
  for legacy single-target blocks and `DeltaBatchPayload`. Per-delta stamps inside a batch
  are unnecessary (one producer, one version) — but stamping at the batch level means the
  upcaster (§8.2) runs per-block, which is the natural unit anyway.
- Pre-release, this is nearly free: it rides an ordinary v11→v12 bump + wipe + relay
  redeploy (House Rule #9). **Every block emitted without a stamp is future migration
  debt** — once real user data exists, unstamped history can only be handled by "wipe" or
  "guess".

Once per-service versions exist (§4), the stamp becomes the emitting device's **full
`VersionVector`** — not a single per-service version, because payloads embed
foreign-service types and decode needs a coherent multi-service snapshot (§4.1.2). But
E0 should not wait for §4: a global int now is strictly better than nothing, and
upgrading the stamp's contents later is just another stamped transition.

### 5.1 Rejected alternative: field-tagged encoding

The other road: move composite serialization from positional to field-tagged
(protobuf-style — each field prefixed by its id/tag), which makes additive changes free
*forever* and removes most of the need for upcasters.

Rejected, or at least deferred, because:

- It is a rewrite of `LinearSerializer`/`BinaryParser`/`CompositeDto` — the hottest, most
  correctness-critical path in the platform.
- It costs bytes on **every value of every row forever**; the stamp + upcasters pay only at
  version boundaries.
- It only solves *additive* changes. Removals, renames, semantic reshapes (the Fit case)
  still need upcasters and floors — so the machinery of this proposal is needed anyway.
- The stable string field ids are long (`"tenshu/file/path"`); a tagged encoding would
  want stable *numeric* tags, which is a new invariant to author and review (tag reuse
  bugs are protobuf's classic footgun).

If the upcaster burden ever proves heavy in practice, tagged encoding can be revisited —
the block stamp makes even *that* transition expressible ("blocks ≥ vN are tagged").

### 5.2 E0 as built (2026-07-25)

Landed exactly as §5 specifies; deltas are placement details:

- **`BlockPayloadEnvelope`** (`:sync-service-common`, beside `DataIdMigrator`):
  `seal(bytes, version = TENSHU_STORAGE_FORMAT_VERSION)` prefixes an unsigned-LEB128
  varint (one byte at current versions); `open(bytes)` returns `(formatVersion,
  payloadBytes)` or `null` on a malformed/truncated prefix — callers drop exactly as
  on a parse failure.
- **Sealed at all four `SyncObserver` emit sites** — single-target `DataBlockPayload`,
  `DeltaBatchPayload` (`sealBatch`), the `FileContentManifestPayload` emit (both the
  content-plane PUT and the legacy mailbox block share one encrypt), and the
  cross-realm re-key manifest re-emit. The Mode-A share path
  (`ShareRelayClient`) is a *different* envelope (K_share sealed-box, own format) and
  deliberately not stamped — it is a snapshot for an account-less viewer, not realm
  history.
- **Opened in the one decode funnel** — `SyncProcessor.decryptAndParseCipher` (serves
  mailbox blocks *and* content-plane manifest reads) plus the cross-realm manifest
  read in `SyncObserver`. GR-7 trial-decrypt now requires decrypt + envelope + parse
  to all succeed before a candidate key wins. At E0 the version is **read, not
  enforced** — recording it is the point; fencing/upcasters arrive with E1/E2.
- The route-1 iOS dev probe that feeds the real ingest path
  (`RelayFolderProbe.pushFolderMutation`) seals too; the raw string-marker probes
  don't (they never hit the parser). (All of them deleted 2026-11-05.)
- Rides `TENSHU_STORAGE_FORMAT_VERSION` **v12→v13** (StorageFormat KDoc calls it out
  as deliberately the *last* unmigratable bump-and-wipe of its kind) — relay redeploy
  owed per House Rule #9 (one was already owed at v12).
- Tests: `BlockPayloadEnvelopeTest` (varint widths, truncation, overlong prefix) +
  `BlockVersionStampTest` (stamped block applies + carries the current version;
  unstamped pre-E0 bytes are dropped, never applied as garbage); all existing
  emit/ingest suites run through the sealed path.

---

## 6. E1 — the Announce operation (IPC session admission)

At the start of every session, the client announces the service version vector it expects;
the proxy replies with its own vector and floors, and admits or refuses the session.

### 6.1 Why an operation (not a transport handshake)

Shaped as an operation, announce rides the existing callstack machinery and therefore works
over **every** transport — Binder, desktop UDS, EPC, in-process route-1
(`FirstPartyLocalRuntime`), and the JS bridge — with zero per-transport work. A
transport-level handshake would have to be built four times and would still not cover the
in-process path.

### 6.2 The frozen envelope (bootstrap constraint)

The announce is itself a callstack of positional composites. If its own wire shape ever
changed, mismatched peers would garble each other *before reaching the version check* —
the negotiation channel must never itself need negotiating.

Therefore: the announce request/response composites are **minimal and frozen forever** —
the `ServiceVersionVector` pairs, the app id, the proxy's vector + floors in the reply, and
nothing clever. Same discipline as the epoch record (§7.1). Document the freeze in the
schema KDoc and enforce it in review.

⚠⚠ **The freeze has been broken exactly once, on purpose, and the exception is spent.** On
2026-10-28, ahead of the Play open test, the announce request and reply were reshaped **in place**
rather than as the `AnnounceSessionV2` that [`core-version-compatibility.md`](core-version-compatibility.md)
§4.7 designed. It was free for one reason only: the pre-Play production reset wipes every client
store and the relay, so no installed client or proxy could be on the old shape — the same
argument that let every version restart at 1 (Appendix A). What changed is in Appendix A.4.
`ServiceVersion` and `VersionVector` were **not** touched: they also ride every synced block's
version stamp (§4.1.2) and the epoch record (§7.1). **From the first Play build on, there is no
such window again** — a changed contract is a new op id with new composites, as the paragraph
above says.

**Accepted residual:** if the *core serialization format itself* changes (the rare global
core bump, §4), even a frozen schema won't parse. Core-format breaks remain the global
426-style lockstep event; announce covers everything above a compatible core. The escape
hatch — a hand-rolled pre-schema byte protocol like EPC's version byte — is noted, not
built; first-party doesn't need it, and if third-party EPC apps ever do, EPC's existing
envelope is the place for it.

### 6.3 Symmetric reply

The proxy must not answer bare yes/no. The reply carries the proxy's own vector and
per-service floors, so the client can render the *right* message:

- proxy behind client → **"Update Control"** (or "update your proxy app");
- client below proxy's floor → **"This app needs updating"**, with the offending services
  named.

A bare rejection cannot distinguish the two, and the distinction is the entire UX.

### 6.4 Session semantics: re-announce on every reconnect

"Session start" means **every transport (re)connect, not once per client process.** The
proxy can be updated underneath a running client — that is exactly the `onBindingDied`
case from [`core-client-resubscription.md`](core-client-resubscription.md) (a Control
update kills the binding and Android never re-delivers `onServiceConnected` on it).

Synergy, deliberate: the client-resubscription machinery (`core-client-resubscription.md`) already owns an
"on reconnect, replay state" hook. **Announce becomes the first thing replayed**, before
the subscription callstacks — a reconnect that fails announce fails *loudly and typed*
instead of resubscribing garbled streams.

The proxy keeps per-connection session state: the admitted vector, recorded against the
transport's connection identity (Binder binding / UDS socket / EPC channel). New but
small; this is also the hook per-session polyfill routing (§9.2) attaches to later.

### 6.5 Policy for the silent client

Clients predating the announce won't send one. The policy is a config value, decided now
so it never becomes an incident:

- **Pre-release: reject-if-silent immediately.** Every first-party client gains the
  announce in the same release wave; the smoke gate asserts it.
- **Post-release: the transition knob.** A proxy release may run legacy-allowed for a
  deprecation window, then flip to reject-if-silent. Silent sessions are treated as
  "vector unknown, assume proxy's own version" during the window (today's implicit
  behavior, made explicit).

### 6.6 Route-1 in-process

`FirstPartyLocalRuntime` clients are the same binary as their proxy — announce is
trivially self-consistent there. **Run it anyway** rather than skipping: one code path,
and it exercises the frozen envelope in every test run constantly, which is exactly where
you want an eternal-compat invariant living.

### 6.7 What E1 buys immediately

Even before any real migration exists, E1 converts today's worst failure mode — silent
positional garbling across a skewed app pair (fact 4) — into a typed, actionable error at
session start. The House Rule #9 cryptic-failure class dies here. That is why E1 is
sequenced *before* the data-migration machinery.

### 6.8 Suggested interface — `AnnounceSession`

⚠ The sketch below is the E1 shape. The params and reply were reshaped in place on 2026-10-28 (§6.2's spent exception); the as-built shape is in Appendix A.4.

Sketch, following the `WriteSetting` op idiom. Receiver is **`AppService`** (`:core`) —
it already owns the app-registration handshake (`RegisterApp` runs "proxy-only, at
handshake time" per `control-app-registration.md`), which is exactly the lifecycle moment
announce belongs to, and it resolves open question #12.4 (app identity: yes, announce
carries `appId`, composing with registration rather than duplicating it).

The composites live in `:core-common` and are **frozen forever** (§6.2) — the KDoc freeze
notice below is normative, not decorative.

```kotlin
// :core-common — ⚠ FROZEN FOREVER. These composites are the negotiation channel;
// their wire shape must never change (§6.2). Additions go in a NEW composite id.

interface ServiceVersion : Data {
    companion object {
        const val ID = "tenshu/core/schema/serviceVersion"
        val Schema = composite(id = ID, fields = {
            string(id = Fields.SERVICE_ID)      // e.g. "tenshu/file/service"
            int(id = Fields.VERSION)
        })
    }
    object Fields {
        const val SERVICE_ID = "$ID/serviceId"
        const val VERSION = "$ID/version"
    }
}

interface VersionVector : Data {
    companion object {
        const val ID = "tenshu/core/schema/versionVector"
        val Schema = composite(id = ID, fields = {
            int(id = Fields.CORE_VERSION)       // the global core floor (§4)
            list(id = Fields.SERVICES, of = ServiceVersion.Schema)
        })
    }
    object Fields { /* CORE_VERSION, SERVICES */ }
}

/** One refused service, with enough context for the right message (§6.3). */
interface ServiceVersionMismatch : Data {
    companion object {
        const val ID = "tenshu/core/schema/serviceVersionMismatch"
        val Schema = composite(id = ID, fields = {
            string(id = Fields.SERVICE_ID)
            int(id = Fields.ANNOUNCED)          // what the client expects
            int(id = Fields.FLOOR)              // oldest the proxy still accepts
            int(id = Fields.CURRENT)            // what the proxy runs
        })
    }
    // announced > current  → "update Control" (proxy behind client)
    // announced < floor    → "this app needs updating" (client cut off)
}

/** The symmetric reply (§6.3). A refusal is a SUCCESSFUL typed reply carrying
 *  this composite — never a bare error — because the payload IS the UX. */
interface SessionAdmission : Data {
    companion object {
        const val ID = "tenshu/core/schema/sessionAdmission"
        val Schema = composite(id = ID, fields = {
            boolean(id = Fields.ADMITTED)
            composite(id = Fields.PROXY_VECTOR, type = VersionVector.Schema)
            composite(id = Fields.PROXY_FLOORS, type = VersionVector.Schema)
            list(id = Fields.MISMATCHES, of = ServiceVersionMismatch.Schema)
        })
    }
    object Fields { /* ADMITTED, PROXY_VECTOR, PROXY_FLOORS, MISMATCHES */ }
}
```

The operation:

```kotlin
// :core-common. NOT an IoOperation — pure per-connection session bookkeeping,
// no store or network touch; it must stay cheap enough to run on every reconnect.
interface AnnounceSession : Operation {

    override val schema: OperationSchema get() = Schema

    fun announceSession(appId: String, expected: VersionVector): RequestObject

    companion object {
        const val ID = "tenshu/app/service/announceSession"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                string(id = Params.APP_ID)
                composite(id = Params.EXPECTED, type = VersionVector.Schema)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val APP_ID = "$ID/appId"
        const val EXPECTED = "$ID/expected"
    }
}

// Client-side extension — the standard shape. NOTE: app code never calls this;
// the client CONNECTION lifecycle does (transport connect + the resubscription
// `onReconnected` hook, §6.4), before any subscription callstack is replayed.
suspend fun AppService.announceSession(
    appId: String,
    expected: VersionVector,
): TResult<SessionAdmission> =
    findOperation<AnnounceSession>(AnnounceSession.ID)
        .announceSession(appId, expected)
        .asTaskInteractor<SessionAdmission>()
        .execute()
```

Proxy side, in prose (the impl is ordinary): `AnnounceSessionImplementation` compares the
announced vector against the proxy's own vector — **assembled by walking the registered
module graph**: each registered `ServiceSchema` carries its version (§4), each service's
floor is derived from its registered migration chain (§4/§8.4), and the `include(...)`
edges supply the dependency closure (§4.1.1). No separate version registry or
`ModuleConfiguration` hook exists to drift. The implementation records the admitted
vector in per-connection session state (§6.4; the hook §9.2's
per-session polyfill routing later attaches to), and returns the `SessionAdmission`. The
dispatcher gates ordinary op resolution on an admitted session once the reject-if-silent
policy (§6.5) is active. Client-side, the connection layer maps a refusal into the typed
error surface (`core-error-channel.md`) so every pending caller fails with the named
mismatches instead of `error/internal`.

### 6.9 E1 core as built (2026-07-25)

Lands §4 + the §5→§4.1.2 stamp upgrade + §6.8; the connection-lifecycle wiring
is deliberately the next slice.

- **`service(id, relations, version = 1)`** — the version is a plain Kotlin
  `val version` on `ServiceSchema` (never serialized; the `@defer`
  `deferredByDefault` precedent), so a materialized-from-bytes `ServiceSchema`
  reports the default and only the DSL-declared instance carries the real
  value. No existing `service(...)` call site changed — everything starts at 1.
- **The walk** — new `SchemaProvider.serviceSchemas()` (default empty;
  `SchemaRegistry` overrides via `allSchemas()`), consumed by
  `SchemaProvider.currentVersionVector(context)` / `currentVersionFloors(context)`
  in `:core-common` (`SchemaVersioning.kt`). Floors == current until E2
  registers migration chains. Entries sorted by service id for determinism.
- **Frozen composites** (§6.8, `:core-common` `org.tenshu.core.common.schema`):
  `ServiceVersion` / `VersionVector` / `ServiceVersionMismatch` /
  `SessionAdmission`, registered in `coreCommonModule` beside the AppService
  block; `SchemaVersioningWireTest` pins serialize→parse round-trips (the
  wire-registration gap).
- **Stamp upgrade** (fmt **v14**): the E0 envelope becomes
  `[varint coreVersion][varint stampLen][serialized VersionVector][payload]` —
  the bare varint stays *outside* the composite segment on purpose, readable
  with zero schema machinery (the freeze-robust floor); the vector is the
  §4.1.2 multi-service snapshot. `SyncObserver` assembles + serializes the
  vector once per instance and writes it at all four emit sites; a zero-length
  stamp segment is the test-convenience "core-only" form.
- **`AnnounceSession`** — op + client extension in `:core-common`
  (`org.tenshu.core.common.app`; the frozen composites live in `:core-common`,
  so the op does too — `:core` cannot see them; receiver is still
  `AppService`), `AnnounceSessionRo` (the `WriteSetting` idiom),
  `AnnounceSessionImplementation` registered in the core
  `implementationFactory`. Non-Io + Terminal, `returnType = SessionAdmission`.
- **Session recording** (§6.4) — new `SessionAnnouncement` holder on
  `ExecutionContext`, **shared** with `withEvaluationScope`-derived per-request
  contexts (the announce op executes on a per-request context; same sharing
  rationale as `streamRegistry`/`observerRegistry`). Recorded only on
  admission. This is the state the §6.5 gate and §9.2 polyfill routing read.
- **Admission compare** — per-service both directions of the §6.3 asymmetry;
  a service unknown to the proxy reports `floor = current = 0`; the core slot
  compares under the sentinel id `"tenshu/core"`; proxy services the client
  did not announce are not mismatches. A refusal returns the typed
  `SessionAdmission` — never an error. `AnnounceSessionImplementationTest`
  covers all five shapes.
- **Not in this slice:** the connection-lifecycle wiring — landed next as §6.10.

### 6.10 E1 wiring + gate as built (2026-07-25)

- **Client announce = a session preflight on `RemoteBinaryTaskExecutor`**, not
  a change to the resubscription provider: `sessionPreflight` runs lazily
  before the FIRST remote call of a session (a coroutine-context marker lets
  the announce's own round-trip through; a mutex serializes racing first
  calls) and is re-armed by the transport's reconnect signal *before*
  `onReconnected` fires — so the resubscribe replays, which arrive through
  `executeAsync`, announce first ("announce becomes the first thing
  replayed", §6.4) with `DefaultInteractorProvider` untouched. This also
  kills the announce-vs-first-op race by construction: no caller can pass
  the preflight un-announced.
- **`SchemaAnnouncer` + `wireSchemaAnnounce`** (`:core-common`
  `org.tenshu.core.common.app`): assembles the client's own walked vector,
  calls the op, prints the `TenshuSchemaAnnounce: <appId> admitted=<bool>`
  beacon (println → `System.out` logcat), and maps a **refused** admission to
  a typed throw carrying the named per-service mismatches — every pending
  caller fails with that instead of `error/internal` (§6.3/§6.8 client
  mapping). A failed/refused preflight stays un-latched, so the next call
  retries.
- **Wired once per app** in the common client module's unnamed
  `TenshuClient` alias (Drive/Fit/Maps/Home/Timeline — covers Android +
  desktop from one line), in the three iOS EPC dev clients, and — §6.6 —
  eagerly fire-and-forget in Control's in-process client (`ProxyModule`),
  which route-1 apps reuse (their `platformPackageName` override makes each
  announce as itself) and in the JS runtime (`app.tenshu.web`). The announce
  appId is the app-level id (`app.tenshu.drive`, …), not the platform
  package; transport-derived identity enforcement stays with
  `BundledAppIdentityResolver`.
- **Reject-if-silent gate** (§6.5): `SchemaSessionPolicy.REJECT_IF_SILENT`
  (const `true` pre-release; becomes the post-release transition knob) read
  by a guard in `LocalTaskExecutor.executeAsyncFromBytes` — the ONE
  serialized-transport funnel (Binder + UDS + EPC all route through
  `ProxyGateway.call`). An un-announced session may execute exactly one
  thing: `AnnounceSession`; everything else fails with the new typed
  `UnannouncedSessionError` (`tenshu/result/error/unannouncedSession`,
  registered like `GenericError`). In-process callers pass live objects
  (no garbling risk) and are deliberately not gated.
  ⚠ **A refused ordinary call is resent once** (2026-10-28, `docs/tech-debt.md`
  *"A call could beat the re-announce…"*). `RemoteBinaryTaskExecutor` re-arms
  the preflight, re-announces and resends, so the caller sees a re-announce
  plus one resend, not the refusal. A second refusal reaches the caller. ⚠⚠ This is
  safe only because the gate refuses before any op runs, so the error must
  never be produced after an op has executed.
- **Smoke gate step 4.6**: asserts the announce beacons — Control
  (announces eagerly, missing = FAIL) + Drive/Maps (lazy clients, missing =
  WARN, refused = FAIL) — the §6.5 "every first-party client announces"
  assertion.
- **Two §6.8-sketch deviations, both surfaced by the smoke gate** (unit +
  in-process tests could not see either — the Android emulator run was the
  only thing exercising the full client Ro → Binder → gate path):
  1. `AnnounceSession` is declared **`IoOperation + TerminalOperation`**, not
     non-Io: in this codebase the trait gates the *client execution path*
     (`asTaskInteractor` refuses non-Io ops — non-Io means client-side
     pure-evaluable), not whether the impl touches a store. The impl remains
     pure session bookkeeping.
  2. `AnnounceSessionRo` creates its request object under the **generic
     `Data` type** (the `WriteSetting` idiom) — a typed request object needs
     a registered per-type client Ro factory (the RO-factory-gap class;
     surfaced as `No request object factory registered for type:
     tenshu/core/schema/sessionAdmission` on the very first real announce).
     The *result* still parses typed via the registered `SessionAdmissionDto`.
- **Tests**: `SchemaSessionGateTest` (unannounced → typed refusal, stub
  never runs; announce passes the gate + admits; a *refused* announce is a
  successful typed reply and the session stays gated) — this also proves the
  announce callstack round-trips the real serialize→parse boundary. The
  PhaseL2 socket test now installs a real `sessionPreflight` over the real
  UDS server. `TestProxy.handleRequest` pre-admits its own session (and
  `admitSchemaSession(clientId)` exists for tests driving `gateway.call`
  directly) — production paths announce for real. **Android smoke gate
  PASSED** with step 4.6: Control + Drive + Maps all
  `schema session admitted` over live Binder.

---

## 7. E2 — realm epoch fencing (cross-device)

Announce is device-local; it cannot fence the *other* devices in a realm. Cross-device
skew is handled by a realm-level **schema epoch vector** and a pull-fence.

### 7.1 The epoch record

A synced record in E2E realm metadata carrying the `ServiceVersionVector`: for each
service that has ever written data in this realm, the highest version any device has
emitted under. Its own schema is **frozen forever** (same bootstrap argument as §6.2 — it
must be readable by every version that will ever fence on it).

**Threat-model note (lands with this phase, House Rule #2):** the epoch vector must live
**inside E2E metadata**, not in relay-visible plaintext. Today's global `formatVersion`
leaks only "user upgraded" — low sensitivity. A per-service vector visible to the relay
would leak **which apps the user runs** (a Fit epoch bump reveals a fitness-app user).
The relay gates only its own protocol version (§7.5) and never sees a schema version at
all; the vector is a normal synced, encrypted record. New assumption + T-entry in
[`sync/threat-model.md`](../sync/threat-model.md) when E2 lands.

### 7.2 Trigger: first write at the new version

The epoch for service S bumps when a device first **writes** S-data under the new
version — not at install, not at announce. ("S-data" = any written value *containing*
S-typed composites, whether S owns the entity or an embedded value — §4.1.3.)
Consequences, all intentional:

- A device that merely *updated* but hasn't written doesn't fence anyone.
- A realm that holds no Fit data never gets a Fit epoch — per-service granularity is
  self-scoping (§4).
- The bump travels as ordinary synced data (the epoch record is just a row), so it reaches
  other devices through the sync they were already doing.

### 7.3 The fence: pull-fenced, push-allowed

A device whose supported vector is below the realm epoch for some service it
materializes:

- **stops pulling** (it would garble or drop blocks it can't parse — today's
  `decryptAndParse → null → dropped` behavior, but now *detected* instead of silent);
- **shows "update this device"**, naming the app(s) — the UX your instinct called
  "make the user aware and stop interacting". Local reads keep working; the device is
  read-only-stale, not bricked. ⚠ **No UI in this slice**, and none has been built since:
  the surface is designed in
  [`core-schema-skew-surface.md`](core-schema-skew-surface.md), which also establishes
  that *"naming the app(s)"* is platform-dependent — on Android/desktop the app to name
  is Control, never the app the banner appears in;
- **may still push** its pending old-format writes. New code reads old formats anyway
  (the asymmetric contract, §3) — the upgraded devices upcast those blocks like any other
  old history. This closes the stranded-writes hazard for free: a pull-fence with
  push-allow loses nothing.

After updating, the fenced device runs its local store migration (§8.1) and rejoins.

### 7.4 Relation to the existing 426 gate

The 426 gate *is* this fence at "exact match, global, wipe" granularity. E2 generalizes it
to "floor, per-service, migrate". The relay-side exact-match check relaxes to a
**core-version floor** (the relay must still refuse peers below the oldest core wire
format it stores blocks for — coordination with relay redeploys shrinks to core bumps
only, instead of every schema change as today).

### 7.5 What the 426 gate is *for* after E2 (added v1.0)

The gate should **not** be retired, but the thing it gates on today is wrong, and that
is exactly why relay redeploys feel too frequent. Today's gate keys on
`TENSHU_STORAGE_FORMAT_VERSION` — a number that moves whenever *any* composite in *any*
service reshapes. But under House Rule #1 the relay sees ciphertext: a Fit composite
gaining a field is **invisible to the relay and cannot affect it**, yet it currently
forces a coordinated redeploy. The gate is doing double duty for two unrelated
contracts.

**Split them.** The relay has a real wire contract — routes, JSON request/response
bodies, and the `SyncBlock` *envelope* it parses (`blockId`, `ownerId`, `hlc`,
`encryptedPayload`, `targetEntityIdHash`) — and that contract deserves a gate. What it
does not have is any stake in the encrypted payload's schema.

- **`RELAY_PROTOCOL_VERSION`** (new, relay-visible, plaintext): versions the envelope +
  routes + HTTP contract. This is what 426 keys on. It moves *rarely* — new routes are
  additive and need no bump; the `manifestContentRef`/`REF_PATTERN` incident (content-addressing Phase 1) is
  the archetype of a change that genuinely does.
- **`TENSHU_STORAGE_FORMAT_VERSION` / the per-service vector**: payload schema. The
  relay stops looking at it entirely. **Payload-only changes owe no relay redeploy.**

**Accept a range, not an exact match.** The relay should advertise
`[minSupported, current]` and accept anything inside it. Exact-match forces a flag day;
a range lets us deploy a relay that speaks N and N+1, then roll clients through app
stores at their own pace — the operator-controlled component moving *first* and
*compatibly* is the whole point of having one.

**Why dropping payload gating is safe** (and not a loss of a real backstop): the
asymmetric contract of §7.3 applies to the relay too. An old client *pushing* old-format
blocks is harmless — it always was; new peers upcast them (§8.2), and the relay only
ever stored opaque bytes. The danger is exclusively on the *reading* side, and that is
fenced client-side by the epoch vector, which does the job strictly better than the
relay could: per-service instead of global, self-scoping, and E2E rather than leaking
app usage (§7.1). The relay gate never protected anything the epoch doesn't protect
better — it just also blocked the harmless case.

**The one permanent floor rule:** `minSupported` must never drop below the protocol
version at which **client-side fencing shipped** (E2) — concretely **1**, recorded as
`RELAY_PROTOCOL_FENCING_FLOOR_VERSION` (§7.8). Older clients don't know about
epochs, so for them the relay gate genuinely is the only fence — they must stay
rejected forever rather than silently reading blocks they cannot parse.

**So: keep 426 exactly for the "heavy changes" instinct.** Envelope reshapes, auth
changes, blob-ref format, route semantics — the changes that actually break the relay's
own contract — keep a hard, typed, unambiguous fence. What goes away is being fenced by
the relay for reasons the relay cannot even observe.

### 7.6 E2d-1 as built (2026-07-26) — the epoch record, its merge, and the write-triggered bump

Lands §7.1 + §7.2 + Q3; the **pull fence itself (§7.3)** followed in E2d-2 — see §7.7.

- **`RealmSchemaEpoch`** (`:sync-service-common`, beside `RekeyMigrationMarker` — it needs the
  frozen `VersionVector` from `:core-common`, which `:sync-service` does not depend on).
  `Syncable(Eager, ConflictStrategy.Merge)`, one row per realm under the constant key
  `realm-schema-epoch`. ⚠ **FROZEN FOREVER** (normative KDoc): every version that will ever fence
  must read it, so a reshape would garble exactly the record a lagging peer needs.
- ⚠ **Deviation from §7.1's sketched shape — the vector rides as a `byteArray`, not a
  `composite(VERSIONS, type = VersionVector.Schema)` field.** The local store persists a composite
  field as its own row, so every element of a `TList` field needs a `DataIdProvider`;
  `VersionVector.services` is a `TList<ServiceVersion>` and `ServiceVersion` is a pure *wire* type
  with none. The alternatives were bolting storage traits onto a frozen wire type or minting a
  storable twin of the vector (a second representation, free to drift). Carrying the **frozen
  serialized encoding verbatim** — byte-for-byte what the E0/E1 block stamp already carries — keeps
  one definition of the vector across announce, stamp and epoch, and stays extensible inside its own
  frozen contract (a new service is a list element, not a field). `decodeVersions(...)` is the read
  accessor.
- **Max-merge** (`RealmSchemaEpochMerger`, resolving Q3): per-entry MAX + core MAX = a
  join-semilattice, so racing uncoordinated bumps converge regardless of arrival order and a device
  that never ran Fit cannot erase the realm's Fit entry (which plain LWW would). ⚠ **Registered in
  the production merger wiring** (`initSyncService`), not only in tests — the repo's standing gotcha
  (Timeline's mergers are test-only) would silently degrade this row to LWW. ⚠ Known bound, honestly
  recorded in its KDoc: `SyncProcessor` only consults a merger when the incoming block is
  HLC-*newer*, so a bump that loses the HLC race can be missed — it can only *delay* fencing, and it
  self-heals because the trigger is a **write** (the device re-bumps next time it writes that
  service's data).
- **Write-triggered bump** (`RealmSchemaEpochWriter`, §7.2 + §4.1.3): runs on `SyncObserver`'s emit
  consumer — locally-originated only (ingest early-returns on `SYNC_INGEST` before the channel, so a
  peer's bump can never ping-pong), keys available (locked-window saves are held and replayed),
  single-threaded (so the per-type verdict cache needs no locking). It follows **contained** types,
  not the written entity's nominal owner, so a lagging reader is fenced for the service whose bytes
  it would actually garble. Excludes its own type (it is `Syncable`, so it returns through this path).
- ⚠ **Refinement decided while landing: a core-only rise does NOT write the epoch row.** Core is the
  global floor whose skew the relay's own 426 gate already refuses (§7.4/§7.5's permanent floor
  rule), so the epoch is the **per-service** instrument — the half nothing else covers. Core still
  rides along whenever a service entry moves, so the row never records a stale floor. Without this
  rule every first write in every realm (and every test harness whose types have no attributable
  service) emitted an epoch block for no fencing benefit.
  **⚠⚠ AMENDED by E2i (§7.11): the premise was wrong — the 426 gate does not refuse core skew**
  (E2e removed payload versions from the relay, and the gate was only ever reachable at realm
  genesis anyway). A core rise now **updates an existing row**. The "does not *create* a row for
  core alone" half stands, for the harness reason above.
- **`SchemaRegistry.owningServiceOf(typeId)`** — the type→service attribution the bump needs,
  derived (nothing declared twice): the service registered in the same `ModuleConfiguration` as the
  type. Its limits are documented at the call site rather than hidden: a module registering several
  services attributes all its types to the first (over-attribution, which can only make the fence
  *more* conservative, and siblings ship as one unit anyway); `:core`'s composites attribute to
  `AppService`, inert because the frozen announce channel is pinned at v1; a type from a
  service-less module returns `null` and is covered by the core floor. The drift-free answer is a
  real per-type index, which arrives only if §4.2's `ModuleDescriptor` graduates.
- **First real per-service version bump**: `SyncService` → `version = 2` with an **empty** registered
  `SchemaMigration(SyncService.ID, fromVersion = 1)`. This is the machinery's first live consumer —
  chain verify, the E2b store version stamp (1→2), and the E1 announce floors (floor 1 / current 2)
  now all run on real input rather than test fixtures.
- **HR #9**: `TENSHU_STORAGE_FORMAT_VERSION` **v14→v15** (a new `Syncable` type + relations ride the
  wire; a pre-E2d peer drops the unknown type = silent loss on that peer). ⚠ `BlockPayloadEnvelope`
  grew a shared `stampOf(...)` so hand-built blocks stamp like production — from E2c on, a core-only
  stamp is dropped by decision once any chain is registered, and one now is.
- **Threat model** (House Rule #2, same slice): asset **A37** + threat **T-SE-01** — the epoch vector
  is E2E precisely because a relay-visible *per-service* vector would leak which apps the user runs;
  residual is availability-only (a relay can delay fencing, exactly as it can already withhold
  blocks) and MAX-merge makes arrival order non-exploitable.
- Still owed elsewhere: §8.3's fenced-device pending-journal regeneration (a fenced device's late
  pushes of below-floor formats are now *visibly* dropped by receivers rather than silently garbled,
  but the journal is not yet regenerated post-migration).

### 7.7 E2d-2 as built (2026-07-26) — the pull fence and its typed signal

Lands §7.3. E2 is now complete end to end for the personal realm: E2d-1 records the epoch, E2d-2
enforces it. What remains in the phase list is **E2e**, the §7.5 relay-gate split.

- **The seam is `SyncScheduler.drainAndPull`** — one line, exactly where the push/pull split already
  lived: `val pullAllowed = (pushAllowed || syncManager.realmRelayRegistered) && !schemaFenced`,
  evaluated *after* `drainPendingBlocks()`/`drainPendingTombstones()` and *before*
  `pullChanges`/`pullTombstones`. The verdict lives in a new `SchemaPullFence`
  (`:sync-service-proxy`), constructed by the scheduler and consulted once per tick.
- **Push/pull separability — verified, and it was already structural.** `drainAndPull` computes two
  independent booleans over disjoint statements: `pushAllowed` (`storageGate` + entitlement backoff)
  guards genesis + the two personal drains; `pullAllowed` guards the two pulls. The metered-exempt
  drain runs even earlier (before the `syncGate` branch), and the shared-realm drain/pull pair is a
  separate, per-realm block below. So "fence only the pull" needed no restructuring — one conjunct
  on an existing gate. The fence is therefore also composable with the two gates that do the
  *reverse* (close writes, leave reads open), which is worth noting because it makes the scheduler's
  gate set complete in both directions.
- **Fetch is skipped, not discarded.** No `GetChanges`/`GetTombstones` call is made at all: cheaper,
  and a pull that never runs cannot half-apply a batch (`applyDeltaBatch` has no partial-rollback
  story).
- **Cursor: frozen by construction, not by an extra guard.** `syncManager.lastSyncTimestamp` is
  assigned in exactly one place — inside the `if (pullAllowed)` block, from
  `max(prior, blocksCursor, tombstonesCursor)` — so skipping the block leaves it untouched, and
  `pullChanges`/`pullTombstones` already return `sinceTimestamp` verbatim on failure so it can never
  regress. A fenced device therefore never looks "caught up": the relay re-serves the skipped blocks
  from the same cursor once the device updates. Test-pinned with a mock relay whose `nextCursor` is
  42, so the unfenced case *moves* the cursor and the fenced case's `0` is a real assertion rather
  than a vacuous one.
- **Two deliberate non-fences**, both documented at the check:
  - **A service this device does not know does NOT fence.** An epoch entry with no matching entry in
    this device's `currentVersionVector` names types no schema here can materialize, so it cannot
    garble a read; fencing on it would stop *all* sync — including personal rows the device does
    read — over data it was never going to touch. (Only `currentVersionVector` membership counts, so
    this is self-scoping in both directions: §7.2 keeps unrelated services out of the epoch, and
    this keeps unrelated epochs from fencing.)
  - **A core-version rise does NOT fence** (the §7.4/§7.5 choice, asked and answered explicitly).
    Core skew is what the relay's 426 gate refuses under the permanent floor rule, so the epoch stays
    the *per-service* instrument. This is also symmetric with E2d-1's refinement: a core-only rise
    doesn't even write the row, so fencing on `coreVersion` would fence on a number the writer never
    intends as a fence signal. Core still rides along whenever a service entry moves, which is why
    the check must ignore it *explicitly* rather than by omission.
    **⚠⚠ REVERSED by E2i (§7.11).** The relay's 426 gate does not refuse core skew: E2e took payload
    versions off the relay entirely, and the gate is reachable only at realm genesis, so it could
    never have caught a device that went stale later. Between this deferral, E2d-1's and E2e's,
    core was fenced by nothing — a v12 device synced "successfully" and dropped every v15 block.
    `coreVersion` now fences like any service entry.
  - A corrupt (undecodable) epoch row is treated as **absent**, matching `RealmSchemaEpochWriter`:
    the shape is frozen, so unreadable bytes mean corruption, not skew, and a corrupt row must
    neither fence the device nor block its own overwrite.
- **`SchemaFenceStatus`** (`:sync-service`, beside `SyncHealthStatus`) — the typed signal, mirroring
  that type exactly: composite + `SchemaFenceStatusDto` + `SchemaFenceStatusRo` + a
  `StringDataIdProvider` on a constant `SINGLETON_KEY`, all registered in `syncServiceCommonModule`,
  plus a new `SyncService.Relations.SCHEMA_FENCE` single-row relation and a client
  `SyncService.schemaFence(): Flow<SchemaFenceStatus?>`. ⚠ **NOT `Syncable`** — locally derived, it
  is a verdict *about this device* and meaningless on another one, so it never enters a block, never
  reaches the relay (HR #1 untouched) and **owes no `TENSHU_STORAGE_FORMAT_VERSION` bump** (HR #9: no
  stored row reshaped, no wire shape added). `SyncHealthStatus` was deliberately **not** reshaped —
  positional composites make an appended field peer-breaking for a version-skewed app↔Control pair.
- **Field shape: `fenced: Boolean` + a `,`-delimited `fencedServiceIds: String`** (read via
  `fencedServices()`), not a `TList<TString>`. Same store-layer constraint that made
  `RealmSchemaEpoch` carry bytes (§7.6): list-field elements are persisted as their own rows and so
  need a `DataIdProvider`. Service ids are path-like and can never contain the separator, so the
  encoding is lossless without escaping (`StringListAdapter` is the repo precedent for encoding a
  string list rather than modelling it as a list field). Richer per-service detail a banner may
  eventually want (realm-vs-device versions, a which-side-is-behind discriminator) goes in a **new
  composite id**, never as appended fields.
- **Written only on a verdict change.** The row is not `Syncable` so a rewrite emits no block, but at
  the hot 1.5 s cadence a per-tick upsert would still be pointless store churn plus a stream
  notification to every observing client. The log line follows the same discipline — one line per
  *transition* (ENGAGED / CLEARED), the `DrainFailureLog` + logging-facade-Phase-0 rule, since "this
  device is behind" persists until the user updates and would otherwise emit ~40 lines a minute.
- ⚠ **`SyncService` was deliberately NOT bumped to v3.** The per-service version is the *data*
  version — the thing that fences peers — and this slice added only a local relation. A bump would be
  actively harmful: the epoch writer would raise the realm's `SyncService` entry on the next
  sync-attributed write and pull-fence every not-yet-updated device over a status row nothing syncs
  (§7.2's write trigger cannot tell the difference). Recorded at the `version = 2` declaration so the
  next person doesn't "fix" it.
- ⚠ **Shared realms are deliberately not fenced** *in this slice*. The epoch row is keyed on a
  constant, so it is the *personal* realm's epoch (§7.6's v1 scope); `pullSharedRealms()` keeps
  running. Halting an invited member's collaboration over another realm's skew would be a false
  positive — but the converse gap is real and now explicit: a device fenced on its personal realm
  can still ingest shared-realm blocks of the offending service's types. Closing it is part of the
  namespaced-epoch-key work, not a separate item. **Both halves closed by E2h — see §7.10**, which
  gives every realm its own epoch row and fences each independently.
- **Tests** (`SchemaPullFenceTest.kt`, 15 cases, two classes): the verdict rules unit-level over
  `SchemaPullFence` (ahead / equal / behind / unknown-service / core-only / core-alongside-current /
  corrupt / write-on-transition), and the scheduler consequences through real `initSyncService`
  wiring with mocked relay ops — pull skipped **and** push drained **and** cursor at 0 in one
  assertion set, versus pull + cursor 42 unfenced. ⚠ Harness note worth reusing: a `pullCount() == 0`
  claim requires `TestProxy(coroutineScope = <TestScope>)`; on the default `Dispatchers.Default`
  scope the auto-started polling loop pulls concurrently with the test body, which is precisely why
  the pre-existing scheduler tests can only assert `>= 1`.
- **No threat-model delta.** Nothing new crosses a trust boundary: the fence reads an existing E2E
  row and the signal it publishes never leaves the device. A37/T-SE-01 (landed with E2d-1) still
  cover the epoch itself; the availability residual recorded there — a relay withholding blocks can
  delay fencing — is unchanged, and a relay cannot *cause* a fence (it would have to forge an
  encrypted epoch row).
- **Still owed:** the user-facing "update this device" banner (§7.3's UX half — a pure consumer of
  `schemaFence()`); ~~§8.3's fenced-device pending-journal regeneration, now the sharper of the two
  gaps because a fenced device *does* keep pushing below-floor formats by design~~ (**closed by E2f,
  §8.8** — as an in-place restamp of the queue rather than the unreachable regenerate-from-store);
  ~~per-shared-realm fencing~~ (**shipped, §7.10**); and ~~E2e's relay-gate split~~
  (**shipped, §7.8**). **Nothing is owed on the E2 tail — next is E3.**

### 7.8 E2e as built (2026-07-26) — the relay-gate split

Lands §7.5, and with it **E2 is complete**. One sentence first, because it is the payoff the
whole phase was for: **a `TENSHU_STORAGE_FORMAT_VERSION` bump no longer owes a relay
redeploy.** Only a `RELAY_PROTOCOL_VERSION` bump does.

**Two constants, two contracts** — both in `:core-common`, in a new `RelayProtocol.kt`
beside `StorageFormat.kt`. Same module because it is the lowest one both
`:sync-relay-server` and every client platform already depend on (the relay already imported
`TENSHU_STORAGE_FORMAT_VERSION` from there), so no new dependency edge; *separate files*
because they now version two contracts that must be free to move independently — which is
the entire point — while a reader who finds one finds the other.

- **`RELAY_PROTOCOL_VERSION` = 1** (+ `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION` = 1 and the
  derived `RELAY_PROTOCOL_SUPPORTED_RANGE`). Covers the routes and their semantics, the JSON
  bodies, the `SyncBlock` envelope the relay parses and indexes (`blockId`, `ownerId`, `hlc`,
  `encryptedPayload`, `targetEntityIdHash`), the auth/entitlement contract, and the
  content-plane blob-ref format (`REF_PATTERN` — the content-addressing archetype). **Additive routes need
  no bump** (an old relay's 404 is already handled as "unsupported" at the probing call
  sites). It starts at 1 rather than higher because 1 is *defined* as the relay contract as
  deployed at the split: nothing about the relay changed in this slice, only what the gate
  reads, so 1 is also — by construction — the version every pre-E2e client already speaks.
  That identity is what makes the transition rule below honest rather than a fudge.
- **`TENSHU_STORAGE_FORMAT_VERSION`** stays exactly as it is and keeps its lockstep
  discipline **between clients** (HR #9 unchanged in that respect). The relay stops reading
  it. Its KDoc's "Relay: the routes refuse a mismatched client" bullet was **false as of this
  slice** and now says so.

**The gate.** `RealmRouting.formatVersionOk()` → `relayProtocolVersionOk()`: read the body's
`relayProtocolVersion`, accept iff it is inside `[min, current]`, else **426** with a message
naming the accepted range *and which side must move* (below the floor → "this client must be
updated"; above current → "this relay must be redeployed"). Asymmetric on purpose: a range
violation has a determinate remedy and the message should say it. Status and chokepoint are
unchanged — `/sync/realm` only, after field validation so a malformed body still 400s.

**The transition rule (explicit, and it expires by construction).** The currently-deployed
relay treats a *missing* `formatVersion` as a mismatch, so the client sends **both** fields
and the new relay accepts a body carrying only `formatVersion`:

> An absent `relayProtocolVersion` is read as protocol **1** — a pre-E2e client, which
> speaks protocol 1 *by definition*. It is therefore admitted exactly while `1` is still
> inside the supported range, i.e. while `minSupported == 1`.

A *present but unparseable* `relayProtocolVersion` is **not** read as pre-E2e — it 426s. The
"absent means 1" reading is only valid for a body that never mentions the field; admitting
garbage as protocol 1 would be the sloppy version of the same rule.

Two properties worth naming. It is not leniency invented for convenience — it is the truth
about those clients, which is why it is safe. And it needs **no future cleanup commit**: the
day `minSupported` rises above 1, "read as 1" falls out of range and those clients are
refused, with no code change at the gate. The client's `"formatVersion"` field is marked a
transition leftover in `RestSyncRelay` and is removable once no pre-E2e relay is in service;
an E2e relay never reads it.

**⚠ The permanent floor rule, with its number.** `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION` must
**never** drop below **1** — recorded as its own constant,
`RELAY_PROTOCOL_FENCING_FLOOR_VERSION = 1`, because 1 is the protocol version E2e ships as
and therefore the first at which **client-side epoch fencing (E2d, §7.3/§7.7) exists in the
field**. A client older than that does not know epochs exist; it will pull blocks from newer
peers and garble them mid-parse, because positional composites misalign rather than error.
For such a client the relay gate is *the only fence there is*. Lowering the floor is a
**data-corruption bug, not a config choice** — not something to relax when an old build
cannot connect. The rule is enforced, not merely documented: `RELAY_PROTOCOL_SUPPORTED_RANGE`
is derived through `require`s that fail loudly at class-init, and `RelayProtocolTest` pins
them.

**Why a range and not exact match** (§7.5, restated because it is the operational half):
exact match forces a flag day. A range lets the operator-controlled component move *first and
compatibly* — deploy a relay speaking N and N+1, then let clients roll through app stores at
their own pace. Both ends are 1 today; the range is there so the first real bump is a
non-event.

**Why dropping payload gating loses nothing** — pinned by a test
(`differingFormatVersion_isAccepted`), because it is the assertion a future reader will
distrust: a client whose `formatVersion` differs from the relay's build in *either*
direction is accepted. Under HR #1 the relay stores ciphertext and cannot be affected by a
payload schema; an old client *pushing* old blocks was always harmless (peers upcast on read,
§8.2); and the read side is fenced client-side by the epoch vector, which is strictly better
than the relay could do — per-service instead of global, self-scoping, and E2E instead of
leaking app usage.

- **Tests.** `RealmRoutingTest`: min-supported accepted · below-floor 426 naming the range +
  the client as the side to move · above-current 426 naming the relay · pre-E2e client (no
  `relayProtocolVersion`) accepted · no version fields at all accepted · present-but-
  unparseable 426 · differing `formatVersion` accepted (twice, one behind and one absurdly
  ahead).
  ⚠ **Two existing tests were deliberately inverted, not fixed**:
  `staleFormatVersion_returns426` and `missingFormatVersion_returns426` asserted the
  exact-match payload gate, which is precisely the contract this slice removes; they are
  replaced by the accept-side assertions above. Plus `RelayProtocolTest` in `:core-common`
  for the range/floor invariants. `AdmitDeviceRoutingTest` / `MembershipGrantRoutingTest`
  bootstrap bodies were left untouched (they send only `formatVersion`, so they now
  incidentally exercise the pre-E2e leniency path). `:system-test-utils` (a real relay,
  end-to-end) is green unchanged, which is the strongest evidence the split works: the live
  client's genesis now passes a gate that reads a different field.
- **Threat model.** No new asset. A37 + T-SE-01 (E2d) already anticipated §7.5 and were
  amended in place from "the relay keeps one plaintext global `formatVersion`" to "the relay
  keeps one plaintext number describing *its own* contract". The metadata the relay learns
  **shrinks**: it no longer learns the client's storage-format number, so the "this user
  upgraded" signal that A37 called acceptable-in-plaintext is simply gone — a small,
  free privacy improvement.
- **`TENSHU_STORAGE_FORMAT_VERSION` not bumped** (HR #9): no stored row, payload or wire
  *payload* shape changed. The only wire delta is an **added plaintext body field** on
  `/sync/realm`, which is a JSON map — additive there is genuinely additive, unlike a
  positional composite.
- **Still owed / unchanged by this slice:** ~~the "update this device" banner (§7.3's UX half)~~
  (**shipped, §7.9**), ~~§8.3's fenced-device pending-journal regeneration~~ (**closed by E2f,
  §8.8**), ~~per-shared-realm epoch keys~~ (**shipped, §7.10**). Next phases are **E3** (op
  floors) and **E4** (compaction).
- ⚠ **One more redeploy is owed, and it is this slice's own.** The relay in service predates
  E2e: it exact-matches `formatVersion` against the version *it* was built with, so it
  already refuses today's clients and will keep doing so until it runs this code. Deploy it
  once and the payload-bump redeploy obligation ends permanently — what this slice retires is
  every redeploy that would have been owed *after* it, not the one already outstanding.

### 7.9 E2g as built (2026-07-27) — the "update this device" banner

Lands §7.3's UX half: *"shows 'update this device', naming the app(s) — make the user
aware and stop interacting."* Pure consumer of the `SchemaFenceStatus` signal E2d-2
published; **no proxy, wire, schema or format change** — Drive-side only, exactly the
shape `SyncHealthStatus`→Drive's banners took one slice after its signal shipped.

The chain, mirroring the sync-health precedent end to end:
`SyncService.schemaFence()` (client, already existed) → `TenshuFilesRepository.schemaFence()`
→ new `SchemaFenceModel` → `SyncBanner.evaluate(storage, health, fence)` → new
`SyncBannerState.NeedsUpdate` → `SyncStatusBanner`. `FilesScreen` needed no change (it
renders whatever state arrives) and `FilesScreenViewModel` just gains a third `combine`
source.

- **Priority: `Locked` > `NeedsUpdate` > `LocalOnly` > everything else.** Every state
  below `NeedsUpdate` describes the **outbound** side (nothing reaching the relay); the
  fence says the **inbound** side is deliberately stopped, which is exactly why a fenced
  device's list can silently disagree with the user's other devices. It is also the one
  state where "Syncing…" would be an outright lie in the *useful* direction — pushes
  really are draining while pulls are fenced (§7.3). Only `Locked` outranks it: an unlock
  is a prerequisite for anything at all, and a locked proxy holds no keys, so its epoch
  read is moot anyway.
- **Evaluated ahead of the `storage == null` optimism guard**, for the same reason
  `Locked` is: the verdict derives from the realm epoch alone, so it is valid — and is
  the most useful thing to say — on a device whose storage figures have not arrived, or
  never will because it has no subscription.
- **Informational, no action button.** The fix is an app-store update, which no in-app
  button can perform (`StorageSetupLauncher` opens *Control*, which is the wrong target
  and inert on desktop anyway). The string carries the whole message, including the
  reassurance that local files are safe and still upload — that reassurance is *true
  precisely because* of push-allow, so the UX and the protocol agree by construction.
- ⚠ **§7.3's "naming the app(s)" half is deliberately NOT done.** `fencedServiceIds`
  rides on `SchemaFenceModel`/`NeedsUpdate` for diagnostics, but the ids are internal
  `tenshu/…/service` paths and **there is no service-id→app-name mapping anywhere in the
  codebase**. Rendering them raw would read as noise ("Update tenshu/sync/service"), and
  deriving a name from the path is a heuristic that would confidently print the wrong
  app. The generic message is also correct for every case today, since first-party apps
  update in near-lockstep through the stores (§9.4). Naming waits on a real mapping —
  most naturally a display-name field on the future richer `ModuleDescriptor` (§4.2).
- **Drive only.** Control has no sync-health banner to extend (its only banner surface is
  `QuotaSoftLimitBanner`), so putting the fence there means building a VM + flow from
  scratch. Deferred, and worth noting the fence is *proxy*-global: whichever app shows
  it, the verdict is the same, so Drive's banner already covers the device.
- **Tests:** six cases added to `SyncBannerTest` (24 total, all green) — fenced ⇒
  `NeedsUpdate` carrying the ids; outranks every outbound state at once; `Locked`
  outranks it; banners before storage is known; a *cleared* fence shadows nothing; and a
  `null` fence is byte-identical to the pre-fence world (which is what keeps every fake
  and non-syncing repository working off the `FilesRepository` default).

### 7.10 E2h as built (2026-07-27) — per-shared-realm fencing

Closes the last E2 tail item, and the gap §7.7 recorded as *"the converse gap is real
and now explicit: a device fenced on its personal realm can still ingest shared-realm
blocks of the offending service's types."* **No wire, schema, store or format change** —
the epoch composite is untouched; only its *key* becomes realm-scoped.

- **One epoch row per realm, keyed by realm.** `RealmRegistry.epochKeyFor(realmId)`:
  the personal realm keeps the bare `SINGLETON_KEY` (⇒ existing rows need no
  migration), a shared realm's row is `"$ownerId::realm-schema-epoch"`. ⚠⚠ **The
  separator is deliberately the same `"$realmId::"` DataId prefix every other
  shared-realm entity carries**, not a bespoke scheme: the epoch row is then routed,
  keyed, encrypted, queued and pushed to its own realm by `RealmRegistry.realmForDataId`
  and the ordinary emit path, **with no special case anywhere**. `:sync-service-common`
  stays separator-agnostic — `RealmSchemaEpoch.create` just takes whatever `epochId` it
  is handed, and realm routing (a `-proxy` concern) composes it.
- **The writer follows the write.** `RealmSchemaEpochWriter` resolves the realm by
  exactly the rule `SyncObserver.generateSyncBlock` uses for the block itself, so the
  bump lands on the epoch of the realm the write routes to. ⚠ This **fixes a real
  misattribution**, not just a scope extension: every bump previously wrote the one
  constant-keyed row, so a shared-folder write raised the *personal* epoch — which would
  fence a personal-realm peer over a shared folder's schema **and** never fence the
  shared realm's actual members. Proven by `sharedRealmEpochBlock_ridesTheSharedRealmsQueue`
  (the bump's own block lands in the shared queue, encrypted under the shared key).
- **The fence evaluates every realm per tick.** `SchemaPullFence.evaluate()` now judges
  the personal realm plus each registered shared realm, caches the shared verdicts, and
  `SyncScheduler.pullSharedRealms` skips a fenced realm's fetch entirely — so
  `runtime.cursor` stands still **by construction** (it is only ever assigned inside the
  branch below the skip), exactly as `lastSyncTimestamp` does for the personal pull.
  `drainSharedRealms` is untouched: pull-fenced, push-allowed, per realm.
- ⚠ **The published `SchemaFenceStatus` stays ONE aggregate row** (union of the
  offending service ids, `fenced` = any realm fenced). It answers *"does this device need
  updating"* — a property of the device, which is what the §7.9 banner says. A
  shared-only fence therefore still banners, which is the point: that folder has
  silently stopped updating. Per-realm detail (*which* folder is stale) is a genuinely
  different signal and, per §7.7's rule, belongs in a **new composite id** — the status
  row is consumed by version-skewed peers over IPC, so its positional shape can never
  gain a field.
- ⚠ `sharedRealmIds` is read **per `evaluate()`**, not captured, so a realm registered
  mid-session is fenced from the next tick; a realm registered mid-*tick* simply syncs
  unfenced until then, the same freshness every other per-tick gate has.
- **No `SyncService` bump**, same reasoning as E2d-2: no data shape changed, and a bump
  would fence peers over a purely local mechanism.
- **Tests (11 new, 384 green):** writer-side routing in `RealmSchemaEpochBumpTest`
  (shared write bumps the shared row not the personal one; the bump's block rides the
  shared queue; a personal write is unchanged with a shared realm registered);
  verdict-level in `SchemaPullFenceTest` (shared-ahead fences that realm only;
  personal-ahead does *not* fence a shared realm; realms judged independently; the
  aggregate status banners on a shared-only fence and dedupes a service behind in two
  realms; no-shared-realms is unchanged); and scheduler-level in
  `MultiRealmPullIsolationTest` — a fenced shared realm ingests nothing **and its cursor
  stays 0**, using the same block the sibling test proves *does* apply.

### 7.11 E2i as built (2026-07-27) — core-version skew was fenced by nothing

Opened by a live incident (2026-07-27): an Android device at `TENSHU_STORAGE_FORMAT_VERSION`
**v12** stopped receiving a v15 desktop's changes. It authenticated, synced "successfully",
and **silently dropped every block** — desktop blocks carry the E0 `BlockPayloadEnvelope`
inside the ciphertext and v12 has no code to open it, so `decryptAndParse → null → dropped`.
Nothing surfaced. Investigating turned up four things, three of which were not what the
incident looked like.

**⚠⚠ 1. Core skew was fenced by nothing, on either side — three slices each deferred it to
another.** E2d-1 (§7.6): a core-only rise deliberately does *not* write the epoch row,
"core is the relay 426 gate's job". E2d-2 (§7.7): the fence deliberately does *not* fence
on `coreVersion`, "the relay 426 gate's job". E2e (§7.8): the relay stopped reading
payload/core versions **entirely**. Each deferral was individually reasonable and read the
others as still holding. Net: the job nobody does.

**⚠⚠ 2. The 426 gate is a GENESIS gate, and always was.** `relayProtocolVersion` is sent by
exactly one call — `RestSyncRelay.createRealm` — and `RealmRegistrar.ensureRegistered()`
latches once-ever (`realmRelayRegistered || lastSyncTimestamp > 0`). **A device that has
synced once never presents a version to the relay again.** So a client that goes stale
*after* bootstrap was structurally invisible to the gate even before E2e. The pre-E2e
`formatVersion` gate only appeared to catch such clients because a format bump came bundled
with a relay redeploy **and data wipe**, which 401'd every device into `ReregisterDevice` →
`createRealm` → the gate. **The wipe cycle was doing the fencing, not the gate** — and E2e
removed the redeploy obligation (correctly) without noticing it was also removing that.

**⚠ 3. The transition rule contradicted the permanent floor rule.** §7.5 requires
`minSupported` never fall below the version at which client-side fencing shipped, because
"older clients don't know about epochs, so for them the relay gate is the only fence". The
transition rule read an *absent* `relayProtocolVersion` as exactly that floor (1) — so a
client of unknown, possibly pre-fencing vintage was admitted as if it were fencing-capable.
§7.8's "the allowance expires by construction when the floor rises" is only true *above* 1;
at 1 it granted precisely what the floor forbade.

**4. Benign:** `mia-android` emits no lockstep beacon, but it has **no Control client at all**
(mock modules), so it cannot be out of lockstep in any way that matters. Left alone.

#### The fix — reader-side, where the fence belongs

- **`SchemaPullFence` now judges `coreVersion` like any service entry**: recorded above this
  device's `TENSHU_STORAGE_FORMAT_VERSION` ⇒ fenced, pull skipped, cursor frozen, E2g banner
  raised. Core is deliberately **coarse** — it is the global store/wire floor, so a rise
  means the shared primitives moved and the hazard is attributable to no single service.
  That coarseness is the point: core is exactly the residue the per-service vector cannot
  describe.
- It rides the published signal under the reserved **`SchemaFenceStatus.CORE_SERVICE_ID`**
  (`"tenshu/core"`), not a new field — the status composite is read by version-skewed peers
  over IPC, so its positional shape can never grow — and this keeps
  `fenced == fencedServiceIds.isNotEmpty()` true, which is how the verdict is derived.
- **`RealmSchemaEpochWriter` now updates an existing row's core**, else the fence goes blind
  to exactly the skew it was just taught to catch: HR#9 bumps the format for a reshape of a
  *core-common* type, which belongs to no service, so no service entry would move. ⚠ It
  still never **creates** a row for core alone — E2d-1 suppressed that on purpose (it makes
  the first write in every realm emit an epoch block; it broke ~10 sync tests) and
  update-if-present is the narrow half that closes the blind spot without reopening it.
- **The E2e transition allowance is retired**: an absent `relayProtocolVersion` is now a
  426 naming the missing declaration. Its premise — "they are the entire deployed fleet" —
  expired at the 2026-07-26 deploy + wipe. ⚠ **This one needs a relay redeploy to take
  effect**; it is a tightening of an existing gate, so no `RELAY_PROTOCOL_VERSION` bump.

#### ⚠ What this does NOT do

**It cannot rescue a device that is already stale.** The fence lives in the *reader*, so it
protects every build that has it (v15 and later); a device predating E2d has no fence and
nothing on the new side can give it one. For those the remedy is unchanged and manual:
`adb install -r` **every** app (preserves `shared_prefs/`, so identity and pairing survive;
the store self-wipes on the `user_version` mismatch). Never uninstall — that destroys
identity and forces a re-pair.

It also does not make the relay a session-level gate. Carrying a version on authenticated
routes would catch a client that goes stale after bootstrap, but it needs a
`RELAY_PROTOCOL_VERSION` bump and re-introduces per-request version chatter; deferred until
post-release, when third-party clients we cannot force-update actually exist.

**Tests:** the two suites that *pinned the old behaviour* were inverted with the reasoning
recorded at each (`coreOnlyEpochAhead_notFenced` → `_fences`,
`coreAheadDoesNotFenceEvenAlongsideACurrentService` → `coreAheadFencesEvenWhenEveryServiceIsCurrent`,
`coreOnlyEpochAhead_stillPulls` → `_skipsThePullAndFreezesTheCursor`,
`preE2eClient_withoutRelayProtocolVersion_isAccepted` → `_isRefused`,
`noVersionFieldsAtAll_isAccepted` → `_isRefused`), plus new cases for the equal/behind
non-fence, the writer's core update, and the writer still not creating a row for core alone.
⚠ Two relay suites (`AdmitDeviceRoutingTest`, `MembershipGrantRoutingTest`) bootstrapped a
realm as a *setup step* with pre-E2e bodies and were leaning on the allowance — they now
send `relayProtocolVersion`, which is what a real client sends anyway.

---

## 8. Data migrations: eager store, lazy history, compaction

Three different artifacts, three different policies — this is where "eager or lazy?"
dissolves into "both, for different things":

### 8.1 Local stored rows: eager, at first open after upgrade

Replace the wipe in `SqliteObjectStore` (+ iOS/JS twins) with a **`SchemaMigration`
chain** (§8.4 — the same artifact the history seam runs): at open, if the recorded
version is below current, run each step's row-rewrite, then bump the recorded version.
Steps are authored per service per version bump, next to the schema change they
accompany (House-Rule-#9 discipline transfers: *changed the shape? ship the step in the
same PR* — the smoke gate can assert a step exists for every bump).

- **Affordable because migrations are metadata-only** (fact 7): content bytes never move —
  `contentId`/CEK addressing means a schema migration rewrites rows, not gigabytes. A
  one-time O(rows) rewrite even at the 10k-file scale the bounded-store work targets is a
  progress screen, not a lifestyle.
- **Crash-safety is a solved shape**: generalize the `DataIdMigrator` intent-journal
  pattern (fact 6) — marker in own transaction → idempotent step → clear marker →
  `resumeIncompleteMigrations()` at init — rather than inventing new machinery.
- **Lazy stored-row migration is the explicitly rejected option**: it would mean every
  read path carries upcasters forever and no write path may assume the current shape —
  permanent complexity purchased to avoid a one-time cost. The store is local; migrate it
  and be done.

### 8.2 Relay history: lazy, upcast-on-read, never rewritten

Blocks are immutable E2E ciphertext. Rewriting history would be a re-emission storm with
HLC consequences and no upside. Instead: when replaying backlog (new device hydration,
recovery), each block's stamp (§5) routes its plaintext through a **version-keyed upcaster
chain** (`vN → vN+1 → … → current`) before materialization. Upcasters are pure functions
per service per bump, authored alongside the schema change — and they are *the same
transform* as the store-migration step, unified as `SchemaMigration` in §8.4: one PR, two
artifacts (schema Δ + `SchemaMigration`), enforced by convention and an init-time
completeness check.

### 8.3 Compaction bounds the floor — and *is* the cut-off mechanism

> ⚠ **Field evidence for E4's priority (2026-08-04):** a wiped Drive-on-iOS replayed relay history
> from seq 0 and was **write-dead for an estimated 13 hours** — the log held ~5.5 historical blocks
> per live entity (78,773 blocks against 14,208 `SyncMetadata` rows), and the one row that would
> have unblocked its writes sat near the tip. Measurements, the three separable problems, and the
> rejected "index the log" alternative are in
> [`sync-device-catch-up.md`](sync-device-catch-up.md). Compaction is the primary answer there; that
> note proposes no change to the design below.

"Upcast forever" is bounded by **compaction**: a device periodically (or on demand)
re-emits current state as fresh blocks at the current version; retention GC eats the
superseded backlog. The recovery blob is already this artifact in spirit — a
current-state snapshot at one version.

> ⚠⚠ **Correction (2026-08-07): "retention GC eats the superseded backlog" is false against the
> relay as built, and the fix is not available.** `SyncMailbox.pushBlock` appends unconditionally on
> both backends; the only body-dropping path, `MailboxStore.dropBodiesFor`, is called solely from
> `gcSweep`, which walks the **tombstone** index — it reclaims only entities that were *deleted*.
> Nothing drops a superseded block for a live entity, so re-emission today makes the log **longer**
> (one block added, none removed). A relay-side supersede rule keyed on `targetEntityIdHash` cannot
> be written safely either: property blocks share the entity's hash and are not superseded by an
> entity block, and the discriminator (`propertyId`) is inside the ciphertext **by design**
> (threat-model **T-R-08**) — which is exactly why the existing GC is tombstone-shaped, deletion
> being the one case where entity granularity is safe. ⚠ Lane-batched `DeltaBatchPayload` blocks
> carry `targetEntityIdHash = null`, so they are invisible to any such rule regardless.
>
> ⚖️ **This does not weaken E4 — it re-scopes its payoff.** Compaction still delivers what §8.3
> actually needs it for: the *proof* that no realm depends on pre-F blocks, so the readable floor
> can advance and upcasters below F can be deleted. What it does **not** deliver is the log-volume
> reduction `sync-device-catch-up.md` §5.2 attributed to it; that needs a relay-side mechanism
> owing a `RELAY_PROTOCOL_VERSION` bump and an HR#2 amendment. Full working in that note's §11.8.

Once compaction guarantees no realm still depends on pre-F blocks, the readable **floor
advances to F** and upcasters below F are deleted. That is the data-plane answer to
"cutting off versions": *supported floor F, moved forward deliberately, with compaction as
the proof obligation.* No user is ever cut off silently — a below-floor device was already
fenced (§7.3) long before its formats stopped being readable.

Detailed compaction design (trigger cadence, interaction with `DeltaBatchPayload`
checkpoints and relay retention GC) stays with its phase (E4), but the **coordination
model is settled** (2026-07-25, resolving open questions 1 + 2):

- **No leader election, no quorum.** The classic algorithms answer a question this
  system doesn't ask. Compacting — re-emitting current state at the current version —
  is *idempotent and convergent*: two devices racing produce redundant blocks that
  merge by the ordinary HLC / semantic-merge rules, not corruption. Consensus would buy
  agreement we don't need at an availability cost we can't afford (the user's devices
  are mostly offline; a quorum may simply never be reachable).
- **Dedup by receipt, not election.** A device intending to compact writes an ordinary
  synced *compaction receipt* row ("device D compacted service S to vN at HLC h");
  peers that see it skip. This is the "signalling block" idea with the blockchain
  removed — inside the trusted device set there is no adversary, so the signal is a
  plain E2E row: no proof-of-work, no chain, and a lost race costs one redundant
  re-emission (bandwidth, never correctness).
- **Floor advance is roster-gated, not time-gated.** The floor for service S advances
  to F only when **every registered, non-revoked device** has recorded a supported
  version ≥ F (each device's vector rides the epoch record / its synced `Device` row —
  convergent state, no interactive round). A device offline for six months therefore
  *holds the floor back* until it returns and updates — or is revoked/pruned through
  the existing device-management path (ADR 0007), which is precisely the lever the
  user already holds for a lost device. Old history stays readable exactly as long as
  someone might still legitimately need it, by construction.
- **Scheduling is best-effort idle work, never correctness-bearing.** Compaction runs
  as a maintenance task on the existing `PlatformScheduler` seam (bgproc Ph6/7) under
  idle + charging + unmetered constraints — WorkManager expresses these directly;
  iOS's `BGProcessingTask` targets exactly the overnight-while-charging profile;
  desktop runs it opportunistically. OEM throttling (the ColorOS class) can only
  *delay* floor advancement, never break a read: correctness must never depend on the
  midnight job having run.
- **Laggard pending writes.** A fenced device's durable pending-block journal may hold
  blocks serialized under the old version. Store migration at open (§8.1) must
  therefore also regenerate the pending journal from the migrated store, so late
  pushes after an update never re-emit below-floor formats.

#### 8.3.1 E4-1 as built (2026-08-02) — the per-device fact the floor advances on

⚠⚠ **Scope note first, because it shapes everything below: E4 currently has nothing to compact.**
`floorFor` walks down only through *registered* `from` steps, and the only chains in the repo are
empty bodies — so **every service's floor already equals its current version**, there is no upcaster
below any floor to delete, and compaction's stated payoff has nothing to bound. E4 is therefore
built **against synthetic data** (the E2j-5 pattern: a synthetic service with a real chain gives the
machinery actual work in tests) rather than deferred — but nothing in it should be read as
exercised by production traffic until a real chain ships.

**What was missing.** Floor advance is roster-gated on a **MIN** across devices, and nothing
anywhere recorded a per-device version. ⚠ [RealmSchemaEpoch] cannot serve: it is merged by **MAX**
("the newest schema anyone has written", which is what *fencing* needs) and a MAX-merged aggregate
can never yield a MIN. The two rows answer opposite questions, so E4-1 adds the opposite row:

- **`DeviceSchemaVersion`** (`:sync-service-common`) — one keyed row per device carrying that
  device's `VersionVector` as serialized bytes (same reason as the epoch's: `TList` members need a
  `DataIdProvider`, `ServiceVersion` is wire-only). `LastWriterWins`, not `Merge`: one author per
  row, so a later statement always supersedes its own earlier one, with nothing to reconcile.
  ⚖️ Deliberately *not* a field on the `Device` roster row — that would churn identity rows on every
  version bump and reshape a load-bearing type for metadata that is not identity (E2h's precedent:
  per-X detail is a NEW composite id, never appended fields).
- **`RosterSchemaFloor.evaluate`** — pure, and the whole rule lives here because every failure mode
  it has is silent and irreversible: advance the floor while a device still needs the old formats
  and that device's history is simply gone. Every uncertainty therefore resolves to *do not
  advance*:
  - ⚠ **Absence is not zero.** A roster device with no row returns `Unknown`, naming it — silence
    means "has not run since the row existed", which is precisely the device that loses data.
  - ⚠ **A revoked device stops counting, even with a stale row still in the store** — revocation
    does not reach in and delete synced rows, and a dead device must not lower the floor from
    beyond the grave. That is ADR 0007's lever doing exactly the job §8.3 assigns it.
  - ⚠ **A service only *some* devices run is omitted, never defaulted to 0** — a device that does
    not know service S cannot read S's history at any version, so a MIN including it is
    meaningless, while a caller reading a missing entry as 0 would refuse to ever advance.
  - ⚠ **An empty roster is `Unknown`, not vacuously advanceable** — "every device has version ≥ F"
    is trivially true of no devices, which would authorise deleting all history on a realm whose
    roster merely failed to load.

**`SyncService` v2 → v3.** The v2 KDoc reserved the next bump for "the first slice that changes
something a *peer* reads"; this is it — `DeviceSchemaVersion` is `Syncable(Eager)` and peers both
receive and read it, the opposite of E2d-2's deliberately-local `SchemaFenceStatus`. The step is
empty (a new type reshapes nothing) but registered, because the chain is what the floor is *derived*
from. ⚠ The floor stays **v1**: the chain is contiguous (1→2→3), so adding a step extends
readability rather than raising the floor — raising it is compaction's job, never a side effect of
shipping a type.

#### 8.3.2 E4-2 as built (2026-08-02) — publishing this device's vector

`DeviceSchemaVersionPublisher`, called once from `initSyncService`.

⚖️ **A one-shot at init, not a per-write hook** — the asymmetry with
`RealmSchemaEpochWriter` is the point. The *realm's* high-water mark moves whenever any device
writes a newer type, so the epoch must watch writes; a *device's own* vector is assembled from its
registered module graph and is therefore a **compile-time constant for the life of the process**.
The only moment it can change is a restart on a new build.

- ⚠ **Write-if-changed.** The row is `Syncable(Eager)`, so an unconditional publish would emit one
  block per launch per device, forever, about a fact that moves only at upgrade. Pinned by a test.
- ⚠ **Placed after the observers are registered**, beside the own-`Device` self-heal and for the
  same reason: earlier it lands local-only, every peer reads this device as *silent*, and a silent
  device pins the floor for the whole realm.
- ⚠ **Requires realm membership** (`currentRealmContext()`), not merely "unlocked" — a device that
  has not joined must not self-assert a roster row (`reconcileOwnDeviceRow`'s rule).
- A row whose bytes will not decode is treated as absent and overwritten: the schema is frozen, so
  a decode failure is corruption, and a corrupt row must not permanently pin this device as silent.

#### 8.3.3 E4-3a as built (2026-08-02) — the dedup signal

`CompactionReceipt` + `CompactionPolicy.shouldCompact`, the "no election, no quorum" half of §8.3.

- The receipt is keyed **per service, not per device**, so a second device's receipt *replaces* the
  first rather than accumulating a row per device forever. What a peer needs to know is "has anyone
  done S at version N", never "who" — ⚠ `compactedBy` is diagnostic only, and nothing may branch on
  it, or the per-device coordination this design removed comes back.
- ⚠ **A receipt naming an older version does not count.** It is a claim about a version, not a
  permanent "this service is done" flag: after the next bump, v2-shaped history is old again.
- ⚠ **`floor >= current` ⇒ never compact.** With no registered chain there is no older readable
  format, so there is nothing a re-emission would lift — the policy must be a no-op rather than a
  timer that re-emits the whole store. That is the state of every service in the repo today, and
  the test pins it.

#### 8.3.4 E4-3b — re-emission (designed, not built)

⚠⚠ **This section previously claimed re-emission faced a structural addressing problem. That was
wrong, and the correction is worth keeping visible** because the mistaken reasoning is the kind that
recurs: it imported E2f's constraint into a situation where it does not hold.

**The (bad) argument was:** `saveData` takes a `DataId`; the store is hash-keyed; E2f established
that `targetEntityIdHash` cannot be inverted back to a `DataId`; therefore a compactor cannot walk
the store and re-save.

**Why it does not hold. `DataId`s are derived, not stored — so nothing needs inverting.**
`ExecutionContext.getDataId(data)` resolves the type's `DataIdProvider` trait and runs it *over the
row's own fields* (`Device` keys on its `deviceId`, `RealmSchemaEpoch` on its `epochId`, and so on).
A stored row is plaintext and carries its key material, so its id is recomputable from it.

⚖️ **The distinction from E2f is the whole point.** A queued `SyncBlock` holds `targetEntityIdHash`
and an *opaque encrypted payload* — there is no row to re-derive from, which is exactly why E2f's
in-place restamp was necessary. A store row is the opposite situation. "The hash is not invertible"
is true in both and decisive in neither.

**So the pipeline is available today, end to end:**

| step | mechanism |
|---|---|
| enumerate rows of a type | `StoreMigrationPort.rowsOfTypes(typeIds)` (E2b), with `InMemoryStoreMigration` as the twins' equivalent (E2j-3) |
| `DbEntry` → `Data` | `StoreRowMigrationCodec.decode(entry, schema)` — the migration seam already does exactly this |
| `Data` → `DataId` | `ExecutionContext.getDataId(data)` |
| emit | ordinary `saveData`, so every observer fires as usual |

**What actually remains** — plumbing and policy, not structure:

- ⚠ `rowsOfTypes` lives on the `internal` *migration* port and is scoped to store-open. Compaction
  wants the same enumeration outside that moment, so the seam widens or grows a sibling. Note
  `ObjectStore` itself still has no enumerate-by-type primitive (`core-relation-system.md` §12) —
  that part of the original claim stands; it is the *store-migration* layer that can enumerate.
- ⚠ A type with **no `DataIdProvider`** cannot have its id recomputed (`getDataId` falls through to
  the simple-value branch). Worth an explicit skip-with-a-named-line rather than a silent gap.
- ⚖️ Re-emitting through `saveData` fires the full observer set — relation maintenance, the epoch
  bump, lane batching. For compaction that is mostly what we want (fresh blocks at the current
  version), but it means a compaction pass also *touches the epoch*, and the interaction with the
  §7.2 write-trigger deserves a decision before building.

Still worth confirming against a real migration chain before building, per the scope note above —
not because the mechanism is in doubt, but because there is currently nothing to re-emit.

#### 8.3.5 E4-3b as built (2026-08-02) — the compactor

`SchemaCompactor` (`:sync-service-proxy`) plus the one primitive that was genuinely missing.

**`ObjectStore.rowsOfTypes(typeIds): List<DbEntry>?`** — implemented on `SqliteObjectStore` (a
`SELECT … WHERE type IN (…)` over the column the migration seam already indexes by) and
`InMemoryObjectStore`; default `null` elsewhere.

- ⚠⚠ **Null and empty mean opposite things.** Empty is "no such rows"; null is "this store cannot
  enumerate". A compactor that read an unsupported store as empty would write a receipt claiming
  the service had been re-emitted, and every peer would then skip the work — so the absence of the
  *capability* has to be distinguishable from the absence of *rows*. There is a test for exactly
  that, asserting no receipt is left behind.
- ⚠ The SQLite implementation reads through the WAL read connection and **does not consult the
  resident cache**: under C-lite size tiering a large row may live only on disk, so a cache-only
  answer would silently omit precisely the biggest rows.
- ⚠ Deliberately narrow — not a general query surface. Reads still go through the chain DSL; this
  exists because compaction needs *all rows of a type*, which the relation arrays cannot promise
  for a type outside a maintained relation.

**The pass itself** is an ordinary write: decode → `getDataId` → `saveData`, so relation
maintenance, the epoch bump, lane batching and the pending journal all run as usual. Compaction is
maintenance, and re-emission is idempotent and convergent (§8.3), so a second write path with
different side effects would buy nothing and could diverge.

- ⚠ `deSerialize` needs a `getEntry` callback because store rows reference nested composites **by
  hash rather than inlining them** (§8.6). Materialising a row therefore means following those
  references back into the store; a missing child is a corrupt store and skips that row rather than
  aborting the pass.
- ⚠ **A row whose type has no `DataIdProvider` is skipped with a named line**, never re-keyed:
  without a provider `saveData` could only mint a *new* id, which would duplicate rather than
  re-emit. Tested.
- ⚖️ **The receipt is written after the work, not before.** §8.3 describes it as written by "a
  device intending to compact", but intent-first lets a crash mid-pass leave a receipt claiming
  completion, and every peer then skips work nobody did. Completion-first costs at most a redundant
  concurrent pass — which §8.3 already accepts as the price of having no election — and can never
  under-report. ⚠ This is a deliberate deviation from the settled text.

⚖️ **The epoch question (§8.3.4) resolves as benign.** Re-emitting through `saveData` can raise the
realm epoch, and that is *correct rather than a side effect to suppress*: the blocks really are
written at the current version, so a peer that cannot read them really should be fenced (§7.2/§7.3).
In practice the first ordinary write after an upgrade bumps the epoch long before a maintenance pass
runs, so it is a no-op in the common case and honest in the rest.

**E4-5 as built:** see §8.3.7.
⚠ Nothing calls `SchemaCompactor` yet: with no registered chain there is nothing to lift, and
`CompactionPolicy` says so by refusing.

#### 8.3.6 E4-4 as built (2026-08-02) — ⚠⚠ there is no floor to advance at runtime

**The slice was scoped as "the floor-advance consumer". It could not be built, because the floor is
not a runtime value.** `SchemaProvider.currentVersionFloors` **derives** it by walking the
*registered* migration chain, and §4's cut-off mechanism is literally "delete steps". Nothing
anywhere stores a floor: `schema_service_versions` records versions, `RealmSchemaEpoch` records
versions, the announce reply computes floors on the spot. So the floor moves when a developer
deletes steps in a PR, and any runtime code claiming to "advance" it would be moving a number
nothing reads.

⚖️ **What compaction and the roster actually produce is the *evidence* that the deletion is safe**,
which is a verdict, not an action — so E4-4 ships as `FloorRetirement.evaluate`, a pure function
answering *"may the steps below N go?"*.

**Two conditions, each necessary, and the reasoning is not symmetric:**

1. ⚠ **No device still runs below the cut** — because a device at `v < F` is still **producing**
   blocks at `v`, and after the deletion nobody could read them. The roster gates on *producers*
   here, which is the same MIN `RosterSchemaFloor` computes put to a different question. A stuck
   device is a **user** action: update it, or revoke it (ADR 0007), and the blocking reason says so.
2. ⚠ **History has been re-emitted at or above the cut** — the compaction receipt. Otherwise blocks
   below the cut are still the only copy of that state. A stale receipt is **our** action: run
   compaction, and the reason says that instead.

The cut is `min(rosterMin, receiptVersion)` — neither condition can be traded against the other.

⚠ **The receipt proves re-emission, not deletion.** Superseded blocks leave the relay on the
ordinary retention sweep afterwards, so the verdict says "the steps may go", never "the history is
gone" — a conservative operator may still want a retention window's grace before cutting.

⚠ **Unknown blocks, in both inputs.** An incomplete roster is never evidence of safety (same rule as
§8.3.1), and a service that has never been compacted blocks outright. The "already at the floor"
case — the state of every service in the repo today — reads as *nothing to retire*, deliberately
distinct from *safe to cut*.

**Where this leaves E4.** The phase's end state is not automation: it is a device-set-aware answer
to a question a human asks at cut-off time, plus the compaction that makes the answer become yes.
E4-5 (scheduling) is the only piece left, and by §8.3 it is explicitly *never correctness-bearing* —
so E4 is functionally complete once someone decides how often the maintenance pass should run.

#### 8.3.7 E4-5 as built (2026-08-02) — the maintenance pass, and where it stops

`SchemaCompactionMaintenance` + a `CompactSchema` op, closing E4's phase map.

**The sweep** walks every service that has a registered chain: derive its floor, read its receipt,
ask [CompactionPolicy], and hand the survivors to [SchemaCompactor].

⚖️ **Which types get lifted is narrower than "the service's types" — it is exactly the types the
chain names.** A type whose shape never changed decodes identically at every version, so its old
blocks are already readable and re-emitting them would be pure bandwidth. That also dissolves a
plumbing problem rather than solving it: `SchemaRegistry` has no service→types index
(`owningServiceOf` only goes the other way, §4.2), and the chain supplies the list for free.

- ⚠ **No chain ⇒ the sweep returns before touching the store.** With every floor equal to its
  current version — the repo's state today — a scheduled pass must cost nothing, not walk the store
  to discover there is nothing to do.
- ⚠ **An empty chain step is skipped too** (the `SyncService` v1→v2 shape): the version moved but no
  type reshaped, so there is no history to lift.
- ⚠ **Per-service failures are logged and swallowed.** One bad row must not stop the other services,
  and none of it may stop the app — which is exactly what "never correctness-bearing" licenses.
- The **second sweep is a no-op because the first left a receipt**, which is what makes a *recurring*
  maintenance item safe: without it every wake would re-emit the whole store. Tested.

**The op.** `CompactSchema` carries `HeadlessSafe` (⚠ `ScheduleService` fail-closed refuses to
schedule anything else, §6.4) alongside `IoOperation` + `TerminalOperation`, takes no parameters and
returns nothing — maintenance has no caller decision to express and no result anyone waits on.
`syncProxyImplementationFactory` gains an optional `compactSchema` lambda, so the op resolves to
*unavailable* until a wiring supplies it — the same shape the task-registry ops already use.

⚠⚠ **Where this deliberately stops: production DI does not supply that lambda yet, so no
`ScheduleItem` is registered.** The blocker is small but real — the sweep must enumerate rows by
type, and `Database` does not expose the `ObjectStore`, so `initSyncService` cannot build it from
what it currently receives. Wiring it means threading the store (or a row-source) through the
init/DI seam, which is a decision about that seam rather than about compaction.

⚖️ Left undone **on purpose**, on two grounds: nothing to compact exists yet (no chain), and E2j's
lesson is that wiring which *looks* done but never runs is worse than wiring that is honestly absent.
The mechanism is tested; lighting it up is one DI line and a `ScheduleItem`, to be added by whoever
first registers a real migration chain.

⚠ **Correction (2026-08-03).** This paragraph originally listed a third ground — that `ConstraintSpec`
enforcement was outstanding, so "idle + charging + unmetered" would not be honoured. **That is
false**: `WorkManagerScheduler.constraintsOf` maps every field (network / charging / idle / storage /
battery) onto WorkManager `Constraints`, and Control-Android binds it. The claim came from
`core-preference-service.md`'s Ph6 note about `BackgroundSyncScheduler` — a *different* consumer —
generalised into a blocker it never was. Recorded rather than deleted, because the mistake is the
recurring one on this track: a memory of a doc standing in for a look at the code.

⚠ What *is* platform-limited: **desktop and iOS bind `NoOpPlatformScheduler`**, so a registered
`ScheduleItem` never fires there. Compaction is Android-only until those platforms get a real
scheduler — worth knowing, since it means the floor can only be retired once the *Android* devices in
a realm have compacted, which the E4-4 roster gate already expresses.

⚖️ And even had constraints been unenforced, it would not have been a *gate*: the receipt makes the
second sweep a no-op, so an unconstrained run costs **one** pass at a poorly-chosen moment — wasteful,
never incorrect, which is the whole licence §8.3 grants this work.

**Both now built** — E4-4 as §8.3.6 (a retirement *verdict*: there is no runtime floor to advance)
and E4-5 as §8.3.7 (the maintenance sweep, minus its production DI line).

### 8.4 Suggested interface — `SchemaMigrationService`

Two clarifications fall out of sketching this, both worth locking in:

**(a) One transform artifact, two seams.** §8.1's store-migration step and §8.2's history
upcaster are *the same domain transform* — "reshape a vN value into its vN+1 shape" —
applied once to store rows at open and once to block payloads at read. Unify them into a
single **`SchemaMigration`**, registered once per service per bump. The three-artifact rule
of §8.2 collapses to two: **schema Δ + `SchemaMigration`, same PR.**

> *Naming note.* Event-sourcing literature calls the read-side half of this an
> **upcaster** (Axon's `EventUpcaster`; Greg Young's *Versioning in an Event Sourced
> System*) — the relay backlog is structurally an event log, and this doc keeps *upcast*
> as the prose verb for that seam (§8.2). The registered artifact is deliberately named
> the generic `SchemaMigration` instead: the identical transform also runs *eagerly* at
> store open, where "upcast" would mislead, and outside the event-sourcing niche the
> term explains nothing.

**(b) Migrations must carry legacy schema snapshots.** Positional decode needs the
*producing* field list (§2 fact 1) — the current schema cannot parse old bytes, period.
So each `SchemaMigration` embeds a frozen snapshot of the composite schemas as they existed
at its `fromVersion`, for every type it reshapes. This is the honest cost of keeping the
positional encoding (§5.1): old field lists live on in migration code instead of on the
wire. The snapshot is declared inline in the step (a `composite(...)` literal, not a
reference to the live schema object — the live one keeps moving).

A service migration is a **list of per-type `CompositeMigration`s** — one entry per
composite type the bump reshaped; most bumps touch 1–3 types out of a service's many:

```kotlin
// :core-common — one composite type's reshape for one version bump.
interface CompositeMigration {

    /** The composite type this migration reshapes (its stable schema id). */
    val typeId: String

    /**
     * Frozen snapshot of the type's schema AS AT fromVersion — the DECODE key
     * (positional decode needs the producing field list, §2). An inline
     * `composite(...)` literal, never a reference to the live schema object.
     */
    val from: CompositeSchema

    /**
     * Frozen snapshot AS AT fromVersion + 1 — the ENCODE key. Chain-integrity
     * invariant, verified at init (§ below): must structurally equal the NEXT
     * migration's `from` for this type, or the LIVE schema when no later
     * migration touches the type.
     */
    val to: CompositeSchema

    /**
     * Reshape one value decoded under [from] into its [to] shape. Pure.
     * null ⇒ drop the value — a deliberate decision, never a parse accident.
     * Hand-implementing this is the ESCAPE HATCH (type splits/merges, cross-type
     * moves); the normal authoring surface is the declarative field-mapping
     * builder below, which GENERATES this implementation from mapping rules.
     */
    fun migrate(old: Data, context: MigrationContext): Data?
}

/** The registered unit: one service, one bump, the types it touched. */
class SchemaMigration(
    val serviceId: String,
    val fromVersion: Int,               // output version is fromVersion + 1
    val migrations: List<CompositeMigration>,
)

/** What a migration may consult; deliberately narrow (no store writes, no IO —
 *  and deliberately NO CLOCK: a migration cannot synthesize timestamps, which
 *  makes the fake-default anti-pattern unrepresentable; see field mapping). */
interface MigrationContext {
    val context: Context                 // schema registry access for building new shapes
    val deviceId: String
}
```

**Dispatch.** The orchestrator resolves each stored row's / each delta's composite type
(the store's type info, or the 16-byte reference tag on the wire), looks up the step's
migration by `typeId`, decodes under `from`, calls `migrate`, re-encodes under `to`.
Types with no migration at a step pass through untouched — with one exception below.

**Field mapping — by id, with forced accounting.** The mapping between `from` and `to`
is computed **by field id**: every id present in both snapshots copies across
automatically (identity mapping, zero code — position is irrelevant to the *mapping*,
ids are stable strings; position only governs decode/encode, which the snapshots own).
Everything that does *not* map automatically must be spelled out, and the init check
enforces that **every field on both sides is accounted for**:

- **`from`-only field → must be explicitly `drop`ped.** Deletion-by-omission is rejected
  at init: a typo'd id in a snapshot would otherwise silently discard data instead of
  copying it (the `MoveFile` silent-loss precedent — explicit field handling is a
  data-loss trap unless the unaccounted case fails loudly). Data loss is always written
  down, never implied.
- **`to`-only *optional* field → starts absent.** There is deliberately **no
  default-value concept** in the machinery. Absent honestly means "this row predates the
  field", and consumers already handle absent optionals (that is what optional *is*). A
  synthesized default — `createdAt = now()` being the canonical offender — is fake data
  that lies to every future reader; `MigrationContext` has no clock precisely so this is
  unrepresentable.
- **`to`-only *required* field → must have an explicit `derive`**, computing the value
  from real old fields. If it cannot be derived from data that actually exists, the
  field cannot be required — make it optional. This is a schema-design rule the
  migration machinery enforces mechanically: *you cannot add a required field to a type
  with pre-existing rows without saying where its value comes from.*
- **Same id, changed type → must have an explicit `convert`.** An id match with a type
  mismatch never copies silently.
- **Renamed field → declare `rename(oldId, newId)`** so the accounting links the two
  ids (otherwise it looks like a drop + an underivable add). Note this is only needed
  when the type is being migrated *anyway* — see the rename corollary below.

**Chain integrity, checked at init.** For each `typeId` the registry orders its
migrations by version and asserts `step[i].to` structurally equals `step[i+1].from`, and
the last `to` equals the live schema. Hand-authored snapshots are the one place this
design can silently drift; the check turns drift into a fail-fast at proxy start, in the
same spirit as the floor/current completeness check.

**Nested types ripple.** Positional encoding inlines a nested composite's bytes into its
parent's (fact 1) — so reshaping `FilePath` changes the *bytes* of every `File` row,
even though `File`'s own field list didn't move. Authors still write only the `FilePath`
migration: the orchestrator decodes embedding rows under a **version-N overlay registry**
(live schemas overlaid with every `from` snapshot at N), recursively migrates nested
values, and re-encodes the parent. The consequence to be aware of: a nested reshape
rewrites every embedding row — the value-nesting cousin of §4's parent-field
global-fence caveat.

**A pleasant corollary: renames are free.** Field ids are never serialized (fact 5), so
a *pure* rename changes no bytes and needs **no migration at all** — only genuine shape
or semantic changes (add, remove, retype, split, merge, re-derive) do. The one caveat:
if the same bump *also* reshapes that type, the rename must be declared
(`rename(oldId, newId)`) so the field-mapping accounting links the two ids instead of
reading them as an unexplained drop plus an underivable add.

**What a concrete migration looks like** — hypothetical `file-service` v2→v3 that adds two
fields to `FileShare` (one required-derivable, one optional), drops one, and retires an
obsolete `LegacyPin` type. The builder generates the `CompositeMigration` (including its
`migrate` implementation) from the mapping rules; only the exceptional fields appear:

```kotlin
val FileServiceMigrationV2toV3 = schemaMigration(FileService.ID, fromVersion = 2) {

    migration(
        typeId = FileShare.ID,
        from = FILE_SHARE_AT_V2,   // inline literal: composite(FileShare.ID, fields = { string(Fields.KEY); … })
        to = FileShare.Schema,     // v3 == live today; becomes an inline literal the day v4 reshapes it
    ) {
        // every id present in both snapshots copies automatically — nothing to write

        drop(FileShare.Fields.LEGACY_FLAG)             // deleted field: acknowledged, never implied

        derive(FileShare.Fields.KIND) { old, ctx ->    // new REQUIRED field: derived from real data
            if (old.read(FILE_SHARE_AT_V2, FileShare.Fields.PATH).isFolder()) ShareKind.FOLDER
            else ShareKind.FILE
        }

        // new OPTIONAL field CREATED_AT: not mentioned — starts absent.
        // Absent = "predates the field"; there is no now()-style fake default.
    }

    dropType(typeId = LegacyPin.ID, from = LEGACY_PIN_AT_V2)   // whole type retired ⇒ migrate returns null
}
```

Init rejects, per the accounting rules: an unmentioned `from`-only field, a required
`to`-only field without `derive`, a retype without `convert`, an id that appears on
neither side of a `rename`. The raw `migrate` override remains available for the shapes
a per-field mapping can't express (splitting one type into two, merging, moving a value
across types).

(`to = FileShare.Schema` referencing the live schema is legitimate *only* while the type
has no later migration — the init check enforces that the day v4 reshapes `FileShare`,
this line must be frozen into a `FILE_SHARE_AT_V3` literal or the proxy refuses to
start.)

### 8.5 E2a as built (2026-07-25) — the artifact, builder, registry, codec

Lands §8.4's registered artifact and everything below it; the store-open seam
(E2b), history upcast (E2c), epoch fencing (E2d) and relay-gate split (E2e)
remain. `org.tenshu.core.common.schema.migration` in `:core-common`:

- **`CompositeMigration` / `SchemaMigration` / `MigrationContext`** exactly per
  §8.4 (`MigrationContext` is a concrete class, and deliberately clock-less).
- **`schemaMigration(serviceId, fromVersion) { migration(typeId, from, to)
  { drop / derive / convert / rename } ; dropType(...) }`** — auto-copy by id;
  forced accounting enforced at resolution with the offending field id in
  every message; the generated `migrate` builds in `to`-order via
  `context.create`; a derive/convert returning null for a required target
  fails at migrate time. `dropType` reuses `from` as its `to` (never encoded).
  ⚠ **Added in CR-2 B6: `dropProperty(typeId, propertyId)`** — retires a *named-map* property slot,
  which the positional mapping cannot reach (the codec preserves properties verbatim) and which
  `DbEntryMapper` throws on once unregistered. Stripped by `StoreSchemaMigrator.runStep` in the
  step's own transaction; **store seam only**, not the history upcast
  (`content-plane-cr2-b6-spec.md` §6.2).
- **`SchemaMigrationRegistry`** — `verify(live)`: per-service fromVersion
  contiguity **and** chain-reaches-current (one check beyond the §8.4 list:
  dead unreachable steps are the same non-contiguity class), per-type
  `step[i].to == step[i+1].from` + last-`to` == live (structural equality =
  ordered `(field id, base type id, isOptional)` triples), and the
  **nested-reshape rejection** — a migrated type inlined as a composite field
  of another live type fails loudly ("not yet supported") instead of silently
  mis-migrating; reference-typed fields are exempt (16-byte ids, not inline
  bytes). `floorFor` derives the §4 floor; `stepsFor` orders a chain window.
- **Module hook**: `ModuleConfiguration.schemaMigration(...)` +
  `SchemaRegistry.schemaMigrations()`; `currentVersionFloors` now derives
  floors from registered chains (== current fallback preserved).
- **`MigrationValueCodec` + `SchemaOverlayProvider`** (internal): decode under
  the frozen `from` via an overlay `SchemaProvider`/`DataFactory` — resolves
  wire tags even for types retired from the live registry, and materialises
  values as `SnapshotComposite`s so `getField(id)` maps positions under the
  OLD field order — then re-encode live. Proven with real
  `BinarySerializer` bytes in / live-registry parse out.
- **⚠ For E2b**: multi-step chain composition must NOT feed a step's output
  (a live-schema dto) to the next step directly — wrap intermediates as
  `SnapshotComposite(step.to, fields)` or thread a per-step overlay context;
  single-step is correct by the last-`to` == live invariant. `verify` runs
  its nested-reshape scan only when handed the concrete `SchemaRegistry` —
  wire it at proxy init.
- 26 tests across builder accounting / chain integrity / floors / byte
  round-trips (`SchemaMigrationBuilderTest`, `SchemaMigrationRegistryTest`,
  `MigrationValueCodecTest`).

The orchestrator — proxy-internal machinery in `:core-common` beside the stores it
migrates, **not** a wire-exposed schema service (nothing about it crosses IPC or the
relay; the name follows the platform convention, the surface does not):

```kotlin
class SchemaMigrationService(
    private val store: ObjectStore,
    private val migrations: SchemaMigrationRegistry,   // populated via module registration
    private val journal: MigrationJournal,       // the DataIdMigrator marker pattern, extracted
) {

    /**
     * Seam 1 — replaces the wipe gate at store open (`SqliteObjectStore.kt:510`).
     * Reads the recorded store version(s), runs each applicable migration chain over
     * the rows it owns, rewrites in place, bumps the recorded version. Journaled
     * per step (marker → rewrite → clear) so a crash resumes idempotently.
     * O(rows), metadata-only (§8.1); [progress] feeds the one-time progress UI.
     */
    fun migrateStoreAtOpen(progress: (MigrationProgress) -> Unit = {})

    /** Re-runs crashed migrations. Once at proxy init, before first store read —
     *  the `resumeIncompleteMigrations()` posture inherited from `DataIdMigrator`. */
    fun resumeIncompleteMigrations(): Int

    /**
     * Seam 2 — history upcast (§8.2). Called by `SyncProcessor` after decrypt,
     * before materialisation, for any block whose E0 stamp is below current.
     * Composes the chain stamp→current; null ⇒ block dropped BY DECISION
     * (logged), replacing today's silent parse-to-null-and-drop.
     */
    fun migratePayload(payload: ByteArray, stamp: BlockVersionStamp): Data?
}
```

Registration rides the same module hook as §6.8's version declaration — the compiler-
enforced pairing is the point:

```kotlin
// inside the service's existing tModule { } block (§4.1.1). The service's version
// comes from its ServiceSchema (§4); the readable floor is DERIVED as the lowest
// contiguous `from` here — cutting off old formats = deleting migration steps. A
// non-contiguous chain (v2→v3 registered, v3→v4 missing, schema at v4) fails at init.
schemaMigration(FileServiceMigrationV2toV3)
schemaMigration(FileServiceMigrationV3toV4)
```

Notes on the recorded store version: today it is one global int (`PRAGMA user_version`).
Under per-service versions it becomes a small vector; keep `user_version` as the core
version (preserving the existing fresh-vs-stale probe) and record the per-service vector
in a meta row inside the store itself. Pre-E2 stores (recorded version below the last
wipe-era format) still take the wipe path — the migration chain begins at the version E2
ships as.

### 8.6 E2b as built (2026-07-25) — the store-open seam (JVM `SqliteObjectStore`)

**Production wiring landed 2026-07-26**: the Android + desktop
`PlatformProxyModule` stores and the hub's `HubRuntime` store open with a
`StoreSchemaMigrations` config (`deviceId` from the secure-storage
`sync_device_id`, empty pre-identity — a store with rows old enough to
migrate always has an identity); `verify` was hoisted to run on EVERY
configured open, stamp-current path included, so broken chains refuse the
proxy start rather than first failing on the user whose store needs them.
Smoke gate PASSED with the config live.

Lands seam 1 of §8.4 for the JVM store; history upcast (E2c), epoch fencing (E2d),
relay-gate split (E2e), and the iOS `FileObjectStore` / JS `IndexedDbObjectStore` twins
(which keep the wipe for now) remain. `org.tenshu.core.common.schema.migration` +
`…proxy.db` in `:core-common`:

- **⚠ Ground fact this slice corrected**: store rows are **not** `BinarySerializer`
  bytes. A row is an `ObjectStore.DbEntry` — the composite's **string** type id (the
  `type` column, not the wire's 16-byte tag), a **positional `DbValue` list** in schema
  field order, and a `properties` map (entity relations; string-keyed, order-free).
  Nested composites/lists are **never inlined** — each lives in its own row referenced
  by `LongValue` hash — so the wire's nested-reshape ripple does not exist at this seam,
  while the positional hazard (§2 fact 1) transfers verbatim. There is no entity
  envelope: entity and composite rows share one shape, `properties` preserved verbatim.
- **`StoreRowMigrationCodec`** — the row twin of `MigrationValueCodec`: decode slots
  under the frozen `from` (a `SnapshotComposite`), run the registered
  `CompositeMigration`, re-encode in `to` order. Scalar fields decode to real values
  (derive/convert can read them); composite/list/genericData/reference slots surface as
  opaque pass-through wrappers (their content migrates in its own rows) — a migration
  cannot mint a fresh composite/list field value at this seam (fails loudly).
  ⚠ The §8.5 multi-step hazard is closed structurally: `migrate` runs under an overlay
  context whose factory materialises migrated types as `SnapshotComposite`s bound to the
  step's `to`, and each step runs as a **full store pass** (step N's committed rows are
  step N+1's input). Escape-hatch `migrate` impls must build outputs via
  `context.context.create`.
- **`StoreSchemaMigrator` + `StoreMigrationJournal`/`StoreMigrationPort`** — the §8.4
  orchestrator over a minimal store port; journal = the `DataIdMigrator` marker shape in
  a `schema_migration_journal` table inside the same DB. Transaction discipline is the
  idempotency proof: begin-marker commits alone BEFORE any rewrite; all rewrites +
  marker-clear + version-bump commit as ONE transaction — a surviving marker therefore
  implies pristine `from`-shaped rows, and the re-run is deterministic. `dropType` rows
  are deleted **and their `TList` membership entries swept** in the same transaction.
- **Recorded versions** — `schema_service_versions(service_id, version)` beside the
  journal; `PRAGMA user_version` stays the global probe (mismatch still wipes, and the
  wipe now also drops both E2b tables). Absent table = pre-E2b/fresh store → stamp
  current, touch no rows (sound because pre-E2b every reshape owed a `user_version`
  bump, HR #9). Below-floor recorded version, or recorded > declared (newer-code
  store), → wipe + stamp. Both tables are additive and self-creating ⇒ **no
  `TENSHU_STORAGE_FORMAT_VERSION` bump owed** by this slice.
- **Wiring** — `SqliteObjectStore.open(…, migrations = StoreSchemaMigrations(context,
  schemas, deviceId)?)` runs resume + migrate between the `user_version` gate and cache
  hydration, synchronously on the store's own connection (writer thread not yet
  started). `SchemaMigrationRegistry.verify(live)` runs here (first consumer of the
  chains; every open except the stamp-current path). `null` config (every current
  production call site — the `PlatformProxyModule` DI wiring is the follow-up slice)
  keeps today's wipe-only behaviour. No-drift open ≈ three tiny SELECTs, zero row
  touches, no journal table ever created.
- 7 tests (`SqliteObjectStoreMigrationTest`): v1→v2 reshape + byte-identical untouched
  rows, multi-step v1→v2→v3 (intermediate `to` ≠ live), dropType + membership sweep,
  crash-marker resume, no-drift no-op, `user_version` wipe re-stamp, pre-E2b stamp.

### 8.7 E2c as built (2026-07-26) — the lazy history upcast (§8.2 seam)

Lands seam 2 of §8.4: blocks whose E0/E1 stamp is behind decode under the *producing*
schemas and upcast to current before materialization, in the one shared decode funnel.
Read-side only — no wire/schema/store change, no format bump. Epoch fencing (E2d) and
the relay-gate split (E2e) remain.

- **`PayloadUpcaster`** (`org.tenshu.core.common.schema.migration`, `:core-common`) —
  builds its `SchemaMigrationRegistry` from `SchemaRegistry.schemaMigrations()` and runs
  `verify` at construction (idempotent with E2b's store-open verify; wirings without a
  configured store still fail fast). `upcast(payloadBytes, stampBytes)` returns a sealed
  `Current(payload, droppedValues)` / `Dropped(reason)`; malformed bytes still **throw**
  (the GR-7 trial-decrypt loop needs parse failures non-fatal).
- **Fast path**: only services with registered chains are judged; if none is behind per
  the stamp, the ordinary live parse runs. A service **absent from the stamp** did not
  exist on the emitter ⇒ its types can't be in the payload ⇒ nothing to migrate. A stamp
  **ahead** of us is deliberately not judged (old-never-reads-new = E2d's pull fence).
- **Slow path**: one overlay parse of the whole tree — the payload envelopes carry
  entity/property values through **generic `Data` fields** (inline 16-byte tag), so a
  from-snapshot `SchemaOverlayProvider` decodes everything correctly (envelopes live,
  migrated values as `SnapshotComposite`s under OLD field order) — then a **uniform
  by-type-id walk** (no per-payload-branch knowledge): entities re-assemble their
  id-keyed field map under the `from` snapshot, lists migrate per element, composites'
  fields (incl. `genericData` slots) recurse. Chain steps run value-to-value with
  intermediates bound to each step's `to` (the shared `OverlayTContext` /
  `toBoundMigrationContext` extracted from `StoreRowMigrationCodec` into
  `MigrationOverlays.kt` — one artifact, both seams); the **final** step builds via the
  live context so the output is a registered dto the mergers/store can hold.
- **`HistoryDecodeOverlay`** — the wire seam's extra duty the store seam never had:
  16-byte tags and `Reference`s of ids only the *producing* schema knew (a `drop`ped
  field's map key on an old entity, a `dropType`d type's tag/entity-type reference)
  resolve through the window's snapshots.
- **Drop-by-decision** (replaces silent parse-to-null): below-floor stamp ⇒ whole block,
  logged with service/versions; `migrate` returning null ⇒ that value — a list element
  (a delta in a batch) is removed and the rest applies, a required field's value (a
  `DataBlockPayload.value`) takes the block. **Core-only stamps** (empty `stampBytes` —
  E0-era/test convenience; production emits the full vector since E1) are dropped BY
  DECISION while any chain is registered: they cannot prove the producing per-service
  versions, and guessing is exactly what this design exists to end.
- **Wiring** — `SyncProcessor.decryptAndParseCipher` routes through a **lazy** upcaster
  built only when `schemaMigrations()` is non-empty: with no chains registered (today's
  production reality) the stamp is never parsed and ingest is byte-identical pre-E2c.
  `MigrationContext.deviceId` = `syncManager.currentRealmContext()?.deviceId ?: ""`,
  read per invocation (empty only while locked, when nothing decrypts anyway).
- **Inherited bounds** (KDoc'd): statically-typed nesting of a migrated type is already
  rejected by `verify` ("nested reshapes not yet supported") — only generic-`Data`-
  carried values ever reach the walk; migrating a payload *envelope* type itself would
  break pre-E1 peers and stays lockstep territory.
- 9 tests (`HistoryUpcastTest`, `:sync-service-proxy`): multi-step v1→v2→v3 composite,
  single-step v2, v1 **entity** through the field map incl. the unregistered retired
  field reference, below-floor drop, current-stamp fast path, `DeltaBatchPayload`
  per-value upcast (old + retired + untouched deltas in one batch), plus verdict-level
  reason assertions (below-floor, core-only, dropType).
- ⚠ For E2d/§8.3: the upcaster reads whatever the stamp says per block — the **fenced
  device's pending-block journal regeneration duty (§8.3) is unchanged** (a late push
  below the floor is now *visibly* dropped by the receiver, not silently garbled), and
  the epoch fence must gate *pulls* before old code ever sees ahead-stamped blocks,
  which this seam deliberately passes to the live parse today.
  **Closed by E2f — see §8.8.**

### 8.8 E2f as built (2026-07-27) — the fenced device's outbound tail

Lands §8.3's last bullet, the sharper of the two gaps §7.7 left open. New
`PendingBlockRestamper` (`:sync-service-proxy`, beside `SchemaPullFence`), plus one
call in `SyncScheduler.tickOnce`.

- **The gap.** A block's stamp is sealed into its ciphertext at *enqueue* time
  (`SyncObserver.blockStamp`) and never touched again — a drain cannot even see it. So
  a device that queues writes, then updates, then drains, pushes blocks stamped at the
  **old** vector. §7.3's fence turns that from a corner into the normal case: a fenced
  device is pull-fenced but **push-allowed by design**, so it keeps queuing for exactly
  as long as it is behind. Harmless today (peers upcast on read, §8.2) — and data loss
  the moment E4 compaction advances the floor past the stamped version, because the
  same push then lands as bytes nobody can decode.
- ⚠⚠ **In-place restamp, NOT "regenerate from the migrated store".** §8.3's wording is
  not reachable as written: a queued block addresses its entity only as
  `targetEntityIdHash` — the opaque hash the relay indexes by — which cannot be
  inverted back to a `DataId`, so the migrated row cannot be found to re-emit from. The
  in-place path is also strictly more faithful: it runs the **same `PayloadUpcaster`**
  (E2c) a receiving peer would run on that block, just locally before the push instead
  of remotely after it, so the block keeps its identity (`blockId` — which the relay
  dedupes on — plus `hlc` and `targetEntityIdHash`) and its *point-in-time* content
  rather than being silently replaced by a newer snapshot. Zero new migration
  machinery: the whole slice is the existing seam pointed the other way.
- **Placement:** `SyncScheduler.tickOnce`, immediately after the `enabledLatch` edge
  and **before `drainExemptBlocks()`** — i.e. before anything can push. The enabled
  guard is what makes realm keys available; a locked / not-yet-rehydrated realm
  **defers without consuming the one-shot** (otherwise the first cold-start tick would
  skip the repair forever — pinned by a test).
- **One-shot per process, and inert in most wirings.** The declared vector is fixed for
  the process, so a queue clean once stays clean (everything enqueued afterwards is
  sealed at the current stamp by construction). With no registered migration chains the
  pass marks itself done without decrypting anything — there is nothing a stale stamp
  could be upcast *from*. ⚠ Note this is **no longer** production's state: E2d-1's
  `SyncService` v2 + empty migration means chains *are* registered, so the pass is live.
- **Cost is one AEAD decrypt per queued block, once.** The stamp is inside the
  ciphertext (HR #1) so it cannot be read any other way — but a matching stamp stops
  right there: no parse, no re-serialize, no re-encrypt, no store write.
- **Drop semantics mirror E2c.** A below-floor stamp (or a migration returning null)
  yields `Drop`: the block is removed from the queue with a named `WARN` line, because
  it could only ever have been pushed in a shape no peer can decode. ⚠ That *is* a lost
  write, and deliberately so — the alternative is pushing bytes that are guaranteed
  garbage. It is only reachable once a chain's floor has moved past a block that sat
  queued across that whole window; E4 compaction is the general repair.
- ⚠ **An undecryptable block is left queued, never discarded.** The classic case is an
  orphaned-owner block from a realm this device left, which `drainPendingBlocks`
  quarantines on its own terms. Restamping must not become the thing that destroys a
  block it merely failed to *read*.
- **Scope: the personal queue only** (`PENDING_BLOCKS`; `PENDING_EXEMPT_BLOCKS` was deleted 2026-10-29),
  matching `SchemaPullFence`'s v1 scope — each shared realm's queue is sealed under its
  own `Kr` and belongs with the namespaced-epoch-key work. `PENDING_LANE_DELTAS` needs
  nothing: it holds **plaintext** `SyncDelta` store rows (pre-seal staging), so the
  E2b store-open seam migrates them as ordinary rows and the lane replay re-seals them
  at the current stamp — handled by construction.
- **No wire, schema, store or format change** ⇒ no `TENSHU_STORAGE_FORMAT_VERSION`
  bump, no relay redeploy, no threat-model delta (nothing new crosses a trust boundary:
  a block is decrypted and re-encrypted under the same realm key, on the device that
  wrote it).
- **Tests:** `PendingBlockRestamperTest`, in `HistoryUpcastTest.kt` so it shares E2c's
  synthetic chain fixture — same service, same v1→v2→v3 chain, opposite direction.
  Seven cases: stale block resealed (identity + HLC + routing hash preserved, stamp now
  this runtime's, payload rebuilt at the live shape with both derives run); current
  stamp left byte-for-byte (not even re-encrypted); below-floor dropped; undecryptable
  left queued; one-shot; locked-realm defer-then-run; chain-less registry fully inert.

---

### 8.9 E2j — ⚠⚠ the format-version bump defeats the migration it ships with (found 2026-07-30)

**The E2 store-migration path has never done real work in production, on any platform, and cannot
until one policy changes.** Found while sequencing the `Device`-row-regeneration tech-debt entry,
whose L1 lever is "close the core-version wipe" — the gate turns out to fire on *every* reshape,
not on a rare core bump.

**The mechanism.** `SqliteObjectStore.open` runs its gates in this order:

1. `PRAGMA user_version != TENSHU_STORAGE_FORMAT_VERSION` → `DELETE FROM objects`, **and drop the
   `schema_service_versions` + `schema_migration_journal` tables** (§8.6, deliberately: they
   describe rows that no longer exist).
2. *then* `StoreSchemaMigrator.resumeIncompleteMigrations()` + `migrateStoreAtOpen()`.

Now read §11's own decision heuristic: *"a wire/store reshape bumps `TENSHU_STORAGE_FORMAT_VERSION`
**and** ships its `SchemaMigration` in the same PR"* (House Rule #9). Those two halves cancel: the
bump wipes the rows and the recorded per-service versions, so by the time the migrator runs there is
nothing left to migrate and it stamps a fresh store. **A reshape shipped per HR #9 is byte-identical
in outcome to the pre-E2 wipe.**

⚠ Not a bug, and not the store seam's fault — `StorageFormat.kt` states the pre-release posture
outright ("we owe no installed base continuity: a format change is a clean slate, not a
migration"), and E2b's §8.6 says `user_version` "stays the global probe (mismatch still wipes)".
E2 built the machinery ahead of the need. The evidence that it is still ahead of the need: **the
only `SchemaMigration` registered anywhere in the repo** is
`schemaMigration(SyncService.ID, fromVersion = 1) {}` — an intentionally *empty* body landed by
E2d-1 to exercise the plumbing on real input.

**Corrected platform picture** (the split is per *store implementation*, not per language runtime —
"it only works on KMP" is the wrong frame, since Android and iOS are both KMP):

| piece | where | iOS route-1 | JS |
|---|---|---|---|
| `SchemaMigration` / registry / `PayloadUpcaster` / `StoreSchemaMigrator` | `core-common/commonMain` | ✅ runs | ✅ runs |
| `SchemaPullFence`, `PendingBlockRestamper` | `sync-service-proxy/commonMain` | ✅ runs | ✅ runs |
| the **open seam that calls** the migrator | `SqliteObjectStore` in `jvmMain` — a hand-created source set spanning **androidMain + desktopMain** (+ the JVM hub) | ❌ `FileObjectStore` wipes on any format mismatch | ❌ `IndexedDbObjectStore` wipes |

So iOS is missing only the seam, not the machinery — and adding it today would buy **nothing**,
because the format bump wipes ahead of it on every platform equally.

#### The sequencing this implies

⚠⚠ **The flip has a prerequisite that must land first, or it converts a safe failure into an unsafe
one.** Today a reshape without a migration produces a *spurious wipe*: annoying, never wrong.
After the flip it would produce **silent garbling** — positional composites, §2 fact 1 — which is
the exact class HR #9 exists to prevent. What stands between the two is drift detection, and
`SchemaMigrationRegistry.verify` only *half* covers it: it catches "the live schema moved without a
new migration step" **only for types that already appear in some registered migration** (it compares
the last step's frozen `to` against live). A type with no snapshot history has nothing to compare
against, so reshaping it passes verify silently. That is nearly every type in the repo.

1. **E2j-1 — schema-shape baseline.** Give every composite a pinned structural shape so a positional
   reshape without a version bump fails loudly at init/test rather than at a peer's parser. The
   fingerprint primitive already exists and is exactly right: `structuralShape()` =
   ordered `(field id, base type id, isOptional)`. Cheap, pure `commonMain`, **no runtime behaviour
   change** — it only adds a check. This is the slice that makes the flip safe.
2. **E2j-2 — retire `TENSHU_STORAGE_FORMAT_VERSION` as a wipe trigger.** Per-service versions become
   the store gate (they already exist, are already recorded, and already drive floors); the format
   version keeps its other jobs, and ⚠ they are not cosmetic — it is **`:core`'s slot in the
   `VersionVector`** (`currentVersionVector(coreVersion = TENSHU_STORAGE_FORMAT_VERSION)`), which is
   what E2i's reader-side fence judges as `coreVersion`, plus the fresh-vs-stale probe for pre-E2
   stores and the smoke gate's lockstep beacon. So this slice removes one *consumer* of the constant,
   not the constant. **House Rule #9's bump clause is replaced by "ship the schema Δ with
   its `SchemaMigration`"** — the amendment §11 already half-announces but whose text was never
   changed (E2e amended only the relay-redeploy half).
3. **E2j-3 — the iOS `FileObjectStore` + JS `IndexedDbObjectStore` twins.** Worth doing *after* 2,
   when the wipe is the exception rather than the rule and these are the only platforms still taking
   it. `StoreSchemaMigrator` is already common; each twin owes a `StoreMigrationPort` adapter over
   its own storage (blob directory / object store) plus a place to record the version vector.

⚖️ **Recommended order: 1 → 2 → 3, and do not start at 3.** ⚠ Do **not** do 2 without 1.

#### E2j-1b — ⚠⚠ constants were the hole in it (2026-08-31)

The baseline pinned **composites**. A `TConstant` has no positional layout, so it was invisible
here — and that made adding a member to a sealed family (a new `FileCategory`, a new
`PairingFailure`, a new `SyncPolicy`) a change to the **wire vocabulary** that every guard passed
and no version bump was required for.

⚠⚠ That matters because House Rule 9 offers this baseline as the reason an author does not have to
be sure: *"Unsure whether you reshaped anything? **You do not have to be.**"* For a constant-only
change that reassurance was **false**.

**Proven, not argued.** A `FileCategory.Spreadsheet` constant, added *and registered*, with no
version bump: `SchemaShapeBaselineEnforcementTest` and `PropertyKindBaselineEnforcementTest` both
passed. ⚠ A first attempt added the `object` without the module's `constant(…)` registration and
also passed — for the wrong reason, since an unregistered constant is not in the vocabulary at all.
The registration is what makes the experiment mean anything.

**What the hole let through is quiet.** A peer sends an id this build cannot resolve, `BinaryParser`
throws `UnknownTypeTagException`, and because that is an `IllegalStateException` raised *outside*
the executor's own `try`, it reaches an app as a failed read — which every repository reports as
**"Control is unreachable"**. The proxy is fine and the client is out of date, which is precisely
what SK-1…SK-4's skew surface exists to say and never got the chance to.

**The fix**: `render` and `check` both read `pinnedShapes()` = composites + constants, a constant's
"shape" being `constant@<familyId>`. 34 constants joined the file (471 → 495 lines).

- ⚠ The value is the **parent id**, not filler: the diff then reads *"a new member of
  `FileCategory`"* rather than a bare id, and a constant that changes families is a shape change
  rather than a silent retire-plus-add.
- ⚠ The on-disk format is **unchanged** — still `typeId|serviceId|version|shape`, four fields — so
  every existing line parses exactly as before. The constant marker is read back out of the shape.
- ⚠⚠ **`check` had to move too, not just `render`.** A `check` still reading composites alone would
  report every pinned constant as `Retired` on the very first run — the file saying one thing and
  the check looking at another. Caught by reading; now pinned by
  `anUnchangedConstantSetReportsNothing`, and re-introducing that one-line drift turns **six** tests
  red.
- ⚠⚠ `Unpinned` carries `isConstant`, because the two owe **different instructions**. A new
  composite is safe — nothing older can be handed one — so "regenerate the baseline" is right. A new
  constant needs the *version bump*, so the announce refuses the pairing. Telling an author to
  regenerate would have let the hazard through with the guard green.

#### E2j-1 as built (2026-07-30)

`SchemaShapeBaseline` in `core-common/…/schema/migration` + `SchemaShapeBaselineEnforcementTest`
in `:system-test-utils`, with **308 composite shapes pinned** in
`system-test-utils/src/test/resources/schema-shape-baseline.txt`. No production behaviour change —
nothing here runs outside tests.

- **The fingerprint is the chain check's own triple.** `shapeFingerprint()` renders exactly what
  `structurallyEquals` compares (`structuralShape()` went `private` → `internal` for that reason):
  a reshape must not pass one check and fail the other. Rendered **readably rather than hashed** —
  for a positional format, *which* field moved to *which* position is the entire review signal, so
  the pinned file is a diff a reviewer can actually read (`tenshu/identity/device` pins
  `deviceId, name, publicKey, isAuthorized, admittedAtEpoch, admittedByDeviceId?` in order).
- **Four verdicts, only one of them hard.** `ReshapedWithoutBump` fails; `StaleBaseline` (shape moved
  *and* the version bumped) reads as "regenerate the file"; `Unpinned` / `Retired` cover additions and
  removals. `Retired` is opt-in (`expectExhaustive`) because a narrower per-app registry legitimately
  knows fewer types.
- ⚠ **Deliberately does not demand a `SchemaMigration`.** Op parameter objects are client-serialized
  composites too (§2 fact 3), so reshaping one breaks old *callers* mid-parse and owes a bump — but
  owes no *store* migration, because nothing persisted it. Separating "stored row" from "wire-only"
  is E2j-2's problem, where the store gate starts depending on the answer.
- ⚠⚠ **Finding: the registry holds far more than data types.**
  `ModuleConfigurationImpl.registerFields` synthesises a placeholder schema for **every field id it
  ever sees**, and relation/service/trait schemas are composites as well — all with empty `values`.
  Pinning them added thousands of content-free lines and buried the diff. The baseline therefore
  keeps only composites that **have** a positional layout, which is sound because a fieldless
  composite has no decode plan to mis-parse, and a type gaining its first field still surfaces as
  `Unpinned`. Regression-tested (`theSyntheticPerFieldSchemasAreNotPinned`).
- ⚠ **Coverage is the service graph `:system-test-utils` wires**, not the whole repo:
  `fit`/`timeline`/`logging`/`ingest`/`place`/`map-region`/`storage` schemas are not registered there,
  so they are unpinned. Widening it is a dependency change on that module — deliberately not bundled
  into this slice. Everything registered *is* judged exhaustively, removals included.
- Regeneration is one command (`-Dtenshu.schemaBaseline.regenerate=true`) and **fails on purpose**
  after writing, so a regeneration is never silently green.
- ⚠ Environment note for anyone reproducing: the build needs **JDK 17** (`jvmToolchain`) *and*
  **JDK 25** (`:control-desktop`'s FFM toolchain, resolved at *configuration* time, so a missing 25
  breaks every task in the build, not just that module). Toolchain auto-provisioning is
  proxy-blocked in CI sandboxes; install both JDKs first.

#### E2j-2 as built (2026-07-30) — the gate migrates instead of wiping

The change is **one gate in `SqliteObjectStore.open`**, and it is small because the machinery
it unblocks was already complete: `StoreSchemaMigrator` has always had the per-service
below-floor and written-by-newer-code wipe paths. All that was wrong is that the format-version
wipe ran *first* and dropped `schema_service_versions` + `schema_migration_journal` with it.

- **`:core` is now addressed like a service.** New canonical `CORE_SERVICE_ID` ("tenshu/core")
  in `:core-common`; `AnnounceSessionImplementation`'s copy aliases it rather than repeating the
  literal. ⚠ `SchemaFenceStatus.CORE_SERVICE_ID` (`:sync-service`, wire-facing, in a module
  `:core-common` cannot depend on) still holds its own copy — keep the two in step.
- **The gate asks the per-service question.** `StoreSchemaMigrations.coreReadableFloor()` =
  `floorFor(CORE_SERVICE_ID, TENSHU_STORAGE_FORMAT_VERSION)`, i.e. **derived from the registered
  core chain, never declared**. Wipe iff `recorded < coreFloor || recorded > current`; otherwise
  stamp current and let the migrator run. A wipe now logs which range it fell outside, matching
  `StoreSchemaMigrator`'s "data loss is written down, never implied" posture.
- ⚖️ **The flip is a no-op until a core reshape ships a migration.** With no core chain the floor
  equals the current version, so every mismatch still wipes — the existing
  *"a `user_version` mismatch still wipes"* test passes unchanged and becomes the regression
  guard for that. What actually changes behaviour is the **policy**: a service-owned reshape now
  bumps its *service* version and must not touch `TENSHU_STORAGE_FORMAT_VERSION`.
- **House Rule #9 rewritten** in `CLAUDE.md` to the two-case form (service-owned → bump the
  service + register the step; core-owned → bump the format version + register a core step),
  with the reason the old wording existed kept, because it was correct for the gate it described.
  ⚠ The failure mode when someone forgets the migration is a **loud wipe** (below the derived
  floor), never a silent mis-parse — that, plus E2j-1's baseline catching a reshape with no bump
  at all, is what makes the flip safe.
- `migrations == null` (unconfigured stores, tests, pre-E2b callers) keeps today's exact
  behaviour by construction: the floor defaults to the current version.
- Tests: three new cases in `SqliteObjectStoreMigrationTest` — below-current-but-inside-the-floor
  **migrates and rows survive**, below-floor wipes, and written-by-newer-code wipes. 10/10 green,
  plus 11/11 `SchemaShapeBaselineTest`.

⚠ **Still owed after this slice**: E2j-3 (the iOS `FileObjectStore` + JS `IndexedDbObjectStore`
twins still wipe unconditionally), and the first *real* core migration — until one exists, the
JVM store's new path is exercised only by tests.

#### E2j-3 as built (2026-07-30) — ⚠⚠ E2j-2 had opened a hole on the twins

**This slice changed character during implementation.** It was scheduled as the capability
slice — teach iOS and JS to migrate. It shipped as a **safety fix**, because E2j-2's policy
move made them *less* safe:

> A service-owned reshape now bumps its service version and deliberately leaves
> `TENSHU_STORAGE_FORMAT_VERSION` alone. That is only safe for a store that **looks at**
> per-service versions. Both twins gated on the format version alone — so after E2j-2 a service
> reshape would have left them neither wiping **nor** migrating, handing v-old rows to new code.
> Positional composites (§2 fact 1) make that **silent garbling**: the exact failure class House
> Rule #9 exists to prevent, re-opened on two platforms by the change that made the third safer.

⚖️ Worth recording as a pattern, not just an incident: **a policy change that makes one
implementation safer can silently unmake an invariant on its twins.** The store gate was
duplicated three ways, so "the rule" only ever lived in the JVM copy. Which is why the fix is
not three edits but one shared rule:

- **`StoreVersionGate` (`commonMain`)** — `coreVersionReachable`, `serviceVersionDrift`,
  `storeMustWipe`, `storeWipeReason`. All three stores judge by these; the JVM store's E2j-2
  inline core check became a call to the same function.
- ⚖️ **One deliberate asymmetry, and it is the whole point:** drift the JVM store **migrates** is
  a **wipe** on a store with no migration seam. A store that cannot migrate has two honest
  options and mis-parsing is not one — and the wipe is a *resync*, not data loss, because the
  relay holds the history (`retention-quota-model.md` tier 2).
- Both twins now record the per-service vector beside the format version — an iOS
  `.serviceversions` sidecar, a JS `meta` key — **stamped last**, so a crash before it just
  re-runs a harmless gate on the next open. Absent record = `Unrecorded` = stamp current, touch
  no rows: the pre-E2b posture, unchanged.
- `migrations = null` keeps today's behaviour by construction on every store.

⚠ **Testing is asymmetric and the asymmetry is the risk.** The rules are unit-tested in
`commonTest` (12 cases, including the one that matters — a seam-less store wiping on drift it
could otherwise migrate) and `:core-common:desktopTest` is 639/639 green. But the sandbox this
was built in cannot compile iOS (no Xcode) or run the JS toolchain, so **both twins' call paths
are unverified beyond review**. A Mac build + a JS test run are owed before trusting them.

**Both call sites are now wired** (iOS DI in `control-shared-proxy` iosMain, mirroring the
desktop block; `JsRuntime`'s `hydration`). The JS init-order worry resolved on reading rather
than needing a redesign: `hydration` is a *coroutine body*, so it runs after the constructor and
every `Lazy` exists, and `tContext` forces `schemaRegistry` while the reverse does not (the
registry only captures a provider lambda) — no reentrancy.

⚠⚠ **The `augment` gap needed a rule, not just a call.** A plugin folded in *after* the store
opened contributes services the open-time gate never saw, so their versions are missing from the
record and a later reshape of one would be invisible to the next open. `recordNewServiceVersions`
adds them — **new entries ONLY**. A late plugin whose service is already recorded at an *older*
version is real drift this open missed; stamping current over it would hide it permanently, while
leaving it stale lets the next open catch it. **Fail towards detecting later, never towards
silence.**

⚠⚠ **Compiling the JS target for the first time caught a regression E2j-1 had shipped:**
`SchemaShapeBaseline.render` used `toSortedMap()`, a **JVM-only** stdlib extension, in
`commonMain` — breaking `:core-common:compileKotlinJs` (and the web build) while every
JVM/desktop task stayed green. That is the same shape as this slice's own headline finding: a
per-platform gap that the platform everyone tests cannot see. **Compile the JS target when
touching `commonMain`.**

**Verification status, precisely:**

| target | state |
|---|---|
| `:core-common:desktopTest` | ✅ 639/639, incl. 12 `StoreVersionGateTest` |
| `:tenshu-sdk-js:compileKotlinJs` | ✅ compiles (this is what caught `toSortedMap`) |
| `:tenshu-sdk-js:jsTest` | ⚠ **cannot run in a sandbox** — karma's tarball fetch from `codeload.github.com` is 403 through the proxy |
| iOS | ⚠ **not compiled** — no Xcode; needs a Mac build + a real-device pass |

#### E2j-3 capability half as built (2026-07-30) — the twins migrate

The seam, via **load-migrate-persist** rather than a live port. [StoreMigrationPort] is shaped
for SQLite — synchronous rows inside real transactions — and neither twin can honour that shape:
IndexedDB is **asynchronous** (a synchronous port is not expressible), and the iOS store has
per-file atomic writes but **no multi-file transaction**, which is exactly what the journal's
idempotency proof rests on. But both already read *every* row at open to hydrate their cache, so
the shape that fits is: hand the rows in, migrate against an in-memory port
(`InMemoryStoreMigration`), hand the result back, persist it however the platform can commit
atomically. **The migrator is untouched**, so the three stores cannot drift in *policy* — only in
how they write bytes.

⚖️ **Crash safety comes out stronger, not weaker.** Nothing is written until the caller persists,
so a crash mid-migration leaves every row `from`-shaped and the next open re-runs
deterministically — the journal's guarantee obtained *structurally* instead of by protocol. A
multi-step chain commits as one persist rather than per step, sound for the same reason.

| store | persist |
|---|---|
| JS | `replaceAllEntries` = **one** readwrite transaction (clear + put-all). IndexedDB gives the atomicity for free. |
| iOS | staged blobs → **commit marker** → move into place → drop staging. The marker is the commit point: a crash before it leaves discardable garbage, a crash after it is resumed idempotently. |

⚠⚠ **Order at iOS open is load-bearing: resume BEFORE discard.** A staging directory carrying the
marker is a migration that already committed; discarding it would leave `from`-shaped rows under
a *stamped* version — the one state the protocol must never produce. (Caught while writing it —
the first draft discarded unconditionally.)

⚠⚠ **Both callers fail closed:** any throw from the migration degrades to a wipe. A migration bug
on a platform whose tests do not run in CI must not be able to *corrupt* rows; a resync is
recoverable, a garbled store is not.

**E2j-3's stopgap is deleted** (`StoreServiceDrift` / `serviceVersionDrift` / `storeMustWipe`).
The real migrator now decides no-op / migrate / wipe per service, and leaving a second rule that
could disagree with the first is precisely what let E2j-2 silently unmake the invariant on the
twins. `storeWipeReason` narrows to the core gate.

⚖️ **`InMemoryStoreMigrationTest` carries more weight than its module suggests:** neither twin's
toolchain runs in the build sandbox, so it is where their migration *logic* is verified. What
stays unverified is only the **persist step** — writing bytes — which is exactly what fail-closed
protects.

**The iOS on-device check, when a device is available.** Steps 1–2 and 5 test the *quiet* paths
(the ones a regression makes loud); 3–4 test migrate; 6 tests wipe:

1. Install Control-on-iOS, pair, let it sync, confirm rows exist.
2. Reopen — expect **no** wipe, `.serviceversions` present in the replica directory (App-Group
   container, or app-local Documents without the entitlement), and **no** `.migrating` left
   behind.
3. Bump one service's `version` **and** register its `SchemaMigration`, rebuild, reopen — expect
   **rows to survive in the new shape**, the sidecar to read the new version, and no wipe log.
   ⚠ This is the step that proves the slice; everything else was already true after E2j-3's
   safety half.
4. Repeat 3 with the app killed mid-migration if you can force it (background the app during a
   large migration) — expect either the old rows intact or the new rows complete, never a mix,
   and a clean result on the next open.
5. Revert to the earlier build (older service version) — expect a wipe: "written by newer code".
6. Bump the service **without** a migration — expect a wipe with a named line and a clean resync.
7. ⚠ The negative that guards E2j-2: a *format-version* bump alone must still wipe (no core chain
   ⇒ floor == current).

**Remaining for E2j overall:** the on-device pass above; a JS test run on a network-enabled host
(karma's fetch is proxy-blocked in the sandbox); and the first *real* core migration, which is
what would exercise E2j-2's core-floor path outside tests.

#### The gates the build sandbox could not run (2026-08-01, on a Mac)

Master merged in (43 commits; `TENSHU_STORAGE_FORMAT_VERSION` 16 → **18**, bumped twice on master
under the *old* House Rule #9 — worth knowing when reading the flip's policy text, but no conflict:
a core-owned bump is still exactly what the format version is for).

⚠⚠ **The first iOS compile failed, and the failure was in the wiring this slice added**:
`PlatformProxyModule.kt` (iosMain) called `openFileObjectStore(directory = …, migrations = …)`, but
only `FileObjectStore.open` had gained the parameter — the free function it actually calls still
took `(directory)` alone. Nothing caught it because *no* iOS target was ever compiled: the E2j-3
finding "a per-platform gap that the platform everyone tests cannot see" applies to the slice's own
call sites, not only to `commonMain` stdlib use. **A slice that wires a platform must compile it.**

| gate | result |
|---|---|
| `:core-common:desktopTest` + `:system-test-utils:test` | ✅ green post-merge — incl. `SchemaShapeBaselineEnforcementTest`, so 43 commits of master reshaped no pinned composite without a bump |
| `:tenshu-sdk-js:jsTest` | ✅ runs on a network-enabled host (real ChromeHeadless) — green, **but** `IndexedDbObjectStoreTest` is 2 pre-existing cases; the JS store's *new* gate and `replaceAllEntries` persist are still untested |
| `:core-common:iosSimulatorArm64Test` | ✅ green after the fix — `StoreVersionGateTest` 3, `InMemoryStoreMigrationTest` 6. ⚠ `FileObjectStoreTest`'s 7 cases predate this slice: the iOS sidecar, staging + commit marker and persist step have **no iOS-side test at all** |
| `:control-shared-proxy` + `:tenshu-sdk-ios` iOS compile, `assembleTenshuSdkDebugXCFramework`, `xcodebuild ControlIos` | ✅ after the fix |
| `scripts/android-smoke.sh` | ✅ PASSED — 7 apps launched, schema lockstep v18, announce admitted; the JVM store opened through E2j-2's new gate on every launch |

So the branch's *unverified* surface is now exactly the two persist steps (iOS staged-commit, JS
one-transaction replace) and the migrate path end to end — which is what the on-device pass above is
for, and why step 3 (bump a service, register its migration, expect rows to survive) is the one that
carries the slice.

#### ⚠⚠ E2j-4 — the on-device pass: a real migration takes the proxy down (found 2026-08-01)

**The store-migration path has never executed in production on any platform (§8.9's headline), and
the first time it does, Control fails to start.** Found running the pass above on a clean iOS
simulator (Control genesis → 26 rows → reopen), then adding a throwaway probe: `Device` gains a
required `migrationProbe` derived from its `deviceId`, `tenshu/identity` goes to `version = 2`, and
the step is registered — exactly what House Rule #9 now instructs an author to do.

Three builds, each differing in one thing, isolate it:

| build | store state at open | result |
|---|---|---|
| service **v2, no migration registered** | recorded v1 vs declared v2, no chain ⇒ below floor | ✅ **exactly as designed** — `StoreSchemaMigrator: 'tenshu/identity' recorded v1 is below the readable floor — wiping store.` + `FileObjectStore: store wiped by the schema migrator`, sidecar re-stamped to 2, clean boot. E2j-3's "a store that cannot migrate wipes, loudly" is **proven on device**. |
| service v2 **+ migration registered**, store already at v2 | recorded 2 == declared 2 ⇒ nothing to migrate | ✅ boots clean. So **registration and chain-verify are fine** — the registry accepts a real `migration(typeId, from, to)` against a live type id. |
| service v2 **+ migration registered**, store at v1 | recorded 1 < declared 2, chain reaches ⇒ **the migration runs** | ⚠⚠ **Control never starts.** iOS: `EXC_BAD_ACCESS` / `SIGSEGV` in the Koin resolution of `buildProxyModule`, no Kotlin exception. Android: `ANR … Reason: Process failed to complete startup`, `Killing … (adj 0): bg anr` — the smoke gate catches it as `FAIL: never announced its schema session`. |

**So the fault is in *executing* a migration, not in declaring one** — and it is not
platform-specific: two platforms, two symptoms, one trigger. The migrate runs inside the
`single<ObjectStore>` factory (store open **is** graph construction), so whatever it re-enters, it
re-enters while that singleton is still being built. ⚠ The iOS stack is **139 frames and finite**
(`ControlSurface.installRealRuntime` → a chain of `buildProxyModule` factories → `Scope#get` →
`kotlin.native.internal#downcast`), so "infinite recursion" is the obvious reading but **not** what
the evidence shows; the Android ANR is equally consistent with a re-entrant resolution that blocks.
Diagnosing which is E2j-4's first job.

⚠ **Scope of the claim.** This is one throwaway migration on one type. A different type might fare
differently, and the probe itself could be malformed — but a malformed migration owes a loud verify
failure, and build 2 proves verify *accepted* this one. Either way the shipped instruction in House
Rule #9 ("bump the service, register the step") currently produces an app that will not launch.

⚠ **This is why the pass exists.** Every gate on this branch is green: 639 `desktopTest`, 12
`StoreVersionGate` cases, `InMemoryStoreMigrationTest`, a full `:system-test-utils` graph boot, the
Android smoke gate, `xcodebuild`. None of them registers a **non-empty** `SchemaMigration` in a
production graph — the only two in the repo (`SyncService` v2, `TimelineService` v1) are
deliberately empty bodies, so every suite exercises the machinery with nothing to do.

#### E2j-4 as built (2026-08-01) — a two-thread DI deadlock, fixed in one file

**Diagnosed from the Android ANR thread dump** (`dumpsys dropbox --print`; the iOS `SIGSEGV` named
nothing, and "infinite recursion" — the obvious reading of a stack-guard fault — was **wrong**):

```
"main" … TimedWaiting            "DefaultDispatcher-worker-2" … Blocked
  runBlocking.joinBlocking          waiting to lock <0x0f59726d> held by thread 1
  ← PlatformProxyModule:533         ← OverlayTContext.getComponents(MigrationOverlays.kt:67)
    single<ObjectStore> {…}         ← toTString → MappedCompositeMigration.migrate
  - locked <0x0f59726d> …           ← StoreRowMigrationCodec.migrateRow
    (+ a stack of sibling             ← StoreSchemaMigrator.runStep
     SingleInstanceFactory locks)     ← SqliteObjectStore$open$2 (withContext(Dispatchers.IO))
```

Read as a cycle it is plain: **the store opens *inside* a DI factory**, so main holds a stack of
Koin `SingleInstanceFactory` locks; `SqliteObjectStore.open` is `withContext(Dispatchers.IO)`, so the
migration runs on a worker; the migration's `derive` calls `toTString(ctx.context)` → `dataFactory`
→ `OverlayTContext.components`, which was **`base.components + overrides`**.

⚠⚠ **`+` is the whole bug.** `TContext.components` in production is not a map — it is a **lazy view
over the container** (`CoreCommonModule`: every `get(key)` is a `koin.getOrNull`). `Map.plus`
copies, copying enumerates, and enumerating resolved **every** component: the interactor provider,
the uploader, the on-demand resolver, the crypto manager — precisely the singletons main was already
building and holding locks for. Neither thread could finish. A migration needs exactly the two ids
the overlay overrides and never touches the rest.

**Fix:** `OverlayTContext` holds a **lookup view** (`OverlayComponents`) — overrides first, else one
keyed read of the base, no enumeration ever. One file; nothing changes about what a migration
*computes*. It also stops re-resolving the whole component set once per created value, so it is the
per-row cost fix at the same time.

⚖️ **Why not "move the migration out of graph construction"** — the other obvious fix: it trades a
deadlock for an ordering problem. Something must then guarantee no consumer reads a `from`-shaped row
between store open and migrate, and *"the store is migrated before anything can read it"* is the
invariant E2j-2 and E2j-3 both rest on. Keeping the migration inside open preserves it by
construction; what had to go was the migration's **dependency on the graph it runs inside**. ⚠ The
`runBlocking`-in-a-factory bridge remains a latent hazard for any future code that resolves DI from a
worker during store open — a tech-debt entry, not a redesign here.

**Verified on both platforms**, same probe, by re-running the pass:

| | before | after |
|---|---|---|
| Android | ANR, `Killing … bg anr` | ✅ starts + announces; `schema_service_versions` = `tenshu/identity\|2`, **151 rows intact**, Device row reads `probe/6d71b2fe-…` = `"probe/" + deviceId` |
| iOS | `SIGSEGV`, no Kotlin exception | ✅ starts, 0 crash reports; sidecar `tenshu/identity=2`, **26 rows intact**, Device row `probe/5dd39006-…` |

So **§8.9's step 3 — the step that carries the slice — now passes on both platforms**: a service
bumped with a registered migration rewrites its rows in place and they survive reshaped.

**Regression test:** `MigrationOverlayContextTest` (3 cases, `commonTest` ⇒ runs on iOS too). A unit
test cannot reproduce a two-thread deadlock, so it pins the *invariant that prevents* it: the base
context's bulk accessors (`entries`/`values`/`containsValue`) throw, so any return to a map-merge
fails the suite. ⚠ The base fixture must be **non-empty** — `Map.plus` short-circuits on an empty
source, which would let the bug pass its own test. Confirmed failing with the fix reverted.
⚠ Backticked test names may not contain commas: Kotlin/Native rejects them and only
`:core-common:iosSimulatorArm64Test` says so.

**Gates:** `:core-common:desktopTest` + `:system-test-utils:test` + `:tenshu-sdk-js:jsTest` +
`:core-common:iosSimulatorArm64Test` green; Android smoke gate PASSED. **House Rule #9's
service-bump path is safe to follow again** and CLAUDE.md's interim warning is withdrawn.

#### E2j-5 as built (2026-08-01) — the gate that was missing

`NonEmptyMigrationGraphSystemTest` (`:system-test-utils`). E2j-4's real lesson is not the `+`; it is
that **three slices shipped with every suite exercising the migration machinery on nothing to do.**
Two properties were each covered alone and never together:

1. a **non-empty** `SchemaMigration` registered in a real service graph with real drift to apply —
   the repo's only two migrations are deliberately empty bodies; and
2. the store opening **inside a DI factory**, which is where production opens it, and which is what
   let the migration re-enter the container that was still being built.

The test supplies exactly that pair over a synthetic service nothing else consumes: seed a real
on-disk store at `version = 1` with one row, assemble `systemTestProxyModule()` (⇒ a genuinely
DI-backed `TContext`), register the v2 config + its migration, then resolve `ObjectStore` from a
`single { runBlocking { openSqliteObjectStore(…) } }` binding shaped like production's, and assert
the row is **kept and reshaped** — `name` preserved, `derived/name` added.

Details that carry the test's weight:

- ⚠ **A real `SqliteObjectStore` on disk, not the harness's `InMemoryObjectStore`** — an in-memory
  store has no recorded version table, so it cannot drift, so it cannot migrate.
- ⚠ **The new field is required-and-derived, never optional.** An optional add starts absent (§8.4)
  and leaves the row's bytes untouched, so it would pass with the migration never running.
- ⚠ **Resolution runs on a watchdog thread (60 s).** A regression here *hangs* rather than fails;
  without it a deadlocked graph wedges the whole suite until Gradle's timeout with nothing naming
  the cause. The failure message points at §8.9.
- The v2 config is registered **after** the registry exists, mirroring E2j-3's `augment` path.

**Confirmed to catch the real bug**: with `OverlayTContext` reverted to the map merge, the test fails
in 68 s with *"deadlocked against the graph it runs inside"*; with the fix, green in seconds.

⚠ **Still open:** the JS twin's persist step remains unexercised (`IndexedDbObjectStoreTest` predates
the seam), and this gate covers the **JVM** store only — the iOS `FileObjectStore` seam has no
equivalent, because its migration path is only reachable from a real iOS process. The device pass in
this section stays the check for it.

**Reproduction** (~4 min per build; the probe is deliberately not committed): add a required field to
`Device.Schema` + `Device.create`, `service(id = ID, version = 2)` in `IdentityService`, and a
`schemaMigration(IdentityService.ID, fromVersion = 1) { migration(Device.ID, from = <the 6-field
snapshot>, to = Device.Schema) { derive(…) } }` in `identityServiceCommonModule`; then genesis a
simulator on the *old* build and launch the new one over it.

**E2j-4 = make the first real migration survive its own execution.** Until it lands, ⚠⚠ **House Rule
#9's service-bump path is not safe to follow**: a bumped service with no migration wipes (loud,
correct, recoverable), but a bumped service *with* one bricks startup. The interim rule is the old
one — reshape a service-owned type by bumping `TENSHU_STORAGE_FORMAT_VERSION` and taking the wipe.

**Is it needed "for iOS"?** No more than for any other platform. Pre-release, wipe-and-resync is the
accepted mechanism everywhere, and iOS is not worse off today (an app update preserves the
container, so Keychain identity survives and the store self-wipes — the same shape as
`adb install -r`). It becomes mandatory the moment there are users who cannot be wiped, and then it
is mandatory for **every platform at once**, not iOS-first.

**What it retires elsewhere.** L1 of the tech-debt entry *"`Device` rows have no regeneration path"*
is exactly E2j-2: closing this gate dissolves the "every replica's store empty simultaneously"
premise that entry's failure condition needs.

#### 8.9.1 E2j-6 — the property-kind baseline, the hole E2j-1 could not see (2026-08-07)

⚠⚠ **E2j-1's baseline is positional, and a property is not stored positionally.** That gap is not
theoretical — it shipped, twice over:

- **C7** moved `File.Thumbnail`/`ThumbnailLarge` from `valueProperty` to `dynamicProperty`.
  The two authoring forms register **different schema kinds under the same id** — a `ValueSchema`
  (value stored inline) versus a `DynamicPropertyRelationSchema` (the slot is a child-row id) — so
  every already-stored slot was suddenly read through the other arm of `DbEntryMapper`.
- `SchemaShapeBaselineEnforcementTest` was **honestly green** ("0 changed shapes"), because a
  property relation lives in a **named map**, not in the positional field list. The migration
  registered with the bump was empty on the reasoning "nothing reshaped positionally" — true, and
  not sufficient.
- **The incident:** inline `TByteArray` bytes cast to a `LongValue` child-row id ⇒
  `ClassCastException` on every read of an existing row, and because the throw escaped the ingest
  path outside its try, the pull cursor stopped advancing and **sync wedged permanently**.

**Why the reader cannot be the fix.** The reader fix closed the arm it could — a stored kind that *cannot*
be a child-row reference is dropped. That covers `TByteArray` and every other non-integer type. It
cannot cover the general case: on a `TInteger` property the stale slot **is** a valid `LongValue`,
byte-for-byte indistinguishable from a legitimate reference. The stored *value* is then dereferenced
as a row id, and when a row happens to live at that address the reader returns **another row's data
as this property's value, with no error anywhere**. A reader can reject a kind *mismatch*; it can
never reject a kind *match that means something else*.

⇒ The check has to see both versions at once, and the only place that is true is a pinned baseline.
`PropertyKindBaseline` (`:core-common`) + `PropertyKindBaselineEnforcementTest` pin every property
and service relation in the wired graph as `INLINE` or `CHILD_ROW` — deliberately **binary**,
mirroring the only distinction `DbEntryMapper` draws, because a finer taxonomy would pin churn that
cannot corrupt anything.

- ⚠ Sourced from the **relation map**, not `allSchemas()`: a schema id alone says nothing about
  whether any row stores a slot under it, and the registry synthesises a placeholder schema for
  every field id it ever sees. A relation entry is exactly the statement "this id can appear as a
  slot on that receiver" — the set at risk.
- ⚠ A flip is **not forbidden** — C7's move to the content plane was right. What it owes is that
  the stale slots stop existing: a migration that strips them, shipped with the bump. The failure
  message says so rather than just naming the diff.
- ⚠ Pinned once per id, not per receiver: the read arm never sees the receiver, so the hazard is
  per id and a duplicate would add lines without adding coverage.
- ⚠ Same coverage caveat as its sibling — `fit`/`timeline`/`logging`/`ingest`/`place`/`map-region`/
  `storage` are not wired into `:system-test-utils`, so their properties are not pinned. Widening
  it is a build change, deliberately not bundled here.
- ⚠ The regenerate flag needs **explicit forwarding** in `system-test-utils/build.gradle.kts`:
  Gradle's `-D` sets the property on the *daemon*, and the test runs in a forked JVM. Without it
  the documented command silently does nothing and leaves the pinned file stale — which was true
  of the new flag until it was caught here, and is the one outcome a baseline must never have.

⚠⚠ **What this does NOT do: it does not repair the rows C7 already stranded.** Every file uploaded
before C7 still carries its thumbnail as an inline slot that the current reader drops, with no
content-plane blob to fetch instead — 743 of 823 `File` rows on the maintainer's devices, measured
on both. Those thumbnails are unreachable until either a migration re-homes the inline bytes to the
content plane or the realm is reset. The guard stops the *next* one; it is not a repair.

### 8.9.4 Why House Rule #9 used to read "always bump the format version"

Recorded here because the rule's own wording no longer explains itself, and the reasoning was
sound for the posture it was written under. This paragraph lived in CLAUDE.md until 2026-08-13;
it belongs next to the mechanism it describes.

The original rule said: any wire or store reshape bumps `TENSHU_STORAGE_FORMAT_VERSION` and
ships its `SchemaMigration` in the same PR. That was correct **while the store-open gate wiped
on every mismatch**. Pre-release we owe no installed base continuity, so a spurious wipe and
resync is cheap — and a silent format mix is not. The rule bought loudness at a price nobody
was paying.

It was not written speculatively. Three change series — **content-addressing Phases 1–2a and the `File.fileId` removal, Phase 3-2a and Phase 3-2b** —
reshaped stored formats under an unchanged version, and two of them produced **white-screen
incidents on iOS**: the store opened, decoded a composite positionally against the wrong field
list, and the app came up with nothing on screen and no error naming the cause. A wipe would
have been strictly better than what those users got.

What changed is §8.9's finding above: the wipe ran *before* the migrator and dropped the
recorded per-service versions with it, so the migration shipped alongside the bump could never
do any work — the two halves of the rule cancelled. E2j-2 made the gate migrate-if-reachable,
and House Rule #9 was rewritten to move the *owning service's* version instead.

⚠ The loudness the old rule bought has been preserved, not traded away: a bumped service with
no registered migration chain still wipes below its derived floor. Forgetting the chain fails
noisily; it does not fail silently, which was the property the two incidents cost us.

---

## 9. Operation migrations: negotiation + adapters, cut-off as dispatcher policy

Smaller than it looks, because of what ops *are* (fact 3): resolved by string id, never
persisted, never relayed.

### 9.1 Versioning an op = a new op id

The platform already does this by instinct — `AdvanceTask` → `RenewTaskLease` was
a rename, not an in-place mutation. Make it the rule: **an operation's wire contract is
immutable; a changed contract is a new id** (suffix convention `…V2` or a new name, per
taste). "Polyfill" is then just the proxy keeping the old id registered, mapped onto an
adapter that calls the new implementation.

### 9.2 Per-session routing (the announce payoff, later)

Because the proxy records each session's announced vector (§6.4), polyfills stop being a
global registration question and become **per-session dispatch**: a session admitted at
file-service v3 gets the old op id routed through an adapter; a v4 session dispatches
straight through. Nothing here needs building now — E1 just has to *record* the vector per
session so the routing hook exists when the first real polyfill is needed.

### 9.3 Cut-off

Deregister the old id; the dispatcher returns a typed `unsupportedOperation` error (the
announce reply already named the floor, so a well-behaved client never even sends it).
Enforcement lives in the proxy — the trusted on-device component; there is no server to
enforce anything and none needed. For the "insecure op" case this is sufficient: the
moment Control ships without the registration, no client on that device can invoke it.

### 9.3.1 E3-1 as built (2026-08-02) — the typed answer, and what it can honestly say

`UnsupportedOperationError` (`:core`, registered in `coreCommonModule`) raised from the callstack
parse boundary. Before it, a caller naming an op this build does not have died in `BinaryParser` on
`error("Schema not found for type ID: …")` — an untyped `IllegalStateException` that
`executeAsyncFromBytes` wrapped **with an empty callstack** ("Parser failure — no usable callstack to
inspect"), i.e. exactly the cryptic `error/internal` class §6.7 exists to kill, in the one place the
phase claims is a supported deprecation step.

⚠⚠ **The finding that changes §9.3's promise: a deregistered op is UNNAMEABLE by the proxy.** Type
ids cross the wire as a 16-byte tag (`idToUuid`), and `SchemaRegistry.getTypeId(bytes)` resolves it
through a reverse map built from the *registered* schemas. Deregistering an op therefore deletes the
proxy's ability to say which op it was — it holds a hash and no preimage. §9.3 says "the dispatcher
returns a typed `unsupportedOperation` error", which is achievable; "and names it" is not, from the
side that dropped it.

So the error carries **`typeTag`** (hex, always populated) and `operationId` (empty in the ordinary
cut-off case), and the naming duty sits with the **caller** — which hashed the id to produce the tag
and can map it straight back. That division is sound rather than merely expedient: the side that
still knows the string is the side that does the lookup. ⚠ It does mean a *human* reading a proxy log
sees a hash. If that becomes a real diagnostic burden, the answer is not to change this error but to
add a **retired-id registry** — `retiredOperation(id)` keeping the tag→id mapping alive without an
implementation, so a deliberate cut-off stays nameable while an unknown *newer* op still cannot be.
Deliberately not built now: no consumer, and §9.4's pressure has not arrived.

**Where it is raised, and why not in the parser proper.** The parser is generic — the same bytes
carry operations, their parameters and sync payloads — so an unknown tag means different things in
different positions and the parser must not editorialise. It now throws an internal
`UnknownTypeTagException` carrying the **position**; `parseArray` converts it to the typed error
**only** when `isTopLevel` and the failure position equals the element it was about to read. A
callstack's top-level elements are operations by construction, so that test is structural, not a
heuristic — and a parameter's unknown type inside a *known* op stays untyped rather than being
mislabelled an op-version problem the caller does not have.

**Tests** (`UnsupportedOperationGateTest`, 3 cases): the setup is two proxies — one that registered
the op so it can *produce* the bytes, one that did not so it must *interpret* them. ⚠ A single-proxy
test cannot reach this at all, since only a peer holding the id can emit a tag the reader lacks. The
second case parses the reply **with the caller's registry**, which is what proves the error type
itself crosses the wire — an unregistered error type would collapse the very case it names back into
`error/internal` (the wire-registration gap). The third pins the positional precision above.

⚠ **A new type, so no version moves.** `UnsupportedOperationError` reshapes nothing — HR #9's bump
clause is about *reshapes*, and `SchemaShapeBaseline` classifies it `Unpinned` (an addition), not
`ReshapedWithoutBump`. The baseline was regenerated. ⚠ Old peers cannot parse this error, by
definition of being old; that is acceptable because a peer skewed enough to receive it is already
refused by the §6.5 announce gate, and the pre-E3 alternative was an untyped failure they could not
act on either.

⚠⚠ **Drive-by fix — the E2j-1 regeneration command never worked.** `-Dtenshu.schemaBaseline.regenerate=true`
sets the property on the Gradle **daemon** JVM; the test runs in a forked one, where `System.getProperty`
returned null and the flag silently did nothing — the run just failed with "regenerate the baseline"
again. `:system-test-utils`'s test task now forwards it explicitly. Worth noting as a shape: the
documented escape hatch of a fail-by-design gate needs its own verification, or the gate is
un-actionable exactly when someone first needs it.

### 9.4 When this pressure actually arrives

First-party apps update in near-lockstep through the stores; real op-version pressure
arrives with **third-party EPC apps and the published `@tenshu/sdk` npm surface** — the
things we can't force-update. EPC already takes the hard-fail posture (transport version
byte, no negotiation). Ship only the announce + typed errors now (E1); build actual
polyfill adapters ad hoc, per op, when the first real consumer exists. Note also that the
Fit-style "Google/Apple changed something" churn is mostly *data*-shape churn — the
per-service data epoch covers it; op contracts move far less often.

### 9.5 The JS SDK: per-service artifacts (settled direction, resolves open question 5)

Today `@tenshu/sdk` is one artifact pinning one implicit global vector at publish time.
The settled direction (2026-07-25): **split the npm surface per service, mirroring the
Kotlin module structure** — already the plan for per-app bundle weight (the Kotlin-side
bundle-splitting track), and schema versioning gives it a second, independent
justification. Concretely:

- Each per-service package carries its service's `SCHEMA_VERSION` and **registers into
  the shared core runtime's module registry** — the JS twin of the Kotlin module DSL
  (`JsRuntime` already mirrors `CoreCommonModule` wiring by rule, so the seam exists).
- The web announce vector is then **assembled from the registered modules at runtime**,
  exactly like native — not pinned as one monolithic constant. Web stops being a special
  case in this proposal: it is simply another client on the "can't force-update" side of
  §9.4, governed by the same per-service floors and deprecation windows.
- Cross-package compatibility (an app pins `@tenshu/file@3` against `@tenshu/core@1`
  when `file@3` needs `core@2`) is expressed twice, deliberately: **npm peer-dependency
  ranges** catch it at install time, and the **runtime registration check** (the JS twin
  of the init-time floor/current verification, §6.8) catches it at boot — fail-fast,
  never a garbled call.
- The payoff for versioning specifically: a Fit schema bump republishes the Fit package
  only; file-service consumers never see a release, so per-service floors get
  per-service deprecation clocks instead of one SDK-wide one.

---

## 10. Threat-model deltas (land per implementing phase, House Rule #2)

| Phase | Delta |
|---|---|
| E0 | None beyond the ordinary format bump — stamp is inside ciphertext. |
| E1 | Announce reply reveals the proxy's service floors to any connecting client — same-device, already-trusted surface; note, no new T-entry expected. |
| E2 | **Epoch vector must be E2E, not relay-visible** (app-usage leak otherwise) — new assumption + T-entry. Relay's plaintext `formatVersion` **removed from the relay entirely** (E2e, §7.8) rather than demoted: the relay keeps one plaintext number, and it describes only its own contract. Metadata the relay learns shrinks. |
| E4 | Compaction re-emits full current state — burst of fresh ciphertext observable by the relay (volume/timing metadata); assess against existing traffic-analysis entries. |

---

## 11. Phasing

Each phase independently shippable; order is by urgency-of-debt, not dependency depth.

| Phase | Contents | Why this position |
|---|---|---|
| **E0 — block stamps** — **SHIPPED 2026-07-25 (as-built §5.2, fmt v13)** | Version stamp inside every block's ciphertext envelope (single-target + `DeltaBatchPayload`): a global int at E0, upgraded to the full `VersionVector` with E1 (§4.1.2); rides one ordinary format bump + wipe + relay redeploy. | The only **irreversible** debt accruing today: every unstamped block emitted after release can only ever be wiped or guessed at. Land even if everything else waits. |
| **E1 — per-service versions + Announce op** — **COMPLETE 2026-07-25 (as-built §6.9 core + §6.10 wiring/gate; fmt v14)** | `SCHEMA_VERSION` per service + core floor (§4); frozen announce envelope, symmetric reply, re-announce on reconnect, silent-client policy, session vector recorded (§6). | Cheap, self-contained, kills the HR #9 silent-garbling class immediately; prerequisite for per-session routing later. |
| **E2 — epoch fencing + store migrations** ✅ **COMPLETE 2026-07-26** | Frozen epoch record in E2E metadata + write-triggered bump + pull-fence/push-allow UX (§7, as built §7.6/§7.7); `SchemaMigration` chain replacing the wipe at store open + the same migrations keyed on block stamps for history (§8.1–8.2, §8.4, as built §8.5–§8.7); **relay gate split** — 426 re-keyed onto a new `RELAY_PROTOCOL_VERSION` accepting a `[min, current]` range, payload versions dropped from the relay entirely (§7.5, as built §7.8); threat-model amendment (A37/T-SE-01). | The actual migration machinery. First phase that lets two devices at different versions coexist — and the phase that ended per-schema-change relay redeploys. |
| **E2f — the fenced device's outbound tail** ✅ **COMPLETE 2026-07-27 (as built §8.8)** | §8.3's last bullet: the pending-block queue is restamped to the current vector before the first drain of the process, so a device that updated while writes were queued never pushes below-version blocks. In-place restamp over the E2c upcaster (the regenerate-from-store wording is unreachable — see §8.8); no wire/schema/format change. | Small, and it is the half of E2's push-allow posture that only becomes *incorrect* once E4 moves the floor — cheapest to close while the queue semantics are fresh. Remaining E2 tail: the §7.3 banner + per-shared-realm fencing. |
| **E2g — the "update this device" banner** ✅ **COMPLETE 2026-07-27 (as built §7.9)** | §7.3's UX half, as a pure Drive-side consumer of E2d-2's `SchemaFenceStatus`: new `SyncBannerState.NeedsUpdate`, ranked `Locked > NeedsUpdate > LocalOnly`. Informational (an app-store update is not an in-app action). No proxy/wire/schema change. ⚠ "naming the app(s)" deferred — no service-id→app-name mapping exists. | The fence has been silently pausing pulls since E2d-2; without the banner a fenced device just looks broken. Cheap, and the last E2 item with user-visible consequences. |
| **E2h — per-shared-realm fencing** ✅ **COMPLETE 2026-07-27 (as built §7.10) — E2 TAIL CLOSED** | Realm-scoped epoch key (`"$ownerId::"`, the same DataId prefix every shared-realm entity carries, so routing/encryption/queueing fall out with no special case); the bump follows the write's realm — fixing a misattribution, not just widening scope; each shared realm's pull fenced on its own epoch, cursor frozen by construction. One aggregate `SchemaFenceStatus` still drives the banner. No wire/schema/format change. | The §7.7 converse gap: a fenced device could still ingest shared-realm blocks of exactly the types it garbles, and a shared-folder write fenced the wrong realm's peers. |
| **E2i — core-version skew** ✅ **COMPLETE 2026-07-27 (as built §7.11)** | Opened by a live silent-desync incident. Core skew turned out to be fenced by **nothing** — E2d-1, E2d-2 and E2e each deferred it to another. `SchemaPullFence` now judges `coreVersion` (reserved `SchemaFenceStatus.CORE_SERVICE_ID`); the epoch writer keeps an existing row's core current; the E2e transition allowance is retired (absent `relayProtocolVersion` ⇒ 426). ⚠ Needs one relay redeploy; no protocol bump. ⚠ Cannot rescue an already-stale device — reinstall remains the remedy. | The gap was invisible: an out-of-date device authenticated, synced "successfully", and dropped every block. Also corrects the belief that the 426 gate ever covered this — it is genesis-only; the wipe cycle was doing the fencing. |
| **E2j — make the store migrations reachable** — **E2j-1 + E2j-2 + E2j-3 IMPLEMENTED 2026-07-30 (as built §8.9)** | ⚠⚠ The E2 store-migration path has never done real work: the `user_version` gate wipes (and drops the per-service version tables) *before* the migrator runs, and HR #9 requires that bump on every reshape — so the two halves of the §11 heuristic cancel. Three slices, in order: **E2j-1** schema-shape baseline so a reshape without a bump fails loudly (the prerequisite — without it the flip trades a spurious wipe for silent garbling); **E2j-2** retire `TENSHU_STORAGE_FORMAT_VERSION` as a wipe trigger, per-service versions become the store gate, HR #9's bump clause replaced by "ship the `SchemaMigration`"; **E2j-3** the iOS `FileObjectStore` + JS `IndexedDbObjectStore` twins — ⚠⚠ which shipped as a SAFETY fix, not the planned capability one: E2j-2's policy move had opened a silent-garbling hole on both (they gated on the format version alone), closed by one shared `StoreVersionGate` where a seam-less store WIPES on drift the JVM migrates. ⚠ Both call sites wired and the twins now MIGRATE (shared `InMemoryStoreMigration`, fail-closed to a wipe; JS commits in one IDB transaction, iOS via a staging dir + commit marker). JS compiles — catching a JVM-only stdlib call E2j-1 had shipped into commonMain — but `jsTest` and iOS remain unverified; §8.9 carries the on-device checklist. | Everything E2 built is dormant until this lands — the only registered migration in the repo is an empty one. Also **L1 of the `Device`-regeneration tech-debt entry**: closing this gate dissolves that entry's "every replica wiped simultaneously" premise. ⚠ Do not start at E2j-3, and do not do E2j-2 without E2j-1. |
| **E3 — op floors + typed errors** | Dispatcher `unsupportedOperation`; deregistration as cut-off (§9.3). Polyfill adapters explicitly **not** built — ad hoc when the first consumer exists (§9.4). | Small; mostly falls out of E1's recorded vector. |
| **E4 — compaction** | Current-state re-emission + retention GC interaction + floor advancement; coordination model pre-settled (§8.3: receipt-row dedup, roster-gated floor, idle-time scheduling — no election, no quorum). | Bounds upcaster lifetime and makes data cut-off real. Cadence/tuning needs real-world backlog sizes; nothing blocks on it. |

**✅ CODE-SIDE RESET MERGED 2026-10-27.** Every development chain was deleted. All service
versions, `TENSHU_STORAGE_FORMAT_VERSION` and `RELAY_PROTOCOL_VERSION` (and its minimum) restarted at
1, and the relay's SQL migrations were squashed. ⚠ The **operational** cut — relay and commerce onto
fresh volumes, every device cleared — is the planned production reset, not yet done
([`how-to/reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md); Appendix A.2 records
its date). The retired numbering lives in **Appendix A**. ⚠ Re-cut once more on 2026-11-05 for
1.0.1, before any build reached a user (Appendix A.6). From the first 1.0.1 install, every chain step
is load-bearing for someone's installed data.

**⚠ The pre-release reset is a one-way door.** Every migration step registered during
development exists only to carry *our own* dev stores and dev relay history; at the release
cut the whole accumulated chain should be **deleted** and versions reset to a clean baseline
(the floor is derived from the chain, so deletion *is* the cut-off — §4/§8.4). After release,
each step is load-bearing for somebody's installed data and the only way to shorten the chain
is E4 compaction advancing the floor (§8.3). Tracked as a release gate in
[`pre-release-checklist.md`](pre-release-checklist.md) §4.

**Decision heuristic — superseded by E2j-2 and the reset; House Rule #9 is the operative text.**
A service-owned reshape bumps *that service's* version and ships its `SchemaMigration` in the same
PR. A `:core` reshape bumps `TENSHU_STORAGE_FORMAT_VERSION` **and** registers a
`schemaMigration(CORE_SERVICE_ID, …)` step. A wipe is no longer an acceptable upgrade outcome.
*Historical wording (pre-E2j):* a wire/store reshape bumps
`TENSHU_STORAGE_FORMAT_VERSION` and ships its `SchemaMigration` in the same PR (House Rule #9,
amended for E2 — §8.4). The **relay-redeploy half of that rule is gone**: it now applies only
to `RELAY_PROTOCOL_VERSION` changes (§7.5/§7.8), which is most of the day-to-day pain the rule
used to impose. Clients still move in lockstep with each other.

⚠⚠ **Those two halves currently cancel each other — see §8.9 (E2j).** The bump wipes the rows *and*
the recorded per-service versions before the migrator runs, so the `SchemaMigration` shipped
alongside it never migrates anything. Until E2j-2 flips the gate, the honest reading of this
heuristic is: **the bump is what takes effect and the migration is inert**, so keep writing
migrations (they are the post-release asset and E2j-1 will start enforcing them) while expecting
the wipe to be what actually happens on upgrade.

---

## 12. Open questions

All five resolved (v0.2 / v0.6, 2026-07-25). Kept as the decision record:

1. ~~Floor policy~~ **Resolved:** the floor is **unlimited until E4 exists** — old
   history stays readable indefinitely; migrations only accumulate. From E4 on,
   compaction runs as opportunistic idle-time maintenance (idle + charging + unmetered
   on the `PlatformScheduler` seam), and the floor advances only behind completed,
   roster-verified compaction — never on a calendar. Full model in §8.3.
2. ~~Who compacts — quorum / leader election?~~ **Resolved: neither.** Compaction is
   idempotent + convergent, so racing compactors cost bandwidth, not correctness;
   dedup rides a synced *compaction receipt* row (the "signalling block" idea minus the
   blockchain — no adversary inside the trusted device set), and floor advance is
   **roster-gated** on every non-revoked device's recorded version, with device
   revocation (ADR 0007) as the escape hatch for the never-returning laggard. Classic
   consensus was rejected outright: the user's devices are mostly offline, a quorum may
   never be reachable, and nothing here needs agreement-before-acting. Full model §8.3.
3. ~~Epoch record contention~~ **Resolved:** per-entry **max-per-service merge** on the
   existing `SemanticMerger` seam — max is a textbook join-semilattice merge, trivially
   convergent and order-independent. ⚠ Remember the known gotcha: semantic mergers must
   be registered in *production* wiring, not just tests. Confirm shape at E2.
4. ~~Does the announce carry app identity?~~ **Resolved in §6.8:** yes — `appId` is a
   param, and the op's receiver is `AppService`, composing with the app-registration
   handshake (`RegisterApp`) instead of duplicating it.
5. ~~`@tenshu/sdk` versioning~~ **Resolved in §9.5:** the npm surface splits into
   per-service artifacts mirroring the Kotlin module DSL; each package carries its
   service's `SCHEMA_VERSION` and registers into the core runtime, the web announce
   vector is assembled from registered modules exactly like native, and per-service
   floors get per-service deprecation clocks. No web-special mechanism.

---

## 13. Implementation notes for Claude Code

- **E0 touchpoints:** `LinearSerializer`/payload envelope in the sync emit path
  (`SyncObserver`/`SyncProcessor`), `DeltaBatchPayload.kt` KDoc (its "no version marker"
  safety-model paragraph gets rewritten), `StorageFormat.kt` bump + KDoc entry, relay
  redeploy (House Rule #9 checklist as usual).
- **E1 touchpoints:** new frozen composites in `:core-common` (registered in
  `coreCommonModule` — remember the wire-registration gap: in-process tests miss missing
  registrations; test serialize→parse explicitly); announce dispatch in
  `LocalTaskExecutor`/`EvaluateCallstack` *before* ordinary op resolution; per-connection
  session state in the proxy; client-side hook in the resubscription
  `onReconnected` path; smoke-gate assertion that every installed app announces.
- **E2 touchpoints:** `SqliteObjectStore.kt:510` (the wipe becomes the migration chain
  entry point) + iOS `FileObjectStore` + JS `IndexedDbObjectStore` twins;
  `DataIdMigrator`'s journal pattern as the template (do not fork it — extract the
  marker/resume shape); epoch record in `:sync-service`; threat-model amendment in the
  same PR. **Relay gate split (§7.5) — as built, §7.8:** `RealmRouting.formatVersionOk()`
  became `relayProtocolVersionOk()` keyed on `RELAY_PROTOCOL_VERSION` with a
  `[min, current]` range (new `core-common/.../RelayProtocol.kt`); `RestSyncRelay.kt` sends
  `relayProtocolVersion` **alongside** the legacy `"formatVersion"` for the transition
  window, since the deployed relay still treats a *missing* field as a mismatch by design.
- The two-artifact rule from §8.2/§8.4 (schema Δ + `SchemaMigration` in one PR) is the
  House Rule #9 successor — when E2 lands, amend House Rule #9's text in `AGENTS.md` in
  the same PR.

---

## Appendix A — Pre-release format history (retired at the release-cut reset, 2026-10-27)

The release-cut reset (§11, [`pre-release-checklist.md`](pre-release-checklist.md) §4) deleted every
development migration chain and restarted all versions at **1**. The history below is what the
constants' KDoc carried until then; it describes **no data that exists any more** and is kept only
so the reasoning behind each rule (HR #9, the core-step requirement, the relay floor) stays legible.

⚠⚠ **The numbers below are a closed series.** Post-reset `v1` is *not* pre-reset `v1`. Nothing in
the tree may compare against a pre-reset number, and the reset relies on every pre-reset store and
the relay volume being wiped at the operational cut (A.2) — a pre-reset store at core v22 reads as "written by newer code" and
wipes; one at the old core v1 would be misread as current, which is why the cut required every dev
device to be cleared rather than trusting the gate.

### A.1 `TENSHU_STORAGE_FORMAT_VERSION` (pre-reset v1 → v22)

| v | Change | Lesson it left |
|---|---|---|
| 1 | Content-addressed file format (`file-content-addressing.md` Ph1). | — |
| 2 | Content-addressing as actually landed (Phases 1–2a, the `DataIdMigrator` steps 3a/3c, `File.fileId` removal). | v1 was stamped *before* the store-breaking PRs merged under it → white-screen Control-on-iOS. Bump with the reshape, not ahead of it. |
| 3 | Timeline `MovementTrace` + `traces` relation. | A new synced type is peer-breaking (old peer drops the block). |
| 4 | `HealthSample` re-keyed onto `sampleId`. | — |
| 5–6 | Fit `DailyNutrition`, `MenstruationDay`. | — |
| 7–8 | `MovementTrace` fidelity fields + polyline codec; `sourceActivityType`. | Positional composites make *additive* inserts peer-breaking, and garble rather than fail. |
| 9 | `Place.labelSource` + `accuracyM`. | — |
| 10 | Fit `Workout.Streams` → content plane. | — |
| 11 | `DeltaBatchPayload`. | Unknown payload variant ⇒ silent drop. |
| 12 | `HubStatus` disk figures. | — |
| 13 | **E0** in-ciphertext block version stamp. | The last unmigratable bump-and-wipe *by design*. |
| 14 | **E1** full-vector block stamp. | — |
| 15 | **E2d-1** `RealmSchemaEpoch`; first per-service bump (Sync 1→2). Last bump owing a relay redeploy (E2e). | — |
| 16 | `Place.mergedInto` + `PlaceNetworkFingerprint`; first non-empty step. | — |
| 17 | `PlaceRole`. | A type used as an op parameter cannot gain a field (nested reshape unsupported). |
| 18 | `SnappedRoute`. | Same rule, third confirmation. |
| 19 | Local `PendingContentEmit` journal. | ⚠⚠ Registered **no core step** → derived core floor = current → every store wiped for a bump that reshaped nothing. |
| 20 | Per-lane tombstone cursor row. | Fix for v19: **every core bump registers a `schemaMigration(CORE_SERVICE_ID, …)` step**, empty if nothing reshaped. |
| 21 | **EI-1** keyed entity ids (`sync-entity-identity.md`). | Deliberate cut — renamed keys orphan `SyncMetadata`/`LocalWriteMetadata`/`ResolvedDataId`; wipe was the safe outcome. |
| 22 | `isA` core op (sorted folder paging SP-2) — a **fence**, not a reshape. | A new op nested in a callstack fails at *decode*, before E3-1's typed error can fire; only a core bump turns it into a named announce refusal. |

Per-service chains at the cut: File 17, Sync 9, Calendar 5, Health 4, Mia 4, Place 3, and App,
Contact, Identity, Inference, Photo and Timeline 2. All of them were deleted. The non-empty steps were
File v15 (`dropType PendingMove`), Health v3 (`dropProperty Workout/referenceRoot`) and Sync v5/v7/v8
(the `PendingContentEmit` reshapes).

### A.2 `RELAY_PROTOCOL_VERSION` (pre-reset v1 → v4)

| v | Change | Min |
|---|---|---|
| 1 | E2e baseline — the 426 gate re-keyed off the format version. | 1 |
| 2 | F4 domain-separation tags on realm-member admit/revoke signatures (auth-contract change). | 1 — ordinary sync kept working. |
| 3 | EI-1 keyed entity ids — a *value* change: old clients diverge silently. | 3 |
| 4 | CR-2 B3 `EdgedPush` / `CascadedTombstonePush` / `reap`; hard cutover with relay reset. | 4 |

The relay's own SQL schema (`RelayDatabase`) had 25 migrations at the cut. They were squashed into one
`version = 1` migration, which is valid only because the relay goes onto a fresh volume.

⚠⚠ **The operational cut has not happened yet.** The reset merged 2026-10-27 is the *code* side. The
relay and commerce databases, both buckets and every client store are wiped at the **planned
production reset (date TBD — record it here when done)**, by
[`how-to/reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md). Until that line carries
a date, a relay running a pre-reset image still holds pre-reset rows and must not be served a
protocol-1 build.

**Pre-reset reshapes ride the same cut, with no bump.** Wire changes merged between the code-side reset and the
operational reset — the §7d length prefixes, F5b, the announce envelope, the `deviceName` split and
the server-side migration squashes (workstreams A–D), plus envelope authentication and F7 (see
`RelayProtocol.kt`) — stay inside protocol **1** and every service at version **1**. That is sound
only because **no store survives the reset**: nothing exists that was written in the earlier shape,
so there is nothing for a bump to fence or a step to carry. ⚠ The day the reset is recorded above,
this exemption ends and HR #9 applies in full.

**Play `versionCode` 6 is the first protocol-1 build to reach testers.** `versionCode` 5 is
superseded and never goes live: it predates the A–D reshapes, so it would speak protocol 1 in a
shape the reset relay does not.

⚠ **The relay fencing floor at the reset.** Post-reset protocol 1 is the version every release client
speaks, and epoch fencing is in it, so `RELAY_PROTOCOL_FENCING_FLOOR_VERSION = 1` still holds.
⚠⚠ A *pre-reset* build that announces protocol 1 (pre-F4, pre-EI-1) would pass the `[1, 1]` gate
and diverge silently. This is safe only because no such build exists outside our own devices, all
of which are cleared at the operational cut. Never install one against a post-reset relay.

### A.3 The server half of the 2026-10 re-cut (code 2026-10-28; the operational reset is still ahead)

The coming complete reset recreates the relay, commerce and dev-lab (`tenshu.xyz`) databases empty
(⚠ not yet done), so both servers' SQL chains were squashed again ahead of it — on top of the code-side reset's relay
squash. Neither touches a client format or `RELAY_PROTOCOL_VERSION`.

| Server | Before | After | Old DB on boot |
|---|---|---|---|
| Relay (`RelayDatabase`, SQLite) | v1 (release-cut baseline) + v2 `erased_realm` + v3 F3 sealed share meta + v4 `VACUUM` | one v1; the pre-F3 revoke `UPDATE` and the pre-v9 `byte_len DEFAULT 0` deleted | refused: *"wipe it"* |
| Commerce (`CommerceDatabase`, Postgres) | v1–v8 | one v1; `acceptance.kind` `NOT NULL`; append-only triggers on `invoice` / `credit_note` / `acceptance` | refused: *"wipe it"* |

⚠⚠ **The version number cannot tell the chains apart** — a pre-re-cut database at version 1 looks like a
new one. Each new baseline therefore writes a `schema_baseline` row (`cut = '2026-10-reset'`), and both
runners refuse any versioned database without it, rather than silently skipping migrations whose numbers
it has already passed. As at the first cut, the series before it is closed: post-re-cut v1 is not
pre-re-cut v1. As-built: `sync-relay-persistence.md` §0.1, `commerce-accounts-and-sessions.md` §9.10.
The relay's legacy wire tolerances went in the same change (bare `SyncBlock`/`DeleteBlock` → 400, not
426; `referenceId`/`hlc`/`edges` on `/sync/content/declare` are unknown keys; `/sync/content/reap`
requires `relayProtocolVersion`) — see `sync-relay-api.md`.

### A.4 The client half of the 2026-10 re-cut (2026-10-28) — still version 1

A second wipe-everything moment, before the Play open test's first build (versionCode 6): every
client store and the relay are reset, so shapes that would otherwise owe a migration were changed
**in place, at version 1**, and recorded here instead of as chain steps. ⚠ The same caveat as A.1
applies: a build from before this date is on a different shape under the same numbers. Never install
one against a post-re-cut proxy or relay.

| Shape | Before | After | Why |
|---|---|---|---|
| `AnnounceSession` params | `appId, expected: VersionVector` | `appId, current: VersionVector, requires: VersionVector` | The client's requirement and its current version are different numbers (`core-version-compatibility.md` §4.3/§4.7); one field refuses a compatible client. |
| `SessionAdmission` | `admitted, proxyVector, proxyFloors, mismatches` | `admitted, updateProxy, updateClient, proxyVector, wireFloors, mismatches, proxyAppId, proxyAppVersion` | §4.7's verdict and D4's host identity. The verdict is two booleans, not a constant family: a constant member added later would be unparseable to an un-rebuilt client — on the negotiation channel itself. |
| `ServiceVersionMismatch` | `serviceId, announced, floor, current` | `serviceId, clientCurrent, clientRequires, proxyFloor, proxyCurrent` | Carries both client numbers, so the floor is judged on `current` and the ceiling on `requires`. |
| `tenshu/identity/deviceHardware` | — | new synced row `deviceId, hardwareName` + relation `tenshu/identity/hardwareDescriptions` | `tech-debt.md` *Device Naming Conflict*: `Device.name` is the user's label, the hardware model is published by the device itself. A row of its own because `Device` has two whole-entity-LWW writers and only one of them knows the hardware. |

Deliberately **not** in the envelope: the proxy's operation-id set (`tech-debt.md` *"Removing an
operation has no compatibility window"*, option 1). `core-version-compatibility.md` §4.5/§4.6 chose
O6 (the version vectors already say what each side has) plus a floor bump for removals (D6); a
vocabulary exchange is O5, deferred to the first un-forceable client.

`SessionAdmission.wireFloors` is today the same walk as the store floor — VC-1's step classification
is what will make the two differ — and `currentVersionRequirement` returns the current vector until
then. Both were shaped now so that VC-1 changes functions, never the frozen wire.

### A.5 Signed admission/membership bytes changed *inside* protocol 1 (2026-10-28)

The complete production reset before the Play open test (relay, commerce and every client store
wiped) re-cut protocol 1 rather than bumping it: ⚠ **no format version and no
`RELAY_PROTOCOL_VERSION` moved**, so this entry is the only record that the bytes did.

* **§7d length prefixes** ([`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)):
  the A33 cert, A33 revocation, admission-key announcement and the three membership messages are
  now `LP(tag) ‖ LP(field)…`, with tags `tenshu-admit-cap-v2`, `tenshu-admit-revoke-v3`,
  `tenshu-admit-announce-v2`, `tenshu-member-{cap,bind,revoke}-v2`.
* **F5b**: `AdmissionRevocation.deviceA33PublicKey` required; relay `admission_revocation.device_a33_public_key`
  is `NOT NULL` in A.3's re-squashed `version = 1` baseline (edited in place, no v2 — valid only because
  the marker refuses every pre-re-cut database).

* **B1**: the pairing/realm family is tagged + length-prefixed too — `tenshu-admit-delegation-v1`,
  `tenshu-realm-bootstrap-v1`, `tenshu-recovery-claim-v1`, `tenshu-offer-response-v1`,
  `tenshu-hlc-tip-v1` — so the delegation, genesis, recovery-claim, offer-response and HLC-tip
  signatures of a pre-re-cut build no longer verify either.
* The relay's `schema_baseline` marker is **`2026-10-reset-b`** (A.3's `2026-10-reset` + this in-place
  edit); a database carrying the earlier marker is refused.

No composite changed shape (`SchemaShapeBaseline` untouched): the signed bytes live inside opaque
`TByteArray` fields (`AdmissionCertGrant.certChain`, `MemberAuthorityKey.capability`,
`AdmissionKeyAnnouncement.signature`), so they change *value*, not layout.

⚠⚠ The A.2 fencing caveat applies with a sharper edge: a build from **before** this re-cut still
announces protocol 1 and passes the `[1, 1]` gate, syncs normally, and then **fails closed** on every
admission/membership signature (401 on pair-via-delegated-admitter, admission revoke, member admit /
revoke; its stored certs and grants never verify). Clients and relay ship together (Play
versionCode 6 + relay deploy) and no pre-re-cut build may onboard against the reset relay.

### A.6 The 1.0.1 re-cut (2026-11-05) — the last free one

**Why it was free.** Control-Android was still in Play review; every other app had been approved
(from a 2026-10-30 build) but held back by managed publishing, and Control-iOS had been rejected
once. No build had reached a user, the only stores were the maintainer's test devices, and the
relay and commerce databases hold test data only. Every app ships again together as **1.0.1**
(Android `versionName`, iOS `MARKETING_VERSION`, desktop `packageVersion`), so the approved 1.0
binaries are superseded before they go live.

| What | Before | After |
|---|---|---|
| `CalendarService` | v4 (empty 1→2, 2→3; real `CalendarServiceMigrationV3toV4`) | **v1**, current shape, no steps |
| `ContactService` | v2 (empty `ContactServiceMigrationV1toV2`) | **v1**, current shape, no step |
| Contacts relocation pass | `RelocateContactPhotos` moved pre-v2 rows' big photos, then swept | deleted; the schedule runs the sweep alone as `SweepContactPhotos` (no network, battery not low) |
| Relay SQL (`RelayDatabase`) | v1 + v2/v3 (`pairing_offer_response.reg_*`) + v4 (`share_takedown`) + v5 (`DROP device_cursor`), marker `2026-10-reset-b` | one v1, marker **`2026-11-reset`** |
| Commerce SQL (`CommerceDatabase`) | v1 + v2 (unverified-account expiry triggers), marker `2026-10-reset` | one v1, marker **`2026-11-reset`** |
| iOS shared store | `FileObjectStore`, S0b-4's drain-gated cutover, the launch reset | SQLite only (`ios-shared-store-sqlite.md` B.22) |

Old-build bridges deleted in the same change (each tolerated rows, files or peers that only an older
build of ours could have produced) are listed in the PR description; the ones deliberately kept or
deferred are named there too.

⚠⚠ **The same caveats as A.1–A.5, once more.** Post-re-cut Calendar v1 is not pre-re-cut Calendar v1,
and protocol 1 did not move. An approved 1.0 build (2026-10-30) would announce the same numbers in
older shapes: it must never be published or onboarded against the reset relay. Every test device is
cleared or reinstalled at the operational reset, and a relay or commerce database from before it is
refused at boot by the new marker (*"wipe it"*).

⚠⚠ **This was the last one.** From the first 1.0.1 install on a user's device, House Rule #9 applies
in full: every reshape bumps and ships its step, and nothing here may be squashed again.
