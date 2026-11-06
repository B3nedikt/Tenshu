package org.tenshu.inference.harness.ios

import org.tenshu.core.common.proxy.InMemoryFileDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The corpus is what the manifest names, checked byte for byte (`ios-on-device-inference.md` §9.2).
 */
class CorpusManifestTest {

    private val files = InMemoryFileDatabase()

    private fun verify(vararg present: String): Result<CorpusManifest.Verified> =
        CorpusManifest.verify(files, "photos", present.toList())

    private fun refusal(vararg present: String): String =
        assertNotNull(verify(*present).exceptionOrNull(), "the corpus must be refused").message.orEmpty()

    private fun twoPhotos() {
        files.writeRawByteArray("photos/a.jpg", "alpha".encodeToByteArray())
        files.writeRawByteArray("photos/b.jpg", "beta".encodeToByteArray())
    }

    @Test
    fun theGoldenManifestOfTheDriverScriptVerifies() {
        // Byte for byte what `run-in3m.sh`'s `corpus_manifest` writes for these two files; its
        // self-test pins the same id, so the script and this reader cannot drift apart unseen.
        twoPhotos()
        files.writeRawByteArray(
            CorpusManifest.FILE,
            (
                "in3m-corpus 1\n" +
                    "id=15e951bef9a4ab29c1bfc0514ffbc7caf68be0035c62386d7b5f8e88403b8109\n" +
                    "count=2\n" +
                    "\n" +
                    "8ed3f6ad685b959ead7022518e1af76cd816f8e8ec7ccdda1ed4018e8f2223f8\t5\ta.jpg\n" +
                    "f44e64e75f3948e9f73f8dfa94721c4ce8cbb4f265c4790c702b2d41cfbf2753\t4\tb.jpg\n"
                ).encodeToByteArray(),
        )

        val corpus: CorpusManifest.Verified = verify("a.jpg", "b.jpg").getOrThrow()

        assertEquals("15e951bef9a4ab29c1bfc0514ffbc7caf68be0035c62386d7b5f8e88403b8109", corpus.id)
        assertEquals(listOf("a.jpg", "b.jpg"), corpus.names)
        assertEquals(emptyList(), corpus.unnamed)
    }

    @Test
    fun filesTheManifestDoesNotNameAreListedAndNotInTheCorpus() {
        twoPhotos()
        files.writeRawByteArray("photos/stand-in-0001.jpg", "left by an earlier push".encodeToByteArray())
        writeCorpusManifest(files, "a.jpg", "b.jpg")

        val corpus: CorpusManifest.Verified = verify("a.jpg", "b.jpg", "stand-in-0001.jpg").getOrThrow()

        assertEquals(listOf("a.jpg", "b.jpg"), corpus.names)
        assertEquals(listOf("stand-in-0001.jpg"), corpus.unnamed)
    }

    @Test
    fun aMissingManifestIsRefused() {
        twoPhotos()

        assertTrue("no corpus manifest" in refusal("a.jpg", "b.jpg"))
    }

    @Test
    fun aNamedFileThatIsMissingIsRefused() {
        twoPhotos()
        writeCorpusManifest(files, "a.jpg", "b.jpg")
        files.deleteFile("photos/b.jpg")

        assertEquals("the corpus names b.jpg, which is not in Documents/photos/", refusal("a.jpg"))
    }

    @Test
    fun aNamedFileOfAnotherSizeIsRefused() {
        twoPhotos()
        writeCorpusManifest(files, "a.jpg", "b.jpg")
        files.writeRawByteArray("photos/b.jpg", "a longer photo of the same name".encodeToByteArray())

        assertEquals("b.jpg is 31 bytes, the corpus manifest says 4", refusal("a.jpg", "b.jpg"))
    }

    @Test
    fun aNamedFileOfTheSameSizeAndOtherBytesIsRefused() {
        twoPhotos()
        writeCorpusManifest(files, "a.jpg", "b.jpg")
        files.writeRawByteArray("photos/b.jpg", "betb".encodeToByteArray())

        val reason: String = refusal("a.jpg", "b.jpg")

        assertTrue(reason.startsWith("b.jpg has sha256 ") && "the corpus manifest says f44e64e75f39…" in reason, reason)
    }

    @Test
    fun aManifestWhoseIdIsNotItsEntriesIsRefused() {
        twoPhotos()
        writeCorpusManifest(files, "a.jpg", "b.jpg")
        // An entry dropped by hand: the id and the count still describe two photos.
        val edited: String = files.readBytes(CorpusManifest.FILE)!!.get().decodeToString().lines()
            .filterNot { it.endsWith("\tb.jpg") }.joinToString("\n")
        files.writeRawByteArray(CorpusManifest.FILE, edited.encodeToByteArray())

        assertTrue("its entries are corpus" in refusal("a.jpg", "b.jpg"))
    }

    @Test
    fun aFileThatIsNotAManifestIsRefused() {
        files.writeRawByteArray(CorpusManifest.FILE, "a.jpg\nb.jpg\n".encodeToByteArray())

        assertTrue("does not open with 'in3m-corpus 1'" in refusal())
    }
}
