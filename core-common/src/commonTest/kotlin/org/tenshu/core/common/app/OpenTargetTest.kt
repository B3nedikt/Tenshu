package org.tenshu.core.common.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TenshuException
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.OpenOutcome
import org.tenshu.core.app.OpenTarget
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.common.TestProxy

import org.tenshu.core.common.app.proxy.TargetDelivery
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.app.proxy.appOpenTargetImplementationFactory
import org.tenshu.core.common.app.proxy.initAppService
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.collection.list.toList
import org.tenshu.core.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OpenTarget` — the resolution half (`control-cross-app-open.md` TT-1a).
 *
 * ⚠⚠ **This slice launches nothing and delivers nothing**, so what is under test is the part that
 * decides: which app owns a type, whether it is installed *on this device*, and whether every
 * refusal names the **handler** rather than the row. The last one is the security property — a
 * reason that leaked "no such row" would turn this op into an existence oracle (CO-D6).
 */
class OpenTargetTest {

    private val deviceId = "device-A"
    private val calendarAppId = "app.tenshu.calendar"

    /** A delivery that records what it was handed. `null` from [deliver] means "never reachable". */
    private class RecordingDelivery(
        private val result: TargetDelivery.Delivered? = TargetDelivery.Delivered(launchTicketId = null),
    ) : TargetDelivery {
        var seenAppId: String? = null
        var seenTarget: DataId? = null
        var seenCaller: ClientConnection? = null

        override suspend fun deliver(
            appId: String,
            request: OpenTargetRequest,
            caller: ClientConnection,
        ): TargetDelivery.Delivered? {
            seenAppId = appId
            seenTarget = request.target
            seenCaller = caller
            return result
        }
    }

    @Test
    fun aRegisteredHandlerReceivesTheTargetAndTheOutcomeNamesIt() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar()
        val delivery = RecordingDelivery()

        val outcome = proxy.open(occurrence(proxy), delivery)

        assertEquals(calendarAppId, outcome.appId.get())
        // ⚠ Empty = Control handed it over itself; a non-empty id would mean the caller still has
        // a launch to fire (`control-cross-app-open.md` §5c).
        assertEquals("", outcome.launchTicketId.get())
        // ⚠ The appId, never a package or a bundle id — those are per-platform renderings the
        // launch seam resolves, and putting one on the wire would make the op platform-specific.
        assertEquals(calendarAppId, delivery.seenAppId)
    }

    @Test
    fun theOccurrenceSurvivesAsACompositeEntityDataId() = runTest {
        // ⚠⚠ The round-trip TT-1a owes: `CompositeEntityDataId` is registered and has a DTO, and
        // **nothing in the codebase used it before this slice**. A `DataId` that arrived flattened
        // to a string, or as the wrong variant, would still "work" right up until the receiving
        // app tried to read two key parts out of it.
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar()
        val delivery = RecordingDelivery()

        proxy.open(occurrence(proxy), delivery)

        val seen = delivery.seenTarget as? DataId.CompositeEntityDataId
        assertTrue(seen != null, "target did not survive as a CompositeEntityDataId")
        assertEquals(HandlerTable.OCCURRENCE_TYPE, seen.type.get())
        val parts = seen.keyValues.toList().filterIsInstance<DataId.StringDataId>()
        assertEquals(listOf("evt-7", "2026-07-16T10:00"), parts.map { part -> part.keyValue.get() })
    }

    @Test
    fun anUnknownTypeIsNoHandler() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar()

        val error = proxy.openFailing(
            DataId.StringDataId.create(proxy.tContext, type = "tenshu/nobody/knows", keyValue = "x"),
            RecordingDelivery(),
        )

        assertEquals(OpenTargetUnavailable.Reasons.NO_HANDLER, error.reason.get())
    }

    @Test
    fun aHandlerThatIsNotRegisteredOnThisDeviceIsRefusedBeforeAnythingIsAttempted() = runTest {
        // ⚠ No `AppRegistration` row: Calendar exists as a product, just not here.
        val proxy = createProxy(backgroundScope)
        val delivery = RecordingDelivery()

        val error = proxy.openFailing(occurrence(proxy), delivery)

        assertEquals(OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE, error.reason.get())
        assertNull(delivery.seenAppId, "delivery was attempted for an unregistered app")
    }

    @Test
    fun aRemovedRegistrationIsNotInstalled() = runTest {
        // ⚠⚠ `RemoveApp` keeps a tombstone rather than deleting the row (§5.1), so a presence
        // check that only asked "is there a row" would cheerfully open an app the user removed.
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar(state = AppRegistration.State.REMOVED)

        val error = proxy.openFailing(occurrence(proxy), RecordingDelivery())

        assertEquals(OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE, error.reason.get())
    }

    @Test
    fun withNoDeliveryBoundTheRefusalSaysSoRatherThanClaimingAnOpen() = runTest {
        // ⚠⚠ TT-1a's own state, and the reason `NO_DELIVERY` exists: returning an `OpenOutcome`
        // naming an app that was never told anything would be a success claim for something that
        // did not happen. It is distinct from LAUNCH_FAILED, which means something WAS attempted.
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar()

        val error = proxy.openFailing(occurrence(proxy), delivery = null)

        assertEquals(OpenTargetUnavailable.Reasons.NO_DELIVERY, error.reason.get())
    }

    @Test
    fun aHandlerThatNeverBecomesReachableIsALaunchFailure() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerCalendar()

        val error = proxy.openFailing(occurrence(proxy), RecordingDelivery(result = null))

        assertEquals(OpenTargetUnavailable.Reasons.LAUNCH_FAILED, error.reason.get())
    }

    @Test
    fun noRefusalRevealsWhetherTheRowExists() = runTest {
        // ⚠⚠ CO-D6's existence oracle, asserted rather than asserted-in-prose. Two ids of the same
        // type — one naming a plausible row, one naming nonsense — must be indistinguishable.
        val proxy = createProxy(backgroundScope)
        val real = proxy.openFailing(occurrence(proxy), RecordingDelivery())
        val invented = proxy.openFailing(
            occurrence(proxy, eventId = "no-such-event", recurrenceId = "1999-01-01T00:00"),
            RecordingDelivery(),
        )

        assertEquals(real.reason.get(), invented.reason.get())
        assertEquals(real.message.get(), invented.message.get())
    }

    // ---- harness ---------------------------------------------------------------

    private fun createProxy(scope: CoroutineScope): TestProxy =
        TestProxy(coroutineScope = scope).also { it.executionContext.initAppService() }

    /** The calendar occurrence a Mia citation would carry — `[eventId, recurrenceId]`. */
    private fun occurrence(
        proxy: TestProxy,
        eventId: String = "evt-7",
        recurrenceId: String = "2026-07-16T10:00",
    ): DataId = DataId.CompositeEntityDataId.create(
        context = proxy.tContext,
        type = HandlerTable.OCCURRENCE_TYPE,
        keyValues = listOf(
            DataId.StringDataId.create(
                proxy.tContext,
                type = "${HandlerTable.OCCURRENCE_TYPE}/eventId",
                keyValue = eventId,
            ),
            DataId.StringDataId.create(
                proxy.tContext,
                type = "${HandlerTable.OCCURRENCE_TYPE}/recurrenceId",
                keyValue = recurrenceId,
            ),
        ),
    )

    @Test
    fun aPlaceGoesToTimelineWhenBothItAndMapsAreInstalled() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.timeline")
        proxy.registerApp("app.tenshu.maps")
        val delivery = RecordingDelivery()

        val outcome = proxy.open(placeId(proxy), delivery)

        // ⚠⚠ The table's ORDER decides, not the store's. Iterating registrations instead would make
        // the handler depend on which app happened to register first, which is not a preference
        // anyone expressed.
        assertEquals("app.tenshu.timeline", outcome.appId.get())
    }

    @Test
    fun aPlaceFallsBackToMapsWhenTimelineIsNotInstalled() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.maps")
        val delivery = RecordingDelivery()

        val outcome = proxy.open(placeId(proxy), delivery)

        // ⭐ The whole point of a candidate LIST: a user with Maps and no Timeline still gets their
        // place opened, instead of being told nothing on this device can show it.
        assertEquals("app.tenshu.maps", outcome.appId.get())
    }

    @Test
    fun aPlaceRefusesWhenNeitherCandidateIsInstalled() = runTest {
        val proxy = createProxy(backgroundScope)
        val delivery = RecordingDelivery()

        val error = proxy.openFailing(placeId(proxy), delivery)

        assertEquals(OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE, error.reason.get())
        // ⚠ The message names BOTH, because "install Timeline" and "install Timeline or Maps" are
        // different instructions and only the second is true.
        val message = error.message.get()
        assertTrue(message.contains("app.tenshu.timeline"), message)
        assertTrue(message.contains("app.tenshu.maps"), message)
        assertNull(delivery.seenAppId, "delivery was attempted with nothing installed")
    }

    @Test
    fun aRemovedCandidateIsSkippedRatherThanChosen() = runTest {
        val proxy = createProxy(backgroundScope)
        // ⚠ `Removed` is a tombstone, not an absence (§5.1) — a fallback that only asked "is there
        // a row" would open an app the user deliberately removed, in preference to one they kept.
        proxy.registerApp("app.tenshu.timeline", AppRegistration.State.REMOVED)
        proxy.registerApp("app.tenshu.maps")

        assertEquals("app.tenshu.maps", proxy.open(placeId(proxy), RecordingDelivery()).appId.get())
    }

    // ---- TT-4: the user's primary-handler choice ------------------------------

    @Test
    fun theUsersChoiceOverridesTheTablesOrder() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.timeline")
        proxy.registerApp("app.tenshu.maps")

        val outcome = proxy.open(
            placeId(proxy),
            RecordingDelivery(),
            preferred = mapOf(HandlerTable.PLACE_TYPE to "app.tenshu.maps"),
        )

        // ⚠⚠ Both apps are installed, so without the preference the table's order wins and this is
        // Timeline — which is what `aPlaceGoesToTimelineWhenBothItAndMapsAreInstalled` asserts.
        // Asserting the SAME fixture with the preference added is what makes this test about the
        // preference rather than about the fixture.
        assertEquals("app.tenshu.maps", outcome.appId.get())
    }

    @Test
    fun aPreferredAppThatIsNotInstalledHereFallsThroughInsteadOfFailing() = runTest {
        // ⭐⭐ The reason the choice is a RE-ORDERING and not an override. A user who picked Maps on
        // their laptop still has places open on a phone that only has Timeline — an override would
        // turn one setting into a broken feature on every device the chosen app is missing from.
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.timeline")

        val outcome = proxy.open(
            placeId(proxy),
            RecordingDelivery(),
            preferred = mapOf(HandlerTable.PLACE_TYPE to "app.tenshu.maps"),
        )

        assertEquals("app.tenshu.timeline", outcome.appId.get())
    }

    @Test
    fun aPreferenceNamingAnAppThatCannotShowTheTypeIsIgnored() = runTest {
        // ⚠⚠ The table is a claim that an app has a RECEIVING ROUTE (§5f.2). Honouring an arbitrary
        // appId would let a stale or hand-edited row send a place to Calendar, which would launch
        // and then show nothing — trading an honest refusal for a silent dead end.
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.timeline")
        proxy.registerApp(calendarAppId)

        val outcome = proxy.open(
            placeId(proxy),
            RecordingDelivery(),
            preferred = mapOf(HandlerTable.PLACE_TYPE to calendarAppId),
        )

        assertEquals("app.tenshu.timeline", outcome.appId.get())
    }

    @Test
    fun aPreferenceForAnotherTypeDoesNotLeakIntoThisOne() = runTest {
        val proxy = createProxy(backgroundScope)
        proxy.registerApp("app.tenshu.timeline")
        proxy.registerApp("app.tenshu.maps")

        val outcome = proxy.open(
            placeId(proxy),
            RecordingDelivery(),
            preferred = mapOf(HandlerTable.OCCURRENCE_TYPE to "app.tenshu.maps"),
        )

        assertEquals("app.tenshu.timeline", outcome.appId.get())
    }

    private fun placeId(proxy: TestProxy): DataId = DataId.StringDataId.create(
        context = proxy.tContext,
        type = HandlerTable.PLACE_TYPE,
        keyValue = "place-7",
    )

    private fun TestProxy.registerCalendar(state: String = AppRegistration.State.ACTIVE) =
        registerApp(calendarAppId, state)

    private fun TestProxy.registerApp(
        appId: String,
        state: String = AppRegistration.State.ACTIVE,
    ) {
        executionContext.saveData(
            AppRegistration.create(
                context = tContext,
                appId = appId,
                deviceId = deviceId,
                createdAt = 1_000L,
                state = state,
                verified = true,
            )
        )
    }

    private suspend fun TestProxy.open(
        target: DataId,
        delivery: TargetDelivery?,
        preferred: Map<String, String> = emptyMap(),
    ): OpenOutcome {
        val implementation = appOpenTargetImplementationFactory(
            id = OpenTarget.ID,
            deviceId = { deviceId },
            delivery = delivery,
            preferredHandlerFor = { _, type -> preferred[type] },
        ) as AsyncOperationImplementation
        return implementation.execute<OpenOutcome>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(OpenTarget.Params.TARGET to target),
        )!!
    }

    private suspend fun TestProxy.openFailing(
        target: DataId,
        delivery: TargetDelivery?,
        preferred: Map<String, String> = emptyMap(),
    ): OpenTargetUnavailable {
        val thrown = assertFailsWith<TenshuException> { open(target, delivery, preferred) }
        return thrown.error as OpenTargetUnavailable
    }
}
