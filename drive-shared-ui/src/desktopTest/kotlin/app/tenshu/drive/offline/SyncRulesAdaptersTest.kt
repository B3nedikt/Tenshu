package app.tenshu.drive.offline

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.PathSyncOverrideSetAdapter
import org.tenshu.core.file.sync.PathSyncOverrideSet
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncPolicyAdapter
import org.tenshu.core.toTString
import org.tenshu.file.common.fileServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Data` codecs for the synced sync-rule preferences, now that [SyncPolicy] is a
 * first-class `TConstant` and the override set rides a registered
 * [PathSyncOverrideSet] composite. The round-trips + the mismatched-value → `null`
 * fallback (so the read path resolves to the preference default) are the
 * load-bearing contracts. A file-service-aware context is needed so the composite
 * schemas resolve.
 */
class SyncRulesAdaptersTest {

    private val ctx = createTestContext(
        createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
    )

    @Test
    fun `policy round-trips as the self-describing constant itself`() {
        for (p in SyncPolicy.entries) {
            // Identity codec: the constant is already a Data, so it rides the
            // polymorphic value slot directly and round-trips to itself.
            assertEquals(p, SyncPolicyAdapter.fromData(SyncPolicyAdapter.toData(p, ctx)))
            assertEquals(p, SyncPolicyAdapter.toData(p, ctx))
        }
    }

    @Test
    fun `policy rejects mismatched or unknown values`() {
        assertNull(SyncPolicyAdapter.fromData(null))
        assertNull(SyncPolicyAdapter.fromData("NOT_A_POLICY".toTString(ctx)))
    }

    @Test
    fun `override list round-trips, preserving order and policy`() {
        val list = listOf(
            PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE),
            PathSyncOverride("/Archive/old", SyncPolicy.ONLINE_ONLY),
            PathSyncOverride("/notes/todo.txt", SyncPolicy.KEEP_OFFLINE),
        )
        // Self-describing: encodes to a PathSyncOverrideSet composite, not a blob.
        val data = PathSyncOverrideSetAdapter.toData(list, ctx)
        assertTrue(data is PathSyncOverrideSet, "overrides encode to the registered wrapper composite")
        assertEquals(list, PathSyncOverrideSetAdapter.fromData(data))
    }

    @Test
    fun `empty override list round-trips`() {
        assertEquals(
            emptyList(),
            PathSyncOverrideSetAdapter.fromData(PathSyncOverrideSetAdapter.toData(emptyList(), ctx)),
        )
    }

    @Test
    fun `override list with unicode path round-trips`() {
        val list = listOf(PathSyncOverride("/Fotos/café/naïve €.png", SyncPolicy.KEEP_OFFLINE))
        assertEquals(list, PathSyncOverrideSetAdapter.fromData(PathSyncOverrideSetAdapter.toData(list, ctx)))
    }

    @Test
    fun `override list rejects mismatched value`() {
        assertNull(PathSyncOverrideSetAdapter.fromData(null))
        assertNull(PathSyncOverrideSetAdapter.fromData("nope".toTString(ctx)))
    }
}
