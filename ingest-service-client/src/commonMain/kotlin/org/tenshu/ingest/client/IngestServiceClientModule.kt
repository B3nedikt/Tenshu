package org.tenshu.ingest.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.IngestService
import org.tenshu.core.ingest.PickSecurityScopedFolder
import org.tenshu.core.ingest.RegisterSource
import org.tenshu.core.ingest.SetSourceEnabled
import org.tenshu.core.ingest.UpdateSourceStatus
import org.tenshu.ingest.client.impl.IngestServiceRo
import org.tenshu.ingest.client.impl.PickSecurityScopedFolderRo
import org.tenshu.ingest.client.impl.RegisterSourceRo
import org.tenshu.ingest.client.impl.SetSourceEnabledRo
import org.tenshu.ingest.client.impl.UpdateSourceStatusRo

val ingestServiceClientModule = tModule {

    /**
     * ⚠⚠ The interface MUST be in the supertype list. This returned a **bare**
     * `CompositeRo(chain, ctx, IngestSource.Schema)` until `ElementRequestObjectRegistrationTest`
     * caught it — which is the *second*, harder failure mode of a missing element Ro, not a
     * cosmetic difference: the factory exists, so the chain builds, and then the element-lambda
     * ops (`sortBy`/`filter`/`search`/`find`) cast the synthetic element to `IngestSource` and
     * throw `ClassCastException: CompositeRo cannot be cast to IngestSource`. A stack trace for
     * that names neither the type nor the registration.
     *
     * `IngestSource`'s accessors are all defaults over `getField`, so implementing the interface
     * on top of [CompositeRo] is the whole of the fix — the same shape `FileServiceClientModule`
     * and `LoggingServiceClientModule` use.
     */
    type(
        interfaceClass = IngestSource::class,
        dataSchema = IngestSource.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, IngestSource.Schema), IngestSource {
                override val schema: CompositeSchema get() = IngestSource.Schema
            }
        }
    )

    operation(
        interfaceClass = PickSecurityScopedFolder::class,
        protocolSchema = PickSecurityScopedFolder.Schema,
        roFactory = ::PickSecurityScopedFolderRo
    )

    operation(
        interfaceClass = RegisterSource::class,
        protocolSchema = RegisterSource.Schema,
        roFactory = ::RegisterSourceRo
    )

    operation(
        interfaceClass = UpdateSourceStatus::class,
        protocolSchema = UpdateSourceStatus.Schema,
        roFactory = ::UpdateSourceStatusRo
    )

    operation(
        interfaceClass = SetSourceEnabled::class,
        protocolSchema = SetSourceEnabled.Schema,
        roFactory = ::SetSourceEnabledRo
    )

    type(
        interfaceClass = IngestService::class,
        dataSchema = IngestService.Schema,
        roFactory = ::IngestServiceRo
    )
}
