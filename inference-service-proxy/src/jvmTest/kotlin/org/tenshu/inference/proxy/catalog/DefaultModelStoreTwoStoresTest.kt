package org.tenshu.inference.proxy.catalog

import org.tenshu.inference.proxy.catalog.DownloadFixtures.MODEL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.entry
import org.tenshu.inference.proxy.catalog.DownloadFixtures.sha256
import org.tenshu.inference.proxy.catalog.DownloadFixtures.textBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.visionBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.vocabularyBytes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two stores of one process over one index, on two threads (`ios-on-device-inference.md`
 * §14.11): a graph being torn down, mid-commit, and its successor's store read for the first
 * time. ⚠ On the JVM only: it needs a second thread inside the first one's commit, and a lock
 * that is reentrant would let a same-thread hook straight through.
 */
class DefaultModelStoreTwoStoresTest {

    @Test
    fun aSecondStoresFirstReadDuringACommitSweepsNothingOfThatCommit() {
        val disk = DownloadDisk()
        val committing = DownloadFixtures.storeOver(disk)
        val bytes = mapOf(
            ModelArtifactRole.VISION to visionBytes,
            ModelArtifactRole.TEXT to textBytes,
            ModelArtifactRole.VOCABULARY to vocabularyBytes,
        )
        bytes.forEach { (role, body) ->
            committing.beginStaging(MODEL, role, sha256(body))
            committing.writeStaged(MODEL, role, 0, body)
        }
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"
        val successor = DownloadFixtures.storeOver(disk)
        val firstReadDone = CountDownLatch(1)
        var reader: Thread? = null
        var seen: List<InstalledModelData>? = null
        // ⚠⚠ The commit has declared its paths and made its renames, and is about to write the
        // index. On another thread the successor's store is read for the first time.
        disk.whileReading = { path ->
            if (reader == null && path == index && disk.paths.none { it.endsWith(".part") }) {
                reader = thread {
                    seen = successor.installed()
                    firstReadDone.countDown()
                }
                // Long enough for an unguarded first read to run its sweep to the end.
                firstReadDone.await(500, TimeUnit.MILLISECONDS)
            }
        }

        val installed: InstalledModelData = committing.commitStaged(entry(), emptyMap())

        disk.whileReading = {}
        assertNotNull(reader, "the commit never read the index after its renames: the case did not reach its moment").join(10_000)
        // ⚠⚠ The record names three files, and all three are there. The first read once swept
        // them: declared by a commit in flight, named by no record yet.
        installed.artifacts.forEach { artifact ->
            assertTrue(disk.fileSize(artifact.localPath) == artifact.byteSize, "the commit's ${artifact.role} file was swept from under its record")
        }
        assertContentEquals(visionBytes, disk.readBytes(installed.pathOf(ModelArtifactRole.VISION)!!)?.get())
        // And the reader waited for the commit: it saw the model, not the moment before it.
        assertEquals(listOf(installed), seen)
        assertEquals(listOf(installed), DownloadFixtures.storeOver(disk).installed())
    }
}
