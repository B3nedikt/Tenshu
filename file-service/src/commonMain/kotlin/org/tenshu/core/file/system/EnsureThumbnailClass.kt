package org.tenshu.core.file.system

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * **Get this file a stored thumbnail class, by any route that is allowed right now**
 * (`drive-xxlarge-thumbnail-rung.md` §4.6, X-4) — the answer for a photograph whose byte holder is
 * offline or gone, where no amount of waiting produces the rung.
 *
 * The proxy pulls it if some device published it, and otherwise **mints it here**, which means
 * downloading the whole original (6–18 MB) and rendering from it. `null` means "not now, and nothing
 * was downloaded": already declined at this quality, no room under quota, a metered network, or the
 * original itself is unreachable.
 *
 * ⚠⚠ **The policy lives in the proxy, not in the caller.** A UI check is not enough: content pulls
 * are not stopped by the sync metered gate (`SyncSchedulerGateTest.closedMeteredGate_stillPulls`), so
 * a network that turned metered after the UI looked — or any other caller — would otherwise download
 * an original over cellular. [Params.USER_REQUESTED] is the deliberate override, for a zoom or an
 * explicit "sharpen", and it is the *only* thing that lifts the metered refusal.
 *
 * ⚠ It is idempotent and cheap to call again: a class already on disk returns its bytes from a stat,
 * and a class this device already declined returns `null` without touching the network.
 */
suspend fun FileSystem.ensureThumbnailClass(
    path: String,
    thumbnailClass: String,
    userRequested: Boolean = false,
): TResult<TByteArray?> =
    findOperation<EnsureThumbnailClass>(EnsureThumbnailClass.ID)
        .ensureThumbnailClass(path, thumbnailClass, userRequested)
        .asTaskInteractor<TByteArray?>()
        .execute()

interface EnsureThumbnailClass : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun ensureThumbnailClass(
        path: String,
        thumbnailClass: String,
        userRequested: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/ensureThumbnailClass"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PATH, type = FilePath.Schema)
                string(id = Params.THUMBNAIL_CLASS)
                boolean(id = Params.USER_REQUESTED, isOptional = true)
            },
            returnType = TByteArray.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PATH = "$ID/path"

        /** A `GetStoredThumbnail.CLASS_*` id. ⚠ Only a **declarable** class may be asked for. */
        const val THUMBNAIL_CLASS = "$ID/thumbnailClass"

        /**
         * The user asked for this picture specifically — a zoom, or a "sharpen" action — so the
         * metered refusal is lifted (D-18).
         *
         * ⚠⚠ **Optional, and absence means "no".** Every other gate still applies: an explicit ask
         * does not buy room under quota, and it does not re-try a class this device already declined.
         * ⚠ It does not lift the *charging* or *idle* constraints either, because a view mint has
         * none — it is a foreground action a person is waiting on, unlike the backfill.
         */
        const val USER_REQUESTED = "$ID/userRequested"
    }
}
