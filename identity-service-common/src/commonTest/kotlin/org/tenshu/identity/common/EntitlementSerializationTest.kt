package org.tenshu.identity.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.identity.Entitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the wire-registration invariant ([wire-registration-gap]) for the
 * Publisher-signed [Entitlement] (storage-entitlement-sync.md §3.1): it crosses
 * the relay sync wire (and the Drive↔Control IPC boundary), so it must survive a
 * binary serialize→parse round-trip with every field intact — including the
 * opaque signature bytes and the two wall-clock instants beyond the 32-bit range.
 * Also asserts the record carries the [Syncable] trait so the sync machinery
 * actually propagates it.
 */
class EntitlementSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun entitlement_survivesWireRoundTrip() {
        val validFrom = 1_900_000_000_000L // ms since epoch, beyond Int range
        val validUntil = 1_902_592_000_000L
        val signature = byteArrayOf(0x30, 0x45, 0x02, 0x21, 0x00, 0xAB.toByte(), 0xCD.toByte())

        val original = Entitlement.create(
            context = context,
            ownerId = "owner-abc",
            publisherId = "pub-dev-1",
            productId = "tenshu.drive.basic",
            validFrom = validFrom,
            validUntil = validUntil,
            publisherSignature = signature,
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as Entitlement

        assertEquals("owner-abc", parsed.ownerId.get(), "ownerId must survive the round-trip")
        assertEquals("pub-dev-1", parsed.publisherId.get(), "publisherId must survive")
        assertEquals("tenshu.drive.basic", parsed.productId.get(), "productId must survive")
        assertEquals(validFrom, parsed.validFrom.get(), "validFrom must survive (Long, beyond Int range)")
        assertEquals(validUntil, parsed.validUntil.get(), "validUntil must survive")
        assertEquals(
            signature.toList(),
            parsed.publisherSignature.get().toList(),
            "publisher signature bytes must survive unchanged",
        )
    }

    @Test
    fun entitlement_schemaCarriesSyncableTrait() {
        assertTrue(
            Entitlement.Schema.hasTrait(Syncable.ID),
            "Entitlement.Schema must declare the Syncable trait so it propagates across devices",
        )
    }
}
