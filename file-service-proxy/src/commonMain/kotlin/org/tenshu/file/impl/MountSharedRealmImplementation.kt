package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.system.MountSharedRealm

/**
 * Phase 3c.2 of `docs/proposals/drive-shared-realm-file-model.md` (the connector):
 * mounts a shared realm into the member's **personal** FS by creating + saving a
 * [Placement].
 *
 * Pure `:file-service`: it writes one personal-realm Syncable keyed by the
 * `sharedRealmId`. The `Placement` routes to the personal realm (its key is an
 * unprefixed realm-owner id), so `saveData` enqueues it on the personal sync stream —
 * it reaches the member's other devices, never the realm's other members. Re-mounting
 * the same realm overwrites the placement (same key).
 */
internal class MountSharedRealmImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sharedRealmId: TString = params.read(MountSharedRealm.Params.SHARED_REALM_ID)
        val parentPath: TString = params.read(MountSharedRealm.Params.PARENT_PATH)
        val name: TString = params.read(MountSharedRealm.Params.NAME)

        executionContext.saveData(
            Entity.create<Placement>(
                context = executionContext.context,
                composite = Placement.create(
                    context = executionContext.context,
                    sharedRealmId = sharedRealmId.get(),
                    parentPath = parentPath.get(),
                    name = name.get(),
                ),
            )
        )

        return null as T
    }
}
