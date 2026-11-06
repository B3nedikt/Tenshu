package org.tenshu.core.common.di

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.FileUploader
import org.tenshu.core.InteractorProvider
import org.tenshu.core.ProxyConnectivity
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.request.RoProvider
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.security.AeadBackend
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.KdfBackend
import org.tenshu.core.common.security.X25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.Clock
import org.tenshu.core.trait.TraitResolver

/**
 * Why a function, not a `val`. Top-level `val` Koin modules share `single<>`
 * instances across separate `koinApplication { }` instances in Koin 4.2 —
 * verified empirically with a minimal repro. Anything that needs to spin up
 * multiple isolated Koin scopes (most notably `:system-test-utils`'s
 * per-device `TestClient`s) needs a fresh module per call, and the leak
 * propagates through `includes(...)` even when the outer module is fresh.
 *
 * Production paths use one global Koin (`startKoin { ... }`) and aren't
 * affected by the leak; the call-site cost is just two `()`. The fix is
 * paired with `cryptoBackendsModule()` for the same reason — `includes()`
 * of a `val` module would re-leak.
 */
fun coreCommonModule(): Module = module {
    // Pulls in the platform-specific crypto backend bindings (Ed25519,
    // X25519, AEAD, KDF on JVM via BouncyCastle; on JS via @noble/curves +
    // @noble/ciphers + @noble/hashes). The expect fun resolves to the right
    // `actual` automatically — every caller of `coreCommonModule()` gets
    // working backends without touching app/test wiring.
    includes(cryptoBackendsModule())

    single<SyncCryptoManager> {
        DefaultSyncCryptoManager(
            ed25519 = get<Ed25519Backend>(),
            x25519 = get<X25519Backend>(),
            aead = get<AeadBackend>(),
            kdf = get<KdfBackend>(),
        )
    }
    single<Clock> { SystemClock() }

    single<TContext> {
        val koin = getKoin()
        object : TContext {
            override val components: Map<String, TComponent> = object : Map<String, TComponent> {
                override val size: Int get() = keys.size
                override fun isEmpty(): Boolean = false
                override fun containsKey(key: String): Boolean = keys.contains(key)
                override fun containsValue(value: TComponent): Boolean = false

                override fun get(key: String): TComponent? {
                    return when (key) {
                        DataFactory.COMPONENT_ID,
                        SchemaProvider.COMPONENT_ID,
                        RoProvider.COMPONENT_ID,
                        OperationResolver.COMPONENT_ID,
                        LazyDataMaterializer.ID -> koin.getOrNull<SchemaRegistry>()

                        InteractorProvider.COMPONENT_ID -> koin.getOrNull<InteractorProvider>()

                        // R5: the same object, asked a different question. Resolved off the
                        // provider rather than bound separately because the provider is what
                        // already self-wires to the executor's reconnect edge — and `as?` means a
                        // provider that is not connectivity-aware simply reads as absent, i.e. as
                        // "no channel to lose", which is the correct answer in-process.
                        ProxyConnectivity.ID ->
                            koin.getOrNull<InteractorProvider>() as? ProxyConnectivity
                        FileUploader.COMPONENT_ID -> koin.getOrNull<FileUploader>()
                        TraitResolver.ID -> koin.getOrNull<TraitResolver>()
                        PureOperationRunner.ID -> koin.getOrNull<PureOperationRunner>()
                        SyncCryptoManager.ID -> koin.getOrNull<SyncCryptoManager>()
                        Clock.COMPONENT_ID -> koin.getOrNull<Clock>()
                        OnDemandResolution.ID -> koin.getOrNull<OnDemandResolution>()
                        ExternalPropertyWriter.ID -> koin.getOrNull<ExternalPropertyWriter>()
                        else -> koin.getOrNull<TComponent>(named(key))
                    }
                }

                override val keys: Set<String>
                    get() = setOf(
                        DataFactory.COMPONENT_ID,
                        SchemaProvider.COMPONENT_ID,
                        RoProvider.COMPONENT_ID,
                        OperationResolver.COMPONENT_ID,
                        LazyDataMaterializer.ID,
                        InteractorProvider.COMPONENT_ID,
                        ProxyConnectivity.ID,
                        FileUploader.COMPONENT_ID,
                        TraitResolver.ID,
                        PureOperationRunner.ID,
                        SyncCryptoManager.ID,
                        Clock.COMPONENT_ID,
                        OnDemandResolution.ID,
                        ExternalPropertyWriter.ID
                    )

                override val values: Collection<TComponent>
                    get() = keys.mapNotNull { get(it) }

                override val entries: Set<Map.Entry<String, TComponent>>
                    get() = keys.mapNotNull { key ->
                        val v = get(key) ?: return@mapNotNull null
                        object : Map.Entry<String, TComponent> {
                            override val key: String = key
                            override val value: TComponent = v
                        }
                    }.toSet()
            }
        }
    }

    single<TraitResolver> { TraitResolverImpl { get() } }
    single<PureOperationRunner> {
        PureOperationRunnerImplementation({ get() }) {
            getOrNull<OperationImplementationFactory>()
                ?: OperationImplementationFactory { id: String ->
                    implementationFactory(id)
                }
        }
    }

    single<SchemaRegistry> {
        createDefaultSchemaRegistry(
            tContextProvider = { get<TContext>() },
            extraConfigs = getOrNull<List<ModuleConfiguration>>(named("coreSchemaConfigs"))
                ?: emptyList()
        )
    }
    single<SchemaProvider> { get<SchemaRegistry>() }
    single<DataFactory> { get<SchemaRegistry>() }

    single { BinarySerializer(get()) }
    single { BinaryParser(get(), get()) }

    // The same DefaultInteractorProvider satisfies both InteractorProvider (DSL extensions ask
    // it to resolve the right Interactor for a RequestObject) and StreamElementDispatcher
    // (transport layers route incoming stream elements to it). Register the concrete class
    // once and bind both interface keys to that singleton so there is no runtime cast.
    single<DefaultInteractorProvider> {
        val provider = DefaultInteractorProvider { get<TaskExecutor>() }

        val taskExecutor = getOrNull<TaskExecutor>()
        if (taskExecutor is RemoteBinaryTaskExecutor) {
            taskExecutor.streamElementHandler = { element ->
                if (element is StreamElement<*>) {
                    provider.dispatchElement(element)
                }
            }
        }

        provider
    }

    single<InteractorProvider> { get<DefaultInteractorProvider>() }
    single<StreamElementDispatcher> { get<DefaultInteractorProvider>() }
}
