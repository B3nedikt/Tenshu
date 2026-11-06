package org.tenshu.storage.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger
import org.tenshu.storage.CapacityResult
import org.tenshu.storage.CheckCapacity
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.availableAfterQueue

/**
 * Tier-3 pre-flight impl (`docs/proposals/storage-capacity-gating.md` §2):
 * answers "do N bytes fit" from the [StorageCapacityHolder] figures, without
 * mutating anything. Unknown figures (unwired / pre-derivation) ⇒ optimistic
 * `fits = true` (matches the Tier-1/Tier-2 unknown-is-allow stance); no storage
 * ⇒ `fits = false`.
 *
 * Hard limit: `required <= limit - used - queued` (ST-7) — the bytes already journalled for upload
 * are not in `used`, because the relay does not hold them yet, so leaving them out measured a whole
 * folder against room the queue in front of it had already claimed. A relay `507` short-circuits to
 * zero: it is the authority on whether a write fits (D11). Shares
 * [org.tenshu.storage.proxy.availableAfterQueue] with the Tier-2 single-file gate so the two
 * scales of the same question cannot answer it differently.
 */
internal class CheckCapacityImplementation(
    private val storageCapacityHolder: StorageCapacityHolder?,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val requiredBytes: TInteger = params.read(CheckCapacity.Params.REQUIRED_BYTES)
        val required = requiredBytes.get()

        val figures = storageCapacityHolder?.figures
        val result = when {
            figures == null -> CapacityResult.create(
                context = executionContext.context,
                fits = true,
                availableBytes = Long.MAX_VALUE,
            )
            !figures.hasStorage -> CapacityResult.create(
                context = executionContext.context,
                fits = false,
                availableBytes = 0L,
            )
            else -> {
                // ⚠⚠ Net of the upload queue, via the same helper `ensureWithinQuota` uses. This is
                // the **folder / multi-file** pre-flight — the one surface whose whole job is
                // answering "will this job work" before a long run starts — so it is exactly where a
                // queue already spoken for must be subtracted. Reporting raw headroom here waved a
                // folder upload through onto a realm that could not hold the backlog in front of it.
                //
                // ⚠ A relay refusal (`507`) means the answer is no whatever the arithmetic says; the
                // two are the same disagreement Tier-2 resolves the same way.
                val available =
                    if (storageCapacityHolder?.relayRejected == true) 0L
                    else availableAfterQueue(figures, storageCapacityHolder?.queuedContentBytes ?: 0L)
                CapacityResult.create(
                    context = executionContext.context,
                    fits = required <= available,
                    availableBytes = available,
                )
            }
        }
        return result as T
    }
}
