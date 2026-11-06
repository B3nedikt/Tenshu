package org.tenshu.file.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.trait.RealmData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Track B Phase B2 (`docs/proposals/core-realm-data.md` §4 Phase 2 + §8): `File` declares the
 * `RealmData` marker and binds `File.CreatedAt` as the newest-first sort key. `Folder` inherits
 * the marker for resolution but folds into File's single entry (§2.4).
 */
class FileRealmDataTest {

    private val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }

    @Test
    fun realmDataTypeIds_containsFileOnce_withFolderFoldedIn() {
        val ids = registry.realmDataTypeIds()

        assertEquals(
            listOf(File.ID),
            ids,
            "File is the only declarer; Folder must fold in (§2.4)",
        )
    }

    @Test
    fun resolveCreatedAt_bindsFileCreatedAt() {
        assertEquals(
            File.CreatedAt.id,
            registry.resolveValueRequirement(File.ID, RealmData.CREATED_AT)?.id,
            "File.CreatedAt must fulfil RealmData.CREATED_AT (the newest-first sort key)",
        )
    }

    @Test
    fun resolveCreatedAt_folderInheritsFileBinding() {
        // Folder folds into File for enumeration but still resolves the requirement via the
        // type hierarchy — proving the binding lookup walks parents.
        assertEquals(
            File.CreatedAt.id,
            registry.resolveValueRequirement(Folder.ID, RealmData.CREATED_AT)?.id,
        )
    }

    @Test
    fun resolveValueRequirement_unboundRequirement_isNull() {
        assertNull(registry.resolveValueRequirement(File.ID, "tenshu/test/no-such-requirement"))
    }
}
