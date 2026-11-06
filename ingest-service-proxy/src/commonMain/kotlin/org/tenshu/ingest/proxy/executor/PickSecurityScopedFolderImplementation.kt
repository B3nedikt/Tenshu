package org.tenshu.ingest.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.toTString
import org.tenshu.ingest.proxy.SecurityScopedFolderPicker
import org.tenshu.core.ingest.PickSecurityScopedFolder

internal class PickSecurityScopedFolderImplementation(
    private val picker: SecurityScopedFolderPicker?,
) : AsyncOperationImplementation {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        return (picker?.pick().orEmpty()).toTString(executionContext.context) as T
    }
}
