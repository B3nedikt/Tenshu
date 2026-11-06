package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.schema.propertyRef
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Resolving a static property of a row the store does not hold yields **no value**, rather than
 * raising.
 *
 * ⚠⚠ The distinction is load-bearing because of where this runs. A projected chain resolves each
 * row's properties by id, and Drive's sorted listing uses one as its **invalidation** chain — a
 * bare `select` with no window, so unlike a page load it cannot fuse into a thin pass and every
 * row is read back individually. `StreamRegistry`'s update handler stops the stream on any
 * `Throwable`, so a raise here is not one failed read: the folder's change stream ends, the client
 * re-subscribes on a backoff that settles at 15 s and fails again each time, and the listing
 * silently never updates again for the session.
 *
 * ⚠⚠ And an array naming a row this store lacks is **routine in a syncing system**, not
 * corruption: a peer's create can land before the parent array that names it, a pull can be
 * interrupted mid-batch, and a pull-fenced device sits in that state indefinitely — one was
 * observed on a real phone (`core-version-compatibility.md` §1.6), which is where this came from.
 *
 * ⚠ The sibling assertion — a composite with no `DataId` at all — stays a raise: that really is an
 * invariant violation rather than an absence.
 */
class ReferenceResolverMissingRowTest {

    @Test
    fun aStaticPropertyOfARowTheStoreLacks_resolvesToNull() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = backgroundScope,
        )

        // Addressable — `getDataId` resolves it from the composite's key — but never stored, so
        // the store has no row behind that id. This is the dangling-reference shape.
        val absent = SyntheticTestData.Factory.create(proxy.tContext, name = "never-stored", value = 1L)

        // A property id that is neither a reference property nor an entity relation, so
        // `resolveProperty` falls through to the static path under test.
        val resolved: Data? = proxy.executionContext.resolveProperty(
            absent,
            propertyRef("test/synthetic/valueProperty"),
        )

        assertNull(resolved, "a property of a row the store does not hold resolves to no value")
    }
}
