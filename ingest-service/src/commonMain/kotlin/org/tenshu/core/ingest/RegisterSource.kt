package org.tenshu.core.ingest

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Idempotent upsert of an [IngestSource] descriptor (`drive-synced-folders-phase2.md`
 * §3.3). Keyed by (`sourceId`, `deviceId`): a re-register updates the descriptor
 * fields (display name, watched paths, app, kind) but **preserves** the row's
 * `enabled` state + live status — so an app re-registering on every launch never
 * clobbers a central kill-switch toggle or in-flight status. A brand-new source
 * defaults `enabled = true`, `lastStatus = IDLE`.
 *
 * [watchedPaths] is desktop-only: newline-separated raw dirs for unsandboxed development
 * or opaque security-scoped grants on MAS macOS for Control's `FolderWatcher`; pass `""`
 * on mobile, where the app enumerates with its own gallery permission.
 */
suspend fun IngestService.registerSource(
    sourceId: String,
    appId: String,
    deviceId: String,
    displayName: String,
    sourceKind: String,
    watchedPaths: String = "",
): TResult<Data?> =
    findOperation<RegisterSource>(RegisterSource.ID)
        .registerSource(sourceId, appId, deviceId, displayName, sourceKind, watchedPaths)
        .asTaskInteractor<Data?>()
        .execute()

interface RegisterSource : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun registerSource(
        sourceId: String,
        appId: String,
        deviceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/ingest/service/registerSource"

        val Schema = operation(
            id = ID,
            receiver = IngestService.Schema.toType(),
            params = {
                string(Params.SOURCE_ID)
                string(Params.APP_ID)
                string(Params.DEVICE_ID)
                string(Params.DISPLAY_NAME)
                string(Params.SOURCE_KIND)
                string(Params.WATCHED_PATHS)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SOURCE_ID = "$ID/sourceId"
        const val APP_ID = "$ID/appId"
        const val DEVICE_ID = "$ID/deviceId"
        const val DISPLAY_NAME = "$ID/displayName"
        const val SOURCE_KIND = "$ID/sourceKind"
        const val WATCHED_PATHS = "$ID/watchedPaths"
    }
}
