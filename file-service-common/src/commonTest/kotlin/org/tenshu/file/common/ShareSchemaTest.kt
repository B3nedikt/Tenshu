package org.tenshu.file.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.share.Share
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-1 schema test for the Mode-A [Share] composite
 * (`docs/file-sharing.md`). Mirrors
 * [FileModificationSchemaTest]:
 *  - `Share` is `Syncable` (the trait factory is registered in
 *    `fileServiceCommonModule`),
 *  - `Share`'s DataId derives from `shareId` (via `ShareDataIdProvider`),
 *  - the seven fields round-trip on a `create(...)` + getter call.
 */
class ShareSchemaTest {

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(fileServiceCommonModule)
    }

    @Test
    fun shareHasSyncableTrait() {
        assertTrue(
            Share.Schema.hasTrait(Syncable.ID),
            "Share must be Syncable so my-shares converges across realm devices",
        )
    }

    @Test
    fun shareDataIdDerivesFromShareId() {
        val p = proxy()
        val share = Share.create(
            context = p.tContext,
            shareId = "share-abc",
            fileId = "/Tax/2024.pdf",
            createdAtMillis = 1_000L,
            createdOnDeviceId = "device-1",
        )
        val entity = Entity.create<Share>(
            context = p.tContext,
            composite = share,
        )

        val dataId = p.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        assertEquals(Share.ID, dataId.type.get())
        assertEquals("share-abc", dataId.keyValue.get())
    }

    @Test
    fun shareFieldsRoundTrip() {
        val p = proxy()
        val share = Share.create(
            context = p.tContext,
            shareId = "share-xyz",
            fileId = "/docs/spec.md",
            createdAtMillis = 2_000L,
            createdOnDeviceId = "device-2",
            expiresAtMillis = 5_000L,
            maxOpens = 3L,
            revoked = true,
        )

        assertEquals("share-xyz", share.shareId.get())
        assertEquals("/docs/spec.md", share.fileId.get())
        assertEquals(2_000L, share.createdAtMillis.get())
        assertEquals("device-2", share.createdOnDeviceId.get())
        assertEquals(5_000L, share.expiresAtMillis.get())
        assertEquals(3L, share.maxOpens.get())
        assertEquals(true, share.revoked.get())
    }
}
