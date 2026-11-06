package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.ReadSharedFile

/**
 * Phase 3c of `docs/proposals/drive-shared-realm-file-model.md`: reads the bytes of
 * shared file `fileId` in realm `realmId`. The shared-realm counterpart of a
 * personal `File.Data` read.
 *
 * Drives the OnDemand fetch the same way the chain's `relations(File.Data)` does,
 * but addressed by `(realmId, fileId)`: dispatch
 * `OnDemandResolution.resolve(sharedFileDataId, SharedFile.DATA_PROPERTY_ID)` — which
 * fetches the file's chunks from the **realm's** content store, the realm-aware
 * `FileContentManifestApplier` decrypts (under the realm keys) + integrity-gates +
 * reassembles them onto the realm byte-path — then reads the materialised bytes from
 * `FileDatabase`. `null` if not yet available (entity/manifest unsynced); the caller
 * retries. The byte-path mirrors `FileBytesPropertyWriter`'s SharedFile resolution.
 */
internal class ReadSharedFileImplementation(
    private val fileDatabase: FileDatabase,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val realmId: TString = params.read(ReadSharedFile.Params.REALM_ID)
        val fileId: TString = params.read(ReadSharedFile.Params.FILE_ID)

        val sharedFileDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = SharedFile.ID,
            keyValue = SharedFile.compositeKey(realmId.get(), fileId.get()),
        )

        // Drive the OnDemand fetch + realm-aware reassembly (a no-op locally if the
        // bytes are already present). Mirrors the chain's `relations(SharedFile.Data)`.
        executionContext.context.onDemandResolution()?.resolve(
            executionContext = executionContext,
            entityId = sharedFileDataId,
            propertyId = SharedFile.DATA_PROPERTY_ID,
            params = null,
        )

        // The applier (and FileBytesPropertyWriter) reassemble shared-file bytes under
        // a deterministic realm byte-path; read them back.
        val bytePath = "/.shared/${realmId.get()}/${fileId.get()}"
        return fileDatabase.readBytes(bytePath) as T
    }
}
