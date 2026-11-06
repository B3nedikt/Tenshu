package org.tenshu.ingest.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.ingest.client.ingestServiceClientModule

fun createIngestTestRegistry(): SchemaRegistry =
    createIngestServiceCommonTestRegistry().apply {
        registerConfig(ingestServiceClientModule)
    }
