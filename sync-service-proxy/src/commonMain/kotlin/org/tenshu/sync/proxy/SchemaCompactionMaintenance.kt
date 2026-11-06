package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.schema.migration.SchemaMigrationRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.CompactionPolicy
import org.tenshu.sync.common.CompactionReceipt

/**
 * The maintenance pass (E4-5, `core-schema-evolution.md` §8.3): for every service
 * whose history still trails its current version, re-emit that history.
 *
 * This is where E4's parts finally compose — [CompactionPolicy] decides,
 * [SchemaCompactor] acts, and the receipt it leaves is what stops peers repeating
 * the work.
 *
 * ## ⚖️ Which types get lifted, and why it is narrower than "the service's types"
 *
 * **Exactly the types the migration chain names.** A type whose shape never
 * changed decodes identically at every version, so its old blocks are already
 * readable and re-emitting them would be pure bandwidth. The chain is therefore
 * not merely *a* source for the type list — it is the precise one, and it falls
 * out of data that already exists rather than needing a service→types index
 * (which `SchemaRegistry` cannot give: `owningServiceOf` only goes the other way,
 * §4.2).
 *
 * ## ⚠ Best-effort, and that is load-bearing
 *
 * §8.3 puts this under idle + charging + unmetered constraints *because nothing
 * may depend on it having run*. A pass that never fires leaves history readable
 * through its upcasters for longer — it cannot break a read. So every failure
 * here is logged and swallowed per service: one service's bad row must not stop
 * the others, and none of it must stop the app.
 */
class SchemaCompactionMaintenance(
    private val executionContext: ExecutionContext,
    private val store: ObjectStore,
) {

    data class Result(
        val compactedServices: List<String>,
        val skippedServices: List<String>,
    )

    fun runOnce(deviceId: String): Result {
        val registry = executionContext.schemaProvider as? SchemaRegistry
            ?: return Result(emptyList(), emptyList())

        val migrations = registry.schemaMigrations()
        if (migrations.isEmpty()) {
            // No chain for a service ⇒ its floor equals its current version ⇒ nothing
            // below it to lift. Returning early keeps
            // the scheduled pass free rather than walking the store for nothing.
            return Result(emptyList(), emptyList())
        }

        val chains = SchemaMigrationRegistry().apply { migrations.forEach(::register) }
        val declared = registry.serviceSchemas().associate { it.id.get() to it.version }
        val compactor = SchemaCompactor(
            executionContext = executionContext,
            store = store,
            mapper = DbEntryMapper(
                executionContext.context,
                registry,
                registry,
                executionContext.traitResolver,
            ),
        )

        val compacted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for ((serviceId, currentVersion) in declared.entries.sortedBy { it.key }) {
            val steps = migrations.filter { it.serviceId == serviceId }
            if (steps.isEmpty()) continue

            val floor = chains.floorFor(serviceId, currentVersion)
            val receiptVersion = readReceiptVersion(serviceId)

            if (!CompactionPolicy.shouldCompact(currentVersion, receiptVersion, floor)) {
                skipped += serviceId
                continue
            }

            // Only the reshaped types — see the class KDoc.
            val typeIds = steps.flatMap { step -> step.migrations.map { it.typeId } }.toSet()
            if (typeIds.isEmpty()) {
                // An empty chain step (the `SyncService` v1→v2 shape): the version
                // moved but no type reshaped, so there is nothing to re-emit. Write
                // the receipt anyway — the service IS current, and without it every
                // peer retries this forever.
                skipped += serviceId
                continue
            }

            runCatching {
                compactor.compact(
                    serviceId = serviceId,
                    typeIds = typeIds,
                    atVersion = currentVersion,
                    deviceId = deviceId,
                )
            }.onSuccess { compacted += serviceId }
                .onFailure {
                    skipped += serviceId
                    println("SchemaCompactionMaintenance: '$serviceId' failed, will retry: $it")
                }
        }

        return Result(compacted, skipped)
    }

    private fun readReceiptVersion(serviceId: String): Int? {
        val id = DataId.StringDataId.create(
            context = executionContext.context,
            type = CompactionReceipt.ID,
            keyValue = CompactionReceipt.receiptIdFor(serviceId),
        )
        val receipt = executionContext.database.getData(id) as? CompactionReceipt ?: return null
        return receipt.compactedToVersion.get().toInt()
    }
}
