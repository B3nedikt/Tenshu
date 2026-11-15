package app.tenshu.control.lifecycle

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Whether Control should leave a running menu-bar tray alone, replace it, or start one —
 * and the diagnostic trail for that decision
 * (docs/proposals/desktop-tray-liveness.md §4.2 + §4.4).
 *
 * ⚠⚠ **Why this is not just `pgrep`.** It used to be. `ensureTrayRunning` guarded on the tray
 * *process existing*, and on 2026-08-31 a tray four days older than the Control that was
 * supposedly running it held the menu bar with no icon on it: Control restarted, saw a live
 * pid, and declined to spawn the tray that would have been visible. A process table cannot
 * see a menu bar, so presence was never the question worth asking. The tray now publishes
 * what only it can know — that its run loop is turning, and that AppKit actually placed its
 * status item — and this decides on *that*.
 *
 * Split out of `Main.kt` and kept free of process/AppKit calls so every row of the table in
 * §4.2 is unit-testable; the shelling out (`pgrep`, `ps`) stays at the call site.
 */
object TrayLiveness {

    /** The tray's heartbeat, written next to the daemon's bridge socket. */
    const val HEARTBEAT_FILE = "tray-agent.state"

    /** Control's append-only decision log, same directory. */
    const val LOG_FILE = "tray-agent.log"

    /** How often the tray republishes its state; mirrored in `TrayHeartbeat.swift`. */
    const val HEARTBEAT_INTERVAL_SECONDS = 10L

    /**
     * A heartbeat older than this means the tray's run loop is not turning. Three intervals,
     * so a single missed tick — a busy machine, a slow atomic write — is never fatal.
     */
    const val STALE_AFTER_SECONDS = HEARTBEAT_INTERVAL_SECONDS * 3

    /**
     * ⚠ A tray younger than this is never replaced, however silent. Without it, a tray that
     * has not yet had time to write its first heartbeat gets killed by the very Control that
     * just spawned it — a restart loop rather than a recovery.
     */
    const val GRACE_SECONDS = STALE_AFTER_SECONDS

    /** Lines kept in [LOG_FILE]; it is a breadcrumb trail, not a log stream. */
    const val LOG_MAX_LINES = 200

    enum class Action { SPAWN, SKIP, REPLACE }

    data class Decision(val action: Action, val reason: String)

    /** One line of [HEARTBEAT_FILE]: when the tray last ticked, and whether it has an icon. */
    data class Heartbeat(val atEpochMillis: Long, val visible: Boolean) {
        companion object {
            /**
             * Parses `<epochMillis> <visible|hidden>`. Null on anything else — a truncated,
             * empty or foreign file is "no heartbeat", never a healthy default.
             */
            fun parse(text: String): Heartbeat? {
                val parts = text.trim().split(Regex("\\s+"))
                if (parts.size != 2) return null
                val millis = parts[0].toLongOrNull() ?: return null
                val visible = when (parts[1].lowercase()) {
                    "visible" -> true
                    "hidden" -> false
                    else -> return null
                }
                return Heartbeat(millis, visible)
            }
        }
    }

    /**
     * Parses BSD `ps -o etime=` — `[[dd-]hh:]mm:ss` — into seconds.
     *
     * ⚠⚠ **macOS `ps` has no `etimes` column.** The seconds-valued one is GNU/procps; BSD `ps`
     * answers `ps: etimes: keyword not found` and prints nothing. An implementation that asks
     * for it therefore reads null *forever*, and the grace period it feeds silently never
     * applies — a guard that looks present in the source and does not exist at runtime. Caught
     * by reproducing the wedged-tray case on a real machine, not by reading the code.
     */
    fun parseElapsedSeconds(etime: String): Long? {
        val text = etime.trim()
        if (text.isEmpty()) return null
        val days: Long
        val clock: String
        if (text.contains('-')) {
            val (d, rest) = text.split("-", limit = 2)
            days = d.toLongOrNull() ?: return null
            clock = rest
        } else {
            days = 0
            clock = text
        }
        val parts = clock.split(":").map { it.toLongOrNull() ?: return null }
        val hours: Long
        val minutes: Long
        val seconds: Long
        when (parts.size) {
            2 -> { hours = 0; minutes = parts[0]; seconds = parts[1] }
            3 -> { hours = parts[0]; minutes = parts[1]; seconds = parts[2] }
            else -> return null
        }
        return days * 86_400 + hours * 3_600 + minutes * 60 + seconds
    }

    /**
     * A `pgrep -f` pattern (POSIX extended regex) matching a command line that is exactly
     * [trayPath] — the tray is spawned with no arguments. Anchored and escaped, so a process
     * that only mentions the path in its arguments is never mistaken for the tray.
     */
    fun trayPgrepPattern(trayPath: String): String =
        "^" + trayPath.replace(Regex("""[.\[\]()*+?^$|{}\\]""")) { "\\" + it.value } + "$"

    fun heartbeatFile(socketDir: File): File = File(socketDir, HEARTBEAT_FILE)

    /** Reads the heartbeat, or null when absent/unreadable/malformed. */
    fun readHeartbeat(socketDir: File): Heartbeat? =
        runCatching { Heartbeat.parse(heartbeatFile(socketDir).readText()) }.getOrNull()

    /**
     * True when a tray is ticking with its icon placed. Says nothing about *which* bundle's
     * tray it is — the heartbeat file is shared by every install channel — which is exactly
     * what a Control that defers to another one needs: it must not add a second icon beside a
     * working one, whoever owns it.
     */
    fun isHealthy(heartbeat: Heartbeat?, nowEpochMillis: Long): Boolean {
        if (heartbeat == null || !heartbeat.visible) return false
        val ageSeconds = ((nowEpochMillis - heartbeat.atEpochMillis) / 1000).coerceAtLeast(0)
        return ageSeconds <= STALE_AFTER_SECONDS
    }

    /**
     * The §4.2 table.
     *
     * @param trayPid the running tray agent's pid, or null when none is running.
     * @param processAgeSeconds how long that process has been up (`ps -o etimes=`), or null
     *   when it could not be read — in which case the grace period cannot be applied and the
     *   heartbeat alone decides.
     * @param heartbeat the tray's last published state, or null when it has published none.
     */
    fun decide(
        trayPid: Long?,
        processAgeSeconds: Long?,
        heartbeat: Heartbeat?,
        nowEpochMillis: Long,
    ): Decision {
        if (trayPid == null) return Decision(Action.SPAWN, "no tray process")

        if (processAgeSeconds != null && processAgeSeconds < GRACE_SECONDS) {
            return Decision(
                Action.SKIP,
                "tray pid $trayPid is ${processAgeSeconds}s old, inside the ${GRACE_SECONDS}s grace period",
            )
        }

        if (heartbeat == null) {
            return Decision(
                Action.REPLACE,
                "tray pid $trayPid publishes no heartbeat (wedged, or a build from before TL-1)",
            )
        }

        // ⚠ Clamped at zero: a heartbeat dated in the future is a clock that moved, not a
        // dead tray, and killing a working agent over skew is the worse failure.
        val ageSeconds = ((nowEpochMillis - heartbeat.atEpochMillis) / 1000).coerceAtLeast(0)

        if (ageSeconds > STALE_AFTER_SECONDS) {
            return Decision(
                Action.REPLACE,
                "tray pid $trayPid heartbeat is ${ageSeconds}s old (stale past ${STALE_AFTER_SECONDS}s)",
            )
        }

        if (!heartbeat.visible) {
            return Decision(
                Action.REPLACE,
                "tray pid $trayPid is alive (${ageSeconds}s) but AppKit placed no status item",
            )
        }

        return Decision(Action.SKIP, "tray pid $trayPid healthy (heartbeat ${ageSeconds}s old, visible)")
    }

    /**
     * Appends one decision to [LOG_FILE], keeping the last [LOG_MAX_LINES].
     *
     * ⚠⚠ The daemon's stdout under launchd goes **nowhere** — no file, and `log show` returns
     * nothing for it — so a tray that vanishes at login leaves behind exactly one process
     * table and no reason. That is what made the original diagnosis a guess. This is a
     * deliberately dumb capped file, to be deleted when `core-logging-facade` lands.
     *
     * Best-effort: a diagnostic that can break startup is worse than no diagnostic.
     */
    fun appendLog(socketDir: File, decision: Decision, nowEpochMillis: Long) {
        runCatching {
            socketDir.mkdirs()
            val stamp = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(nowEpochMillis))
            val line = "$stamp ${decision.action.name.lowercase().padEnd(7)} ${decision.reason}"
            val file = File(socketDir, LOG_FILE)
            val kept = (if (file.exists()) file.readLines() else emptyList()) + line
            file.writeText(kept.takeLast(LOG_MAX_LINES).joinToString("\n") + "\n")
        }
    }
}
