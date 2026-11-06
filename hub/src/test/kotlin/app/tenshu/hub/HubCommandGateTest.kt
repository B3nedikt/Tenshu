package app.tenshu.hub

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** H4: the pure at-most-once decision table + the durable consumed journal. */
class HubCommandGateTest {

    private val now = 1_000_000_000L

    private fun decide(
        target: String = "hub-1",
        own: String = "hub-1",
        issuedAt: Long = now,
        consumed: Boolean = false,
    ) = HubCommandGate.decide(
        targetDeviceId = target,
        issuedAtEpochMs = issuedAt,
        ownDeviceId = own,
        nowEpochMs = now,
        alreadyConsumed = consumed,
    )

    @Test
    fun freshCommandForThisDevice_executes() {
        assertEquals(HubCommandGate.Decision.Execute, decide())
    }

    @Test
    fun commandForAnotherDevice_isIgnored() {
        assertEquals(HubCommandGate.Decision.NotOurs, decide(target = "other-hub"))
    }

    @Test
    fun consumedCommand_isIdempotentNoOp() {
        assertEquals(HubCommandGate.Decision.AlreadyConsumed, decide(consumed = true))
    }

    @Test
    fun commandOlderThanSanityWindow_isStale() {
        // The backlog-replay guard: a hub restored from sync history must not
        // replay a restart storm.
        assertEquals(
            HubCommandGate.Decision.Stale,
            decide(issuedAt = now - HubCommandGate.DEFAULT_STALE_WINDOW_MS - 1),
        )
    }

    @Test
    fun commandExactlyAtWindowEdge_stillExecutes() {
        assertEquals(
            HubCommandGate.Decision.Execute,
            decide(issuedAt = now - HubCommandGate.DEFAULT_STALE_WINDOW_MS),
        )
    }

    @Test
    fun futureIssuedAt_executes() {
        // Modest clock skew between issuer and hub must not lose commands.
        assertEquals(HubCommandGate.Decision.Execute, decide(issuedAt = now + 60_000))
    }

    @Test
    fun misTargetedWins_overConsumedAndStale() {
        assertEquals(
            HubCommandGate.Decision.NotOurs,
            decide(target = "other-hub", consumed = true, issuedAt = 0L),
        )
    }
}

class ConsumedCommandJournalTest {

    @Test
    fun markConsumed_isDurableAcrossInstances() {
        val dir = createTempDirectory("hub-journal-test").toFile()
        try {
            val file = File(dir, "consumed-commands.log")
            val journal = ConsumedCommandJournal(file)
            assertFalse(journal.isConsumed("cmd-1"))
            journal.markConsumed("cmd-1")
            journal.markConsumed("cmd-2")
            assertTrue(journal.isConsumed("cmd-1"))

            // Fresh instance over the same file — the restart case.
            val reloaded = ConsumedCommandJournal(file)
            assertTrue(reloaded.isConsumed("cmd-1"))
            assertTrue(reloaded.isConsumed("cmd-2"))
            assertFalse(reloaded.isConsumed("cmd-3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateMark_appendsOnlyOnce() {
        val dir = createTempDirectory("hub-journal-test").toFile()
        try {
            val file = File(dir, "consumed-commands.log")
            val journal = ConsumedCommandJournal(file)
            journal.markConsumed("cmd-1")
            journal.markConsumed("cmd-1")
            assertEquals(listOf("cmd-1"), file.readLines().filter { it.isNotBlank() })
        } finally {
            dir.deleteRecursively()
        }
    }
}
