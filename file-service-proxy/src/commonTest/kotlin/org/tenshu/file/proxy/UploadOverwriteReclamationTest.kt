package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.util.toTString
import org.tenshu.core.file.File
import org.tenshu.core.file.Data
import org.tenshu.core.file.ContentId
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * An **overwrite** reassigns `File.ContentId`, so the
 * previous content loses its last reference with no delete (`content-plane-refcount.md` §3.2).
 *
 * ⚠ The superseded content is freed by the relay's own sweep once the row's push drops its edge,
 * so what is pinned here is
 * when an overwrite mints a new id (and so supersedes one) and when it keeps the old one.
 */
class UploadOverwriteReclamationTest {

    private suspend fun org.tenshu.core.common.TestProxy.upload(path: String, bytes: ByteArray) =
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to path.toTString(),
                UploadFile.Params.DATA to bytes.toTByteArray(tContext),
            ),
        )

    private suspend fun org.tenshu.core.common.TestProxy.replace(path: String, bytes: ByteArray) =
        executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                ReplaceFile.Params.PARENT_PATH to "/".toTString(),
                ReplaceFile.Params.FILE_NAME to path.toTString(),
                ReplaceFile.Params.DATA to bytes.toTByteArray(tContext),
            ),
        )

    @Test
    fun replacingAFilesBytesMintsANewContentId() = runTest {
        val proxy = createFileProxy()

        proxy.upload("note.txt", "first".encodeToByteArray())
        val first = proxy.executionContext.database
            .getData(fileId(proxy, "/note.txt"))
            ?.let { contentIdOf(proxy, it) }
        assertTrue(!first.isNullOrBlank(), "the upload must have stamped a contentId")

        proxy.replace("note.txt", "second".encodeToByteArray())
        val second = proxy.executionContext.database
            .getData(fileId(proxy, "/note.txt"))
            ?.let { contentIdOf(proxy, it) }

        assertTrue(!second.isNullOrBlank() && second != first, "new bytes are new content: $first -> $second")
    }

    @Test
    fun replacingWithIdenticalBytesIsANoOp() = runTest {
        // ⚠⚠ Identical bytes must not mint a NEW random contentId: a fresh CEK and new blobs would
        // make "save with no changes" re-upload the whole file and supersede its previous copy.
        // An overwrite compares the new bytes' whole-file hash with `File.ContentHash` and
        // returns before minting, so the file keeps its contentId and nothing is orphaned.
        val proxy = createFileProxy()

        proxy.upload("same.txt", "unchanged".encodeToByteArray())
        val before = proxy.executionContext.database.getData(fileId(proxy, "/same.txt"))?.let { contentIdOf(proxy, it) }
        proxy.replace("same.txt", "unchanged".encodeToByteArray())
        val after = proxy.executionContext.database.getData(fileId(proxy, "/same.txt"))?.let { contentIdOf(proxy, it) }

        assertTrue(!before.isNullOrBlank(), "the upload must have stamped a contentId")
        assertEquals(before, after, "an identical overwrite must keep the file's contentId")
    }

    @Test
    fun replacingIdenticalBytesTheDeviceDoesNotHoldRewritesThem() = runTest {
        // A row synced from a peer, or evicted here, keeps its ContentHash and ContentId with no bytes
        // on this device. An overwrite from it is how content the relay lost gets re-seeded, so it must
        // not be mistaken for "nothing changed".
        val proxy = createFileProxy()
        val bytes = "evicted".encodeToByteArray()

        proxy.upload("gone.txt", bytes)
        val before = proxy.executionContext.database.getData(fileId(proxy, "/gone.txt"))?.let { contentIdOf(proxy, it) }
        proxy.fileDatabase.deleteFile("/gone.txt")
        proxy.replace("gone.txt", bytes)
        val after = proxy.executionContext.database.getData(fileId(proxy, "/gone.txt"))?.let { contentIdOf(proxy, it) }

        assertTrue(bytes.contentEquals(proxy.fileDatabase.readBytes("/gone.txt")?.get()), "the bytes must be written back")
        assertTrue(before != after, "the absent bytes take the full write, which mints a new contentId")
    }

    @Test
    fun replacingWithBytesTheRowNamesButTheLocalFileLacksWritesThem() = runTest {
        // A peer's overwrite syncs the row (ContentHash of the new bytes) ahead of the bytes, so the
        // local file can still be the previous version at the same length. Replacing with the new bytes
        // must land them: a size match is not a content match.
        val proxy = createFileProxy()
        val current = "AAAA".encodeToByteArray()

        proxy.upload("stale.txt", current)
        proxy.fileDatabase.writeRawByteArray("/stale.txt", "BBBB".encodeToByteArray())
        proxy.replace("stale.txt", current)

        assertTrue(
            current.contentEquals(proxy.fileDatabase.readBytes("/stale.txt")?.get()),
            "same-length stale local bytes must be overwritten, not mistaken for the content the row names",
        )
    }

    /**
     * Records hand-offs and relay checks. [relayHolds] answers `ensureContentOnRelay`; null leaves the
     * interface default, which hands off. [interruptFirst] fails the first hand-off, as a process death
     * or cancellation between saving the row and journalling would.
     */
    private class RecordingContentEmitter(
        private val relayHolds: Boolean? = true,
        private var interruptFirst: Boolean = false,
        /** Thrown from [discardSupersededContent], to see what the upload does with it. */
        private val discardThrows: Throwable? = null,
    ) : FileContentSyncEmitter {
        val handedOff = mutableListOf<String>()
        val relayChecks = mutableListOf<Pair<String, String>>()
        val discarded = mutableListOf<Pair<String, String>>()

        override suspend fun discardSupersededContent(targetEntityId: DataId, superseded: List<Pair<String, String>>) {
            discardThrows?.let { throw it }
            discarded += superseded
        }

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            if (interruptFirst) {
                interruptFirst = false
                error("interrupted before the content was journalled")
            }
            handedOff += filePath
            return true
        }

        override suspend fun ensureContentOnRelay(
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
            contentId: String,
        ): Boolean {
            if (relayHolds == null) return super.ensureContentOnRelay(targetEntityId, propertyId, filePath, contentId)
            relayChecks += filePath to contentId
            if (!relayHolds) emitFileContent(targetEntityId, propertyId, filePath)
            return relayHolds
        }

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true
    }

    private fun proxyWith(emitter: RecordingContentEmitter) =
        createFileProxy(
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )

    @Test
    fun anOverwriteNamesEveryIntentItSupersededToTheJournal() = runTest {
        // ⚠⚠ The journal is keyed on content, so the new intents do not
        // replace the old ones, and behind a closed gate nothing else retires them. The overwrite is
        // the only party that still knows the old ids — the row names the new ones by now.
        val emitter = RecordingContentEmitter()
        val proxy = proxyWith(emitter)
        proxy.upload("note.txt", "first".encodeToByteArray())
        val first = proxy.executionContext.database.getData(fileId(proxy, "/note.txt"))?.let { contentIdOf(proxy, it) }!!

        proxy.replace("note.txt", "second".encodeToByteArray())

        assertEquals(
            listOf(first to File.Data.id) + ThumbnailClasses.ALL.map { rung ->
                FileBytesPropertyWriter.derivedThumbnailContentId(first, rung.id) to rung.property.id
            },
            emitter.discarded,
            "the file's own content and every rung derived from it — a new file content is a new id for each",
        )
    }

    @Test
    fun anOverwriteCancelledDuringItsMotionMintStillRetiresWhatItSuperseded() = runTest {
        // ⚠ The cleanup runs before the thumbnail work: the motion mint rethrows cancellation, so a
        // background overwrite cancelled there would otherwise leave the old intents queued behind a
        // closed gate, where nothing else retires them.
        var mints = 0
        val emitter = RecordingContentEmitter()
        val proxy = createFileProxy(
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
            motionThumbnailRenderer = MotionThumbnailRenderer { _, _ ->
                if (++mints > 1) throw kotlinx.coroutines.CancellationException("cancelled mid-mint")
                null
            },
        )
        proxy.upload("clip.mp4", "first".encodeToByteArray())
        val first = proxy.executionContext.database.getData(fileId(proxy, "/clip.mp4"))?.let { contentIdOf(proxy, it) }!!

        val outcome = runCatching { proxy.replace("clip.mp4", "second".encodeToByteArray()) }

        assertTrue(mints > 1, "precondition: the overwrite reached the motion mint and was cancelled there")
        assertTrue(
            outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            "precondition: the op ended in the cancellation, not in success; got $outcome",
        )
        assertTrue(
            (first to File.Data.id) in emitter.discarded,
            "the superseded intent was retired before the cancellable work, not after it",
        )
    }

    @Test
    fun aCancelledDiscardCancelsTheUpload_ratherThanCarryingOnIntoThumbnails() = runTest {
        // ⚠⚠ The discard suspends — it waits on emit-key locks, possibly behind a drain publishing a
        // large file — so a cancelled upload is most likely to BE cancelled there. A bare
        // `runCatching` would catch that and let the op carry on into thumbnail work and bookkeeping for
        // a caller that had gone. Best-effort is about failures; cancellation is not one.
        val emitter = RecordingContentEmitter(discardThrows = kotlinx.coroutines.CancellationException("caller went away"))
        val proxy = proxyWith(emitter)
        proxy.upload("note.txt", "first".encodeToByteArray())

        val outcome = runCatching { proxy.replace("note.txt", "second".encodeToByteArray()) }

        assertTrue(
            outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            "the cancellation reaches the caller; got $outcome",
        )
    }

    @Test
    fun aDiscardThatFailsDoesNotFailAnOverwriteThatAlreadyLanded() = runTest {
        // The other half: the bytes and the row are saved before the discard runs, so failing the op
        // here would have the user retry an overwrite that already happened. The drain retires what
        // the discard could not.
        val emitter = RecordingContentEmitter(discardThrows = IllegalStateException("store unavailable"))
        val proxy = proxyWith(emitter)
        proxy.upload("note.txt", "first".encodeToByteArray())
        val first = proxy.executionContext.database.getData(fileId(proxy, "/note.txt"))?.let { contentIdOf(proxy, it) }

        proxy.replace("note.txt", "second".encodeToByteArray())
        val second = proxy.executionContext.database.getData(fileId(proxy, "/note.txt"))?.let { contentIdOf(proxy, it) }

        assertTrue(second != null && second != first, "the overwrite completed: $first -> $second")
    }

    @Test
    fun anUnchangedOverwriteAsksTheRelayForTheContentItKeeps() = runTest {
        val emitter = RecordingContentEmitter(relayHolds = true)
        val proxy = proxyWith(emitter)
        val bytes = "kept".encodeToByteArray()

        proxy.upload("kept.txt", bytes)
        val kept = proxy.executionContext.database.getData(fileId(proxy, "/kept.txt"))?.let { contentIdOf(proxy, it) }
        proxy.replace("kept.txt", bytes)

        assertEquals(listOf("/kept.txt" to kept), emitter.relayChecks, "the no-op must ask about the id it keeps")
        assertEquals(listOf("/kept.txt"), emitter.handedOff, "content the relay holds is not sent again")
    }

    @Test
    fun anUnchangedOverwriteReEmitsUnderTheSameIdWhenTheRelayLostIt() = runTest {
        val emitter = RecordingContentEmitter(relayHolds = false)
        val proxy = proxyWith(emitter)
        val bytes = "lost".encodeToByteArray()

        proxy.upload("lost.txt", bytes)
        val kept = proxy.executionContext.database.getData(fileId(proxy, "/lost.txt"))?.let { contentIdOf(proxy, it) }
        proxy.replace("lost.txt", bytes)
        val after = proxy.executionContext.database.getData(fileId(proxy, "/lost.txt"))?.let { contentIdOf(proxy, it) }

        assertEquals(listOf("/lost.txt", "/lost.txt"), emitter.handedOff, "a relay without the content gets it again")
        assertEquals(kept, after, "the repair keeps the contentId every peer row already names")
    }

    @Test
    fun anEmitterThatCannotAskTheRelayHandsTheContentOff() = runTest {
        // The interface default: an emitter with no way to ask must never skip.
        val emitter = RecordingContentEmitter(relayHolds = null)
        val proxy = proxyWith(emitter)
        val bytes = "unknown".encodeToByteArray()

        proxy.upload("unknown.txt", bytes)
        proxy.replace("unknown.txt", bytes)

        assertEquals(listOf("/unknown.txt", "/unknown.txt"), emitter.handedOff)
    }

    @Test
    fun anOverwriteRetriedAfterAnInterruptedWriteHandsTheContentOff() = runTest {
        // The row and the local bytes are both saved before `File.Data` is handed to the emitter. If
        // the write dies in between, they match with nothing uploaded, and retrying the same bytes
        // must finish the hand-off rather than read as "unchanged".
        val emitter = RecordingContentEmitter(relayHolds = true, interruptFirst = true)
        val proxy = proxyWith(emitter)
        val bytes = "half-written".encodeToByteArray()

        assertFails { proxy.upload("half.txt", bytes) }
        val saved = proxy.executionContext.database.getData(fileId(proxy, "/half.txt"))?.let { contentIdOf(proxy, it) }
        assertTrue(!saved.isNullOrBlank(), "precondition: the interrupted write saved its row")

        proxy.replace("half.txt", bytes)
        assertEquals(listOf("/half.txt"), emitter.handedOff, "the retry must hand the content off")
        assertTrue(emitter.relayChecks.isEmpty(), "an unsent write takes the full write, not the relay check")

        proxy.replace("half.txt", bytes)
        assertEquals(listOf("/half.txt"), emitter.handedOff, "once handed off, the same bytes are a no-op again")
        assertEquals(1, emitter.relayChecks.size)
    }

    @Test
    fun theLocalComparisonCoversEveryWindowOfALargeFile() = runTest {
        val proxy = createFileProxy()
        val size = UploadFileImplementation.COMPARE_CHUNK_BYTES * 3 / 2
        val bytes = ByteArray(size) { (it % 251).toByte() }

        proxy.upload("big.bin", bytes)
        val first = proxy.executionContext.database.getData(fileId(proxy, "/big.bin"))?.let { contentIdOf(proxy, it) }
        proxy.replace("big.bin", bytes.copyOf())
        val unchanged = proxy.executionContext.database.getData(fileId(proxy, "/big.bin"))?.let { contentIdOf(proxy, it) }
        assertEquals(first, unchanged, "identical bytes spanning two windows are a no-op")

        // Stale local copy that differs only in its last byte, i.e. in the second window.
        proxy.fileDatabase.writeRawByteArray("/big.bin", bytes.copyOf().also { it[size - 1] = (it[size - 1] + 1).toByte() })
        proxy.replace("big.bin", bytes.copyOf())

        assertTrue(bytes.contentEquals(proxy.fileDatabase.readBytes("/big.bin")?.get()), "a difference past the first window must be written")
        val rewritten = proxy.executionContext.database.getData(fileId(proxy, "/big.bin"))?.let { contentIdOf(proxy, it) }
        assertTrue(rewritten != first, "the rewrite takes the full write, which mints a new contentId")
    }

    private fun fileId(proxy: org.tenshu.core.common.TestProxy, path: String) =
        org.tenshu.core.entity.DataId.StringDataId.create(
            context = proxy.tContext,
            type = File.ID,
            keyValue = path,
        )

    private fun contentIdOf(proxy: org.tenshu.core.common.TestProxy, data: Data): String? =
        (
            proxy.executionContext.resolveProperty(
                data,
                propertyRef(File.ContentId.id),
            ) as? TString
            )?.get()
}
