package org.tenshu.core.common.schema.migration

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.core.TContext

/**
 * The id `:core` migrates and fences under. `:core` registers no `ServiceSchema`
 * — its version rides the `VersionVector`'s dedicated `coreVersion` field (§4),
 * which is [org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION] — so a
 * sentinel id is what lets it be *addressed* like a service: registered as
 * `schemaMigration(CORE_SERVICE_ID, fromVersion = …)`, floored by
 * [SchemaMigrationRegistry.floorFor], reported in an announce mismatch.
 *
 * ## Registering a core step
 *
 * Core steps are registered in `coreCommonModule`, like any service's beside its
 * `service(...)` declaration. At the release baseline
 * (`core-schema-evolution.md` Appendix A) the core chain is **empty**:
 * [TENSHU_STORAGE_FORMAT_VERSION] is 1, so there is nothing below it to reach.
 *
 * ⚠⚠ **Every bump of [TENSHU_STORAGE_FORMAT_VERSION] registers
 * `schemaMigration(CORE_SERVICE_ID, fromVersion = N - 1) {}`**, empty if nothing
 * reshaped. The floor walks back only over registered steps
 * ([StoreSchemaMigrations.coreReadableFloor]), so a bump without one leaves the floor
 * at the new version, and every installed store at N - 1 is **wiped** (Appendix A records a
 * pre-release bump that did exactly that). `:core` declares no `ServiceSchema`, so
 * [SchemaMigrationRegistry.verify] cannot catch it; `ServiceMigrationChainsReachTheirVersionTest`
 * does.
 *
 * ⚠ **Contiguity trap.** [SchemaMigrationRegistry.verify] requires a chain to be
 * contiguous, so a deliberate wipe-bump (no step) leaves every earlier step **inert**:
 * the floor can never walk past the gap. Don't "close the gap" later. That would bring back
 * the very migration path the wipe was meant to cut. Appendix A's EI-1 entry is the
 * worked example.
 *
 * ⚠ `SchemaFenceStatus.CORE_SERVICE_ID` in `:sync-service` holds the same string
 * for the reader-side fence. It is a separate, wire-facing constant in a
 * module `:core-common` cannot depend on; keep the two in step.
 */
const val CORE_SERVICE_ID = "tenshu/core"

/**
 * What the store-open migration seam needs from the runtime
 * (`core-schema-evolution.md` §8.1/§8.4): the live [SchemaRegistry] — which
 * carries both the declared per-service versions ([SchemaRegistry.serviceSchemas])
 * and the module-registered [SchemaMigration] chains
 * ([SchemaRegistry.schemaMigrations]), so nothing is declared twice — plus the
 * [TContext]/deviceId a [MigrationContext] is built from.
 *
 * Passed to every durable store's open call — `openSqliteObjectStore(...)` and
 * `IndexedDbObjectStore.open(...)` (the iOS shared replica reads its versions from it,
 * `replicaBuildOf`). `null` means no per-service version table and a wipe-only
 * gate.
 */
class StoreSchemaMigrations(
    val context: TContext,
    val schemas: SchemaRegistry,
    /** For [MigrationContext.deviceId] — what a `derive` may consult (§8.4). */
    val deviceId: String,
) {

    /**
     * The lowest `TENSHU_STORAGE_FORMAT_VERSION` this build can still migrate a
     * store forward from — `:core`'s readable floor, derived from its registered
     * migration chain exactly as every service derives its own
     * ([SchemaMigrationRegistry.floorFor]). The store-open gate wipes below it
     * (`core-schema-evolution.md` §8.9).
     *
     * ⚠ The format version **is** `:core`'s slot in the `VersionVector`
     * (`currentVersionVector(coreVersion = TENSHU_STORAGE_FORMAT_VERSION)`), which
     * is what makes this the same question the per-service gate asks — and what
     * lets a core migration be registered as an ordinary
     * `schemaMigration(CORE_SERVICE_ID, fromVersion = …)`.
     *
     * With no core chain registered, as at the release baseline, this equals
     * [TENSHU_STORAGE_FORMAT_VERSION]. A store one version back then wipes. Each core step
     * (see [CORE_SERVICE_ID]) lowers the floor by one, so that store migrates instead.
     * Below the floor, or above the current version, the store always wipes.
     *
     * ⚠⚠ This is the **store** floor only. The **announce** floor for `:core` never
     * consults the chain (see [org.tenshu.core.common.schema.currentVersionFloors]).
     * A core step therefore lets this build *read* an older store, but it still refuses an
     * older *client*. That surprises people, and it is deliberate.
     */
    fun coreReadableFloor(): Int = chains().floorFor(CORE_SERVICE_ID, TENSHU_STORAGE_FORMAT_VERSION)

    /**
     * The declared per-service version vector — what a store's recorded vector is
     * compared against, and what a wipe re-stamps.
     */
    fun declaredServiceVersions(): Map<String, Int> =
        schemas.serviceSchemas().associate { it.id.get() to it.version }

    /**
     * [serviceId]'s derived readable floor. ⚠ Takes the declared version from the
     * live schemas, so a service this build does not register floors at 0 — which
     * cannot be below any recorded version, and so is never *itself* a reason to
     * wipe (the migrator walks DECLARED services, so a recorded-but-unknown one is
     * simply never judged).
     */
    fun readableFloorOf(serviceId: String): Int =
        chains().floorFor(serviceId, declaredServiceVersions()[serviceId] ?: 0)

    private fun chains(): SchemaMigrationRegistry =
        SchemaMigrationRegistry().apply { schemas.schemaMigrations().forEach(::register) }
}

/**
 * Seam 1 of the §8.4 `SchemaMigrationService` sketch — the eager store
 * migration that replaces the wipe at open (§8.1): at open, any service whose
 * recorded version trails its declared version runs its registered
 * [SchemaMigration] chain over the rows it owns, rewriting in place, then
 * bumps the recorded version. Journaled per step ([StoreMigrationJournal]:
 * marker → rewrite → clear) so a crash resumes idempotently.
 *
 * Recorded-version model (§8.4 closing note): `PRAGMA user_version` is the
 * core/global format probe, judged by *reachability* against the derived core
 * floor (§8.9) rather than by equality — a mismatch does not imply a wipe; the
 * per-service vector lives in a `schema_service_versions` table inside the store
 * itself — additive, self-creating, and NOTHING here is serialized to the wire
 * (no `TENSHU_STORAGE_FORMAT_VERSION` bump owed: a store lacking the table is
 * stamped current at open, and code that does not know the table ignores it).
 *
 * Multi-step chains run as one FULL store pass per step — the rows step N
 * commits are re-read from the store by step N+1 (whose `from` structurally
 * equals step N's `to`, enforced by [SchemaMigrationRegistry.verify]). This
 * sidesteps the §8.5 intermediate-dto hazard by construction; the codec's
 * `to`-bound overlay closes the remaining encode-side half (see
 * [StoreRowMigrationCodec]).
 *
 * Runs synchronously at open, on the store's own connection, BEFORE cache
 * hydration and before the store is handed out — the store's writer-thread /
 * mailbox model is never in play. With zero drift the whole thing costs a
 * handful of tiny SELECTs (versions + journal) and touches no rows.
 */
internal class StoreSchemaMigrator(
    private val port: StoreMigrationPort,
    private val journal: StoreMigrationJournal,
    config: StoreSchemaMigrations,
) {

    private val live = config.schemas
    private val context = config.context
    private val deviceId = config.deviceId

    private val registry = SchemaMigrationRegistry().apply {
        live.schemaMigrations().forEach(::register)
    }

    // The §8.4 init-time chain-integrity fail-fast, wired here at store open
    // (the first consumer of the chains) — needs the CONCRETE SchemaRegistry
    // for the nested-reshape scan (§8.5). Run once per open, before any
    // migration decision.
    private var verified = false
    private fun verifyOnce() {
        if (verified) return
        registry.verify(live)
        verified = true
    }

    /**
     * Re-runs steps whose journal marker survived a crash. Call at open,
     * before [migrateStoreAtOpen]. Idempotent by the journal's transaction
     * discipline: a surviving marker implies the step's rewrite transaction
     * never committed, so every row is still `from`-shaped and the re-run
     * decodes/rewrites deterministically (see [StoreMigrationJournal]).
     *
     * @return the number of resumed steps.
     */
    fun resumeIncompleteMigrations(): Int {
        val markers = journal.incompleteSteps()
        if (markers.isEmpty()) return 0
        verifyOnce()
        val recorded = if (port.hasServiceVersionTable()) port.readServiceVersions() else emptyMap()
        markers.forEach { marker ->
            // Invariant check: clear-marker and version-bump commit together,
            // so a live marker's service must still be recorded AT the
            // marker's fromVersion. Anything else is an impossible state —
            // fail the open loudly rather than re-migrate migrated rows.
            val recordedVersion = recorded[marker.serviceId]
            check(recordedVersion == marker.fromVersion) {
                "Incomplete-migration marker for '${marker.serviceId}' v${marker.fromVersion}→" +
                    "v${marker.fromVersion + 1} but the recorded service version is " +
                    "$recordedVersion — journal and version table have diverged."
            }
            val step = registry
                .stepsFor(marker.serviceId, marker.fromVersion, marker.fromVersion + 1)
                .firstOrNull()
            if (step == null) {
                // The chain was cut (steps deleted) under an in-flight
                // marker: the rows can no longer be read. Same posture as
                // below-floor — wipe and stamp current.
                println(
                    "StoreSchemaMigrator: no registered step for journaled migration " +
                        "'${marker.serviceId}' v${marker.fromVersion} — wiping store (below floor)."
                )
                wipeAndStampCurrent()
                return markers.size
            }
            runStep(step, progress = {})
        }
        return markers.size
    }

    /**
     * The store-open walk (§8.1). Per service, in deterministic (sorted-id)
     * order:
     *  - no version table → a fresh store, or one written before the table existed: stamp current,
     * touch no rows
     *    (see [StoreMigrationPort.hasServiceVersionTable]);
     *  - recorded == declared → no-op;
     *  - recorded < floor, or recorded > declared (store written by newer
     *    code) → the wipe path: no step can read those rows;
     *  - otherwise → run the chain's steps in order, each journaled.
     */
    fun migrateStoreAtOpen(progress: (MigrationProgress) -> Unit = {}) {
        // §8.4 init-time fail-fast: broken chains refuse the proxy start on
        // EVERY configured open — including the stamp-current path — not only
        // once drift exists (a chain that first fails when a user's store
        // actually needs it is the worst possible discovery time).
        verifyOnce()

        val declared = live.serviceSchemas()
            .associate { it.id.get() to it.version }

        if (!port.hasServiceVersionTable()) {
            port.transaction { port.stampServiceVersions(declared) }
            return
        }

        verifyOnce()
        val recorded = port.readServiceVersions()

        declared.entries.sortedBy { it.key }.forEach { (serviceId, current) ->
            when (val from = recorded[serviceId]) {
                // Service newly registered in this build — no earlier build
                // could have written its rows; stamp current.
                null -> port.transaction {
                    port.stampServiceVersions(mapOf(serviceId to current))
                }

                current -> Unit // in step — the no-op path

                else -> {
                    if (from > current) {
                        // A newer build wrote this store (service-level
                        // downgrade under a matching user_version): current
                        // code cannot read forward-shaped rows.
                        println(
                            "StoreSchemaMigrator: '$serviceId' recorded v$from > declared " +
                                "v$current — store written by newer code; wiping."
                        )
                        wipeAndStampCurrent()
                        return
                    }
                    if (from < registry.floorFor(serviceId, current)) {
                        println(
                            "StoreSchemaMigrator: '$serviceId' recorded v$from is below the " +
                                "readable floor — wiping store."
                        )
                        wipeAndStampCurrent()
                        return
                    }
                    registry.stepsFor(serviceId, from, current).forEach { step ->
                        runStep(step, progress)
                    }
                }
            }
        }
    }

    /**
     * One journaled step: marker (own transaction) → decode/migrate every row
     * of the step's types → one transaction committing all rewrites +
     * deletions + membership sweep + marker clear + version bump. See
     * [StoreMigrationJournal] for why this split makes a crash re-run
     * idempotent.
     */
    private fun runStep(step: SchemaMigration, progress: (MigrationProgress) -> Unit) {
        val marker = StoreMigrationStep(step.serviceId, step.fromVersion)
        port.transaction { journal.beginStep(marker) }

        val codec = StoreRowMigrationCodec(step, live, context, deviceId)
        val rewrites = ArrayList<ObjectStore.DbEntry>()
        val dropped = HashSet<Long>()
        port.rowsOfTypes(step.migrations.map { it.typeId }.toSet()).forEach { row ->
            when (val migrated = codec.migrateRow(row)) {
                null -> dropped += row.dataIdHash
                else -> rewrites += migrated
            }
        }

        // Retired property slots ([DroppedProperty]): stripped from every surviving row of the type,
        // on top of that row's composite rewrite when the step migrates the type as well.
        val strippedRows = stripDroppedProperties(step, rewrites, dropped)
        if (strippedRows.isNotEmpty()) {
            val replaced = strippedRows.map { it.dataIdHash }.toSet()
            rewrites.removeAll { it.dataIdHash in replaced }
            rewrites += strippedRows
        }

        // Rows deleted by decision leave dangling LongValue members behind in
        // array rows (`TList.ID` rows hold member hashes) — sweep them so a
        // later array read never dereferences a deleted row. Deterministic
        // and idempotent like the rewrites (re-filtering an already-swept
        // array changes nothing).
        val membershipSweeps = if (dropped.isEmpty()) {
            emptyList()
        } else {
            port.rowsOfTypes(setOf(TList.ID)).mapNotNull { arrayRow ->
                val kept = arrayRow.fields.filterNot { member ->
                    member is ObjectStore.DbValue.LongValue && member.value in dropped
                }
                if (kept.size == arrayRow.fields.size) null else arrayRow.copy(fields = kept)
            }
        }

        port.transaction {
            rewrites.forEach(port::rewriteRow)
            dropped.forEach(port::deleteRow)
            membershipSweeps.forEach(port::rewriteRow)
            journal.clearStep(marker)
            port.stampServiceVersions(mapOf(step.serviceId to step.fromVersion + 1))
        }

        if (dropped.isNotEmpty()) {
            // Data loss is always written down, never implied (§8.4) — the
            // migration DECIDED these drops; say so where an operator can see.
            println(
                "StoreSchemaMigrator: '${step.serviceId}' v${step.fromVersion}→" +
                    "v${step.fromVersion + 1} dropped ${dropped.size} row(s) by migration decision."
            )
        }
        progress(
            MigrationProgress(
                serviceId = step.serviceId,
                fromVersion = step.fromVersion,
                toVersion = step.fromVersion + 1,
                rewrittenRows = rewrites.size,
                droppedRows = dropped.size,
            )
        )
    }

    /** The rows [step]'s [SchemaMigration.droppedProperties] change, with those slots removed. */
    private fun stripDroppedProperties(
        step: SchemaMigration,
        rewrites: List<ObjectStore.DbEntry>,
        dropped: Set<Long>,
    ): List<ObjectStore.DbEntry> {
        if (step.droppedProperties.isEmpty()) return emptyList()
        val retiredByType = step.droppedProperties.groupBy({ it.typeId }, { it.propertyId })
        val rewritten = rewrites.associateBy { it.dataIdHash }
        return port.rowsOfTypes(retiredByType.keys).mapNotNull { stored ->
            if (stored.dataIdHash in dropped) return@mapNotNull null
            val row = rewritten[stored.dataIdHash] ?: stored
            val retired = retiredByType.getValue(row.type)
            if (row.properties.keys.none { it in retired }) return@mapNotNull null
            row.copy(properties = row.properties.filterKeys { it !in retired })
        }
    }

    private fun wipeAndStampCurrent() {
        val declared = live.serviceSchemas().associate { it.id.get() to it.version }
        port.transaction {
            port.wipeAllRows()
            port.stampServiceVersions(declared)
        }
    }
}
