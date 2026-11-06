package org.tenshu.file.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.placement.name
import org.tenshu.core.file.placement.parentPath
import org.tenshu.core.file.placement.sharedRealmId
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Schema test for the [Placement] connector — a per-member mount of a shared realm
 * into the member's personal FS (`docs/proposals/drive-shared-realm-file-model.md`
 * §3 D3 / §6 Phase 3c.2). Mirrors [SharedFileSchemaTest]:
 *  - `Placement` is `Syncable` (trait factory in `fileServiceCommonModule`) so a
 *    member's mounts converge across their own devices,
 *  - its DataId is the **unprefixed** `sharedRealmId` (via `PlacementDataIdProvider`)
 *    — no `::`, so `RealmRegistry.realmForDataId` routes it to the *personal* realm,
 *  - the fields round-trip on `create(...)` + getter,
 *  - and it survives a serialize → parse round-trip (the proxy `BinaryParser` wire
 *    path — in-process getters alone wouldn't exercise the registration).
 */
class PlacementSchemaTest {

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(fileServiceCommonModule)
    }

    @Test
    fun placementHasSyncableTrait() {
        assertTrue(
            Placement.Schema.hasTrait(Syncable.ID),
            "Placement must be Syncable so a member's mounts converge across their devices",
        )
    }

    @Test
    fun dataIdIsTheUnprefixedSharedRealmId() {
        val p = proxy()
        val placement = Placement.create(
            context = p.tContext,
            sharedRealmId = "realm-owner-1",
            parentPath = "/Shared with me",
            name = "Holiday photos",
        )
        val entity = Entity.create<Placement>(context = p.tContext, composite = placement)

        val dataId = p.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        assertEquals(Placement.ID, dataId.type.get())
        // No `::` prefix → routes to the personal realm (the connector is private).
        assertEquals("realm-owner-1", dataId.keyValue.get())
        assertTrue("::" !in dataId.keyValue.get(), "the Placement key is unprefixed (personal realm)")
    }

    @Test
    fun fieldsRoundTrip() {
        val p = proxy()
        val placement = Placement.create(
            context = p.tContext,
            sharedRealmId = "realm-owner-2",
            parentPath = "/Shared",
            name = "Team docs",
        )

        assertEquals("realm-owner-2", placement.sharedRealmId.get())
        assertEquals("/Shared", placement.parentPath.get())
        assertEquals("Team docs", placement.name.get())
    }

    @Test
    fun survivesSerializeParseRoundTrip() {
        val p = proxy()
        val serializer = BinarySerializer(p.schemaRegistry)

        val entity = Entity.create<Placement>(
            context = p.tContext,
            composite = Placement.create(
                context = p.tContext,
                sharedRealmId = "realm-owner-3",
                parentPath = "/Shared with me",
                name = "Recipes",
            ),
        )

        val bytes = serializer.serialize(entity)

        @Suppress("UNCHECKED_CAST")
        val parsed = p.parser.parse<Entity<Placement>>(bytes)

        assertEquals("realm-owner-3", parsed.sharedRealmId.get())
        assertEquals("/Shared with me", parsed.parentPath.get())
        assertEquals("Recipes", parsed.name.get())
    }
}
