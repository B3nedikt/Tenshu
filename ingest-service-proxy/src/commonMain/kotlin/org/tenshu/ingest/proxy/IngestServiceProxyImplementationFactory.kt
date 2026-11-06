package org.tenshu.ingest.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.ingest.PickSecurityScopedFolder
import org.tenshu.core.ingest.RegisterSource
import org.tenshu.core.ingest.SetSourceEnabled
import org.tenshu.core.ingest.UpdateSourceStatus
import org.tenshu.ingest.proxy.executor.PickSecurityScopedFolderImplementation
import org.tenshu.ingest.proxy.executor.RegisterSourceImplementation
import org.tenshu.ingest.proxy.executor.SetSourceEnabledImplementation
import org.tenshu.ingest.proxy.executor.UpdateSourceStatusImplementation

/**
 * [runs] is optional and defaults to absent: only the host that renders a foreground notification
 * from it (Control on Android) needs the device-local run signal, and a host that passes nothing
 * behaves exactly as before. See [IngestRunRegistry].
 */
fun ingestServiceProxyImplementationFactory(
    id: String,
    runs: IngestRunRegistry? = null,
    folderPicker: SecurityScopedFolderPicker? = null,
): OperationImplementation? {
    return when (id) {
        PickSecurityScopedFolder.ID -> PickSecurityScopedFolderImplementation(folderPicker)
        RegisterSource.ID -> RegisterSourceImplementation()
        UpdateSourceStatus.ID -> UpdateSourceStatusImplementation(runs)
        SetSourceEnabled.ID -> SetSourceEnabledImplementation()
        else -> null
    }
}
