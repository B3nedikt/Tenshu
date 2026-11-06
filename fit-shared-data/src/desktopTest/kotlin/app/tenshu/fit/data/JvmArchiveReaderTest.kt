package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The archive side of the Takeout import. The load-bearing behaviour is not "it can unzip" — it is
 * that it decides what to read *by name, before reading it*, and yields **one member at a time**. A
 * real Takeout is whatever the user ticked, so the Fit folder can sit inside tens of gigabytes of
 * photos and mail; reading the whole archive into a list (the obvious shape) OOMs on precisely the
 * export a long-time Fit user has.
 */
class JvmArchiveReaderTest {

    private val parser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader()))

    /** Drains the streaming source into a list — the test wants to assert over the whole set. */
    private suspend fun collect(zip: ByteArray): List<ActivityFile> = buildList {
        JvmArchiveReader.source(openStream = { ByteArrayInputStream(zip) }, isRelevant = parser::isRelevant)
            .forEach { add(it) }
    }

    @Test
    fun onlyTheRelevantMembersAreReadOutOfTheArchive() = runTest {
        val zip = zipOf(
            "Takeout/Fit/Daily activity metrics/Daily activity metrics.csv" to "Date,Step count\n2026-01-04,8000",
            "Takeout/Fit/Activities/run.tcx" to "<TrainingCenterDatabase/>",
            "Takeout/Photos/holiday.jpg" to "not health data",
            "Takeout/Fit/All Data/derived_steps.json" to "{}",
        )

        assertEquals(
            listOf("Takeout/Fit/Daily activity metrics/Daily activity metrics.csv", "Takeout/Fit/Activities/run.tcx"),
            collect(zip).map { it.name },
            "the photo and the JSON dump must never be read into memory",
        )
    }

    @Test
    fun theSourceIsReConsumable() = runTest {
        // forEach opens a fresh stream each call, so a caller may walk it more than once.
        val zip = zipOf("Takeout/Fit/Activities/run.tcx" to TCX)
        assertEquals(collect(zip).map { it.name }, collect(zip).map { it.name })
    }

    @Test
    fun aRealTakeoutShapedArchiveImportsBothItsDaysAndItsWorkouts() = runTest {
        val zip = zipOf(
            "Takeout/Fit/Daily activity metrics/Daily activity metrics.csv" to
                "Date,Step count,Distance (m),Move Minutes count,Calories (kcal)\n" +
                "2026-01-04,8000,6100,55,320\n2026-01-05,9100,6900,61,355",
            "Takeout/Fit/Activities/run.tcx" to TCX,
            "Takeout/Drive/notes.txt" to "unrelated",
        )

        val health = parser.parse(collect(zip))

        assertEquals(2, health.dailyActivities.size)
        assertEquals(1, health.workouts.size)
        assertTrue(health.workouts.single().distanceM > 0)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val TCX = """
            <?xml version="1.0"?>
            <TrainingCenterDatabase>
              <Activities>
                <Activity Sport="Running">
                  <Id>2026-01-04T08:00:00Z</Id>
                  <Lap StartTime="2026-01-04T08:00:00Z">
                    <TotalTimeSeconds>1800</TotalTimeSeconds>
                    <DistanceMeters>5000</DistanceMeters>
                    <Calories>300</Calories>
                  </Lap>
                </Activity>
              </Activities>
            </TrainingCenterDatabase>
        """.trimIndent()
    }
}
