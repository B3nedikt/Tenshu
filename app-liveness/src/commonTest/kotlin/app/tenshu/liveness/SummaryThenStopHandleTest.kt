package app.tenshu.liveness

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stop/notify ORDER is the whole fix (2026-10-30, a stuck un-dismissable "Importing…" card in
 * Fit): the ongoing card must be REMOVED with the foreground state — never detached and then
 * overwritten by an update Android may drop — and the summary must be its own post.
 */
class SummaryThenStopHandleTest {

    private val calls = mutableListOf<String>()
    private var promotionAllowed = true

    private val ops = object : ForegroundNotificationOps {
        override fun promote(content: LivenessNotificationContent): Boolean {
            calls += "promote:${content.text}"
            return promotionAllowed
        }
        override fun updateOngoing(content: LivenessNotificationContent) { calls += "update:${content.text}" }
        override fun removeOngoing() { calls += "removeOngoing" }
        override fun postSummary(content: LivenessNotificationContent) { calls += "summary:${content.text}" }
        override fun cancelSummary() { calls += "cancelSummary" }
        override fun stopService() { calls += "stop" }
    }

    private val handle = SummaryThenStopHandle(ops)

    private fun content(text: String, phase: LivenessPhase = LivenessPhase.IN_FLIGHT) =
        LivenessNotificationContent("t", text, phase)

    @Test
    fun terminalRemovesTheOngoingCardThenPostsTheSummaryThenStops() {
        handle.onActive(content("0"))
        handle.onContentChanged(content("18"))
        handle.onTerminal(content("done", LivenessPhase.TERMINAL))
        assertEquals(
            listOf("cancelSummary", "promote:0", "update:18", "removeOngoing", "summary:done", "stop"),
            calls,
        )
    }

    @Test
    fun nothingUpdatesTheOngoingCardAfterTheTerminalEdge() {
        // A stray in-flight refresh after the summary must re-promote (a new run), never post an
        // ongoing card with no foreground service behind it.
        handle.onActive(content("0"))
        handle.onTerminal(content("done", LivenessPhase.TERMINAL))
        calls.clear()
        handle.onContentChanged(content("late"))
        assertEquals(listOf("promote:late"), calls)
    }

    @Test
    fun aNewRunClearsTheLastRunsSummaryBeforePromoting() {
        handle.onActive(content("0"))
        handle.onTerminal(content("done", LivenessPhase.TERMINAL))
        calls.clear()
        handle.onActive(content("again"))
        assertEquals(listOf("cancelSummary", "promote:again"), calls)
    }

    @Test
    fun aRefusedPromotionNeverPostsAnOngoingCardAndRetriesOnTheNextRefresh() {
        promotionAllowed = false
        handle.onActive(content("0"))
        handle.onContentChanged(content("1"))
        promotionAllowed = true
        handle.onContentChanged(content("2"))
        handle.onContentChanged(content("3"))
        assertEquals(listOf("cancelSummary", "promote:0", "promote:1", "promote:2", "update:3"), calls)
    }

    @Test
    fun idleRemovesAndStopsWithoutASummary() {
        handle.onIdle()
        assertEquals(listOf("removeOngoing", "stop"), calls)
    }

    @Test
    fun throughTheLivenessEdgesATakeoutRunEndsWithNoOngoingCard() {
        // End to end over the real edge logic: per-record ticks, then Imported — the run from the bug.
        val liveness = NotificationLiveness<Pair<String, LivenessPhase>?>(
            kotlinx.coroutines.flow.MutableStateFlow(null),
            { it?.let { (text, phase) -> content(text, phase) } },
            handle,
            minContentChangeInterval = kotlin.time.Duration.INFINITE,
        )
        liveness.onState("Starting…" to LivenessPhase.IN_FLIGHT)
        for (n in 1..18) liveness.onState("$n records so far" to LivenessPhase.IN_FLIGHT)
        liveness.onState("18 records imported" to LivenessPhase.TERMINAL)
        assertEquals(
            listOf("cancelSummary", "promote:Starting…", "removeOngoing", "summary:18 records imported", "stop"),
            calls,
        )
    }
}
