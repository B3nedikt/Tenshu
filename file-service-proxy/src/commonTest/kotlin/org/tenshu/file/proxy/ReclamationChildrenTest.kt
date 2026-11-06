package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.file.sync.LiveContentIds
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The realm walk's `children` contract (`reclamationChildren`), against a real store — the seam the
 * describe backfill's tree walk (`declarableContent`) still rests on.
 *
 * ⚠ CR-2 B6 removed the other seam this file pinned, ST-3b's production candidate recorder.
 */
class ReclamationChildrenTest {

    // --- The walk -------------------------------------------------------------------------

    /**
     * ⚠⚠ **The distinction the whole safety property rests on.** A folder that will not load must
     * report `null`, never an empty list: `LiveContentIds` reads an empty list as "this subtree
     * holds no content", and every file underneath it would then look unreferenced.
     *
     * ⚠ To confirm the guard is the thing under test rather than an accident of the fixture, delete
     * the `?: return null` from `reclamationChildren`'s folder load: this is the only test in the
     * module that goes red.
     */
    @Test
    fun aFolderThatWillNotLoad_reportsFailure_notAnEmptyFolder() = runTest {
        val proxy = createFileProxy()
        assertNull(
            proxy.executionContext.reclamationChildren("/no-such-folder"),
            "an unreadable folder must be distinguishable from an empty one",
        )
    }

    /**
     * The other half of the same contract: a folder that loads and is genuinely empty is an empty
     * list, not a failure. Reading *that* as failure would abort every walk and stall reclamation
     * for ever — safe, but useless.
     */
    @Test
    fun anEmptyFolder_isEmpty_notAFailure() = runTest {
        val proxy = createFileProxy()
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/empty".toTString()),
        )

        assertEquals(emptyList(), proxy.executionContext.reclamationChildren("/empty"))
    }

    /** A folder's children are reported with their paths, so the walk can descend. */
    @Test
    fun aFolderReportsItsChildren() = runTest {
        val proxy = createFileProxy()
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/docs".toTString()),
        )

        val children = proxy.executionContext.reclamationChildren("/")
        assertTrue(
            children?.any { it is LiveContentIds.Node.Folder && it.path == "/docs" } == true,
            "the root must report the folder that was just created; got $children",
        )
    }
}
