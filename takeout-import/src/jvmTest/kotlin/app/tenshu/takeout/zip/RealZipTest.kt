package app.tenshu.takeout.zip

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry as JvmZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader against a **real** archive — one `java.util.zip` wrote, with real DEFLATE payloads,
 * read back through [jvmInflate].
 *
 * ⚠⚠ `commonTest`'s hand-built fixtures prove the reader survives the *shapes* an export can take,
 * and they cannot prove the one thing that matters most: that it reads a file some other program
 * produced. A parser tested only against its own test-writer agrees with itself.
 */
class RealZipTest {

    private fun realZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(JvmZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsADeflatedEntryWrittenByJavaUtilZip() {
        // ⚠ Repetitive text so DEFLATE actually compresses — an incompressible payload would be
        // STORED and the inflate path would never run.
        val json = "{\"timelineObjects\":[" + "{\"placeVisit\":{}},".repeat(200) + "]}"
        val bytes = realZip(
            mapOf(
                "Takeout/Location History/2023_JANUARY.json" to json,
                "Takeout/archive_browser.html" to "<html></html>",
            ),
        )

        val archive = ZipArchive.open(ByteArrayZipSource(bytes), jvmInflate)!!
        val entry = archive.entries.first { it.name.endsWith("2023_JANUARY.json") }

        assertEquals(ZipArchive.METHOD_DEFLATE, entry.method, "the fixture must exercise inflate")
        assertTrue(entry.compressedSize < entry.uncompressedSize)
        assertEquals(json, archive.read(entry)!!.decodeToString())
    }

    @Test
    fun theRouterClassifiesARealExportsLayout() {
        val bytes = realZip(
            mapOf(
                "Takeout/Standortverlauf/2023_JANUAR.json" to "{\"timelineObjects\":[]}",
                "Takeout/Google Fotos/IMG_1.jpg" to "ÿØ not really a jpeg",
                "Takeout/Google Fotos/IMG_1.jpg.json" to "{\"photoTakenTime\":{}}",
                "Takeout/Chrome/Verlauf.json" to "{\"Browser History\":[]}",
            ),
        )
        val archive = ZipArchive.open(ByteArrayZipSource(bytes), jvmInflate)!!

        val scan = app.tenshu.takeout.TakeoutRouter.classify(archive.entries) { entry ->
            // ⚠ The sniff reads a *prefix*, not the entry: an export's JSONs run to tens of MB.
            archive.read(entry)?.copyOf(minOf(512, entry.uncompressedSize.toInt()))
        }

        assertEquals(3, scan.understood)
        assertEquals(listOf("Takeout/Chrome/Verlauf.json"), scan.unmatched)
        assertEquals(
            listOf("Takeout/Standortverlauf/2023_JANUAR.json"),
            scan.of(app.tenshu.takeout.TakeoutEntryKind.TIMELINE).map { it.name },
        )
    }
}
