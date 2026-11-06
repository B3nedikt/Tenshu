package org.tenshu.inference.harness.ios

import org.tenshu.core.common.proxy.InMemoryFileDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorFileTest {

    private val files = InMemoryFileDatabase()

    private val mine = VectorFile.Fingerprint(
        tower = "vision",
        row = "coreml-ane",
        osBuild = "Version 26.6.1 (Build 23G1)",
        archiveSha256 = "5d43919b3d5f" + "0".repeat(52),
        preprocessingRevision = 1,
        corpusId = "15e951bef9a4" + "2".repeat(52),
    )
    private val a: FloatArray = floatArrayOf(1f, 0f)
    private val b: FloatArray = floatArrayOf(0.6f, 0.8f)

    /** Keeps one photo's vector under [fingerprint] and returns the file's name. */
    private fun keep(fingerprint: VectorFile.Fingerprint): String {
        val name: String = VectorFile.nameFor(fingerprint)
        VectorFile.write(files, name, fingerprint, mapOf("IMG_0001.jpg" to b))
        return name
    }

    private fun compare(vararg names: String): List<String> =
        VectorFile.compareWithKept(files, names.toList(), VectorFile.nameFor(mine), mine, mapOf("IMG_0001.jpg" to a))

    @Test
    fun vectorsAndTheirFingerprintRoundTripBitForBit() {
        val vectors = mapOf(
            "IMG_0001.jpg" to floatArrayOf(0.6f, -0.8f, 1.0e-7f),
            "a photo with spaces.heic" to floatArrayOf(-0.0f, Float.MIN_VALUE, 1f),
        )

        VectorFile.write(files, "v.txt", mine, vectors)
        val read: VectorFile.Stored = assertNotNull(VectorFile.read(files, "v.txt"))

        assertEquals(mine, read.fingerprint)
        assertEquals(vectors.keys.toList(), read.vectors.keys.toList())
        vectors.forEach { (name, vector) -> assertContentEquals(vector, read.vectors[name]) }
    }

    @Test
    fun aFileFromAnotherRowOrOsBuildIsCompared() {
        // The two comparisons the files exist for: another compute-units row, another OS build.
        val otherRow: String = keep(mine.copy(row = "coreml-cpu"))
        val otherOs: String = keep(mine.copy(osBuild = "Version 26.7 (Build 23H5)"))

        val lines: List<String> = compare(otherRow, otherOs)

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("cosine vs $otherRow (coreml-cpu, ") && "shared 1  mean 0.600000  min 0.600000" in lines[0], lines[0])
        assertTrue(lines[1].startsWith("cosine vs $otherOs (coreml-ane, Version 26.7 (Build 23H5))"), lines[1])
    }

    @Test
    fun aFileFromAnotherArchiveIsSkippedAndSaysSo() {
        // A re-export of the same checkpoint: same row, same OS, another digest.
        val reExport: String = keep(mine.copy(archiveSha256 = "550b842857df" + "1".repeat(52)))

        val lines: List<String> = compare(reExport)

        assertEquals(listOf("skipped $reExport: another archive (sha256 550b842857df…, this run 5d43919b3d5f…)"), lines)
    }

    @Test
    fun aFileFromAnotherPreprocessingRevisionIsSkippedAndSaysSo() {
        val older: String = keep(mine.copy(row = "coreml-cpu", preprocessingRevision = 0))

        val lines: List<String> = compare(older)

        assertEquals(listOf("skipped $older: preprocessing revision 0, this run 1"), lines)
    }

    @Test
    fun aFileFromAnotherCorpusIsSkippedAndSaysSo() {
        // The same photo NAME in the stand-in corpus and in the real one is not the same photo.
        val standIn: String = keep(mine.copy(row = "coreml-cpu", corpusId = "aaaaaaaaaaaa" + "3".repeat(52)))

        val lines: List<String> = compare(standIn)

        assertEquals(listOf("skipped $standIn: another corpus (aaaaaaaaaaaa…, this run 15e951bef9a4…)"), lines)
    }

    @Test
    fun aFileOfTheOtherTowerIsSkipped() {
        val text: String = keep(mine.copy(tower = "text", row = "coreml-cpu"))

        assertEquals(listOf("skipped $text: the text tower, not the vision tower"), compare(text))
    }

    @Test
    fun anOldFormatFileIsNeverComparedAndIsNamed() {
        // Format 1: no header, the photo lines alone. Its vector would agree perfectly.
        val old = "in3m-vectors-coreml-cpu-Version26.6.1Build23G1.txt"
        files.writeRawByteArray(old, "IMG_0001.jpg\t3f80000000000000\n".encodeToByteArray())
        // Format 2: a fingerprint with no corpus in it.
        val two = "in3m-vectors-coreml-cpu-Version26.6.1Build23G1-5d43919b3d5f-p1.txt"
        files.writeRawByteArray(
            two,
            ("in3m-vectors 2\ntower=vision\nrow=coreml-cpu\nosBuild=x\narchiveSha256=${mine.archiveSha256}\n" +
                "preprocessingRevision=1\n\nIMG_0001.jpg\t3f80000000000000\n").encodeToByteArray(),
        )

        val lines: List<String> = compare(old, two)

        assertNull(VectorFile.read(files, old))
        assertNull(VectorFile.read(files, two))
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("skipped $old: no fingerprint header"), lines[0])
        assertTrue(lines[1].startsWith("skipped $two: no fingerprint header"), lines[1])
    }

    @Test
    fun thisRunsOwnFileAndUnrelatedFilesAreNotLines() {
        val own: String = keep(mine)

        assertEquals(emptyList(), compare(own, "in3m-report.txt", "vision_fp16.mlpackage.tar"))
    }

    @Test
    fun agreementIsOverThePhotosBothRunsEmbedded() {
        val mine = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f), "only-mine" to floatArrayOf(1f, 0f))
        val theirs = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0.6f, 0.8f), "only-theirs" to floatArrayOf(0f, 1f))

        val agreement: VectorFile.Agreement = assertNotNull(VectorFile.agreement(mine, theirs))

        assertEquals(2, agreement.shared)
        assertEquals(0.9f, agreement.mean, 1e-6f)
        assertEquals(0.8f, agreement.min, 1e-6f)
        assertNull(VectorFile.agreement(mine, mapOf("z" to floatArrayOf(1f, 0f))))
    }

    @Test
    fun aFileNameCarriesTheRowTheOsBuildTheArchiveAndTheRevision() {
        assertEquals("in3m-vectors-coreml-ane-Version26.6.1Build23G1-5d43919b3d5f-p1-c15e951be.txt", VectorFile.nameFor(mine))
    }

    @Test
    fun numbersAreFormattedWithoutAPlatformFormatter() {
        assertEquals("5.80", In3mHarness.ms(5_800))
        assertEquals("0.01", In3mHarness.ms(6))
        assertEquals("176.3 MB", In3mHarness.megabytes(176_300_000))
        assertEquals("-0.5 MB", In3mHarness.megabytes(-500_000))
        assertEquals("n/a", In3mHarness.fixed(Double.NaN, 2))
        assertEquals("1.07", In3mHarness.fixed(1.0700001, 2))
    }
}
