# Move `Workout.Streams` to the content plane (read-scaling option B)

**Status:** **COMPLETE (B1 + B2 + B3 docs) 2026-07-23** — schema flip + resolver + writer + emit +
factory widening + storage-format **v10** bump, all pieces 1–8 below; as-built deltas in
§"As built". The `spike/workout-streams-content-plane` branch is superseded by this landing. Original scoping (2026-07-14) below, kept for the piece map.

The bulk Takeout import OOMs Control because the
`ObjectStore` keeps the whole dataset in an in-memory `MutableMap` (`SqliteObjectStore` is cache-aside
— reads never touch SQLite), and each imported workout's dense route/HR blob (`Workout.Streams`, an
eager stored field) sits in that map forever (~56 KB/record → 256 MB at ~4.5k records → crash). See
`fit-read-scaling.md` for the diagnosis and the A/B/C options; this scopes **B**: store the stream
blob on the **content plane** so the `Workout` entity stays small, exactly as `File` keeps its bytes
off the entity and on the content plane.

## Why B is a bounded slice, not a core project

The content plane is already generic. It is not a File feature — it is a **`propertyId`-routed**
subsystem in `:core-common` + `:sync-service-proxy`, and `File` is one consumer:

- **`FileDatabase`** (`:core-common`) — the external byte store (`writeByteArray(path, bytes)` /
  read). Keyed by an opaque string path. Not File-specific.
- **`ExternalPropertyWriter`** (`:core-common`) — a **registered list**; each declares `supports(propertyId)`
  and writes incoming synced bytes to the external store. `FileBytesPropertyWriter` is one impl.
- **`OnDemandResolutionImpl` / `SyncProcessor` / the manifest applier** (`:sync-service-proxy`) — route
  content blocks "by their decoded `DataBlockPayload.propertyId`" (its words), chunk/seal/reassemble
  (ADR 0017), fetch lazily from the relay. Generic.
- **`dynamicProperty(Syncable(SyncStrategy.OnDemand))`** (schema DSL) — the property kind that keeps
  the blob *off* the entity and resolves it on demand. `File.Data` uses it.

So B = give `Workout.Streams` the same treatment `File.Data` has, registering a workout writer +
resolver. The machinery underneath is untouched.

## The pieces (each mirrors a File original)

| # | Piece | File-service template | Workout version (new) | Module |
|---|---|---|---|---|
| 1 | Schema property | `File.Data` = `dynamicProperty<File,TByteArray>(traits=Syncable(OnDemand)){ getData() }` | `Workout.Streams` — **change** from `Property.Value<TByteArray>` to `dynamicProperty<Workout,TByteArray>(traits=Syncable(OnDemand)){ getStreams() }` | `:health-service` |
| 2 | Resolver op | `GetData` / `File.getData()` | `GetWorkoutStreams` / `Workout.getStreams()` | `:health-service` |
| 3 | External-store key | `resolveExternalPath` → `File.path` (or SharedFile's `/.shared/{realmId}/{fileId}`) | synthetic `/.health/streams/{workoutId}` | `:health-service-proxy` |
| 4 | Property writer | `FileBytesPropertyWriter : ExternalPropertyWriter` (`supports(File.Data.id)`, writes to `FileDatabase`) | `WorkoutStreamsPropertyWriter` (`supports(Workout.Streams.id)`, resolves Workout→`/.health/streams/{id}`, writes to `FileDatabase`) | `:health-service-proxy` |
| 5 | Host emit | `UploadFileImplementation`: `save(entity)` → `awaitEmitIdle()` → `emitPropertyBlock(File.Data.id, bytes)` | `RecordWorkoutImplementation`: after saving the Workout, if streams present, `awaitEmitIdle()` → `emitPropertyBlock(Workout.Streams.id, streamBytes)` | `:health-service-proxy` |
| 6 | Register the writer | file writers registered in the file proxy init | add `WorkoutStreamsPropertyWriter` to the health proxy init | `:health-service-proxy` |
| 7 | Read call-sites | `entity.get(File.Data)` | `FitProjectionRepository.workout(id)` already reads `relations(Workout.Streams)` + `entity.get(Streams)` — a dynamic on-demand property reads the same way (returns null → not-yet-resolved), so the detail read barely changes; the **list** read already excludes streams (unchanged) | `:fit-shared-data` |
| 8 | Write (import/record) | — | `HealthImportWriter` / `addWorkout` stop passing `streams=` as a create field; instead the record op emits the block (piece 5). One code path. | `:fit-shared-data` + `:health-service-proxy` |
| 9 | Migration | — | Existing stored `Workout.Streams` fields (recorded/imported before this) → one-time: on read-miss, or a boot pass that re-emits stored blobs through the new path. Or accept that pre-existing streams are dropped (routes re-appear only for workouts recorded/imported after). | decide |

## Code sketch (the load-bearing three)

**1. Schema** (`Workout.kt`) — the one-line-in-spirit change that does the heavy lifting:
```kotlin
// was: private val workoutStreams: Property.Value<TByteArray> = valueProperty(id=".../streams", schema=TByteArray.Schema)
private val workoutStreams: Property.Dynamic<TByteArray> = dynamicProperty<Workout, TByteArray>(
    id = "${Workout.ID}/streams",
    receiver = Workout.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand)),
) { getStreams() }
val Workout.Companion.Streams: Property.Dynamic<TByteArray> get() = workoutStreams
```

**4. Writer** (`WorkoutStreamsPropertyWriter.kt`) — near-verbatim from `FileBytesPropertyWriter`:
```kotlin
class WorkoutStreamsPropertyWriter(private val fileDatabase: FileDatabase) : ExternalPropertyWriter {
    override fun supports(propertyId: String) = propertyId == Workout.Streams.id
    override fun write(ctx, targetEntityId, propertyId, value) {
        val bytes = value as? TByteArray ?: return
        val path = resolveExternalPath(ctx, targetEntityId, propertyId) ?: return
        fileDatabase.writeByteArray(path, bytes)
    }
    override fun resolveExternalPath(ctx, targetEntityId, propertyId): String? {
        val w = ctx.database.getData(targetEntityId) as? Workout ?: return null
        return "/.health/streams/${w.getField<TString>(Workout.Fields.WORKOUT_ID).get()}"
    }
}
```

**5. Emit** (`RecordWorkoutImplementation`) — the host side, mirroring `UploadFileImplementation` lines ~286–298:
```kotlin
// after saving the Workout entity, if the caller supplied stream bytes:
streamBytes?.let {
    syncBlockEmitterHolder?.emitter?.awaitEmitIdle()       // entity block HLC-stamped first (ADR 0015)
    syncBlockEmitterHolder?.emitter?.emitPropertyBlock(propertyId = Workout.Streams.id, value = it)
}
```

## Open questions (resolve during the spike)

- **Q1 — one shared `FileDatabase` for two subsystems?** Files and health-stream blobs would share the
  same on-disk byte store, namespaced by the `/.shared/…` vs `/.health/…` path prefix (the prefix
  convention already exists for shared files). Clean, or does health want its own store instance? Lean
  shared: it is a KV keyed by an opaque path; the prefix is the namespace.
- **Q2 — migration.** Simplest v1: **drop** pre-existing stored streams (routes re-appear only for
  workouts written after the change) — no migration code, and the Takeout re-import (which we want to
  re-run anyway) repopulates via the new path. If preserving already-recorded routes matters, a boot
  re-emit pass is a follow-up.
- **Q3 — does `RecordWorkoutImplementation` currently take the streams param the same way** the create
  field did? The write op signature changes (streams stop being a `create` field, become an emitted
  block). Confirm the op schema + IPC surface handles the separate block (it does for File; verify the
  health op wiring).
- **Q4 — wire-compat.** `Workout.Streams` changing from a stored value property to a dynamic on-demand
  property is a **schema shape change**; old peers expecting the eager field vs new peers resolving on
  demand. Ship lockstep (Fit + Control update together — already the case here), and confirm no other
  service reads `Workout.Streams` as an eager field.

## Wiring findings (from tracing the health proxy — the non-obvious cost)

A trace of `:health-service-proxy` against the file twin surfaced the real integration points, all in
the proxy assembly rather than the schema:

- **`healthServiceProxyImplementationFactory(id)` takes only the op id today** — the file factory takes
  `fileDatabase: FileDatabase`. So the health factory signature must gain `FileDatabase` to construct
  `GetWorkoutStreamsImplementation(fileDatabase)`. That ripples to **both** callers:
  `InProcessFitProxy` (test/desktop) and **Control's proxy assembly** (the `HealthService` host wiring
  in `:control-shared-proxy`). Control already owns a `FileDatabase` (files use it), so it is an inject,
  not a new store — but it is a cross-module signature change, and it is the bulk of the risk.
- **`ExternalPropertyWriter` registration** — `WorkoutStreamsPropertyWriter` must be added to the
  registered writer list the `SyncProcessor` consults (where `FileBytesPropertyWriter` is registered).
  Confirm that list is assembled per-service or centrally; the health service must contribute to it.
- **Emit plumbing** — `RecordWorkoutImplementation` needs the `syncBlockEmitterHolder` (the emit
  channel `UploadFileImplementation` uses). Verify the health record ops receive it (the file ops do via
  their execution context); if not, that seam is added too.
- **Read path** — `FitProjectionRepository.workout(id)` reads `relations(Workout.Streams)` on a one-shot
  `getAll()`. An `OnDemand` dynamic property resolves through `GetWorkoutStreams` (which reads the local
  `FileDatabase`); if the bytes are local (just imported) it returns them, else it triggers the relay
  fetch — same as `File.Data`. Confirm the one-shot terminal dispatches the resolver (deferred-properties
  §: one-shot reads *of a deferred prop* return null; an **OnDemand** prop is resolved, not deferred — but
  verify, because if the detail read comes back null the route silently vanishes).

None of these are hard individually — every one has a working file twin — but together they make B a
**cross-module slice** (`:health-service` + `:health-service-proxy` + `:control-shared-proxy` +
`:fit-shared-data`), which is why it is the next slice, not a fragment tacked onto this session.

## Effort & recommendation

**Medium — a real slice, ~6 new/changed files across `:health-service` + `:health-service-proxy` +
`:fit-shared-data`, plus a migration call (Q2).** The generic content-plane machinery is reused
untouched; the risk is entirely in the wiring (pieces 3–6) and Q3/Q4, which a spike settles fast
because every piece has a working File-service twin to copy.

A compiling spike ≈ the slice (pieces 1–8 must all land together to compile and round-trip), so it is
best done as one focused effort rather than a fragment. Recommend greenlighting it as the next slice;
this doc is the implementation plan. Once it lands, the Takeout import completes at full size (the
entity cache holds ~13.5k small workout rows, not their blobs), and routes stay available on the
detail screen via lazy resolve — and it advances the same "blobs off the entity cache" direction that
option C (bounded read-through store cache) would generalise for the TB-scale case.

## As built (B1, 2026-07-23)

All nine pieces landed in one PR, plus the pieces the scoping missed. Deltas vs the plan:

- **Piece 2b (missed by the scoping): the client-side Ro.** `operation(GetWorkoutStreams.Schema)`
  in the *common* module registers the schema, but `SchemaRegistry.findOperation` — which the
  `dynamicProperty` template `{ getStreams() }` dispatches through — resolves the operation via the
  **client** module's `operation(interfaceClass, protocolSchema, roFactory)` registration. A
  `GetWorkoutStreamsRo` (verbatim twin of `GetDataRo`) in `:health-service-client` was required.
  Symptom while missing: every select projecting `Workout.Streams` failed with
  `Operation tenshu/health/getWorkoutStreams not found`, surfaced to the repository as an opaque
  `tenshu/core/error/internal` — worth remembering for the next on-demand property.
- **Factory signature:** `healthServiceProxyImplementationFactory(id, fileDatabase,
  syncBlockEmitterHolder = null)` — `fileDatabase` deliberately **required** (every caller has one;
  an optional default would silently strand stream blobs). Ripple: 3 Control `PlatformProxyModule`s
  (deps were already in scope) + `HealthTestProxy` + 7 test call sites.
- **Writer registration is a single component, not a list** (stale assumption in "Wiring
  findings"): the dispatcher looks up ONE `ExternalPropertyWriter` on the `TContext`. New generic
  `DelegatingExternalPropertyWriter` (`:core-common`) fans out by `supports()`; Control registers
  `Delegating(FileBytesPropertyWriter, WorkoutStreamsPropertyWriter)` on all three platforms.
- **Emit rides the modern content path.** `RecordWorkoutImplementation` became an
  `AsyncOperationImplementation(fileDatabase, syncBlockEmitterHolder)` (the op already carried
  `IoOperation` → dispatch-compatible): save entity → write blob to
  `/.health/streams/{workoutId}` → `awaitEmitIdle()` (ADR 0015 entity-before-bytes) →
  `fileContentEmitter.emitFileContent(...)`, falling back to `emitPropertyBlock` when no content
  emitter is wired. A workout has no `contentId` value property, so the manifest is addressed by
  entity id (the documented pre-content-addressing fallback) — **still chunked + CEK-sealed**.
- **Q1 → shared `FileDatabase`**, namespaced `/.health/…` (mirrors `/.shared/…`).
- **Q2 → drop pre-existing streams**, executed via the **storage-format v10 bump** (HR #9: the
  entity row/wire shape changed — old rows carry a property new code no longer registers). The
  Phase-0 reset wipes stale stores; a re-import repopulates. ⚠ This bullet said "Deployed relays
  must move to v10 (426 gate) — coordinate the redeploy" until 2026-08-11. **No redeploy is owed**:
  schema-evolution E2e (2026-07-26) took payload versions off the relay, three days after this was
  written. The 426 gate reads `RELAY_PROTOCOL_VERSION`, never the storage format version.
- **Q3 → op wire param unchanged** (`streams` optional byteArray); only the proxy-side persistence
  changed.
- **Q4 → lockstep + bump** (above). No other reader of `Workout.Streams` existed
  (`FitProjectionRepository` detail read only; History list already byte-less).
- **Read path (B1 scope):** a local read resolves synchronously through `GetWorkoutStreams`
  during the select — the fit detail read works unchanged on the writing device. **Cross-device**,
  a not-yet-fetched blob leaves a `PendingOnDemandProperty` that only Maybe/stream terminals
  materialise — `getAll()` does not → the detail read must move to a materialising terminal
  (**B2**), alongside a receiver-side system test (`:system-test-utils` gets health deps + the
  workout writer in its composite then).
- **Threat model:** A34 always *described* content-plane custody for the dense payloads; the code
  now matches it. Dated as-built note added to A34 (no trust-boundary change in either direction —
  both the old eager block and the new chunks are sealed; HR #1 unchanged).

## As built (B2, 2026-07-23)

- **Detail read moved to a materialising terminal.** `FitProjectionRepository.workout(id)` was
  `getAll()` + client-side `firstOrNull` — and `getAll()` does **not** materialise a
  `PendingOnDemandProperty`, so a blob not yet local would silently read as "no route" forever on
  a second device. Now the `getRemoteFile` shape: `workouts.search { workoutId equalTo id }
  .select { fields(…); relations(Streams) }.get()` — the Maybe `get()` terminal dispatches
  `OnDemandResolution`, fetching the chunks before the entity crosses IPC. (Also drops the old
  fetch-all-rows-then-filter pass.)
- **`workoutStreamsPath(workoutId)`** promoted to a public top-level helper in
  `:health-service-proxy` — the one string the writer, resolver, record emit, and tests must
  agree on.
- **`WorkoutStreamsOnDemandSystemTest`** (`:system-test-utils`, twin of
  `FileBytesOnDemandSystemTest`): host records a 48 KiB blob → joiner converges on the small
  entity with **no blob ingested** (asserted — the pre-option-B behaviour shipped it into every
  receiver) → the fit detail chain triggers the on-demand fetch → bytes land byte-identical
  under `/.health/streams/{id}`. The harness gained the health modules, `initHealthService()`,
  the health factory in the op chain, and the production-mirroring composite writer.
