package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Track B Phase B3 (`docs/proposals/core-realm-data.md` §4 Phase 3): the JS SDK surfaces the
 * `RealmData` marker enumeration so the web Control Data section can walk it generically. File
 * is the only v1 declarer; Folder inherits the marker but folds into File (§2.4).
 */
class RealmDataTest : SdkRuntimeTest() {

    @Test
    fun realmDataTypeIds_containsFileOnce_noFolderDuplicate() = runTest {
        val ids = TenshuSdk.realmDataTypeIds().await().toList()

        assertTrue(File.ID in ids, "File must surface as a realm-data type; got $ids")
        assertFalse(
            Folder.ID in ids,
            "Folder inherits the marker but folds into File (§2.4); got $ids",
        )
        assertEquals(1, ids.count { it == File.ID }, "File must appear exactly once")
    }
}
