package org.tenshu.ingest.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.ingest.proxy.IngestRunRegistry
import org.tenshu.ingest.proxy.initIngestService
import org.tenshu.ingest.proxy.ingestServiceProxyImplementationFactory

fun createIngestProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /** Wired exactly as Control-on-Android does, so a test can assert on the run signal. */
    runs: IngestRunRegistry? = null,
) =
    TestProxy(
        schemaRegistry = createIngestTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            ingestServiceProxyImplementationFactory(id, runs)
        }
    ).also {
        // Mirror production wiring: `initIngestService` registers the
        // DataObserver that maintains `IngestService.Relations.SOURCES` on every
        // saved `IngestSource`. Without it the upsert `saveData` lands the row in
        // the DB but never in the relation, so `ingestService.sources` is empty.
        it.executionContext.initIngestService()
    }
