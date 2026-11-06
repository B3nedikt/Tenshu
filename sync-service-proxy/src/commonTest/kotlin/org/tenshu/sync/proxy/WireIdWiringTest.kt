package org.tenshu.sync.proxy

import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EI-1's seam, pinned because getting it wrong is **silent**.
 *
 * ⚠⚠ `TombstoneRegistry`'s reverse index looks local — a string to a `DataId`, never leaving the
 * device — but its consumers (`SyncProcessor.mergeData`, `FileContentManifestApplier`) resolve
 * hashes that **arrived from the relay**. Key it locally while peers name entities by their wire id
 * and every inbound tombstone misses; both halves still look healthy, and nothing reports it.
 */
class WireIdWiringTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
    }

    private fun id(key: String): DataId =
        DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, key)

    /** The volume key a realm would supply; the registry never sees it, only the resolved name. */
    private val entityKey = WireEntityId.keyFor(ByteArray(32) { it.toByte() })

    @Test
    fun aKeyedRegistryIndexesUnderTheWireName_notTheLocalHash() {
        val registry = TombstoneRegistry(
            executionContext = proxy.executionContext,
            wireIdFor = { WireEntityId.of(it, entityKey) },
        )
        val dataId = id("/Photos/holiday.jpg")
        registry.rememberDataId(dataId)

        val wire = WireEntityId.of(dataId, entityKey)
        assertEquals(dataId, registry.resolveDataId(wire), "an inbound wire name resolves")
        assertNull(
            registry.resolveDataId(dataId.getDataIdHash().toString()),
            "and the pre-EI-1 local hash does NOT — if it did, the index would be keyed twice over",
        )
        assertNotEquals(dataId.getDataIdHash().toString(), wire)
    }

    /**
     * ⚠⚠ There is no default any more, and that is the guard: `wireIdFor` is required, so nobody
     * silently gets pre-EI-1 naming. A harness with no realm passes [localTestNaming] and thereby
     * states, visibly, that it does not cover the wire.
     */
    @Test
    fun aHarnessWithoutARealmMustSayItIsUsingLocalNaming() {
        val registry = TombstoneRegistry(
            executionContext = proxy.executionContext,
            wireIdFor = localTestNaming,
        )
        val dataId = id("/a")
        registry.rememberDataId(dataId)

        assertTrue(
            registry.resolveDataId(dataId.getDataIdHash().toString()) != null,
            "unset ⇒ pre-EI-1 local naming, which is why production must pass a resolver",
        )
    }
}
