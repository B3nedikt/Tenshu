package org.tenshu.file.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule

fun createFileServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(fileServiceCommonModule)
    // file-service ops reach sync-common types directly: `DataIdMigrator` sweeps `SyncMetadata`
    // and journals via `RekeyMigrationMarker` (content-addressing §3.3.4). The production
    // file-service-proxy registry has these (it `api`-depends on `:sync-service-common`); mirror
    // that here so `MoveFile` etc. can create them in tests.
    registerConfig(syncServiceCommonModule)
}
