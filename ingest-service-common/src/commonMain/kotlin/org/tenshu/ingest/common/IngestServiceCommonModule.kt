package org.tenshu.ingest.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.IngestSourceDataIdProvider
import org.tenshu.core.ingest.IngestService
import org.tenshu.ingest.common.impl.IngestSourceDataIdProviderImpl
import org.tenshu.ingest.common.impl.IngestSourceDto

val ingestServiceCommonModule = tModule {

    service(IngestService.Schema)

    composite(
        interfaceClass = IngestSource::class,
        dataSchema = IngestSource.Schema,
        dtoFactory = ::IngestSourceDto
    )

    trait(
        dataSchema = IngestSource.Schema,
        traitSchema = IngestSourceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            IngestSourceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

}
