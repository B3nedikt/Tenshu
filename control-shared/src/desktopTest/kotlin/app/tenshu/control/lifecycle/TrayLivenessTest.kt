package app.tenshu.control.lifecycle

import app.tenshu.control.lifecycle.TrayLiveness.Action
import app.tenshu.control.lifecycle.TrayLiveness.Heartbeat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §4.2 decision table, row by row. The point of the split is that these run without a
 * menu bar, a tray process or a macOS host — the shelling out lives at the call site.
 */
class TrayLivenessTest {

    private val dir = Files.createTempDirectory("tenshu-tray-liveness").toFile()

    private val now = 1_756_800_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun secondsAgo(seconds: Long) = now - seconds * 1000

    // ── trayPgrepPattern: only the tray itself, never a process that mentions its path ──

    private val trayPath = "/Applications/TenshuControl.app/Contents/Helpers/tray-agent"

    private fun matches(commandLine: String) =
        Regex(TrayLiveness.trayPgrepPattern(trayPath)).containsMatchIn(commandLine)

    @Test
    fun pgrepPatternMatchesTheTrayItself() {
        assertTrue(matches(trayPath))
    }

    @Test
    fun pgrepPatternIgnoresProcessesThatMentionThePath() {
        assertFalse(matches("/bin/zsh -c pgrep -f $trayPath"))
        assertFalse(matches("codesign -dv $trayPath"))
        assertFalse(matches("$trayPath --debug"))
    }

    @Test
    fun pgrepPatternEscapesTheDots() {
        assertFalse(matches("/Applications/TenshuControlXapp/Contents/Helpers/tray-agent"))
    }

    // ── isHealthy: may a deferring Control leave the tray alone? ─────────────────────

    @Test
    fun freshVisibleHeartbeatIsHealthy() {
        assertTrue(TrayLiveness.isHealthy(Heartbeat(secondsAgo(5), visible = true), now))
    }

    @Test
    fun missingHeartbeatIsNotHealthy() {
        assertFalse(TrayLiveness.isHealthy(null, now))
    }

    @Test
    fun staleHeartbeatIsNotHealthy() {
        // A tray that exited leaves its last line behind; that is the case being revived.
        val stale = Heartbeat(secondsAgo(TrayLiveness.STALE_AFTER_SECONDS + 1), visible = true)
        assertFalse(TrayLiveness.isHealthy(stale, now))
    }

    @Test
    fun hiddenHeartbeatIsNotHealthy() {
        assertFalse(TrayLiveness.isHealthy(Heartbeat(secondsAgo(1), visible = false), now))
    }

    @Test
    fun futureHeartbeatIsHealthy() {
        assertTrue(TrayLiveness.isHealthy(Heartbeat(now + 60_000, visible = true), now))
    }

    // ── The table ────────────────────────────────────────────────────────────────────

    @Test
    fun noTrayProcessSpawns() {
        val decision = TrayLiveness.decide(
            trayPid = null,
            processAgeSeconds = null,
            heartbeat = null,
            nowEpochMillis = now,
        )
        assertEquals(Action.SPAWN, decision.action)
    }

    @Test
    fun freshVisibleHeartbeatSkips() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 600,
            heartbeat = Heartbeat(secondsAgo(4), visible = true),
            nowEpochMillis = now,
        )
        assertEquals(Action.SKIP, decision.action)
        assertTrue(decision.reason.contains("healthy"), decision.reason)
    }

    @Test
    fun staleHeartbeatReplaces() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 600,
            heartbeat = Heartbeat(secondsAgo(TrayLiveness.STALE_AFTER_SECONDS + 1), visible = true),
            nowEpochMillis = now,
        )
        assertEquals(Action.REPLACE, decision.action)
        assertTrue(decision.reason.contains("stale"), decision.reason)
    }

    @Test
    fun heartbeatExactlyAtTheStalenessBoundaryIsStillFresh() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 600,
            heartbeat = Heartbeat(secondsAgo(TrayLiveness.STALE_AFTER_SECONDS), visible = true),
            nowEpochMillis = now,
        )
        assertEquals(Action.SKIP, decision.action)
    }

    /** The 2026-08-31 case: a live process holding the menu bar with no icon on it. */
    @Test
    fun aliveButIconlessReplaces() {
        val decision = TrayLiveness.decide(
            trayPid = 24912,
            processAgeSeconds = 4 * 24 * 3600,
            heartbeat = Heartbeat(secondsAgo(2), visible = false),
            nowEpochMillis = now,
        )
        assertEquals(Action.REPLACE, decision.action)
        assertTrue(decision.reason.contains("no status item"), decision.reason)
        assertTrue(decision.reason.contains("24912"), decision.reason)
    }

    /** A tray built before TL-1 writes nothing — replaced once, on the first start after upgrade. */
    @Test
    fun noHeartbeatFromAnOldBuildReplaces() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 600,
            heartbeat = null,
            nowEpochMillis = now,
        )
        assertEquals(Action.REPLACE, decision.action)
    }

    // ── The grace period ─────────────────────────────────────────────────────────────

    @Test
    fun aJustSpawnedTrayIsNeverReplacedForSilence() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 1,
            heartbeat = null,
            nowEpochMillis = now,
        )
        assertEquals(Action.SKIP, decision.action)
        assertTrue(decision.reason.contains("grace"), decision.reason)
    }

    @Test
    fun graceAppliesEvenWhenTheHeartbeatSaysHidden() {
        // Placement legitimately takes a moment; §4.3's own retry owns that window, not a kill.
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 2,
            heartbeat = Heartbeat(secondsAgo(1), visible = false),
            nowEpochMillis = now,
        )
        assertEquals(Action.SKIP, decision.action)
    }

    @Test
    fun anUnreadableProcessAgeFallsBackToTheHeartbeat() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = null,
            heartbeat = Heartbeat(secondsAgo(2), visible = false),
            nowEpochMillis = now,
        )
        assertEquals(Action.REPLACE, decision.action)
    }

    /** ⚠ Clock skew must not read as death — killing a working tray is the worse outcome. */
    @Test
    fun aFutureDatedHeartbeatIsTreatedAsFresh() {
        val decision = TrayLiveness.decide(
            trayPid = 4242,
            processAgeSeconds = 600,
            heartbeat = Heartbeat(now + 60_000, visible = true),
            nowEpochMillis = now,
        )
        assertEquals(Action.SKIP, decision.action)
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────────

    @Test
    fun parsesBothVerdicts() {
        assertEquals(Heartbeat(1756800000000, true), Heartbeat.parse("1756800000000 visible"))
        assertEquals(Heartbeat(1756800000000, false), Heartbeat.parse("1756800000000 hidden\n"))
    }

    @Test
    fun rejectsMalformedHeartbeats() {
        assertNull(Heartbeat.parse(""))
        assertNull(Heartbeat.parse("1756800000000"))
        assertNull(Heartbeat.parse("1756800000000 maybe"))
        assertNull(Heartbeat.parse("not-a-number visible"))
        assertNull(Heartbeat.parse("1756800000000 visible extra"))
    }

    @Test
    fun readsHeartbeatFromDisk() {
        TrayLiveness.heartbeatFile(dir).writeText("1756800000000 visible\n")
        assertEquals(Heartbeat(1756800000000, true), TrayLiveness.readHeartbeat(dir))
    }

    @Test
    fun absentHeartbeatFileReadsAsNull() {
        assertNull(TrayLiveness.readHeartbeat(dir))
    }

    // ── `ps -o etime=` ───────────────────────────────────────────────────────────────

    @Test
    fun parsesEveryBsdElapsedShape() {
        assertEquals(31, TrayLiveness.parseElapsedSeconds("00:31"))
        assertEquals(90, TrayLiveness.parseElapsedSeconds(" 01:30 "))
        assertEquals(3661, TrayLiveness.parseElapsedSeconds("01:01:01"))
        assertEquals(4 * 86_400 + 3 * 3600 + 2 * 60 + 1, TrayLiveness.parseElapsedSeconds("4-03:02:01"))
    }

    /** ⚠ The macOS `ps` reality: `etimes` does not exist, so nothing must read as a valid age. */
    @Test
    fun rejectsMalformedElapsedTimes() {
        assertNull(TrayLiveness.parseElapsedSeconds(""))
        assertNull(TrayLiveness.parseElapsedSeconds("   "))
        assertNull(TrayLiveness.parseElapsedSeconds("31"))
        assertNull(TrayLiveness.parseElapsedSeconds("ps: etimes: keyword not found"))
        assertNull(TrayLiveness.parseElapsedSeconds("aa:bb"))
        assertNull(TrayLiveness.parseElapsedSeconds("1:2:3:4"))
    }

    // ── The decision log ─────────────────────────────────────────────────────────────

    @Test
    fun logAppendsAndCarriesTheReason() {
        TrayLiveness.appendLog(dir, TrayLiveness.Decision(Action.SPAWN, "no tray process"), now)
        TrayLiveness.appendLog(dir, TrayLiveness.Decision(Action.SKIP, "healthy"), now)

        val lines = File(dir, TrayLiveness.LOG_FILE).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("spawn"), lines[0])
        assertTrue(lines[0].contains("no tray process"), lines[0])
        // An ISO-8601 instant, so the line is sortable and unambiguous across timezones.
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").containsMatchIn(lines[0]), lines[0])
    }

    @Test
    fun logIsCappedAtTheMostRecentLines() {
        repeat(TrayLiveness.LOG_MAX_LINES + 25) { i ->
            TrayLiveness.appendLog(dir, TrayLiveness.Decision(Action.SKIP, "entry $i"), now)
        }

        val lines = File(dir, TrayLiveness.LOG_FILE).readLines()
        assertEquals(TrayLiveness.LOG_MAX_LINES, lines.size)
        assertTrue(lines.last().contains("entry ${TrayLiveness.LOG_MAX_LINES + 24}"), lines.last())
        assertTrue(lines.none { it.contains("entry 0 ") }, "oldest entry should have been dropped")
    }
}
