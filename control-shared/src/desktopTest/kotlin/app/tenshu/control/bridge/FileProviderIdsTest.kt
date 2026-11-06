package app.tenshu.control.bridge

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FileProviderIdsTest {

    private val dir: File = Files.createTempDirectory("fp-ids").toFile()
    private val journal = File(dir, "ids.tsv")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun theRootHasAFixedIdentifier_andIsNeverMinted() {
        val ids = FileProviderIds(journal)
        assertEquals(FileProviderIds.ROOT_ID, ids.idFor("/"))
        assertEquals(FileProviderIds.ROOT_ID, ids.idFor(""))
        assertEquals("/", ids.pathFor(FileProviderIds.ROOT_ID))
    }

    @Test
    fun anIdentifierIsStablePerPath_andUnknownOnesResolveToNothing() {
        val ids = FileProviderIds()
        val a = ids.idFor("/a.txt")
        assertEquals(a, ids.idFor("/a.txt"))
        assertNotEquals(a, ids.idFor("/b.txt"))
        assertEquals("/a.txt", ids.pathFor(a))
        assertNull(ids.pathFor("never-issued"))
    }

    @Test
    fun aRenameKeepsTheIdentifier() {
        val ids = FileProviderIds()
        val id = ids.idFor("/docs/note.txt")
        ids.moved("/docs/note.txt", "/docs/renamed.txt")
        assertEquals("/docs/renamed.txt", ids.pathFor(id))
        assertEquals(id, ids.idFor("/docs/renamed.txt"))
        // The old path is free again: a new file there is a different item.
        assertNotEquals(id, ids.idFor("/docs/note.txt"))
    }

    @Test
    fun movingAFolderCarriesEverythingUnderIt_andNothingBesideIt() {
        val ids = FileProviderIds()
        val folder = ids.idFor("/docs")
        val child = ids.idFor("/docs/a/deep.txt")
        // Shares the prefix as a string, not as a path.
        val sibling = ids.idFor("/docs-old/keep.txt")

        ids.moved("/docs", "/archive/papers")

        assertEquals("/archive/papers", ids.pathFor(folder))
        assertEquals("/archive/papers/a/deep.txt", ids.pathFor(child))
        assertEquals("/docs-old/keep.txt", ids.pathFor(sibling))
    }

    @Test
    fun aMoveOntoAnExistingPathRetiresWhatWasThere() {
        val ids = FileProviderIds()
        val moved = ids.idFor("/new.txt")
        val overwritten = ids.idFor("/old.txt")
        ids.moved("/new.txt", "/old.txt")
        assertEquals("/old.txt", ids.pathFor(moved))
        assertNull(ids.pathFor(overwritten))
    }

    @Test
    fun removingAFolderDropsItsDescendants() {
        val ids = FileProviderIds()
        val folder = ids.idFor("/docs")
        val child = ids.idFor("/docs/a.txt")
        ids.removed("/docs")
        assertNull(ids.pathFor(folder))
        assertNull(ids.pathFor(child))
    }

    @Test
    fun retainOnlyReportsWhatItDropped() {
        val ids = FileProviderIds()
        val kept = ids.idFor("/kept.txt")
        val gone = ids.idFor("/gone.txt")
        assertEquals(setOf(gone), ids.retainOnly(setOf("/kept.txt")))
        assertEquals("/kept.txt", ids.pathFor(kept))
        assertNull(ids.pathFor(gone))
    }

    @Test
    fun aDeferredMoveKeepsItsIdentifierOnceItIsApplied() {
        val ids = FileProviderIds()
        val id = ids.idFor("/a.txt")
        ids.deferMove("/a.txt", "/b.txt", fingerprint = "the-file")

        // Not applied yet: the item is still where it was, and so is the identifier.
        ids.settleDeferredMoves(mapOf("/a.txt" to "the-file"))
        assertEquals("/a.txt", ids.pathFor(id))

        // Replayed: the source is gone and the destination holds the same item.
        ids.settleDeferredMoves(mapOf("/b.txt" to "the-file"))
        assertEquals("/b.txt", ids.pathFor(id))
        assertEquals(id, ids.idFor("/b.txt"))

        // Settled once. A later, unrelated disappearance of /b.txt is not that move again.
        ids.settleDeferredMoves(emptyMap())
        assertEquals("/b.txt", ids.pathFor(id))
    }

    @Test
    fun aStrangerAtTheDestinationIsNotTheReplayedMove() {
        // The source was deleted by someone else and an unrelated file now sits at the
        // destination. By path existence alone this is indistinguishable from the replay.
        val ids = FileProviderIds()
        val id = ids.idFor("/a.txt")
        ids.deferMove("/a.txt", "/b.txt", fingerprint = "the-file")

        ids.settleDeferredMoves(mapOf("/b.txt" to "a-stranger"))

        assertEquals("/a.txt", ids.pathFor(id), "the identifier must not follow a stranger")
        assertNotEquals(id, ids.idFor("/b.txt"))
        // …and the deferral is dropped, so a later file with the right fingerprint at /b.txt is
        // not adopted either: that move never happened.
        ids.settleDeferredMoves(mapOf("/b.txt" to "the-file"))
        assertEquals("/a.txt", ids.pathFor(id))
    }

    @Test
    fun anItemWithNoFingerprintAtTheDestinationIsNeverAdopted() {
        // Legacy folders carry nothing that tells one from another; "present, fingerprint
        // unknown" must not read as "the same item", nor as "the source is gone".
        val ids = FileProviderIds()
        val id = ids.idFor("/a")
        ids.deferMove("/a", "/b", fingerprint = "the-folder")

        ids.settleDeferredMoves(mapOf("/a" to null))
        assertEquals("/a", ids.pathFor(id), "a present source with no fingerprint is still present")

        ids.settleDeferredMoves(mapOf("/b" to null))
        assertEquals("/a", ids.pathFor(id))
        assertNotEquals(id, ids.idFor("/b"))
    }

    @Test
    fun aMoveIntentSurvivesARestart_andIsSettledAfterIt() {
        // Control died after the move committed and before the re-point was journalled.
        val first = FileProviderIds(journal)
        val id = first.idFor("/a.txt")
        first.deferMove("/a.txt", "/b.txt", fingerprint = "the-file")

        val second = FileProviderIds(journal)
        assertEquals("/a.txt", second.pathFor(id), "nothing is re-pointed on the intent alone")
        second.settleDeferredMoves(mapOf("/b.txt" to "the-file"))
        assertEquals("/b.txt", second.pathFor(id))

        // Settled is settled: a third start does not see the intent again.
        val third = FileProviderIds(journal)
        assertEquals("/b.txt", third.pathFor(id))
        third.settleDeferredMoves(mapOf("/a.txt" to "the-file"))
        assertEquals("/b.txt", third.pathFor(id))
    }

    @Test
    fun aCrashBetweenTheRepointAndTheIntentClosingIsHarmless() {
        // What an append-only journal holds if Control dies mid-settle: the id is already at
        // the destination and the intent is still open.
        val first = FileProviderIds(journal)
        val id = first.idFor("/a.txt")
        first.deferMove("/a.txt", "/b.txt", fingerprint = "the-file")
        first.moved("/a.txt", "/b.txt")
        journal.writeText(journal.readLines().filterNot { it.startsWith("<") }.joinToString("") { "$it\n" })

        val second = FileProviderIds(journal)
        assertEquals("/b.txt", second.pathFor(id))
        // Settling the still-open intent must not retire the identifier it already moved.
        second.settleDeferredMoves(mapOf("/b.txt" to "the-file"))
        assertEquals("/b.txt", second.pathFor(id))
        assertEquals(id, second.idFor("/b.txt"))
    }

    @Test
    fun aCancelledOrCompletedIntentIsNotReplayedAfterARestart() {
        val first = FileProviderIds(journal)
        val failed = first.idFor("/failed.txt")
        val done = first.idFor("/done.txt")
        first.deferMove("/failed.txt", "/x.txt", fingerprint = "f")
        first.cancelMove("/failed.txt")
        first.deferMove("/done.txt", "/y.txt", fingerprint = "d")
        first.moved("/done.txt", "/y.txt")

        val second = FileProviderIds(journal)
        // Were either intent still open, these states would re-point the identifiers.
        second.settleDeferredMoves(mapOf("/x.txt" to "f", "/z.txt" to "d"))
        assertEquals("/failed.txt", second.pathFor(failed))
        assertEquals("/y.txt", second.pathFor(done))
    }

    @Test
    fun aSettledDeferredMoveSurvivesThePruneThatFollows() {
        // The order refreshFeed relies on: settle, THEN prune against the live paths.
        val ids = FileProviderIds()
        val id = ids.idFor("/docs/a.txt")
        ids.deferMove("/docs/a.txt", "/docs/b.txt", fingerprint = "the-file")
        val live = mapOf("/docs" to "folder", "/docs/b.txt" to "the-file")

        ids.settleDeferredMoves(live)
        assertEquals(emptySet(), ids.retainOnly(live.keys))
        assertEquals("/docs/b.txt", ids.pathFor(id))
    }

    @Test
    fun identifiersSurviveARestart_includingMovesRemovalsAndAwkwardNames() {
        val first = FileProviderIds(journal)
        val moved = first.idFor("/a.txt")
        val removed = first.idFor("/b.txt")
        val awkward = first.idFor("/with\ttab and\nnewline.txt")
        first.moved("/a.txt", "/renamed.txt")
        first.removed("/b.txt")

        val second = FileProviderIds(journal)
        assertEquals("/renamed.txt", second.pathFor(moved))
        assertNull(second.pathFor(removed))
        assertEquals("/with\ttab and\nnewline.txt", second.pathFor(awkward))
        assertEquals(moved, second.idFor("/renamed.txt"))
    }

    @Test
    fun aJournalCutShortMidRecordStillLoads() {
        val first = FileProviderIds(journal)
        val intact = first.idFor("/intact.txt")
        // A crash mid-append: half a percent-escape, no newline. Then plain garbage.
        journal.appendText("11111111-1111-1111-1111-111111111111\t%2Fcut%2")
        journal.appendText("\nnot a record at all\n\t\n")

        val second = FileProviderIds(journal)
        assertEquals("/intact.txt", second.pathFor(intact))
        assertNull(second.pathFor("11111111-1111-1111-1111-111111111111"))
        // …and it is still writable afterwards.
        val added = second.idFor("/later.txt")
        assertEquals("/later.txt", FileProviderIds(journal).pathFor(added))
    }

    @Test
    fun anUnterminatedFinalRecordIsDropped_evenWhenItParses() {
        val first = FileProviderIds(journal)
        val folder = first.idFor("/docs")
        // The crash landed mid-path: what is on disk is a VALID record for the parent folder.
        val torn = "22222222-2222-2222-2222-222222222222"
        journal.appendText("$torn\t%2Fdocs")

        val second = FileProviderIds(journal)
        assertNull(second.pathFor(torn), "a torn record must not resolve to a prefix of its path")
        assertEquals("/docs", second.pathFor(folder))

        // The fragment is gone from disk, so the next record is not glued onto it.
        val next = second.idFor("/next.txt")
        val third = FileProviderIds(journal)
        assertEquals("/next.txt", third.pathFor(next))
        assertEquals("/docs", third.pathFor(folder))
        assertNull(third.pathFor(torn))
    }

    @Test
    fun aJournalThatIsMostlyHistoryIsRewritten_withoutLosingAnything() {
        val first = FileProviderIds(journal)
        val id = first.idFor("/f0")
        repeat(200) { first.moved("/f$it", "/f${it + 1}") }
        val before = journal.readLines().size

        val second = FileProviderIds(journal)
        assertEquals("/f200", second.pathFor(id))
        assert(journal.readLines().size < before) { "journal was not compacted" }
        assertEquals("/f200", FileProviderIds(journal).pathFor(id))
    }
}
