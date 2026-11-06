package app.tenshu.reachability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.app.SchemaSkew
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The announce → reachability adapter (`core-schema-skew-surface.md` SK-1).
 *
 * ⚠⚠ The mapping is pinned case by case on purpose. It is a total function over three enum values
 * — exactly the shape that gets quietly copied into a second app's DI and then drifts, leaving
 * two apps naming different binaries for the same skew. One definition, one test.
 */
class SchemaSkewSourceTest {

    private fun skew(direction: SchemaSkew.Direction) =
        SchemaSkew(appId = "app.tenshu.fit", direction = direction, serviceIds = listOf("tenshu/x"))

    /**
     * ⚠⚠ The inversion that matters. The *client* being ahead means the **proxy** is the stale
     * one — so a device in this state must be told to update Control, not the app showing the
     * banner. Getting this backwards sends the user to update something that is already current.
     */
    @Test
    fun aClientAheadOfItsProxyPointsAtControl() {
        assertEquals(
            UpdateTarget.CONTROL,
            skew(SchemaSkew.Direction.PROXY_BEHIND).toUnreachableReason().update,
        )
    }

    @Test
    fun aClientBehindItsProxyPointsAtTheApp() {
        assertEquals(
            UpdateTarget.THIS_APP,
            skew(SchemaSkew.Direction.CLIENT_BEHIND).toUnreachableReason().update,
        )
    }

    @Test
    fun divergentBuildsPointAtBoth() {
        assertEquals(
            UpdateTarget.BOTH,
            skew(SchemaSkew.Direction.BOTH).toUnreachableReason().update,
        )
    }

    @Test
    fun theServiceIdsSurviveTheAdaptation() {
        assertEquals(
            listOf("tenshu/x"),
            skew(SchemaSkew.Direction.BOTH).toUnreachableReason().serviceIds,
        )
    }

    /**
     * ⚠⚠ The wiring's own failure mode. A source that never mirrors compiles, runs, and reports
     * every skewed device as a transport outage — i.e. it looks exactly like the bug it was added
     * to fix. This asserts the seam actually carries a value.
     */
    @Test
    fun anUnboundSourceIsEmptyAndABoundOneMirrors() = runTest {
        val source = MutableSchemaSkewSource()
        assertEquals(null, source.flow.value, "nothing bound yet must not fabricate a verdict")

        val announcer = FakeAnnouncer()
        // ⚠ Unconfined + explicitly cancelled. The mirror is an endless collect, so the test's own
        // scope would make `runTest` wait out its timeout on a coroutine that by design never
        // ends; unconfined also means the collector subscribes eagerly rather than at the next
        // scheduler pass, which is what lets the assertions read a settled value.
        val bindScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        source.bindTo(announcer.skew, bindScope)

        announcer.emit(skew(SchemaSkew.Direction.PROXY_BEHIND))
        assertEquals(UpdateTarget.CONTROL, source.flow.value?.update)

        // ⚠ And it must clear: an admitted announce means the user fixed it, and a verdict that
        // outlives its fix keeps telling them to update what they already updated.
        announcer.emit(null)
        assertEquals(null, source.flow.value)

        bindScope.cancel()
    }
}

/** A stand-in for `SchemaAnnouncer`, which needs a live client to construct. */
private class FakeAnnouncer {
    val skew = kotlinx.coroutines.flow.MutableStateFlow<SchemaSkew?>(null)
    fun emit(value: SchemaSkew?) {
        skew.value = value
    }
}
