package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.core.trait.isPunctual
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.proxy.PlatformScheduler
import org.tenshu.sync.proxy.ScheduleRunState
import org.tenshu.sync.proxy.scheduleItemFor
import org.tenshu.sync.proxy.scheduledItemsRef
import org.tenshu.sync.proxy.writeScheduleItem

/**
 * Phase 5 (§3.2(b) / §6.4): registers a head-less invocation. **First**
 * enforcement point of the fail-closed `HeadlessSafe` invariant — an op whose
 * schema is unknown or not `isHeadlessSafe()` is rejected here, before the
 * item is ever persisted (the drain loop re-checks as defence in depth for
 * wire-deserialised / older-build items). `dedupKey` coalesces: an existing
 * item with the same key is replaced atomically (§3.3) — **in place**, by one row write, so a write
 * that fails leaves the previous registration intact (`writeScheduleItem`).
 *
 * Phase 6/7: after persisting, the item is also handed to the
 * [platformScheduler] (if wired) so an OS scheduler can (re)start the work when
 * the app is closed — the persisted registry stays the source of truth; this is
 * the second driver (§5.1 "one store, two drivers").
 */
internal class ScheduleImplementation(
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
        val opId: TString = params.read(Schedule.Params.OP_ID)
        val scheduleSpec: ScheduleSpec = params.read(Schedule.Params.SCHEDULE_SPEC)
        val constraintSpec: ConstraintSpec = params.read(Schedule.Params.CONSTRAINT_SPEC)
        val retryPolicy: RetryPolicy = params.read(Schedule.Params.RETRY_POLICY)
        val dedupKey: TString = params.read(Schedule.Params.DEDUP_KEY)

        val schema = executionContext.schemaProvider.getSchema(opId.get()) as? OperationSchema
        if (schema == null || !schema.isHeadlessSafe()) {
            throw TenshuException(
                GenericError.create(
                    context = executionContext.context,
                    message = "Operation '${opId.get()}' is not HeadlessSafe; refusing " +
                        "to schedule (core-background-processing.md §6.4 fail-closed)."
                )
            )
        }

        val item = ScheduleItem.create(
            context = executionContext.context,
            dedupKey = dedupKey.get(),
            opId = opId.get(),
            scheduleSpec = scheduleSpec,
            constraintSpec = constraintSpec,
            retryPolicy = retryPolicy
        )

        val ref = scheduledItemsRef(executionContext)
        executionContext.database.transaction {
            // ⚠⚠ Bump BEFORE the row write (see [ScheduleRunState]): a run of this key that is in
            // flight must stand down rather than overwrite this registration through their shared id.
            runState.bump(dedupKey.get())
            writeScheduleItem(executionContext, ref, item)
        }

        // Hand the OS scheduler the same item (advisory; desktop/test wirings pass
        // no scheduler and rely on the in-process ScheduleDrainLoop).
        // ⭐ The op's schema is already in hand for the HeadlessSafe gate above,
        // so punctuality (RE-D5) costs one more trait read rather than a new
        // field on a stored composite — no format bump.
        val scheduler = platformScheduler ?: return item as T
        try {
            scheduler.register(item, punctual = schema.isPunctual())
        } catch (e: Throwable) {
            // ⚠⚠ An item the OS scheduler OWNS has no other driver: the in-process loop skips it
            // (`PlatformScheduler.ownsDispatch`). A row left behind here is a schedule nothing runs
            // that reads, to `ListScheduled` and to every log, as registered — the media index pass
            // spent its whole life on Android like that (`control-background-work-off-fgs.md` §3.6).
            // So the registration is undone and the failure is the caller's to report.
            // ⚠ An item the loop still drives keeps its row: there the loop is a real driver and the
            // OS hand-off only buys app-closed wakes, as before.
            if (scheduler.ownsDispatch(item, punctual = schema.isPunctual())) withdraw(executionContext, ref, dedupKey.get(), scheduler)
            throw TenshuException(
                GenericError.create(
                    context = executionContext.context,
                    message = "Operation '${opId.get()}' could not be handed to the platform " +
                        "scheduler (key '${dedupKey.get()}'): $e",
                )
            )
        }

        return item as T
    }

    /** Removes [dedupKey]'s row and any OS work under it, as `CancelScheduled` does. */
    private fun withdraw(
        executionContext: ExecutionContext,
        ref: org.tenshu.core.common.proxy.ArrayDatabaseReference,
        dedupKey: String,
        scheduler: PlatformScheduler,
    ) {
        executionContext.database.transaction {
            runState.bump(dedupKey)
            scheduleItemFor(executionContext, ref, dedupKey)?.let { existing ->
                executionContext.database.removeFromArray(ref, executionContext.getDataId(existing)!!)
            }
        }
        runCatching { scheduler.cancel(dedupKey) }
    }
}
