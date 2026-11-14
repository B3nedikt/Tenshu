package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable face template store (IN-6 F2).
 *
 * ⚠⚠ The half that matters is **survival**: a store that forgot on restart would re-embed every face
 * in the library on every launch, which for faces is a detector pass plus an embed per face — worse
 * than the image case `DefaultEmbeddingStoreTest` makes the same argument about.
 */
class DefaultFaceTemplateStoreTest {

    private val files = MapFileDatabase()
    private val path = "/inference/faces.log"
    private val model = ModelProvenance("facenet", 1)
    private val otherModel = ModelProvenance("facenet", 2)

    private fun store() = DefaultFaceTemplateStore(files, path)

    private fun face(vararg values: Float, box: FaceBox = FaceBox(0.1f, 0.2f, 0.3f, 0.4f, 0.9f)) =
        StoredFace(box, floatArrayOf(*values))

    @Test
    fun templatesSurviveAReopen() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)
            put("/b.jpg", listOf(face(0.5f, 0.5f)), model)
        }

        val reopened = store()

        assertEquals(3, reopened.size())
        assertEquals(model, reopened.provenance())
        assertEquals(2, reopened.facesOf("/a.jpg").size)
        assertEquals(listOf(0.5f, 0.5f), reopened.facesOf("/b.jpg").single().vector.toList())
    }

    /** ⚠ The box travels with the vector — the UI draws it, and a crop is bounded by it. */
    @Test
    fun theBoxSurvivesAReopen() = runTest {
        val box = FaceBox(left = 0.11f, top = 0.22f, right = 0.33f, bottom = 0.44f, confidence = 0.87f)
        store().put("/a.jpg", listOf(face(1f, 0f, box = box)), model)

        assertEquals(box, store().facesOf("/a.jpg").single().box)
    }

    /**
     * ⚠⚠ The majority case. A photograph with no faces must come back as *looked at*, or every pass
     * re-reads the whole library for ever.
     */
    @Test
    fun aPhotographWithNoFacesSurvivesAsAVisit() = runTest {
        store().put("/landscape.jpg", emptyList(), model)

        val reopened = store()

        assertTrue(reopened.visited("/landscape.jpg"))
        assertEquals(emptyList(), reopened.facesOf("/landscape.jpg"))
        assertEquals(0, reopened.size())
    }

    @Test
    fun theLastWriteWinsAcrossAReopen() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/a.jpg", listOf(face(0f, 1f), face(1f, 1f)), model)
        }

        val reopened = store()

        assertEquals(2, reopened.size(), "a log replays in order, and the later record wins")
        assertEquals(listOf(0f, 1f), reopened.facesOf("/a.jpg").first().vector.toList())
    }

    @Test
    fun aRemovalSurvivesAReopen() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/b.jpg", listOf(face(0f, 1f)), model)
            remove("/a.jpg")
        }

        val reopened = store()

        assertFalse(reopened.visited("/a.jpg"), "without a tombstone the face would come back")
        assertTrue(reopened.visited("/b.jpg"))
    }

    /**
     * ⚠⚠ **An index pass is hours long and is routinely killed mid-write.** Parsing stops at the
     * first record that does not fit and everything before it stands; refusing to open would turn
     * one interrupted pass into a store that never opens again.
     */
    @Test
    fun aTornTailIsDiscardedAndTheRestSurvives() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/b.jpg", listOf(face(0f, 1f)), model)
        }
        files.truncateBy(6)

        val reopened = store()

        assertTrue(reopened.visited("/a.jpg"), "the complete record before the tear must survive")
        assertFalse(reopened.visited("/b.jpg"), "and the half-written one must not")
        assertEquals(model, reopened.provenance())
    }

    /** ⚠ And a store with a torn tail is still WRITABLE — the next pass appends over the stump. */
    @Test
    fun aTornStoreCanBeWrittenAgain() = runTest {
        store().put("/a.jpg", listOf(face(1f, 0f)), model)
        files.truncateBy(4)

        val recovered = store()
        recovered.put("/c.jpg", listOf(face(1f, 1f)), model)

        assertTrue(store().visited("/c.jpg"), "the record written after the tear must replay")
    }

    @Test
    fun aForeignStampIsRefusedAcrossAReopen() = runTest {
        store().put("/a.jpg", listOf(face(1f, 0f)), model)

        assertFalse(store().put("/b.jpg", listOf(face(0f, 1f)), otherModel))
        assertFalse(store().visited("/b.jpg"))
    }

    /**
     * ⚠⚠ A clear that only empties the maps is no clear at all: the log IS the store, so the next
     * process start replays it. For a **biometric** store outliving a realm reset, that is the
     * cross-realm answer §34.6 exists to prevent, one level worse.
     */
    @Test
    fun aClearedStoreStaysClearedAcrossAReopen() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/empty.jpg", emptyList(), model)
            clear()
        }

        val reopened = store()

        assertEquals(0, reopened.size())
        assertNull(reopened.provenance())
        assertFalse(reopened.visited("/a.jpg"))
        assertFalse(reopened.visited("/empty.jpg"))
        assertNull(files.sizeOf(path), "the file itself must go — a header alone still owns the store")
    }

    @Test
    fun aClearedStoreCanBeWrittenAndReadBack() = runTest {
        val cleared = store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            clear()
            put("/new.jpg", listOf(face(1f, 1f, 1f)), otherModel)
        }

        assertEquals(1, cleared.size())
        assertEquals(otherModel, store().provenance())
        assertEquals(3, store().facesOf("/new.jpg").single().vector.size)
    }

    /**
     * ⚠⚠ A face log and an embedding log must never be read as each other — and the two headers are
     * the SAME SHAPE (an 8-byte magic, a 2-byte stamp length, the stamp), so a store that skipped
     * the magic check would parse a foreign log's header perfectly and **adopt its model stamp**.
     *
     * ⚠ That consequence is what this asserts, not the symptom: an earlier version checked only
     * that no faces were read, which is true either way because the record bodies differ and the
     * parse gives up. Adopting the stamp is the part that then refuses every write from the real
     * face model, and it is invisible until someone wonders why nothing indexes.
     */
    @Test
    fun anEmbeddingLogIsNotReadAsAFaceLog() = runTest {
        DefaultEmbeddingStore(files, path).put("/a.jpg", floatArrayOf(1f, 0f), model)

        val asFaces = store()

        assertEquals(0, asFaces.size())
        assertTrue(
            asFaces.put("/b.jpg", listOf(face(1f, 1f, 1f)), otherModel),
            "a store that adopted the foreign log's stamp would refuse this write",
        )
        assertEquals(otherModel, store().provenance())
    }

    // ---- IN-6 F6c name suggestions: the rejections leave by every exit the templates do -------

    private fun no(path: String, name: String = "Anna") = FaceRejection(path, name, 100, 200, 300, 400)

    @Test
    fun rejectionsSurviveAReopen() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            assertEquals(1, reject(listOf(no("/a.jpg"))))
            // ⚠ A repeat "No" folds.
            assertEquals(0, reject(listOf(no("/a.jpg"))))
        }
        assertEquals(listOf(no("/a.jpg")), store().rejections())
    }

    @Test
    fun aClearDeletesTheRejectionsFileToo() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            reject(listOf(no("/a.jpg")))
            clear()
        }
        // ⚠⚠ Every caller of `clear` is deleting face data — a wipe, a realm change, a withdrawal.
        assertNull(files.sizeOf(FaceRejections.pathBeside(path)), "the rejections file must be gone")
        assertTrue(store().rejections().isEmpty())
    }

    @Test
    fun aForgottenPhotographTakesItsRejections() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/b.jpg", listOf(face(1f, 0f)), model)
            reject(listOf(no("/a.jpg"), no("/b.jpg")))
            forget(listOf("/a.jpg"))
            remove("/b.jpg")
        }
        assertTrue(store().rejections().isEmpty())
    }

    @Test
    fun rejectionsAreBoundedNewestKept() = runTest {
        val rejections = FaceRejections(files, "/r")
        rejections.add((0 until FaceRejections.MAX_RECORDS + 5).map { no("/p$it.jpg") })
        val held = rejections.all()
        assertEquals(FaceRejections.MAX_RECORDS, held.size)
        assertEquals(no("/p${FaceRejections.MAX_RECORDS + 4}.jpg"), held.last())
    }

    @Test
    fun aRejectionIsHeldOnlyBesideATemplate() = runTest {
        // ⚠⚠ The custody argument: a rejection must never exist where erasing the templates — the
        // only thing withdrawal and the reconcile look for — would not find it.
        val empty = store()
        assertEquals(0, empty.reject(listOf(no("/a.jpg"))), "no stamp: nothing held, nothing recorded")
        empty.put("/a.jpg", listOf(face(1f, 0f)), model)
        assertEquals(1, empty.reject(listOf(no("/a.jpg"), no("/never-indexed.jpg"))))
        assertEquals(listOf(no("/a.jpg")), store().rejections())
    }

    @Test
    fun aClearThatDiesAfterTheRejectionsLeavesTheStampForTheNextWithdrawal() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            reject(listOf(no("/a.jpg")))
        }
        files.failWritesTo = path // the template wipe dies after the rejections went
        runCatching { store().clear() }
        files.failWritesTo = null

        assertNull(files.sizeOf(FaceRejections.pathBeside(path)), "the names and boxes are gone first")
        // ⚠⚠ And the store still says it holds something, so a withdrawal's `provenance() != null`
        // asks again and the templates go too.
        val reopened = store()
        assertEquals(model, reopened.provenance())
        reopened.clear()
        assertNull(reopened.provenance())
    }

    @Test
    fun aRejectionEraseThatFailsLeavesEverythingForTheRetry() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            reject(listOf(no("/a.jpg")))
        }
        files.failWritesTo = FaceRejections.pathBeside(path)
        val dying = store()
        assertTrue(runCatching { dying.clear() }.isFailure)
        files.failWritesTo = null

        // The templates were not touched, so the stamp still asks for the next withdrawal.
        assertEquals(model, store().provenance())
        store().clear()
        assertNull(files.sizeOf(FaceRejections.pathBeside(path)))
    }

    @Test
    fun aForgetWhoseRejectionsFailKeepsTheTemplateSoTheReconcileComesBack() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            reject(listOf(no("/a.jpg")))
        }
        files.failWritesTo = FaceRejections.pathBeside(path)
        val removal = store().forget(listOf("/a.jpg"))
        files.failWritesTo = null

        assertTrue(!removal.complete)
        // ⚠⚠ The dead path is still REMEMBERED — which is what the next pass's reconcile walks.
        assertTrue("/a.jpg" in store().remembered())
        store().forget(listOf("/a.jpg"))
        assertTrue(store().rejections().isEmpty())
        assertTrue("/a.jpg" !in store().remembered())
    }

    @Test
    fun anUnreadableRejectionsFileIsAnErrorAndIsNeverWrittenOver() = runTest {
        store().put("/a.jpg", listOf(face(1f, 0f)), model)
        files.writeRawByteArray(FaceRejections.pathBeside(path), "not json".encodeToByteArray())
        val store = store()

        // ⚠⚠ Not an empty list: that would fold and ask about every answered No again.
        assertTrue(runCatching { store.rejections() }.exceptionOrNull() is RejectionsUnreadable)
        assertTrue(runCatching { store.reject(listOf(no("/a.jpg"))) }.isFailure)
        assertEquals("not json", files.read(FaceRejections.pathBeside(path)), "never written over")
        // ⚠ Still an error on the next ask — a failed read is not cached as "nothing".
        assertTrue(runCatching { store.rejections() }.isFailure)
        // A deleted photograph's answers cannot be picked out of it, so the file goes whole.
        store.forget(listOf("/a.jpg"))
        assertNull(files.sizeOf(FaceRejections.pathBeside(path)))
    }

    private class MapFileDatabase : FileDatabase {
        private val files = mutableMapOf<String, ByteArray>()

        fun sizeOf(filePath: String): Long? = files[filePath]?.size?.toLong()

        fun truncateBy(bytes: Int) {
            files.keys.forEach { key ->
                val current = files.getValue(key)
                files[key] = current.copyOf(maxOf(0, current.size - bytes))
            }
        }

        /** Writes to this path throw — a disk that refuses one file mid-operation. */
        var failWritesTo: String? = null

        fun read(filePath: String): String? = files[filePath]?.decodeToString()

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (filePath == failWritesTo) error("refused: $filePath")
            files[filePath] = bytes
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) {
            files[filePath] = bytes.get()
        }

        override fun readBytes(filePath: String): TByteArray? = files[filePath]?.let { TByteArrayDto(it) }

        override fun deleteFile(filePath: String) {
            files.remove(filePath)
        }
    }

    /**
     * ⚠⚠ **A batch whose vectors disagree with each other must be refused BEFORE encoding.**
     * The width check reads the widths already in the store, so on the very first non-empty write
     * there is nothing to compare against and a mixed batch sailed straight through. `encodePut`
     * then sizes the record from the FIRST face while writing each face's own width: a longer later
     * vector writes past the array and takes the whole index pass down with it, and a shorter one
     * leaves a malformed trailing region the replay reads as garbage.
     */
    @Test
    fun aFirstWriteWhoseVectorsDisagreeIsRefusedRatherThanEncoded() = runTest {
        val store = store()

        val accepted = store.put(
            "/a.jpg",
            listOf(face(1f, 0f, 0f, 0f), face(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)),
            model,
        )

        assertFalse(accepted, "mixed widths in one batch cannot be encoded")
        assertFalse(store.visited("/a.jpg"), "and nothing is recorded against the file")
        assertEquals(0, store.size())
    }

    /** ⚠ The consistent case, so the test above cannot pass by refusing everything. */
    @Test
    fun aFirstWriteWhoseVectorsAgreeIsAccepted() = runTest {
        val store = store()

        val accepted = store.put(
            "/a.jpg",
            listOf(face(1f, 0f, 0f, 0f), face(0f, 1f, 0f, 0f)),
            model,
        )

        assertTrue(accepted)
        assertEquals(2, store.size())
    }

    /**
     * ⚠⚠⚠ **A torn tail must leave the FILE valid, not just this process's view of it.**
     *
     * Recovery moved the logical append offset and stopped there, but `writeBytesRange` overwrites
     * in place without truncating. So the next record — if it is SHORTER than the torn one it lands
     * on — leaves a stale suffix behind it, and the following reopen parses that suffix as a record.
     * Arbitrary path bytes and vector floats can resemble a valid PUT, so the store replays state
     * nobody ever wrote.
     *
     * The fixture makes the second write deliberately shorter than the torn remains.
     */
    @Test
    fun aTornTailIsDroppedFromTheFileSoAShorterRecordCannotResurrectIt() = runTest {
        store().apply {
            put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)
            put("/b-also-fairly-long-name.jpg", listOf(face(0.5f, 0.5f), face(0.25f, 0.75f)), model)
        }
        val whole = files.readBytes(path)!!.get()

        // Cut mid-record: the second PUT is now torn.
        files.writeRawByteArray(path, whole.copyOfRange(0, whole.size - 12))

        val validEnd = MapFileDatabase().also {
            DefaultFaceTemplateStore(it, path)
                .put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)
        }.readBytes(path)!!.get().size

        // Reopening recovers the valid prefix, and must physically blank the torn remains.
        val recovered = store()
        assertTrue(recovered.visited("/a-file-with-a-long-name.jpg"))
        assertFalse(recovered.visited("/b-also-fairly-long-name.jpg"), "the torn record is gone")

        // ⚠⚠ The assertion that actually pins the fix. Recovering only the in-memory offset leaves
        // the torn bytes on disk, and every downstream consequence — a shorter record leaving a
        // stale suffix, that suffix parsing as a phantom PUT — depends on bytes that happen to be
        // there. The torn bytes being ZERO is the direct, always-observable consequence, and a
        // behavioural assertion here passed with the fix removed.
        // ⚠ Zeros, not a shorter file (P0f-a): the tail is blanked in place, because removing it
        // meant rewriting every live template, and a kill inside that rewrite cut the log.
        val afterRecovery = files.readBytes(path)!!.get()
        assertTrue(afterRecovery.size > validEnd, "precondition: there is a tail to look at")
        assertTrue(
            (validEnd until afterRecovery.size).all { afterRecovery[it] == 0.toByte() },
            "the torn tail must be blanked in the file",
        )

        // And the store still works afterwards: the prefix is intact, not merely shorter.
        recovered.put("/c.jpg", listOf(face(1f, 0f)), model)
        val reopened = store()
        assertTrue(reopened.visited("/a-file-with-a-long-name.jpg"))
        assertTrue(reopened.visited("/c.jpg"))
        assertFalse(reopened.visited("/b-also-fairly-long-name.jpg"))
        assertEquals(3, reopened.size(), "two faces from /a and one from /c — no phantoms")
    }

    /**
     * ⚠⚠ **A tear inside a record's HEADER must be truncated too**, and it was the hole left by the
     * first version of the torn-tail fix: those two paths took a bare `break` out of the replay
     * loop, which sets the append offset and leaves the bytes on disk — the very thing `finishAt`
     * exists to prevent, in the branches that fell out of the loop instead of returning from it.
     *
     * Cutting a few bytes past a record's start lands after the flag and inside the path.
     */
    @Test
    fun aTearInsideARecordHeaderIsAlsoDroppedFromTheFile() = runTest {
        store().apply { put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f)), model) }
        val afterFirst = files.readBytes(path)!!.get().size

        store().apply { put("/b-also-fairly-long-name.jpg", listOf(face(0f, 1f)), model) }
        val whole = files.readBytes(path)!!.get()

        // ⚠ BOTH header tears, because they are different branches: `+2` lands after the flag
        // while the 2-byte path length is still incomplete, `+6` lands inside the path itself. A
        // fixture that only cuts in one place leaves the other branch untested — and it did.
        listOf(afterFirst + 2, afterFirst + 6).forEach { cut ->
            files.writeRawByteArray(path, whole.copyOfRange(0, cut))

            val recovered = store()

            assertTrue(recovered.visited("/a-file-with-a-long-name.jpg"), "the intact record stands")
            assertFalse(recovered.visited("/b-also-fairly-long-name.jpg"))
            val after = files.readBytes(path)!!.get()
            assertTrue(
                after.size > afterFirst && (afterFirst until after.size).all { after[it] == 0.toByte() },
                "a header tear at $cut must be blanked in the file",
            )
        }
    }

    /**
     * A file database whose FIRST raw write fails, and which works from then on — a transient I/O
     * error landing exactly on the header write.
     */
    private class FailsFirstRawWrite(private val inner: MapFileDatabase) : FileDatabase {
        var failed = false
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (!failed) {
                failed = true
                throw IllegalStateException("transient I/O failure")
            }
            inner.writeRawByteArray(filePath, bytes)
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) = inner.writeByteArray(filePath, bytes)
        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun deleteFile(filePath: String) = inner.deleteFile(filePath)
    }

    /**
     * ⚠⚠⚠ **A header write that fails must not leave the store claiming a stamp it never wrote.**
     *
     * With the stamp assigned first, the next `put` sees a non-null stamp, skips the header, and
     * appends a record at offset 0 — producing a file with no magic. `load` rejects that outright,
     * so every template and every "already looked at" recorded since is silently gone on the next
     * start. Assigning the stamp only after the write means the retry writes a complete header.
     */
    @Test
    fun aFailedHeaderWriteLeavesNoStampBehind() = runTest {
        val flaky = FailsFirstRawWrite(files)
        val store = DefaultFaceTemplateStore(flaky, path)

        // The first put's header write fails.
        runCatching { store.put("/a.jpg", listOf(face(1f, 0f)), model) }
        assertNull(store.provenance(), "a stamp that was never written must not be remembered")

        // The retry must produce a COMPLETE file — header and record.
        assertTrue(store.put("/a.jpg", listOf(face(1f, 0f)), model))

        val reopened = DefaultFaceTemplateStore(flaky, path)
        assertTrue(reopened.visited("/a.jpg"), "the retry's file must survive a reopen")
        assertEquals(1, reopened.size())
    }

    /** A database whose ranged writes fail — the append path — while everything else works. */
    private class FailsRangedWrites(private val inner: MapFileDatabase) : FileDatabase {
        var failRanged = false
        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
            if (failRanged) throw IllegalStateException("transient I/O failure")
            inner.writeBytesRange(filePath, offset, bytes)
        }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) =
            inner.writeRawByteArray(filePath, bytes)

        override fun writeByteArray(filePath: String, bytes: TByteArray) = inner.writeByteArray(filePath, bytes)
        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun deleteFile(filePath: String) = inner.deleteFile(filePath)
    }

    /** A database whose `deleteFile` silently does nothing — what a real filesystem may do. */
    private class DeleteDoesNothing(private val inner: MapFileDatabase) : FileDatabase {
        override fun deleteFile(filePath: String) = Unit
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) =
            inner.writeRawByteArray(filePath, bytes)

        override fun writeByteArray(filePath: String, bytes: TByteArray) = inner.writeByteArray(filePath, bytes)
        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) =
            inner.writeBytesRange(filePath, offset, bytes)
    }

    /**
     * ⚠⚠⚠ **A removal whose tombstone could not be written must not look removed.**
     *
     * Dropping the row from the map first leaves the log still carrying this file's last `PUT`
     * while memory says it is gone — so a retry is a no-op (the map has nothing to remove) and the
     * next reopen brings the template back. For a biometric store that is a deletion the user was
     * told had happened and had not.
     */
    @Test
    fun aRemovalWhoseTombstoneFailsLeavesTheRowRemovable() = runTest {
        val flaky = FailsRangedWrites(files)
        val store = DefaultFaceTemplateStore(flaky, path)
        store.put("/a.jpg", listOf(face(1f, 0f)), model)

        flaky.failRanged = true
        runCatching { store.remove("/a.jpg") }

        assertTrue(store.visited("/a.jpg"), "the row must still be there to retry")

        flaky.failRanged = false
        store.remove("/a.jpg")

        assertFalse(store.visited("/a.jpg"))
        assertFalse(
            DefaultFaceTemplateStore(flaky, path).visited("/a.jpg"),
            "and the removal is durable, not just in memory",
        )
    }

    /**
     * ⚠⚠⚠ **A wipe must be durable even when `deleteFile` silently does nothing.** It returns
     * `Unit`, is allowed a no-op default, and real filesystem implementations do not report a
     * refusal — so `clear()` could return with the file intact and the next start would replay
     * every template it claimed to remove. That is exactly the "biometric store survives a realm
     * reset" defect this store's own KDoc names.
     */
    @Test
    fun clearingSurvivesAFileDatabaseThatWillNotDelete() = runTest {
        val stubborn = DeleteDoesNothing(files)
        val store = DefaultFaceTemplateStore(stubborn, path)
        store.put("/a.jpg", listOf(face(1f, 0f)), model)
        store.put("/b.jpg", listOf(face(0f, 1f)), model)

        store.clear()

        val reopened = DefaultFaceTemplateStore(stubborn, path)
        assertEquals(0, reopened.size(), "nothing may come back from the file")
        assertFalse(reopened.visited("/a.jpg"))
        assertFalse(reopened.visited("/b.jpg"))
        assertNull(reopened.provenance())
    }

    /** Reads work; every raw write fails — a disk that has gone read-only under us. */
    private class ReadOnlyDisk(private val inner: MapFileDatabase) : FileDatabase {
        override fun writeRawByteArray(filePath: String, bytes: ByteArray): Unit =
            throw IllegalStateException("read-only")

        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray): Unit =
            throw IllegalStateException("read-only")

        override fun writeByteArray(filePath: String, bytes: TByteArray): Unit =
            throw IllegalStateException("read-only")

        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun deleteFile(filePath: String) = Unit
    }

    /**
     * ⚠⚠ **Truncating a torn tail must not turn a readable store into an unopenable one.**
     *
     * `load` used to only read, so it could not throw; adding the truncation made it a writer, and
     * it runs from `init`. On a disk that has gone read-only that would fail the CONSTRUCTOR —
     * taking a store that previously opened fine with a torn tail and making it unopenable, which
     * is a strictly worse outcome than the defect the truncation prevents. The append offset is
     * already correct without the write, so the truncation is best-effort by design.
     */
    @Test
    fun aTornTailOnAReadOnlyDiskStillOpens() = runTest {
        store().apply {
            put("/a.jpg", listOf(face(1f, 0f)), model)
            put("/b.jpg", listOf(face(0f, 1f)), model)
        }
        val whole = files.readBytes(path)!!.get()
        files.writeRawByteArray(path, whole.copyOfRange(0, whole.size - 12))

        val recovered = DefaultFaceTemplateStore(ReadOnlyDisk(files), path)

        assertTrue(recovered.visited("/a.jpg"), "the valid prefix still loads")
        assertFalse(recovered.visited("/b.jpg"), "the torn record is still discarded in memory")
    }

    /**
     * Reads work, and appends work; what fails until [allowWrites] is set is exactly the write that
     * blanks a torn tail — a ranged write of nothing but zeros.
     */
    private class RawWritesFailUntilAllowed(private val inner: MapFileDatabase) : FileDatabase {
        var allowWrites = false
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (!allowWrites) throw IllegalStateException("read-only")
            inner.writeRawByteArray(filePath, bytes)
        }

        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
            if (!allowWrites && bytes.all { it == 0.toByte() }) throw IllegalStateException("read-only")
            inner.writeBytesRange(filePath, offset, bytes)
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) = inner.writeByteArray(filePath, bytes)
        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun deleteFile(filePath: String) = inner.deleteFile(filePath)
    }

    /**
     * ⚠⚠⚠ **An append may not run over a tail the truncation could not remove.**
     *
     * Making the truncation best-effort stopped an unwritable disk from failing the constructor,
     * but it also let the store carry on appending over stale bytes — and a record shorter than the
     * one it lands on leaves that record's suffix behind, which the next reopen parses. That is the
     * phantom the truncation exists to prevent, arriving by a slower road. So the append retries the
     * truncation first and refuses the write if it still cannot.
     */
    @Test
    fun anAppendOverAnUntruncatedTailIsRefusedUntilTheTailIsGone() = runTest {
        store().apply {
            put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f)), model)
            put("/b-also-fairly-long-name.jpg", listOf(face(0f, 1f)), model)
        }
        val whole = files.readBytes(path)!!.get()
        files.writeRawByteArray(path, whole.copyOfRange(0, whole.size - 12))

        val disk = RawWritesFailUntilAllowed(files)
        val store = DefaultFaceTemplateStore(disk, path)

        // Readable: the constructor did not fail even though the truncation could not be written.
        assertTrue(store.visited("/a-file-with-a-long-name.jpg"))

        // ⚠ But writing is refused while the stale tail is still there.
        assertTrue(
            runCatching { store.put("/c.jpg", listOf(face(1f, 0f)), model) }.isFailure,
            "an append over an untruncated tail must not silently succeed",
        )

        // Once the disk comes back, the append retries the truncation and proceeds.
        disk.allowWrites = true
        assertTrue(store.put("/c.jpg", listOf(face(1f, 0f)), model))

        val reopened = DefaultFaceTemplateStore(files, path)
        assertTrue(reopened.visited("/a-file-with-a-long-name.jpg"))
        assertTrue(reopened.visited("/c.jpg"))
        assertFalse(reopened.visited("/b-also-fairly-long-name.jpg"))
        assertEquals(2, reopened.size(), "no phantom from the stale tail")
    }

    /**
     * A ranged write that persists a PREFIX and then throws — a write torn by the device dying
     * part-way, which is the case the append log exists to survive.
     */
    private class WritesPrefixThenThrows(private val inner: MapFileDatabase) : FileDatabase {
        var tearNext = false
        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
            if (!tearNext) return inner.writeBytesRange(filePath, offset, bytes)
            tearNext = false
            inner.writeBytesRange(filePath, offset, bytes.copyOfRange(0, bytes.size / 2))
            throw IllegalStateException("torn mid-write")
        }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) =
            inner.writeRawByteArray(filePath, bytes)

        override fun writeByteArray(filePath: String, bytes: TByteArray) = inner.writeByteArray(filePath, bytes)
        override fun readBytes(filePath: String): TByteArray? = inner.readBytes(filePath)
        override fun deleteFile(filePath: String) = inner.deleteFile(filePath)
    }

    /**
     * ⚠⚠⚠ **A failed append leaves stale bytes, and the next append must not run over them.**
     *
     * The offset staying put is right — nothing complete landed — but a write that threw may have
     * persisted a prefix. A later, shorter record would then overwrite only part of that prefix and
     * leave a suffix behind, which the next replay parses as a phantom. The tail is quarantined on
     * failure exactly as a torn tail found at load is.
     */
    @Test
    fun aTornAppendQuarantinesTheTailBeforeTheNextWrite() = runTest {
        val disk = WritesPrefixThenThrows(files)
        val store = DefaultFaceTemplateStore(disk, path)
        store.put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)

        disk.tearNext = true
        assertTrue(
            runCatching {
                store.put("/b-also-fairly-long-name.jpg", listOf(face(0.5f, 0.5f), face(0.25f, 0.75f)), model)
            }.isFailure,
            "the torn write must surface, not be swallowed",
        )

        // A SHORTER record than the prefix the torn write left behind.
        store.put("/c.jpg", listOf(face(1f, 0f)), model)

        // ⚠⚠ **The file must carry nothing beyond its last record**, and that is the only assertion
        // that can see this. Reading the store back cannot: the leftover here is five bytes, which
        // the parser rejects as a malformed record and truncates at the next load — so `visited`
        // and `size()` agree whether or not the tail was quarantined, and an earlier version of
        // this test passed with the fix removed. Comparing against a store written cleanly is what
        // makes the stale bytes observable.
        val referenceFiles = MapFileDatabase()
        DefaultFaceTemplateStore(referenceFiles, path).apply {
            put("/a-file-with-a-long-name.jpg", listOf(face(1f, 0f), face(0f, 1f)), model)
            put("/c.jpg", listOf(face(1f, 0f)), model)
        }
        // ⚠ "Nothing" is zeros now, not a shorter file: the tail is blanked in place (P0f-a).
        val clean = referenceFiles.readBytes(path)!!.get()
        val actual = files.readBytes(path)!!.get()
        assertEquals(clean.toList(), actual.copyOf(clean.size).toList(), "the records themselves")
        assertTrue(
            (clean.size until actual.size).all { actual[it] == 0.toByte() },
            "a torn append must leave no stale bytes behind the next record",
        )

        val reopened = DefaultFaceTemplateStore(files, path)
        assertTrue(reopened.visited("/a-file-with-a-long-name.jpg"))
        assertTrue(reopened.visited("/c.jpg"))
        assertFalse(reopened.visited("/b-also-fairly-long-name.jpg"), "the torn record never landed")
        assertEquals(3, reopened.size(), "two faces from /a and one from /c — no phantom from the tear")
    }
}
