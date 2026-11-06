package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.number.toTInteger
import org.tenshu.core.health.RecordImportedHealth
import org.tenshu.health.proxy.DailyActivityFold

internal class RecordImportedHealthImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // Same kind order as the per-record write path. Each `saveData` fires `SyncObserver`
        // (outbound SyncBlock) + the kind's relation maintainer, exactly as the per-record ops
        // do — the batch changes only how many IPC round-trips carry the records, not how they
        // are persisted.
        // Daily activities fold onto the stored day instead of replacing it — same reason as the
        // per-record `RecordDailyActivity` path, and this is the one the bulk importers use.
        var added = 0
        added += saveAll(executionContext, params, RecordImportedHealth.Params.DAILY_ACTIVITIES) { item ->
            DailyActivityFold.foldOntoStored(executionContext, item)
        }
        added += saveAll(executionContext, params, RecordImportedHealth.Params.DAILY_NUTRITIONS)
        added += saveAll(executionContext, params, RecordImportedHealth.Params.MENSTRUATION_DAYS)
        added += saveAll(executionContext, params, RecordImportedHealth.Params.SLEEP_SESSIONS)
        added += saveAll(executionContext, params, RecordImportedHealth.Params.HEALTH_SAMPLES)
        added += saveAll(executionContext, params, RecordImportedHealth.Params.MEDICAL_RECORDS)
        added += saveAll(executionContext, params, RecordImportedHealth.Params.WORKOUTS)

        return added.toLong().toTInteger(executionContext.context) as T
    }

    /**
     * Saves each record of one kind, optionally passing it through [transform] first — the seam a
     * kind uses when the incoming record must be reconciled with what is already stored rather
     * than replacing it wholesale.
     */
    private fun saveAll(
        executionContext: ExecutionContext,
        params: Map<String, Data>?,
        paramId: String,
        transform: (Data) -> Data = { it },
    ): Int {
        val items = (params?.get(paramId) as? TList<*>)?.toList() ?: return 0
        var added = 0
        items.forEach { item ->
            if (item is Data) {
                val record = transform(item)
                // ⚠⚠ Probed BEFORE the save, and this is the whole point of the operation's
                // return value. Every key here is a stable *source* id — Health Connect's own
                // record id, a calendar date, `"metric::timeMs"` — so re-reading an unchanged
                // window lands on the same rows and adds nothing. Counting what was *read*
                // instead is what filled the import log with identical entries.
                //
                // `contains` rather than `getData(...) != null`: the answer is almost always
                // "yes", and the read form would deserialise the whole row to learn it.
                // ⚠ A row whose id cannot be derived counts as new rather than being dropped:
                // it is still saved, and a run that under-reports is better than one that
                // silently skips records it did write.
                val id = executionContext.getDataId(record)
                if (id == null || !executionContext.database.contains(id)) added++
                executionContext.saveData(record)
            }
        }
        return added
    }

}
