package org.tenshu.core.file.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LiveContentIds]' walk (`drive-upload-stop-and-reclamation.md` §4.10): the live set must never look
 * complete when it is not.
 *
 * ⚠ CR-2 B6 removed ST-3b's candidate record, the other half these tests pinned, and the sweep whose
 * veto read this walk; the walk itself stays (`content-plane-cr2-b6-spec.md` §7).
 */
class LiveContentIdsTest {

    // --- the realm live set --------------------------------------------------------------------

    @Test
    fun liveSet_collectsEveryContentIdInTheTree() = runTest {
        val tree = mapOf(
            "/" to listOf(
                LiveContentIds.Node.File("/a.txt", "c-a"),
                LiveContentIds.Node.Folder("/sub"),
            ),
            "/sub" to listOf(
                LiveContentIds.Node.File("/sub/b.txt", "c-b"),
                LiveContentIds.Node.Folder("/sub/deep"),
            ),
            "/sub/deep" to listOf(LiveContentIds.Node.File("/sub/deep/c.txt", "c-c")),
        )
        val result = LiveContentIds.collect("/") { tree[it] }
        assertEquals(setOf("c-a", "c-b", "c-c"), assertIs<LiveContentIds.Result.Complete>(result).contentIds)
    }

    /**
     * ⚠⚠ The one that matters. A folder that cannot be listed must fail the WHOLE walk: treating
     * it as empty yields a complete-looking set that is missing a subtree, and the policy would
     * then reclaim the bytes of every file underneath it.
     */
    @Test
    fun liveSet_anUnreadableFolder_failsTheWholeWalk() = runTest {
        val tree = mapOf(
            "/" to listOf(
                LiveContentIds.Node.File("/a.txt", "c-a"),
                LiveContentIds.Node.Folder("/unreadable"),
            ),
        )
        val result = LiveContentIds.collect("/") { tree[it] }   // "/unreadable" -> null
        val incomplete = assertIs<LiveContentIds.Result.Incomplete>(
            result,
            "an unreadable folder must not read as an empty one",
        )
        assertTrue(incomplete.reason.contains("/unreadable"), incomplete.reason)
    }

    /** ⚠ A file with no contentId owns no blobs — skipped, not an error. */
    @Test
    fun liveSet_toleratesFilesWithoutContent() = runTest {
        val tree = mapOf(
            "/" to listOf(
                LiveContentIds.Node.File("/legacy.txt", null),
                LiveContentIds.Node.File("/blank.txt", "  "),
                LiveContentIds.Node.File("/real.txt", "c-real"),
            ),
        )
        val result = LiveContentIds.collect("/") { tree[it] }
        assertEquals(setOf("c-real"), assertIs<LiveContentIds.Result.Complete>(result).contentIds)
    }

    /** A cycle must terminate rather than spin — a guard against a future mount/placement shape. */
    @Test
    fun liveSet_terminatesOnACycle() = runTest {
        val tree = mapOf(
            "/" to listOf(LiveContentIds.Node.Folder("/loop")),
            "/loop" to listOf(LiveContentIds.Node.Folder("/"), LiveContentIds.Node.File("/loop/x", "c-x")),
        )
        val result = LiveContentIds.collect("/") { tree[it] }
        assertEquals(setOf("c-x"), assertIs<LiveContentIds.Result.Complete>(result).contentIds)
    }

    /** An empty realm is complete and empty — distinct from a failed walk. */
    @Test
    fun liveSet_anEmptyRealmIsCompleteNotIncomplete() = runTest {
        val result = LiveContentIds.collect("/") { emptyList() }
        assertEquals(emptySet(), assertIs<LiveContentIds.Result.Complete>(result).contentIds)
    }
}
