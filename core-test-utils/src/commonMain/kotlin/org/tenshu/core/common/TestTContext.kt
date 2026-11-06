package org.tenshu.core.common

import org.tenshu.core.InteractorProvider
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.request.RoProvider
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.Clock
import org.tenshu.core.trait.TraitResolver

fun createTestContext(
    schemaRegistry: SchemaRegistry = createCoreTestRegistry(),
    interactorProvider: InteractorProvider? = null,
    traitResolver: TraitResolver? = null,
    pureOperationRunner: PureOperationRunner? = null,
    syncCryptoManager: SyncCryptoManager = BootstrapSyncCryptoManager(),
    clock: Clock = SystemClock(),
    additionalComponents: Map<String, TComponent> = emptyMap()
): TContext {
    val componentsMap = mutableMapOf<String, TComponent>()
    val tContext = object : TContext {
        override val components: Map<String, TComponent> get() = componentsMap
    }

    val finalTraitResolver = traitResolver ?: TraitResolverImpl { tContext }
    val finalPureOperationRunner = pureOperationRunner ?: PureOperationRunnerImplementation(
        tContextProvider = { tContext },
        implementationFactoryProvider = {
            OperationImplementationFactory { id -> implementationFactory(id) }
        }
    )

    componentsMap[DataFactory.COMPONENT_ID] = schemaRegistry
    componentsMap[SchemaProvider.COMPONENT_ID] = schemaRegistry
    componentsMap[RoProvider.COMPONENT_ID] = schemaRegistry
    componentsMap[OperationResolver.COMPONENT_ID] = schemaRegistry
    componentsMap[LazyDataMaterializer.ID] = schemaRegistry
    componentsMap[TraitResolver.ID] = finalTraitResolver
    componentsMap[PureOperationRunner.ID] = finalPureOperationRunner
    componentsMap[SyncCryptoManager.ID] = syncCryptoManager
    componentsMap[Clock.COMPONENT_ID] = clock
    componentsMap.putAll(additionalComponents)

    if (interactorProvider != null) {
        componentsMap[InteractorProvider.COMPONENT_ID] = interactorProvider
    }

    return tContext
}
