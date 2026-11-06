package app.tenshu.calendar.data.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.calendar.common.ics.ImportedEvent

/** `ExternalCopyBatches` — a copy's calls must each fit one Binder transaction. */
class ExternalCopyBatchesTest {

    private fun event(uid: String, notes: String = "", raw: String = "") = ImportedEvent(
        uid = uid,
        title = uid,
        startLocal = "2026-07-01T09:00",
        endLocal = "2026-07-01T10:00",
        timeZoneId = "Europe/Berlin",
        allDay = false,
        location = "",
        notes = notes,
        recurrenceRule = "",
        exceptionDates = "",
        recurrenceId = null,
        reminderMinutes = listOf(10),
        raw = raw,
    )

    /** ⚠⚠ Codex's case: 200 events with 3 KB descriptions, each carried twice, pass ~1 MB. */
    @Test
    fun long_descriptions_split_a_batch_by_bytes_before_the_count_cap() {
        val text = "x".repeat(3_000)
        val events = (1..200).map { event("e$it", notes = text, raw = "DESCRIPTION:$text") }

        val plan = ExternalCopyBatches.plan(events)

        assertTrue(plan.batches.size > 1, "one 200-event batch would be over a megabyte")
        plan.batches.forEach { batch ->
            assertTrue(batch.sumOf { ExternalCopyBatches.sizeOf(it.event, it.withSource) } <= ExternalCopyBatches.BUDGET_BYTES)
        }
        assertEquals(events, plan.batches.flatten().map { it.event }, "every event, in order")
        assertTrue(plan.batches.flatten().all { it.withSource })
    }

    @Test
    fun small_events_still_stop_at_the_count_cap() {
        val plan = ExternalCopyBatches.plan((1..450).map { event("e$it") }, budget = Int.MAX_VALUE)
        assertEquals(listOf(200, 200, 50), plan.batches.map { it.size })
    }

    /** ⚠ Too big with its sidecar: the event still goes, the export-only raw copy does not. */
    @Test
    fun an_oversized_sidecar_is_dropped_before_the_event_is() {
        val big = event("big", notes = "short", raw = "y".repeat(ExternalCopyBatches.BUDGET_BYTES))
        val plan = ExternalCopyBatches.plan(listOf(event("a"), big))

        val planned = plan.batches.flatten().single { it.event.uid == "big" }
        assertFalse(planned.withSource)
        assertTrue(plan.tooLarge.isEmpty())
    }

    /** ⚠ Too big even alone: left out and reported, so it cannot fail the rest of the copy. */
    @Test
    fun an_event_too_big_on_its_own_is_left_out_not_fatal() {
        val huge = event("huge", notes = "z".repeat(ExternalCopyBatches.BUDGET_BYTES))
        val plan = ExternalCopyBatches.plan(listOf(event("a"), huge, event("b")))

        assertEquals(listOf("huge"), plan.tooLarge.map { it.uid })
        assertEquals(listOf("a", "b"), plan.batches.flatten().map { it.event.uid })
    }

    /** Multi-byte text counts as bytes, not characters. */
    @Test
    fun the_estimate_counts_utf8_bytes() {
        val ascii = ExternalCopyBatches.sizeOf(event("a", notes = "a".repeat(100)), withSource = false)
        val emoji = ExternalCopyBatches.sizeOf(event("a", notes = "😀".repeat(100)), withSource = false)
        assertEquals(300, emoji - ascii)
    }
}
