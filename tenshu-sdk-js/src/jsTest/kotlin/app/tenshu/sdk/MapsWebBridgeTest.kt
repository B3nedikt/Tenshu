@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.fold
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.RoleSource
import org.tenshu.core.place.recordPlace
import org.tenshu.core.place.recordPlaceRole
import org.tenshu.place.client.placeService

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Maps` — web-app-suite Wave 3, Maps run A —
 * against the **real** in-process runtime, places seeded through the real `recordPlace` op.
 *
 * ⚠ The jsTest suite shares one `JsRuntime`: every test seeds uniquely-named rows and looks only at
 * those, near (20°, 20°) — away from `TimelineWebBridgeTest`'s (10°, 10°) cells.
 *
 * ⚠ Not here, because there is nothing to test: saved state, notes and saved lists (list CRUD,
 * membership, deleting a list) have no store — `maps-app.md` §Web scope 5a.
 */
class MapsWebBridgeTest : SdkRuntimeTest() {

    private val client get() = JsRuntime.tenshuClient
    private val ctx get() = client.environment.tContext

    private fun stamp() = "${Date.now().toLong()}-${(0..99999).random()}"

    private fun cell(): Pair<Long, Long> =
        (200_000_000L + (0..400).random() * 10_000L) to (200_000_000L + (0..400).random() * 10_000L)

    private fun assertOk(result: TResult<Data?>, what: String) =
        result.fold(onSuccess = {}, onFailure = { throw AssertionError("$what failed: $it") })

    private suspend fun seedPlace(id: String, label: String = "", at: Pair<Long, Long> = cell()) = assertOk(
        client.placeService.recordPlace(Place.create(ctx, id, at.first, at.second, UnixInstantDto(0L), label = label)),
        "recordPlace $id",
    )

    private suspend fun seedRole(placeId: String, role: String, source: String) = assertOk(
        client.placeService.recordPlaceRole(
            PlaceRole.create(ctx, placeId = placeId, role = role, roleSource = source, decidedAt = UnixInstantDto(1L)),
        ),
        "recordPlaceRole $placeId",
    )

    private suspend fun mapsPlaces(): List<dynamic> {
        val result = Maps.places().await()
        assertEquals(true, result.ok, "places() must succeed; got ${JSON.stringify(result)}")
        return result.places.unsafeCast<Array<dynamic>>().toList()
    }

    private suspend fun mapsPlace(id: String): dynamic {
        val result = Maps.place(id).await()
        assertEquals(true, result.ok, "place() must succeed; got ${JSON.stringify(result)}")
        return result.place
    }

    @Test
    fun aPlace_projectsTheKeysMapsPublishes_inDegrees() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val id = "pl-maps-shape-${stamp()}"
        seedPlace(id, label = "Kiosk", at = 201_234_567L to -1_234_567L)

        val row = mapsPlaces().first { it.placeId == id }
        assertEquals("Kiosk", row.name)
        assertEquals(false, row.nameIsUsers, "a seeded (geocoded) label is not the user's")
        assertEquals(20.1234567, row.latitude as Double, 1e-9)
        assertEquals(-0.1234567, row.longitude as Double, 1e-9)
        assertNull(row.role)
        assertEquals(
            setOf("placeId", "name", "nameIsUsers", "city", "countryCode", "latitude", "longitude", "role"),
            js("Object").keys(row).unsafeCast<Array<String>>().toSet(),
            "the row carries exactly what web/sdk/src/maps.ts publishes — and no saved / note / list field",
        )
    }

    @Test
    fun aRename_showsInTheList_theDetail_andTimelinesRead() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val id = "pl-maps-rename-$s"
        seedPlace(id, label = "Old $s")

        val renamed = Maps.renamePlace(id, "  Café $s  ").await()
        assertEquals(true, renamed.ok, "rename must succeed; got ${JSON.stringify(renamed)}")

        val row = mapsPlaces().first { it.placeId == id }
        assertEquals("Café $s", row.name, "trimmed, and in the Maps list")
        assertEquals(true, row.nameIsUsers)
        assertEquals("Café $s", mapsPlace(id).place.name, "and in the Maps detail")

        val timeline = Timeline.places().await()
        assertEquals(true, timeline.ok)
        val tRow = timeline.places.unsafeCast<Array<dynamic>>().first { it.placeId == id }
        assertEquals("Café $s", tRow.name, "one store, one write path: Timeline reads the same name")
        assertEquals(true, tRow.nameIsUsers)
        assertEquals("Café $s", Timeline.place(id).await().place.place.name)
    }

    @Test
    fun aMissingId_isAnAnswer_onRead_andATypedFailure_onWrite() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val missing = "pl-maps-missing-${stamp()}"
        val read = Maps.place(missing).await()
        assertEquals(true, read.ok, "an unknown place is not a broken read")
        assertNull(read.place, "… it is `place: null`")

        val write = Maps.renamePlace(missing, "Anything").await()
        assertEquals(false, write.ok)
        assertEquals("error_timeline_place_not_found", write.error.kind)
        assertTrue(mapsPlaces().none { it.placeId == missing }, "a failed rename mints no place")

        val id = "pl-maps-blank-${stamp()}"
        seedPlace(id, label = "Kept")
        assertEquals("error_timeline_blank_name", Maps.renamePlace(id, "   ").await().error.kind)
        assertEquals("Kept", mapsPlaces().first { it.placeId == id }.name, "a refused rename changes nothing")
    }

    @Test
    fun aFoldedPlace_leavesTheList_andItsIdFollowsToTheSurvivor() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val a = "pl-maps-fold-a-$s"
        val b = "pl-maps-fold-b-$s"
        seedPlace(a, label = "A $s")
        seedPlace(b, label = "B $s")
        val merged = Timeline.mergePlaces(a, b).await()
        assertEquals(true, merged.ok, "merge must succeed; got ${JSON.stringify(merged)}")
        val survivor = merged.survivorId as String
        val folded = if (survivor == a) b else a

        val ids = mapsPlaces().map { it.placeId as String }
        assertTrue(survivor in ids && folded !in ids, "one place, one record (D2): $ids")
        val detail = mapsPlace(folded)
        assertEquals(survivor, detail.place.placeId)
        assertEquals(folded, detail.redirectedFrom)
        assertEquals(1, detail.mergedPlaceCount)
        assertNull(mapsPlace(survivor).redirectedFrom)
    }

    @Test
    fun aRename_viaAFoldedId_renamesTheSurvivor() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val a = "pl-maps-foldrn-a-$s"
        val b = "pl-maps-foldrn-b-$s"
        seedPlace(a, label = "A $s")
        seedPlace(b, label = "B $s")
        val merged = Timeline.mergePlaces(a, b).await()
        assertEquals(true, merged.ok, "merge must succeed; got ${JSON.stringify(merged)}")
        val survivor = merged.survivorId as String
        val folded = if (survivor == a) b else a

        assertEquals(true, Maps.renamePlace(folded, "Via stale $s").await().ok)
        assertEquals("Via stale $s", mapsPlace(folded).place.name, "the detail a stale link shows is renamed")
        assertEquals("Via stale $s", mapsPlaces().first { it.placeId == survivor }.name)
    }

    @Test
    fun onlyAConfirmedRole_reachesARow() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val confirmed = "pl-maps-role-user-$s"
        val suggested = "pl-maps-role-inferred-$s"
        val dismissed = "pl-maps-role-none-$s"
        seedPlace(confirmed); seedPlace(suggested); seedPlace(dismissed)
        seedRole(confirmed, "home", RoleSource.USER)
        seedRole(suggested, "work", RoleSource.INFERRED)
        seedRole(dismissed, "none", RoleSource.USER)

        val rows = mapsPlaces().associateBy { it.placeId as String }
        assertEquals("home", rows.getValue(confirmed).role)
        assertNull(rows.getValue(suggested).role, "a suggestion never changes how a place is drawn")
        assertNull(rows.getValue(dismissed).role, "`none` is a dismissal, not a role")

        val future = "pl-maps-role-future-$s"
        seedPlace(future)
        seedRole(future, "gym", RoleSource.USER)
        assertNull(mapsPlaces().first { it.placeId == future }.role, "a role the d.ts doesn't name reads as none")
    }

    @Test
    fun observePlaces_pushesARename_andAnUnsubscribe_isNotAFailure() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val id = "pl-maps-push-$s"
        seedPlace(id, label = "Before $s")

        val seen = CompletableDeferred<Unit>()
        var failure: String? = null
        val stop = Maps.observePlaces(
            callback = { r ->
                if (r.ok == true && r.places.unsafeCast<Array<dynamic>>().any { it.placeId == id && it.name == "After $s" }) {
                    seen.complete(Unit)
                }
            },
            onError = { failure = it },
        )
        try {
            assertEquals(true, Maps.renamePlace(id, "After $s").await().ok)
            withTimeout(10.seconds) { seen.await() }
        } finally {
            stop()
        }
        delay(50)
        assertNull(failure, "the subscriber's own cancel is not reported as a failure")
    }

    /**
     * ⚠⚠ Nothing starts in the background (`PlacePlugin` KDoc). Two places in one identity cell are
     * exactly what `PlaceReconciler.reconcileAll()` would merge; reading them through every Maps
     * surface — snapshot, push, detail — must write no `PlaceMerge` row. (The observer allow-list is
     * `NoBackgroundWorkInATabTest`; `Maps` adds nothing to it.)
     */
    @Test
    fun readingMaps_mergesNothing_evenTwinsInOneCell() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val at = cell()
        val a = "pl-maps-twin-a-$s"
        val b = "pl-maps-twin-b-$s"
        seedPlace(a, at = at)
        seedPlace(b, at = at)

        val pushed = CompletableDeferred<Unit>()
        val stop = Maps.observePlaces({ pushed.complete(Unit) }, {})
        try {
            withTimeout(10.seconds) { pushed.await() }
            mapsPlaces(); mapsPlace(a); mapsPlace(b)
            delay(1500) // past any save-triggered coalesce window
        } finally {
            stop()
        }

        val ids = mapsPlaces().map { it.placeId as String }
        assertTrue(a in ids && b in ids, "both twins stay separate places: nothing folded them")
        val merges = client.placeService.merges.select { fields(PlaceMerge.Fields.PLACE_ID) }.getAll()
            .fold(onSuccess = { it.toList() }, onFailure = { throw AssertionError("merges read failed: $it") })
            .map { it.getFieldValue<TString>(PlaceMerge.Fields.PLACE_ID).get() }
        assertFalse(a in merges || b in merges, "a Maps read wrote a PlaceMerge row: $merges")
    }
}
