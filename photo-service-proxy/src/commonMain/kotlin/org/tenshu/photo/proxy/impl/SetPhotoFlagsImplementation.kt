package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.SetPhotoFlags

/** Upserts one photo's flags. ⚠ Writes a row even when both are `false` — see [SetPhotoFlags]. */
internal class SetPhotoFlagsImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val contentId: TString = params.read(SetPhotoFlags.Params.CONTENT_ID)
        val favourite: TBoolean = params.read(SetPhotoFlags.Params.FAVOURITE)
        val hidden: TBoolean = params.read(SetPhotoFlags.Params.HIDDEN)

        executionContext.saveData(
            PhotoFlags.create(
                context = executionContext.context,
                contentId = contentId.get(),
                favourite = favourite.get(),
                hidden = hidden.get(),
            )
        )
        return null as T
    }
}
