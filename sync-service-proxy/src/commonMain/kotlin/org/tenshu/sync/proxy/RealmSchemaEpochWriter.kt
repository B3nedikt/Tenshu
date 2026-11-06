package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.containedTypeIds
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.entity.DataId
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.decodeVersions

/**
 * Write-triggered realm epoch bump (`core-schema-evolution.md` §7.2 + §4.1.3).
 *
 * For every locally-originated write that will actually **leave the device**,
 * raises the realm's [RealmSchemaEpoch] entry for each service whose types the
 * written value *contains*, when this device's declared version for that service
 * is above what the row records. Nothing else triggers it: not install, not
 * announce, not a pull — only a write, which is what makes per-service
 * granularity self-scoping (a realm with no Fit data never grows a Fit entry,
 * so a Fit-less user is never fenced for Fit).
 *
 * ## Where it runs
 *
 * On `SyncObserver`'s single emit-consumer coroutine, right after the save's own
 * blocks are generated. That placement buys three things:
 *  - **Locally-originated only** — `onDataSaved` early-returns on
 *    [WriteOrigin.SYNC_INGEST] before the emit channel, so an ingested peer
 *    write can never re-trigger a bump (which would turn one device's bump into
 *    a ping-pong across the realm).
 *  - **Keys are available** — a save landing while the realm is locked is held
 *    and replayed on unlock, so the epoch row is emitted rather than dropped.
 *  - **Single-threaded** — the verdict cache below needs no locking.
 *
 * ## Contained types, not the owning entity (§4.1.3)
 *
 * A payload can embed foreign-service composites (the `genericData` precedent: a
 * preference row can hold a sync-service `SyncPolicy`), so the *nominal* owner of
 * the written entity is the wrong unit. The value is walked
 * ([containedTypeIds]) and every contained type attributed to its service via
 * [SchemaRegistry.owningServiceOf] — whose rule *and its limits* are documented
 * there. A lagging reader is then fenced for the service whose bytes it would
 * actually garble.
 *
 * ## Cost
 *
 * This runs on every emit, so the per-type verdict (owning service + this
 * device's declared version for it) is **cached after the first walk** — the
 * schema set is fixed for the process. When nothing is behind, the whole thing
 * is one walk, a map lookup per type and one `getData` — no write, no block.
 *
 * ## Per-realm, routed like any other entity
 *
 * The bump lands on the epoch row of **the realm this write routes to**, resolved
 * by exactly the rule `SyncObserver.generateSyncBlock` uses for the block itself
 * ([RealmRegistry.realmForDataId] on the written entity's DataId). The row's key
 * carries that realm's namespace prefix ([RealmRegistry.epochKeyFor]), so the save
 * that records the bump is itself routed, encrypted, queued and pushed to the same
 * realm by the ordinary emit path — the epoch travels with the data that caused it.
 *
 * Before this, every bump wrote the one constant-keyed row, so a shared-realm write
 * raised the *personal* realm's epoch: truthful ("this device emits at version N")
 * but attributed to the wrong realm, which would fence a personal-realm peer over a
 * shared folder's schema and never fence the shared realm's actual members.
 */
internal class RealmSchemaEpochWriter(
    private val executionContext: ExecutionContext,
    /**
     * Realm routing for the bump (SR-M.1b). `null` — direct-construction tests and
     * any single-realm wiring — means every write is personal, which is exactly the
     * pre-shared-realm behaviour.
     */
    private val realmRegistry: RealmRegistry? = null,
) {

    /** One type's attribution: `null` service ⇒ unattributable (core floor covers it). */
    private class Verdict(val serviceId: String?, val declaredVersion: Int)

    private val schemas: SchemaRegistry? = executionContext.schemaProvider as? SchemaRegistry

    /** serviceId → this device's declared schema version. */
    private val declaredVersions: Map<String, Int> by lazy {
        schemas?.serviceSchemas()?.associate { it.id.get() to it.version }.orEmpty()
    }

    /** typeId → verdict. Consumer-thread confined (see class KDoc); unbounded but schema-sized. */
    private val verdicts = HashMap<String, Verdict>()

    /** Reused: the epoch row is read on every emit, decoded from its frozen bytes. */
    private val parser: BinaryParser by lazy {
        BinaryParser(
            executionContext.context.schemaProvider(),
            executionContext.context.dataFactory(),
        )
    }

    /** epoch key → its DataId. Consumer-thread confined; one entry per realm. */
    private val epochDataIds = HashMap<String, DataId.StringDataId>()

    private fun epochDataId(epochKey: String): DataId.StringDataId =
        epochDataIds.getOrPut(epochKey) {
            DataId.StringDataId.create(
                context = executionContext.context,
                type = RealmSchemaEpoch.ID,
                keyValue = epochKey,
            )
        }

    /**
     * The epoch key of the realm [data]'s save routes to — the same routing decision
     * `SyncObserver.generateSyncBlock` makes for the block. Falls back to the personal
     * key whenever there is no registry, no resolvable realm, or the realm *is* the
     * personal one.
     */
    private fun epochKeyFor(data: Data): String {
        val registry = realmRegistry ?: return RealmSchemaEpoch.SINGLETON_KEY
        val realm = registry.realmForDataId(executionContext.getDataId(data))
            ?: return RealmSchemaEpoch.SINGLETON_KEY
        return RealmRegistry.epochKeyFor(realm.ownerId.takeUnless { registry.isPersonal(realm) })
    }

    /**
     * Bump the realm epoch for [data]'s contained services, if any is behind.
     * Cheap and side-effect-free in the steady state.
     */
    fun onLocalWrite(data: Data) {
        if (schemas == null) return

        // Only data that actually syncs can fence a peer (§7.2), so mirror
        // `SyncObserver.processSavedData`'s emit test: the value's own Syncable
        // trait, plus any Syncable relation property of an entity. A purely
        // local row (SyncMetadata, ResolvedDataId, LocalWriteMetadata, a
        // derived relation view)
        // reaches no peer and must not move the realm's epoch.
        val emitted = syncedValuesOf(data)
        if (emitted.isEmpty()) return

        val types = LinkedHashSet<String>()
        emitted.forEach { types += containedTypeIds(it) }

        // ⚠ The epoch row's own save must never re-trigger a bump — it is
        // Syncable, so it comes back through this very path and would loop.
        if (RealmSchemaEpoch.ID in types) return

        // Highest declared version per contained service.
        val required = HashMap<String, Int>()
        types.forEach { typeId ->
            val verdict = verdicts.getOrPut(typeId) {
                val serviceId = schemas.owningServiceOf(typeId)
                Verdict(serviceId, serviceId?.let { declaredVersions[it] } ?: 0)
            }
            val serviceId = verdict.serviceId ?: return@forEach
            required[serviceId] = maxOf(required[serviceId] ?: 0, verdict.declaredVersion)
        }

        val epochKey = epochKeyFor(data)
        val existing = executionContext.database.getData(epochDataId(epochKey)) as? RealmSchemaEpoch
        // A row whose bytes will not decode (corruption — the schema is frozen)
        // is treated as absent and overwritten, rather than blocking every bump.
        val existingVector = existing?.let { runCatching { it.decodeVersions(parser) }.getOrNull() }
        val recorded = existingVector?.toMap().orEmpty()
        val recordedCore = existingVector?.coreVersion?.get()?.toInt() ?: 0

        val behind = required.filter { (serviceId, version) -> (recorded[serviceId] ?: 0) < version }
        val core = maxOf(recordedCore, TENSHU_STORAGE_FORMAT_VERSION)
        // A core rise now UPDATES an existing row (§7.11) — `SchemaPullFence` fences on
        // core, and it can only do so if the realm's recorded floor is kept current.
        // Without this, a bump that reshapes a core-common type without moving any
        // *service* version would leave the row's core stale and the fence blind to
        // exactly the skew it was just taught to catch.
        val coreBehind = existingVector != null && recordedCore < TENSHU_STORAGE_FORMAT_VERSION
        // ⚠ Still never CREATES a row for core alone. E2d-1 suppressed that on purpose:
        // it would make the very first write in every realm emit an epoch block, which
        // broke ~10 sync tests and puts the row in realms whose writes carry no
        // attributable service at all. Update-if-present is the narrow half that closes
        // the fence's blind spot without reopening that.
        if (behind.isEmpty() && !coreBehind) return

        val merged = LinkedHashMap(recorded)
        behind.forEach { (serviceId, version) -> merged[serviceId] = version }

        executionContext.saveData(
            RealmSchemaEpoch.create(
                context = executionContext.context,
                // The realm-scoped key is what routes this very save back to the same
                // realm's keys/queue/relay on the emit path — see the class KDoc.
                epochId = epochKey,
                versions = VersionVector.create(
                    context = executionContext.context,
                    coreVersion = core,
                    services = merged.entries
                        .sortedBy { it.key }
                        .map { (serviceId, version) ->
                            ServiceVersion.create(executionContext.context, serviceId, version)
                        },
                ),
            ),
            // A local bump, so it emits its own block like any Syncable save —
            // that block is how the other devices learn the new epoch (§7.2:
            // "the bump travels as ordinary synced data").
            origin = WriteOrigin.USER,
        )
    }

    /**
     * The values [data]'s save actually emits — the same fan-out
     * `SyncObserver.processSavedData` performs, evaluated for its types only.
     */
    private fun syncedValuesOf(data: Data): List<Data> {
        val schemas = schemas ?: return emptyList()
        val typeId = if (data is Entity<*>) data.type.get() else data.id
        val schema = schemas.getSchema(typeId) ?: data.schema
        val values = mutableListOf<Data>()
        if (schema.hasTrait(Syncable.ID)) values += data
        if (data is Entity<*>) {
            data.properties.forEach { (ref, value) ->
                if (schemas.getSchema(ref.get())?.hasTrait(Syncable.ID) == true) values += value
            }
        }
        return values
    }
}
