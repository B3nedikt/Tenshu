package org.tenshu.core.file.system

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * True byte-presence probe: are the bytes of the file at [path] on **this
 * device's** local disk right now (`docs/proposals/drive-offline-online.md` §3,
 * the presence signal)?
 *
 * This is the *presence* axis — distinct from the *intent* axis
 * ([org.tenshu.core.file.OfflinePreferences]-backed pin set). A file can have
 * local bytes because it was pinned and downloaded **or** merely because it was
 * opened once (lazy-on-open `Syncable(OnDemand)` materialisation); both surface
 * here as `true`. A pinned-but-not-yet-fetched file surfaces as `false`.
 *
 * Backed by [org.tenshu.core.common.proxy.FileDatabase.fileSize], which is O(1)
 * on the real-filesystem impls — no payload read. It is a per-file probe by
 * design: callers MUST invoke it **lazily and bounded** per visible row (the
 * same `produceState` + [kotlinx.coroutines.sync.Semaphore] discipline previews
 * use), **never eagerly across a whole listing** — resolving presence eagerly in
 * the listing chain trips Android's Binder watchdog.
 */
suspend fun FileSystem.hasLocalData(path: String): TResult<TBoolean> =
    findOperation<HasLocalData>(HasLocalData.ID)
        .hasLocalData(path)
        .asTaskInteractor<TBoolean>()
        .execute()

interface HasLocalData : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun hasLocalData(path: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/hasLocalData"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PATH, type = FilePath.Schema)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PATH = "$ID/path"
    }
}
