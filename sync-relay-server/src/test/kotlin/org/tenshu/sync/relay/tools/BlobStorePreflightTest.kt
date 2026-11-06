package org.tenshu.sync.relay.tools

import com.adobe.testing.s3mock.S3MockApplication
import org.tenshu.sync.relay.storage.BlobStore
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.S3BlobStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-window store check (`hosting-provider-economics.md` D10; the first step of
 * `docs/how-to/migrate-relay-blobs-to-s3.md`).
 *
 * ⚠ These prove the *check* works — that it passes a sound store and fails an unsound one. They
 * cannot prove any particular bucket is sound; that is the point of the check existing at all.
 */
class BlobStorePreflightTest {

    private var s3mock: S3MockApplication? = null
    private var tempDir: File? = null

    @AfterTest
    fun teardown() {
        s3mock?.stop()
        s3mock = null
        tempDir?.deleteRecursively()
    }

    private fun fileStore(): BlobStore =
        FileBlobStore(File.createTempFile("preflight", "").also { it.delete(); it.mkdirs() }.also { tempDir = it })

    private fun realS3(): BlobStore {
        val app = S3MockApplication.start(
            mapOf(
                S3MockApplication.PROP_HTTP_PORT to 0,
                S3MockApplication.PROP_SILENT to true,
                S3MockApplication.PROP_INITIAL_BUCKETS to "preflight-bucket",
            )
        ).also { s3mock = it }
        return S3BlobStore(
            client = S3BlobStore.clientFor(
                endpoint = "http://127.0.0.1:${app.httpPort}",
                region = "us-east-1",
                accessKey = "k",
                secretKey = "s",
            ),
            bucket = "preflight-bucket",
        )
    }

    @Test
    fun passesAgainstARealS3() {
        val report = BlobStorePreflight(realS3()).run()
        assertTrue(report.ok, report.checks.filterNot { it.passed }.toString())
        assertEquals(6, report.checks.size)
    }

    @Test
    fun passesAgainstTheFilesystemStore() {
        assertTrue(BlobStorePreflight(fileStore()).run().ok)
    }

    /**
     * ⚠⚠ The failure the whole check exists for. A store that keeps `aws-chunked` framing does not
     * reject the write — it returns MORE bytes than were sent, and only on read.
     */
    @Test
    fun failsWhenTheStoreKeepsChunkFraming_andSaysWhichSettingFixesIt() {
        val framing = object : BlobStore {
            private val inner = fileStore()
            override fun put(key: String, bytes: ByteArray) = inner.put(key, bytes + ByteArray(1_526))
            override fun get(key: String) = inner.get(key)
            override fun exists(key: String) = inner.exists(key)
            override fun delete(key: String) = inner.delete(key)
            override fun probe() = inner.probe()
        }

        val report = BlobStorePreflight(framing).run()

        assertFalse(report.ok)
        val failed = report.checks.single { !it.passed }
        assertEquals("read-back-is-byte-exact", failed.name)
        assertTrue(failed.detail.contains("chunkedEncodingEnabled"), failed.detail)
        assertTrue(failed.detail.contains("2098678"), failed.detail)
    }

    @Test
    fun failsWhenExistsLiesAboutAWrittenObject() {
        // SqliteShareStore's GC reads exists() as "this share never received chunk 0", so a store
        // that answers it wrongly deletes live share records. Cheap to check, expensive to miss.
        val lying = object : BlobStore {
            private val inner = fileStore()
            override fun put(key: String, bytes: ByteArray) = inner.put(key, bytes)
            override fun get(key: String) = inner.get(key)
            override fun exists(key: String) = false
            override fun delete(key: String) = inner.delete(key)
            override fun probe() = inner.probe()
        }

        val report = BlobStorePreflight(lying).run()

        assertFalse(report.ok)
        assertEquals("exists-reports-present", report.checks.single { !it.passed }.name)
    }

    @Test
    fun failsWhenTheStoreRefusesWrites_ratherThanThrowingOut() {
        // A read-only credential, or a bucket policy without PutObject. The check must report it,
        // not propagate — the CLI turns a Report into an exit code.
        val readOnly = object : BlobStore {
            override fun put(key: String, bytes: ByteArray) = error("AccessDenied")
            override fun get(key: String): ByteArray? = null
            override fun exists(key: String) = false
            override fun delete(key: String) {}
            override fun probe() {}
        }

        val report = BlobStorePreflight(readOnly).run()

        assertFalse(report.ok)
        assertEquals("write", report.checks.first().name)
        // Stops at the first failure rather than reporting five confusing consequences of it.
        assertEquals(1, report.checks.size)
    }

    @Test
    fun leavesNothingBehind_evenWhenItFails() {
        val root = File.createTempFile("preflight-clean", "").also { it.delete(); it.mkdirs() }
        tempDir = root
        val store = FileBlobStore(root)

        BlobStorePreflight(store).run()
        assertEquals(emptyList(), root.listFiles()?.map { it.name }?.sorted() ?: emptyList())

        // And on the failing path, where the object was written before the check failed.
        val corrupting = object : BlobStore {
            override fun put(key: String, bytes: ByteArray) = store.put(key, bytes + byteArrayOf(1))
            override fun get(key: String) = store.get(key)
            override fun exists(key: String) = store.exists(key)
            override fun delete(key: String) = store.delete(key)
            override fun probe() = store.probe()
        }
        assertFalse(BlobStorePreflight(corrupting).run().ok)
        assertEquals(
            emptyList(),
            root.listFiles()?.map { it.name }?.sorted() ?: emptyList(),
            "a failed preflight left an orphan in the store",
        )
    }
}
