package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.schema.composite
import org.tenshu.core.trait.RealmData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase B1 of `docs/tracks/track-b-control-data.md` (proposal §2.3 / §2.4 / §3.2):
 * [org.tenshu.core.core.SchemaProvider.realmDataTypeIds] enumerates the type ids that
 * **declare** the [RealmData] marker, and a sub-type that merely *inherits* it does not
 * produce a duplicate entry.
 */
class RealmDataIntegrationTest {

    // Two fixture data types declaring the RealmData marker, plus a sub-type of one that
    // inherits it (its schema parents the declarer's).
    private val photoSchema = composite(
        id = "test/realmdata/photo",
        parent = Composite.Schema,
        traits = listOf(RealmData.Schema),
    )

    private val noteSchema = composite(
        id = "test/realmdata/note",
        parent = Composite.Schema,
        traits = listOf(RealmData.Schema),
    )

    // Parents photoSchema → inherits RealmData for resolution, must NOT enumerate separately.
    private val rawPhotoSchema = composite(
        id = "test/realmdata/rawPhoto",
        parent = photoSchema,
    )

    private fun registry() = TestProxy().apply {
        schemaRegistry.registerConfig(
            tModule {
                composite(photoSchema)
                composite(noteSchema)
                composite(rawPhotoSchema)
            }
        )
    }.schemaRegistry

    @Test
    fun realmDataTypeIds_enumeratesDeclarers() {
        val ids = registry().realmDataTypeIds()

        assertTrue("test/realmdata/photo" in ids)
        assertTrue("test/realmdata/note" in ids)
    }

    @Test
    fun realmDataTypeIds_doesNotDuplicateInheritedTrait() {
        val ids = registry().realmDataTypeIds()

        // rawPhoto inherits RealmData from photo (§2.4) — it folds into photo's entry.
        assertFalse(
            "test/realmdata/rawPhoto" in ids,
            "a sub-type that only inherits the marker must not enumerate separately",
        )
        // The two declarers appear exactly once each (no duplicate from the inheriting type).
        assertEquals(
            listOf("test/realmdata/note", "test/realmdata/photo"),
            ids.filter { it.startsWith("test/realmdata/") }.sorted(),
        )
    }
}
