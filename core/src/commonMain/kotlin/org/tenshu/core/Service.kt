package org.tenshu.core

import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service

interface Service : Data {

    override val schema: ServiceSchema
        get() = service(ID)

    companion object {
        const val ID = "tenshu/core/service"
    }
}
