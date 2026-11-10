package app.tenshu.control.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.tenshu.core.common.proxy.BridgeException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileProviderStagingTest {

    private val dir: File = Files.createTempDirectory("fp-staging").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // The real age source is the inode change time, which nothing can backdate — so the
    // tests that need an old entry say how old it is through the class's seam.
    private val ages = HashMap<String, Long>()

    private fun backdate(file: File, millisAgo: Long) {
        ages[file.name] = millisAgo
    }

    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    /**
     * Skips a case that needs staging to be AVAILABLE, on a host without POSIX permissions
     * (Windows). There staging cannot make its directory owner-only, so it fails closed —
     * [stagingFailsClosedWithoutPosixPermissions] pins that instead. Staging is the macOS File
     * Provider's hand-off; the Windows design needs none (`desktop-windows-port.md` §7.2).
     */
    private fun posixOnly() = assumeTrue("staging needs POSIX permissions", posix)

    @Test
    fun stagingFailsClosedWithoutPosixPermissions() {
        org.junit.Assume.assumeFalse("this host has POSIX permissions", posix)
        val staging = FileProviderStaging(dir)
        assertEquals("unavailable", assertFailsWith<BridgeException> { staging.validate("abcdefgh") }.code)
        assertEquals("unavailable", assertFailsWith<BridgeException> { staging.createDownload("abcdefgh") }.code)
    }

    private fun agedStaging(directory: File = dir) = FileProviderStaging(directory) { path ->
        val real = inodeChangeMillis(path) ?: return@FileProviderStaging null
        real - (ages[path.fileName.toString()] ?: 0L)
    }

    @Test
    fun aNameIsATokenAndNeverAPath() {
        posixOnly()
        val staging = FileProviderStaging(dir)
        staging.validate("3f2c9a10-aaaa-bbbb-cccc-000000000001")
        for (hostile in listOf("../escape", "a/b-c-d-e-f", "..", "", "short", "name.with.dots", "/etc/passwd", "x".repeat(65))) {
            val error = assertFailsWith<BridgeException>("'$hostile' must be refused") { staging.validate(hostile) }
            assertEquals("invalid_request", error.code)
            assertEquals("invalid_request", assertFailsWith<BridgeException> { staging.openUpload(hostile) }.code)
            assertEquals("invalid_request", assertFailsWith<BridgeException> { staging.createDownload(hostile) }.code)
        }
    }

    @Test
    fun withoutADirectoryStagingIsUnavailable_notACrash() {
        assertEquals("unavailable", assertFailsWith<BridgeException> { FileProviderStaging(null).validate("abcdefgh") }.code)
    }

    @Test
    fun theDirectoryIsPrivateToTheUser() {
        posixOnly()
        FileProviderStaging(dir)
        val permissions = Files.getPosixFilePermissions(dir.toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"), permissions)
    }

    @Test
    fun discardRemovesTheFile_andSettledLeavesIt() {
        posixOnly()
        val staging = FileProviderStaging(dir)
        val kept = File(dir, "kept-0001").apply { writeText("plaintext") }
        val dropped = File(dir, "dropped-0001").apply { writeText("plaintext") }

        staging.settled("kept-0001")
        staging.discard("dropped-0001")

        assertTrue(kept.exists(), "a settled transfer's file is the other side's to collect")
        assertFalse(dropped.exists(), "a failed transfer must not leave plaintext behind")
    }

    @Test
    fun sweepRemovesOnlyWhatACrashLeftLongAgo() {
        posixOnly()
        val staging = agedStaging()
        val stale = File(dir, "stale-0001").apply { writeText("x") }
        val fresh = File(dir, "fresh-0001").apply { writeText("x") }
        backdate(stale, FileProviderStaging.MAX_AGE_MILLIS + 60_000)

        staging.sweep(System.currentTimeMillis())

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun anOldDocumentStagedJustNowIsNotStale() {
        posixOnly()
        // The REAL age source, no seam. A staged upload is a clone of the user's file and keeps
        // its dates — setting an old modification time drags the creation time back with it on
        // APFS — so by either date this file is a year stale the moment it arrives.
        val staging = FileProviderStaging(dir)
        val upload = File(dir, "upload-0001").apply { writeText("an old document") }
        val yearAgo = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
        Files.getFileAttributeView(upload.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java)
            .setTimes(yearAgo, yearAgo, yearAgo)

        staging.sweep(System.currentTimeMillis())

        assertTrue(upload.exists(), "arrival, not the document's own dates, is the staging age")
        assertEquals("an old document", staging.openUpload("upload-0001").use { it.readBytes().decodeToString() })
    }

    @Test
    fun aSymlinkNamedLikeAnUploadIsNotRead() {
        posixOnly()
        // The name is a valid token; the entry behind it points out of the directory.
        val staging = FileProviderStaging(dir)
        val secret = Files.createTempFile("fp-secret", ".txt").toFile().apply { writeText("daemon-readable secret") }
        try {
            Files.createSymbolicLink(File(dir, "upload-link1").toPath(), secret.toPath())

            assertEquals("invalid_request", assertFailsWith<BridgeException> { staging.openUpload("upload-link1") }.code)
            assertEquals("invalid_request", assertFailsWith<BridgeException> { staging.uploadSize("upload-link1") }.code)

            // Discarding the name removes the LINK and leaves what it pointed at.
            staging.discard("upload-link1")
            assertFalse(Files.exists(File(dir, "upload-link1").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("daemon-readable secret", secret.readText())
        } finally {
            secret.delete()
        }
    }

    @Test
    fun aDownloadNeverWritesThroughAPlantedSymlink() {
        posixOnly()
        val staging = FileProviderStaging(dir)
        val victim = Files.createTempFile("fp-victim", ".txt").toFile().apply { writeText("do not truncate me") }
        try {
            Files.createSymbolicLink(File(dir, "download-link1").toPath(), victim.toPath())

            staging.createDownload("download-link1").use { it.write("plaintext".toByteArray()) }

            assertEquals("do not truncate me", victim.readText(), "the link's target must be untouched")
            val staged = File(dir, "download-link1").toPath()
            assertTrue(Files.isRegularFile(staged, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("plaintext", staged.toFile().readText())
        } finally {
            victim.delete()
        }
    }

    @Test
    fun aStagingPathThatIsItselfASymlinkSwitchesStagingOff() {
        posixOnly()
        val elsewhere = Files.createTempDirectory("fp-elsewhere").toFile()
        val link = File(dir, "linked-staging")
        try {
            Files.createSymbolicLink(link.toPath(), elsewhere.toPath())
            val staging = FileProviderStaging(link)
            assertEquals("unavailable", assertFailsWith<BridgeException> { staging.validate("abcdefgh") }.code)
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    @Test
    fun replacingTheDirectoryAfterwardsSwitchesStagingOff_ratherThanRedirectingIt() {
        posixOnly()
        // The directory was real when staging started. Then it is moved aside and a symlink to
        // an attacker's directory is put at its path — with entries of the right names in it.
        val real = File(dir, "staging").apply { mkdirs() }
        val staging = FileProviderStaging(real)
        File(real, "upload-0001").writeText("the staged upload")

        val elsewhere = Files.createTempDirectory("fp-elsewhere").toFile()
        val victim = File(elsewhere, "download-0001").apply { writeText("do not truncate me") }
        val bait = File(elsewhere, "upload-0001").apply { writeText("a file the daemon must not upload") }
        try {
            assertTrue(real.renameTo(File(dir, "staging-moved")))
            Files.createSymbolicLink(real.toPath(), elsewhere.toPath())

            assertEquals("unavailable", assertFailsWith<BridgeException> { staging.openUpload("upload-0001") }.code)
            assertEquals("unavailable", assertFailsWith<BridgeException> { staging.createDownload("download-0001") }.code)
            staging.discard("upload-0001")
            staging.sweep(System.currentTimeMillis() + 2 * FileProviderStaging.MAX_AGE_MILLIS)

            assertEquals("do not truncate me", victim.readText())
            assertTrue(bait.exists(), "nothing behind the link may be deleted either")
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    @Test
    fun aDifferentRealDirectoryAtThePathIsNotTrustedEither() {
        // ⚠ Would pass off POSIX for the wrong reason (staging is never available there).
        posixOnly()
        // Not a symlink this time: the directory is replaced by another real one.
        val real = File(dir, "staging").apply { mkdirs() }
        val staging = FileProviderStaging(real)
        assertTrue(real.renameTo(File(dir, "staging-moved")))
        File(dir, "staging").mkdirs()
        File(dir, "staging/upload-0001").writeText("planted")

        assertEquals("unavailable", assertFailsWith<BridgeException> { staging.openUpload("upload-0001") }.code)
    }

    @Test
    fun aLeftoverIsSweptOnLaterUse_withoutARestart_butNeverTheEntryInUse() {
        posixOnly()
        val staging = agedStaging()
        // Staged by an extension that then crashed: the daemon was never told about it.
        val orphan = File(dir, "orphan-0001").apply { writeText("plaintext") }
        // An entry that is overdue by its timestamps and is the very one being asked about.
        val inUse = File(dir, "in-use-0001").apply { writeText("being consumed") }
        // The start-up sweep is long past; make the hourly interval look elapsed, before the
        // entries are old enough for THAT sweep to take them.
        staging.sweep(System.currentTimeMillis() - FileProviderStaging.SWEEP_INTERVAL_MILLIS - 1)
        backdate(orphan, FileProviderStaging.MAX_AGE_MILLIS + 60_000)
        backdate(inUse, FileProviderStaging.MAX_AGE_MILLIS + 60_000)

        staging.validate("in-use-0001")

        assertFalse(orphan.exists(), "ordinary staging activity must sweep what is overdue")
        assertTrue(inUse.exists(), "the entry a request names is never swept by that request")
    }

    @Test
    fun copyRangesReadsInChunks_andNeverAsksPastTheEnd() = runBlocking {
        val source = ByteArray(10_000) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()
        val requests = mutableListOf<Pair<Long, Int>>()

        val written = copyRanges(source.size.toLong(), sink, chunkBytes = 4096) { offset, length ->
            requests += offset to length
            source.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        assertEquals(10_000L, written)
        assertContentEquals(source, sink.toByteArray())
        assertEquals(listOf(0L to 4096, 4096L to 4096, 8192L to 1808), requests)
    }

    @Test
    fun copyRangesFollowsShortReads() = runBlocking {
        // A read may return fewer bytes than asked; the next one starts where it actually ended.
        val source = ByteArray(1000) { it.toByte() }
        val sink = ByteArrayOutputStream()
        copyRanges(source.size.toLong(), sink, chunkBytes = 400) { offset, length ->
            source.copyOfRange(offset.toInt(), offset.toInt() + minOf(length, 150))
        }
        assertContentEquals(source, sink.toByteArray())
    }

    @Test
    fun aFailedOrPrematurelyEmptyReadIsAnError_neverAShortFile() {
        val failed = assertFailsWith<BridgeException> {
            runBlocking { copyRanges(1000, ByteArrayOutputStream(), chunkBytes = 400) { offset, _ -> if (offset == 0L) ByteArray(400) else null } }
        }
        assertEquals("unavailable", failed.code)

        val truncated = assertFailsWith<BridgeException> {
            runBlocking { copyRanges(1000, ByteArrayOutputStream(), chunkBytes = 400) { offset, _ -> if (offset == 0L) ByteArray(400) else ByteArray(0) } }
        }
        assertEquals("unavailable", truncated.code)
    }

    @Test
    fun cancellationStopsAtTheNextChunk() {
        var reads = 0
        val error = assertFailsWith<BridgeException> {
            runBlocking {
                copyRanges(10_000, ByteArrayOutputStream(), chunkBytes = 1000, isCancelled = { reads >= 2 }) { _, length ->
                    reads++
                    ByteArray(length)
                }
            }
        }
        assertEquals("cancelled", error.code)
        assertEquals(2, reads)
    }

    @Test
    fun anEmptyFileIsZeroReads() = runBlocking {
        var reads = 0
        assertEquals(0L, copyRanges(0, ByteArrayOutputStream()) { _, _ -> reads++; ByteArray(0) })
        assertEquals(0, reads)
    }
}
