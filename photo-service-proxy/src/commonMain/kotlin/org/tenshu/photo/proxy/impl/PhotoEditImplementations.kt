package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.photo.ClearPhotoEdit
import org.tenshu.photo.PhotoEdit
import org.tenshu.photo.SetPhotoEdit
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Upserts one photo's edit. ⚠ Uploads nothing and never reads [PhotoEdit.recipe] — see [SetPhotoEdit].
 *
 * ⚠ Refuses an empty `contentId` or rendition path rather than minting a row keyed on nothing, or a
 * row whose rendition can never be found (`photos-editing.md` §12.4 would then show a placeholder
 * for good).
 */
internal class SetPhotoEditImplementation(
    private val nowEpochMs: () -> Long,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val contentId: TString = params.read(SetPhotoEdit.Params.CONTENT_ID)
        val recipe: TString = params.read(SetPhotoEdit.Params.RECIPE)
        val renditionPath: TString = params.read(SetPhotoEdit.Params.RENDITION_PATH)
        val redacted: TBoolean = params.read(SetPhotoEdit.Params.REDACTED)
        require(contentId.get().isNotEmpty()) { "A photo edit needs the photo's contentId" }
        require(renditionPath.get().isNotEmpty()) { "A photo edit needs its rendition's path" }

        executionContext.saveData(
            PhotoEdit.create(
                context = executionContext.context,
                contentId = contentId.get(),
                recipe = recipe.get(),
                renditionPath = renditionPath.get(),
                redacted = redacted.get(),
                editedAtMs = nowEpochMs(),
            )
        )
        return null as T
    }
}

/**
 * Drops one photo's edit — *Revert*. ⚠ Deletes no file; see [ClearPhotoEdit].
 *
 * ⚠⚠ **The tombstone is the op's own duty** (`SyncObserver.onDataDeleted` is a no-op): without it
 * the revert is device-local, and every other device keeps an edit whose rendition the reverting
 * device deletes next — a placeholder there for good (`photos-editing.md` §12.4).
 */
internal class ClearPhotoEditImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val contentId: TString = params.read(ClearPhotoEdit.Params.CONTENT_ID)
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = PhotoEdit.ID,
            keyValue = contentId.get(),
        )
        executionContext.deleteData(dataId)
        // ⚠ The id form: only the emitter can derive the relay-visible name (`DeleteWorkout`'s KDoc).
        tombstoneEmitter?.emit(targetEntityId = dataId, targetEntityType = PhotoEdit.ID)
        return null as T
    }
}
