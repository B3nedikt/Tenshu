package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.JvmFileDatabase
import org.tenshu.inference.InferenceCapability
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model store's one-writer rule across **processes** (`photos-people-and-pets.md` §12.12):
 * a desktop Control that slips past `ControlInstanceGuard` (a pre-guard build) can open the same
 * data directory.
 *
 * Each "process" here is a [DefaultModelStore] with its own monitor, its own
 * [FileLockModelStoreOwnership] over the one lock file, and its own [ProcessLocalStorage] over the
 * one "properties file" — in one JVM.
 *
 * ⚠⚠ What that model leaves out, so nobody reads more into a green run than it says:
 *  - **The lock is refused by a different mechanism.** Two holders in one JVM make `tryLock` throw
 *    `OverlappingFileLockException`; a real second process makes it return `null`. Both land on
 *    the same refusal, but only the first branch runs here.
 *  - [ProcessLocalStorage] models `DesktopSecureStorageDelegate` as **read from its code**
 *    (properties loaded once in the constructor, the whole map rewritten on each write), not as
 *    observed across two running Controls.
 *  - Nothing here interleaves the two at the instruction level; each step runs to completion.
 */
class DefaultModelStoreTwoProcessTest {

    private val root: File = Files.createTempDirectory("model-store-two-process").toFile()
    private val lockFile = File(root, "models/store.lock")
    private val propertiesFile = mutableMapOf<String, String>()
    private val role = ModelArtifactRole.VISION
    private val held = mutableListOf<FileLockModelStoreOwnership>()

    @AfterTest
    fun cleanUp() {
        held.forEach { it.release() }
        root.deleteRecursively()
    }

    /**
     * A Control process starting: lock first, then the properties file is read, then the store —
     * the order desktop's DI gives it.
     */
    private fun startProcess(): DefaultModelStore {
        val ownership = FileLockModelStoreOwnership(lockFile).also { held += it }
        val storage = ProcessLocalStorage(propertiesFile)
        return DefaultModelStore(storage, JvmFileDatabase(root), "/models", { "unused" }, ownership)
    }

    private fun entry(sha256: String = "aaaa1111") = ModelEntryData(
        modelId = "siglip",
        name = "SigLIP",
        modelVersion = 1,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "onnx",
        artifactVersion = 1,
        artifacts = listOf(ModelArtifactData(role, "models/siglip.onnx", sha256, PAYLOAD.size.toLong())),
        licence = "Apache-2.0",
    )

    private fun DefaultModelStore.install(sha256: String = "aaaa1111"): InstalledModelData {
        beginStaging("siglip", role, sha256)
        writeStaged("siglip", role, 0, PAYLOAD)
        return commitStaged(entry(sha256), emptyMap())
    }

    private fun exists(path: String?) = path != null && File(root, path).isFile

    /**
     * ⚠⚠ The owner's declarations outlive its commit (reclamation runs in the next process), so
     * a second process can read a list naming a path the owner has put back since. Its first index
     * read must not sweep that path from under the owner.
     */
    @Test
    fun aSecondProcessNeverSweepsWhatTheOwnerReinstalled() {
        val owner = startProcess()
        val path = owner.install().pathOf(role)
        owner.remove("siglip")
        assertTrue(exists(path), "the evicted bytes wait for the next process")

        // A second Control starts now: it reads "nothing installed, <path> is garbage".
        val second = startProcess()
        // …and the owner re-installs the same content, at the same content-addressed path.
        assertEquals(path, owner.install().pathOf(role))

        assertEquals(emptyList(), second.installed(), "the second process reads its own stale index")

        assertTrue(exists(path), "the second process swept an artifact the owner's index names")
        assertTrue(exists(owner.pathOf("siglip", role)))
    }

    @Test
    fun aSecondProcessReadsWhatIsInstalledAndIsRefusedEveryWrite() {
        val owner = startProcess()
        val path = owner.install().pathOf(role)

        val second = startProcess()
        assertTrue(held.last().refusal() is ModelStoreRefusal.OwnedElsewhere, "a held lock is contention")

        assertEquals(path, second.pathOf("siglip", role))
        assertEquals(listOf("siglip"), second.installed().map { it.modelId })
        val refused = listOf<() -> Unit>(
            { second.beginStaging("siglip", role, "bbbb2222") },
            { second.writeStaged("siglip", role, 0, PAYLOAD) },
            { second.commitStaged(entry("bbbb2222"), emptyMap()) },
            { second.abortStaging("siglip", role) },
            { second.remove("siglip") },
            { second.add(entry("bbbb2222"), mapOf(role to PAYLOAD)) },
        )
        refused.forEachIndexed { i, write ->
            val e = assertFailsWith<ModelStoreNotOwnedException>("write #$i was let through") { write() }
            assertTrue("Another Control process" in e.message.orEmpty(), "write #$i: ${e.message}")
        }
        // The owner is untouched and still writes.
        assertEquals(listOf("siglip"), owner.installed().map { it.modelId })
        assertTrue(exists(path))
        owner.remove("siglip")
        assertEquals(emptyList(), owner.installed())
    }

    /**
     * ⚠ Ownership is decided once. A refused process keeps the index it read while the owner could
     * still write; taking over after the owner dies would sweep against that stale read.
     */
    @Test
    fun aRefusedProcessStaysRefusedAfterTheOwnerDies() {
        val owner = startProcess()
        owner.install()
        val second = startProcess()

        held.first().release()

        assertFailsWith<ModelStoreNotOwnedException> { second.remove("siglip") }
        // A process started AFTER the owner died does own it.
        assertNull(FileLockModelStoreOwnership(lockFile).also { held += it }.refusal())
    }

    @Test
    fun aLockThatCannotBeTakenAtAllIsRefusedAndSaysSo() {
        // The lock file's parent is a regular file, so the lock file cannot be created.
        val blocker = File(root, "not-a-directory").apply { writeText("x") }

        val lock = File(blocker, "store.lock")
        val refusal = assertNotNull(FileLockModelStoreOwnership(lock).refusal())

        // ⚠⚠ Its own kind: nothing here says another Control holds it, because none does.
        assertTrue(refusal is ModelStoreRefusal.LockUnavailable, "$refusal")
        assertTrue("Could not lock the model store" in refusal.message, refusal.message)
        assertTrue(lock.path in refusal.detail, "the detail must say where: ${refusal.detail}")
        assertTrue("Another Control" !in refusal.message, refusal.message)
    }

    /**
     * `DesktopSecureStorageDelegate`'s non-secret half, per process: the shared file is read
     * **once**, at construction, and every write rewrites it from this process's own map.
     */
    private class ProcessLocalStorage(private val file: MutableMap<String, String>) : SecureStorageDelegate {
        private val props = file.toMutableMap()
        private fun persist() { file.clear(); file.putAll(props) }
        override fun storeSecret(key: String, value: String) = error("the model store keeps no secrets")
        override fun getSecret(key: String): String? = error("the model store keeps no secrets")
        override fun storeString(key: String, value: String) { props[key] = value; persist() }
        override fun getString(key: String): String? = props[key]
        override fun remove(key: String) { if (props.remove(key) != null) persist() }
    }

    private companion object {
        val PAYLOAD = "WEIGHTS".encodeToByteArray()
    }
}
