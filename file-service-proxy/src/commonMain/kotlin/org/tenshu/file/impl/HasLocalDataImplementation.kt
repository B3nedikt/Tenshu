package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.file.system.HasLocalData
import org.tenshu.core.toTBoolean

/**
 * Answer the true byte-presence probe for one file
 * (`docs/proposals/drive-offline-online.md` §3, v2 presence signal): are the
 * file's bytes on local disk right now?
 *
 * Cheap by construction — [FileDatabase.fileSize] is O(1) on the
 * real-filesystem impls (a `length()` stat, no payload read). Returns `true`
 * whenever a local copy exists, whether it got there via an explicit pin
 * download or a lazy-on-open materialisation; `false` for an online-only file
 * (incl. a pinned file whose bytes haven't been fetched yet).
 */
internal class HasLocalDataImplementation(
    private val fileDatabase: FileDatabase
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val path: TString = params.read(HasLocalData.Params.PATH)
        val present = fileDatabase.fileSize(path.get()) != null
        return present.toTBoolean(executionContext.context) as T
    }
}
