package org.tenshu.timeline.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry

fun createTimelineProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    objectStore: ObjectStore = InMemoryObjectStore(),
    /** The store's own lock when it has one (the iOS SQLite store); the test proxy's otherwise. */
    lock: DatabaseLock? = null,
): TestProxy {
    val factory = { id: String, _: TestProxy -> timelineServiceProxyImplementationFactory(id) }
    val proxy = if (lock != null) {
        TestProxy(
            schemaRegistry = createTimelineTestRegistry(),
            coroutineScope = coroutineScope,
            objectStore = objectStore,
            lock = lock,
            implementationFactoryOverride = factory,
        )
    } else {
        TestProxy(
            schemaRegistry = createTimelineTestRegistry(),
            coroutineScope = coroutineScope,
            objectStore = objectStore,
            implementationFactoryOverride = factory,
        )
    }
    // Mirror production wiring: `initTimelineService` registers the DataObservers that
    // maintain `TimelineService.Relations.MOMENTS` / `.TRIPS` on every saved entity.
    proxy.executionContext.initTimelineService(SemanticMergerRegistry())
    return proxy
}
