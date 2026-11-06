package app.tenshu.timeline.data.geocode

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **No Timeline code may call a geocoding op except through [PlaceGeocodingEgress].**
 *
 * The three `:map-region-service` ops below can each send a coordinate to a third-party place-name
 * provider (komoot's public Photon by default). [PlaceGeocodingEgress] checks the user's opt-in
 * before every one of them; a direct call skips that check. This is not hypothetical:
 * `TimelineProjectionRepository` called `nearestTransitStop` and `reverseGeocodeCandidates` directly
 * and sent transit endpoints to Photon with the opt-in off (`GeocodingConsentEgressTest`).
 *
 * A KDoc rule asking the next caller to remember the gate is how that happened, so this test enforces
 * it: a new caller fails here and is told where the door is.
 *
 * ⚠ Scans **main** source sets of every `timeline-*` module plus the iOS SDK surface, and names only
 * real calls (identifier followed by `(`), so a KDoc that mentions an op in backticks is fine.
 */
class GeocodingEgressGuardTest {

    /** The repository root, from this module's directory (Gradle runs tests there). */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val gate = "timeline-shared-data/src/commonMain/kotlin/app/tenshu/timeline/data/geocode/PlaceGeocodingEgress.kt"

    private val egressCall = Regex("""\b(nearestTransitStop|reverseGeocodeCandidates|reverseGeocodePlace)\s*\(""")

    private fun timelineSources(): List<File> {
        val modules = root.listFiles { f -> f.isDirectory && f.name.startsWith("timeline-") }.orEmpty().toList() +
            File(root, "tenshu-sdk-ios-timeline")
        return modules.flatMap { module ->
            File(module, "src").listFiles { f -> f.isDirectory && !f.name.contains("Test", ignoreCase = true) }
                .orEmpty()
                .flatMap { set -> set.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() }
        }
    }

    @Test
    fun onlyTheGateCallsAGeocodingOp() {
        val sources = timelineSources()
        // ⚠ Not vacuous: a wrong root or a renamed module would scan nothing and pass.
        assertTrue(sources.size > 100, "scanned only ${sources.size} files — is the root right?")
        assertTrue(
            sources.any { it.relativeTo(root).invariantSeparatorsPath == gate },
            "the gate itself was not scanned",
        )

        val offenders = sources
            .filter { it.relativeTo(root).invariantSeparatorsPath != gate }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    if (code.trimStart().startsWith("*")) return@mapIndexedNotNull null
                    egressCall.find(code)?.let { "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
                }
            }

        assertEquals(
            emptyList(),
            offenders,
            "Call these through PlaceGeocodingEgress ($gate), which checks the place-name opt-in. " +
                "A direct call sends the coordinate to the third-party geocoder whatever the user chose.",
        )
    }
}
