package org.tenshu.systemtest

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.identity.Device
import org.tenshu.sync.proxy.TombstoneWireType
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ EI-4's one remaining obligation (`sync-entity-identity.md` §5b): the non-device tombstone
 * sentinel must equal **no** registered schema id. A tombstone's `targetEntityType` on the wire is
 * `Device.ID` or [TombstoneWireType.NON_DEVICE]; if a real type ever took the sentinel's name, the
 * wire value would again name a concrete type (and a future reader that trusted it would misread
 * every delete).
 *
 * Judged over the whole service graph this module wires ([serviceGraphConfigs], all twenty
 * services, common and client halves), so a type added anywhere in that graph cannot collide
 * silently. Every id the registry can resolve is checked: schema-mapping keys, each schema's own
 * id, and relation ids.
 */
class TombstoneSentinelCollisionTest {

    @Test
    fun theNonDeviceSentinelCollidesWithNoRegisteredId() {
        val configs = serviceGraphConfigs()
        val registry = createCoreTestRegistry().apply { configs.forEach { registerConfig(it) } }

        val ids = buildSet {
            configs.forEach { addAll(it.schemaMapping.keys) }
            registry.allSchemas().forEach { add(it.id.get()) }
            registry.registeredRelations().forEach { (receiver, relation) -> add(receiver); add(relation) }
        }

        // A walk whose input can silently shrink goes green by covering less: name a floor.
        assertTrue(ids.size > 500, "the graph looks truncated: only ${ids.size} ids")
        assertTrue(Device.ID in ids, "precondition: Device must be registered, or the graph is not the real one")

        if (TombstoneWireType.NON_DEVICE in ids) {
            fail(
                "The EI-4 tombstone sentinel \"${TombstoneWireType.NON_DEVICE}\" is a registered id. Rename the " +
                    "sentinel (TombstoneWireType.NON_DEVICE), never the type: a tombstone's wire type must stay one bit."
            )
        }
        assertTrue(registry.getSchema(TombstoneWireType.NON_DEVICE) == null)
    }
}
