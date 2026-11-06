package org.tenshu.identity.common

import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.params
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IdentityServiceRelationsTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }

    @Test
    fun devicesRelation_resolvesAndCarriesDeviceItemType() {
        val service = ServiceTypeReferenceDto(IdentityService.ID)
        val list = EntityListReferenceDto(IdentityService.Relations.DEVICES)

        val relation = registry.getServiceDataRelation(service, list)

        assertNotNull(
            relation,
            "IdentityService.Relations.DEVICES should resolve via the registered service-data relation"
        )
        assertEquals(IdentityService.ID, (relation.from as Reference).get())
        assertEquals(IdentityService.Relations.DEVICES, (relation.to as Reference).get())
        assertEquals(TList.ID, relation.type.baseSchema?.id?.get())
        val itemTypeId = relation.type.params.toList().firstOrNull()
            ?.type?.baseSchema?.id?.get()
        assertEquals(Device.ID, itemTypeId)
    }
}
