package app.tenshu.ingest.backup

import kotlin.test.Test
import kotlin.test.assertEquals

/** [duplicateLookahead] — which candidates the content skip hashes ahead of their turn (§40). */
class DuplicateLookaheadTest {

    private fun item(name: String, size: Long?) =
        MediaItem(uri = "uri/$name", fileName = name, addedAtMillis = 0, cursor = name, sizeBytes = size)

    private fun names(batch: List<MediaItem>) = batch.map { it.fileName }

    @Test
    fun sizedCandidatesAreBatched_upToTheByteBudget() {
        val half = DUPLICATE_LOOKAHEAD_BYTES / 2
        val items = listOf(item("a", half), item("b", half), item("c", 1), item("d", 1))

        assertEquals(listOf("a", "b"), names(duplicateLookahead(items, 0, emptySet())))
        assertEquals(listOf("b", "c", "d"), names(duplicateLookahead(items, 1, emptySet())))
    }

    @Test
    fun aCandidateOfUnknownSize_endsTheBatch_ratherThanCountingAsNothing() {
        // ⚠⚠ iOS sizes nothing, so "unknown is zero" would let 32 videos of any length be hashed
        // before the first upload or the next Stop. Unknown is not small.
        val items = listOf(item("a", 1), item("b", 1), item("video", null), item("d", 1))

        assertEquals(listOf("a", "b"), names(duplicateLookahead(items, 0, emptySet())))
    }

    @Test
    fun aCandidateOfUnknownSize_isStillTakenOnItsOwnTurn_alone() {
        val items = listOf(item("video", null), item("b", 1), item("c", 1))

        assertEquals(listOf("video"), names(duplicateLookahead(items, 0, emptySet())))
    }

    @Test
    fun whatAnEarlierFolderAlreadyHandled_isNeitherHashedNorCounted() {
        val items = listOf(item("a", 1), item("seen", null), item("c", 1))

        assertEquals(listOf("a", "c"), names(duplicateLookahead(items, 0, setOf("uri/seen"))))
    }
}
