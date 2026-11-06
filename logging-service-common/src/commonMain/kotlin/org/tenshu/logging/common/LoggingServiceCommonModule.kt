package org.tenshu.logging.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.ErrorEventDataIdProvider
import org.tenshu.logging.LoggingService
import org.tenshu.logging.common.impl.ErrorEventDataIdProviderImpl
import org.tenshu.logging.common.impl.ErrorEventDto

val loggingServiceCommonModule = tModule {

    service(LoggingService.Schema)

    composite(
        interfaceClass = ErrorEvent::class,
        dataSchema = ErrorEvent.Schema,
        dtoFactory = ::ErrorEventDto
    )

    trait(
        dataSchema = ErrorEvent.Schema,
        traitSchema = ErrorEventDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ErrorEventDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

}
