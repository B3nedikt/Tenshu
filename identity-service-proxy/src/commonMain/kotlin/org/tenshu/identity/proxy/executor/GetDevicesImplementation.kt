package org.tenshu.identity.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.identity.IdentityService

/**
 * Per `docs/adr/0007-sync-device-management.md` Phase 2, `getDevices` is a local-DB
 * relation query against `IdentityService.Relations.DEVICES`. The legacy
 * `GET /identity/devices` relay endpoint is gone (Phase 3 cleanup).
 *
 * Returns the `ArrayDatabaseReference`; the existing list-resolution pipeline materialises
 * lazily via the `GetAll` operation that the client extension chains after this one.
 */
internal class GetDevicesImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val arrayRef = executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(IdentityService.ID),
            ref = EntityListReferenceDto(IdentityService.Relations.DEVICES)
        )
        return arrayRef as T
    }
}
