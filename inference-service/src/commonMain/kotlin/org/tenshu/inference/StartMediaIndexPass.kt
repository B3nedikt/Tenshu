package org.tenshu.inference

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * **Index my photos now** — the user-initiated pass (IN-D6's missing responsiveness lever).
 *
 * ⚠⚠ **It returns as soon as the pass has STARTED, not when it ends.** A first index is minutes to
 * hours; a call that waited would hold an IPC request open for the duration and time out long before
 * the work finished. Progress is [IndexProgress]'s stream, which is what the screen already renders.
 *
 * ⚠⚠ **Distinct from [MediaIndexPass], which stays `HeadlessSafe` and scheduler-only.** The two run
 * through one runner, so a manual start while the scheduled pass is working joins it rather than
 * walking the library twice.
 *
 * ⚠ A manual start is held to a **different** bar than the scheduled one: the user is holding the
 * device, so "not in use" cannot be required — see `IndexingConditions.allowManualIndexing`. What it
 * still respects is a low battery and the user's own pause.
 *
 * Fails with [IndexPassRefused] rather than succeeding silently: a button that reports success and
 * indexes nothing is the shape this service has spent five slices removing.
 */
suspend fun InferenceService.startMediaIndexPass(): TResult<Data?> =
    findOperation<StartMediaIndexPass>(StartMediaIndexPass.ID)
        .startMediaIndexPass()
        .asTaskInteractor<Data?>()
        .execute()

interface StartMediaIndexPass : Operation {

    override val schema: OperationSchema get() = Schema

    fun startMediaIndexPass(): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/startMediaIndexPass"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
