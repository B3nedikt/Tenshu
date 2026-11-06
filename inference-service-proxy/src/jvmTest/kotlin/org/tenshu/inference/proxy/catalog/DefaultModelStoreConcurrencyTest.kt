package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.JvmFileDatabase
import org.tenshu.inference.InferenceCapability
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model store's index and sweep (`photos-people-and-pets.md` §12.12) against a **second
 * thread in the same process**.
 *
 * `DefaultModelDownloader` runs one job per model on `Dispatchers.Default`, so two different models
 * can commit at once. A commit declares its new paths as garbage *before* renaming into them and
 * relies on the index to protect them — which only holds if its record is never lost to another
 * writer, and if no sweep can run between its rename and its index write. ⚠ A commit never sweeps,
 * so the only sweep left to race is the first read's (second test). What a second *commit* can
 * still do is overwrite the first one's record (first test), which leaves a completed install
 * declared and unindexed: the next start deletes it.
 *
 * ⚠⚠ These run on **real threads over a real filesystem**, with latches holding one commit open at
 * the exact point the race needs. A single test dispatcher cannot produce the interleaving at all —
 * it runs each commit to completion — so a `runTest` version of this would pass on a store that
 * does not serialise its mutations. Each latch wait is bounded by [WINDOW_MS]: on a store that
 * serialises its mutations the second writer is (correctly) blocked, the window simply expires, and
 * the first commit finishes.
 */
class DefaultModelStoreConcurrencyTest {

    private val root: File = Files.createTempDirectory("model-store-race").toFile()
    private val files = HookedFileDatabase(JvmFileDatabase(root))
    private val storage = HookedStorage()
    private val store = DefaultModelStore(storage, files, modelsDir = "/models", digestOf = { "unused" }, ownership = ModelStoreOwnership.SoleProcess)
    private val role = ModelArtifactRole.VISION

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun entry(modelId: String, sha256: String) = ModelEntryData(
        modelId = modelId,
        name = modelId,
        modelVersion = 1,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "onnx",
        artifactVersion = 1,
        artifacts = listOf(ModelArtifactData(role, "models/$modelId.onnx", sha256, PAYLOAD.size.toLong())),
        licence = "Apache-2.0",
    )

    /** Stages [modelId]'s one artifact exactly as `DefaultModelDownloader` does. */
    private fun stage(modelId: String, sha256: String, through: DefaultModelStore = store) {
        through.beginStaging(modelId, role, sha256)
        through.writeStaged(modelId, role, 0, PAYLOAD)
    }

    private fun exists(path: String?) = path != null && File(root, path).isFile

    @Test
    fun aConcurrentCommitNeverCostsAnotherModelItsInstall() = runBlocking {
        stage("alpha", "aaaa1111")
        stage("beta", "bbbb2222")

        // Hold B open INSIDE its index write: it has renamed, it has computed the index it is about
        // to store, and its new path is declared garbage and protected by nothing but that record.
        val bWriting = CountDownLatch(1)
        val aFinished = CountDownLatch(1)
        val firstWrite = AtomicBoolean(true)
        storage.beforeStoreString = { key, value ->
            if (key == INDEX_KEY && "beta" in value && firstWrite.compareAndSet(true, false)) {
                bWriting.countDown()
                aFinished.await(WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }

        val b = async(Dispatchers.Default) { store.commitStaged(entry("beta", "bbbb2222"), emptyMap()) }
        assertTrue(bWriting.await(10, TimeUnit.SECONDS), "B never reached its index write")
        val a = async(Dispatchers.Default) {
            store.commitStaged(entry("alpha", "aaaa1111"), emptyMap()).also { aFinished.countDown() }
        }
        val bRecord = b.await()
        val aRecord = a.await()

        // ⚠⚠ The defect: A committed whole inside B's window, and B then stored the index it had
        // computed before A existed. A's record is gone while A's path is still declared garbage.
        assertEquals(setOf("alpha", "beta"), store.installed().map { it.modelId }.toSet())
        assertTrue(exists(bRecord.pathOf(role)), "B's committed artifact is missing")
        assertTrue(exists(aRecord.pathOf(role)), "A's committed artifact is missing")
        // And through a fresh store over the same backing stores, i.e. the next process — whose
        // first read is the sweep, so this is where a lost record costs its bytes.
        val reopened = DefaultModelStore(storage, files, modelsDir = "/models", digestOf = { "unused" }, ownership = ModelStoreOwnership.SoleProcess)
        assertEquals(setOf("alpha", "beta"), reopened.installed().map { it.modelId }.toSet())
        assertTrue(exists(aRecord.pathOf(role)), "A's install was swept by the next process")
        assertTrue(exists(bRecord.pathOf(role)), "B's install was swept by the next process")
    }

    @Test
    fun theFirstReadsRecoverySweepNeverRunsInsideACommit() = runBlocking {
        // ⚠ Staged by an earlier process. `beginStaging` reads the index (an index that cannot be
        // read refuses a download before its fetch), so a process that staged has had its first
        // read already, and the downloader cannot open this window. A commit of bytes
        // staged before this process began still can, and the lock is what closes it.
        stage(
            "beta",
            "bbbb2222",
            through = DefaultModelStore(storage, files, modelsDir = "/models", digestOf = { "unused" }, ownership = ModelStoreOwnership.SoleProcess),
        )

        // X is the process's FIRST index read (a `pathOf` from a backend, say), held after it read
        // the stored index but before it caches it and runs the once-per-process recovery sweep.
        val xReading = CountDownLatch(1)
        val bRenamed = CountDownLatch(1)
        val xFinished = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        storage.afterGetString = { key ->
            if (key == INDEX_KEY && firstRead.compareAndSet(true, false)) {
                xReading.countDown()
                bRenamed.await(WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }
        files.afterMove = { to ->
            if ("/beta." in to) {
                bRenamed.countDown()
                xFinished.await(WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }

        val x = async(Dispatchers.Default) { store.installed().also { xFinished.countDown() } }
        assertTrue(xReading.await(10, TimeUnit.SECONDS), "X never reached its index read")
        val b = async(Dispatchers.Default) { store.commitStaged(entry("beta", "bbbb2222"), emptyMap()) }
        x.await()
        val bRecord = b.await()

        assertTrue(exists(bRecord.pathOf(role)), "B's committed artifact was swept by the first read's recovery")
        assertEquals(listOf("beta"), store.installed().map { it.modelId })
        assertEquals(bRecord.pathOf(role), store.pathOf("beta", role))
    }

    /** A real filesystem with a hook after each rename. */
    private class HookedFileDatabase(private val inner: FileDatabase) : FileDatabase by inner {
        @Volatile var afterMove: ((String) -> Unit)? = null
        override fun moveFile(fromPath: String, toPath: String) {
            inner.moveFile(fromPath, toPath)
            afterMove?.invoke(toPath)
        }
    }

    /** A thread-safe map with a hook after each string read and before each string write. */
    private class HookedStorage : SecureStorageDelegate {
        private val map = ConcurrentHashMap<String, String>()
        @Volatile var afterGetString: ((String) -> Unit)? = null
        @Volatile var beforeStoreString: ((String, String) -> Unit)? = null
        override fun storeSecret(key: String, value: String) { map[key] = value }
        override fun getSecret(key: String): String? = map[key]
        override fun storeString(key: String, value: String) {
            beforeStoreString?.invoke(key, value)
            map[key] = value
        }
        override fun getString(key: String): String? = map[key].also { afterGetString?.invoke(key) }
        override fun remove(key: String) { map.remove(key) }
    }

    private companion object {
        /** How long a held writer waits for the other; the whole race fits in a few milliseconds. */
        const val WINDOW_MS = 1_000L
        const val INDEX_KEY = "inference.models.index"
        val PAYLOAD = "WEIGHTS".encodeToByteArray()
    }
}
