package org.tenshu.place.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry

fun createPlaceProxy(coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) =
    TestProxy(
        schemaRegistry = createPlaceTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            placeServiceProxyImplementationFactory(id)
        }
    ).also {
        // Mirror production wiring: `initPlaceService` registers the DataObserver that
        // maintains `PlaceService.Relations.PLACES` on every saved `Place`. Without it,
        // `RecordPlace`'s `saveData` lands the entity in the DB but never in the
        // relation, so the places stream returns nothing.
        it.executionContext.initPlaceService(SemanticMergerRegistry())
    }
