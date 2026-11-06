package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.proxy.InMemoryFileDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TarReader] against archives `scripts/inference/export-coreml-towers.py` really wrote.
 *
 * ⚠ `TarReaderTest` builds its archives in Kotlin, so on its own it proves the reader agrees with
 * a writer that shares its reading of the format. These are Python's `tarfile` output, the writer
 * every shipped tower goes through, and this runs on the JVM gate where the iOS cases cannot.
 */
class TarReaderExportedArchiveTest {

    private val fixtures = File("src/iosTest/resources/coreml")

    @Test
    fun everyFixtureArchiveUnpacksToOneMlPackage() {
        val archives: List<File> = fixtures.listFiles { file -> file.name.endsWith(".mlpackage.tar") }.orEmpty().sorted()
        assertEquals(4, archives.size, "the fixtures `--fixture` writes, in ${fixtures.absolutePath}")

        archives.forEach { archive ->
            val files = InMemoryFileDatabase()
            files.writeRawByteArray("in.tar", archive.readBytes())

            val written: List<TarEntry> = TarReader.unpack(files, "in.tar", "out")

            val stem: String = archive.name.removeSuffix(".tar")
            assertEquals(
                listOf(
                    "$stem/Data/com.apple.CoreML/model.mlmodel",
                    "$stem/Data/com.apple.CoreML/weights/weight.bin",
                    "$stem/Manifest.json",
                ),
                written.map { it.path },
                archive.name,
            )
            written.forEach { entry ->
                assertEquals(entry.size, files.fileSize("out/${entry.path}"), entry.path)
                assertTrue(entry.size > 0, entry.path)
            }
        }
    }
}
