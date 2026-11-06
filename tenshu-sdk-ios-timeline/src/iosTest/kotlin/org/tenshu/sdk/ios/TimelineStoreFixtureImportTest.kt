package org.tenshu.sdk.ios

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store-screenshot seed's import (`TimelineLocalRuntime.importGoogleTimelineForStoreFixture`,
 * `store-screenshots.md` §3f): a legacy Google Timeline export goes through the shared
 * `FileTimelineImporter` into the in-process runtime, and the home feed and Places read it back —
 * the rows the screenshot walk captures.
 */
class TimelineStoreFixtureImportTest {

    @Test
    fun aLegacyExportLandsAsNamedPlacesAndAMovement() = runBlocking {
        val assembly = TimelineLocalRuntime.assemble(
            replicaDirectory = NSTemporaryDirectory() + "timeline-fixture-import-" + Random.nextLong().toString(16),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val written = TimelineLocalRuntime.importGoogleTimeline(assembly, listOf("2026_OCTOBER.json"), listOf(EXPORT))
        // Two places + two visits + one movement moment + its trace.
        assertEquals(6, written)
        val repo = assembly.client.timelineRepository
        val names = repo.places().first().map { it.name }.toSet()
        assertEquals(setOf("Piazza Santo Spirito", "Uffizi Gallery"), names)
        val moments = repo.homeDays().first().flatMap { it.moments }
        assertTrue(moments.any { it.title.startsWith("Walking") }, "the walk between them: ${moments.map { it.title }}")
        // ⚠ A feed with rows must not flip the "Can't reach Tenshu Control" banner on: the first
        // store-screenshot run showed it over every screen of a working route-1 runtime.
        assertTrue(repo.controlReachable().value != false, "a working runtime reads as unreachable after the feed read")
    }

    private companion object {
        val EXPORT = """
            {"timelineObjects": [
              {"placeVisit": {"location": {"latitudeE7": 437670000, "longitudeE7": 112470000, "name": "Piazza Santo Spirito"},
                "duration": {"startTimestamp": "2026-10-28T08:00:00Z", "endTimestamp": "2026-10-28T08:30:00Z"}}},
              {"activitySegment": {"activityType": "WALKING", "distance": 900, "confidence": "HIGH",
                "duration": {"startTimestamp": "2026-10-28T08:30:00Z", "endTimestamp": "2026-10-28T08:42:00Z"},
                "simplifiedRawPath": {"points": [
                  {"latE7": 437670000, "lngE7": 112470000, "timestamp": "2026-10-28T08:30:00Z"},
                  {"latE7": 437675000, "lngE7": 112510000, "timestamp": "2026-10-28T08:36:00Z"},
                  {"latE7": 437679000, "lngE7": 112553000, "timestamp": "2026-10-28T08:42:00Z"}]}}},
              {"placeVisit": {"location": {"latitudeE7": 437679000, "longitudeE7": 112553000, "name": "Uffizi Gallery"},
                "duration": {"startTimestamp": "2026-10-28T08:42:00Z", "endTimestamp": "2026-10-28T11:00:00Z"}}}
            ]}
        """.trimIndent()
    }
}
