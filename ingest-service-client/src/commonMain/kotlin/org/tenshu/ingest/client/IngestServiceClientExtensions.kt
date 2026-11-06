package org.tenshu.ingest.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.ingest.IngestService

val TenshuClient.ingestService: IngestService
    get() = getService(IngestService.ID)
