package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.sync.CancelScheduled
import org.tenshu.sync.proxy.PlatformScheduler
import org.tenshu.sync.proxy.ScheduleRunState
import org.tenshu.sync.proxy.scheduleItemFor
import org.tenshu.sync.proxy.scheduledItemsRef

/**
 * Phase 5 (§3.2(b)): remove the pending `ScheduleItem` with `dedupKey`. A
 * no-op if none matches. Phase 6/7: also tears down any OS-level work the
 * [platformScheduler] registered under the key (work already mid-dispatch is
 * the Layer-2 in-process cancel — §6.2).
 */
internal class CancelScheduledImplementation(
    private val platformScheduler: PlatformScheduler? = null,
    private val runState: ScheduleRunState = ScheduleRunState.Default,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val dedupKey: TString = params.read(CancelScheduled.Params.DEDUP_KEY)
        val ref = scheduledItemsRef(executionContext)

        executionContext.database.transaction {
            // ⚠⚠ Bump BEFORE the removal, and even when no row matches (see [ScheduleRunState]): a
            // run of this key that is in flight must not write the row back when it completes.
            runState.bump(dedupKey.get())
            val existing = scheduleItemFor(executionContext, ref, dedupKey.get())
            if (existing != null) {
                executionContext.database.removeFromArray(
                    ref,
                    executionContext.getDataId(existing)!!
                )
            }
        }

        platformScheduler?.cancel(dedupKey.get())

        return null as T
    }
}
