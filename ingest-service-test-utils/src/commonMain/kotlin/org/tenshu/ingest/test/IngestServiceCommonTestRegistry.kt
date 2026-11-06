package org.tenshu.ingest.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.ingest.common.ingestServiceCommonModule

fun createIngestServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(ingestServiceCommonModule)
}
