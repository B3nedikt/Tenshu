package app.tenshu.calendar.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.client.calendarService
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold

/**
 * Calendar's **one** Control-reachability engine (`fit-app.md` §9.14 recipe,
 * `:app-reachability`) — built once per runtime so every read feeds one shell
 * signal rather than one signal per screen.
 *
 * ⚠ The probe is what detects the problem, not the reads. A reactive read against a
 * dead transport does not reliably fail — it *hangs* — so a shell waiting for a read
 * to report trouble waits forever. This is the cheapest round trip available here:
 * one calendar row, one field.
 *
 * ⚠⚠ It reads **calendars, not occurrences**. Expanding a recurrence window is real
 * work on the proxy side, and a health check that costs more than the screen it
 * protects is a health check that gets turned off. A realm with no calendars still
 * answers `Success(empty)`, which is the distinction that matters: the fold is on
 * whether Control *replied*, never on whether it had anything to say.
 *
 * [probeScope] `null` (tests, previews) disables the probe; the signal then tracks
 * only organic read outcomes.
 */
fun calendarControlReachability(
    client: TenshuClient,
    probeScope: CoroutineScope?,
    probeIntervalMs: Long = 5_000,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b), so the shell banner can say
     * *update Control* rather than *can't reach Control* when the proxy is answering and refusing.
     * ⚠ Absent (tests, previews) every outage reads as a transport problem — the pre-SK-1
     * behaviour, not a crash.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (SK-3) and the proxy's published host identity (SK-2).
     * ⚠⚠ Absent, a fenced device renders no banner at all — it answers every read and looks
     * healthy while silently receiving nothing.
     */
    schemaFence: StateFlow<List<String>>? = null,
    proxyHost: StateFlow<ProxyHost?>? = null,
    /**
     * Whether Control's device has a realm (ADR 0025 §Decision 8) — wired on Android/desktop to
     * `syncService.getSyncStatus()`. ⚠ Absent (iOS route-1, tests, previews), `NotSetUp` never
     * fires.
     */
    controlHasRealm: app.tenshu.reachability.RealmProbe,
): ControlReachability = ControlReachability(
    probe = {
        try {
            client.calendarService.calendars.take(1)
                .select { fields(Calendar.Field.CALENDAR_ID) }.getAll()
                .fold(onSuccess = { true }, onFailure = { false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold.
            false
        }
    },
    scope = probeScope,
    probeIntervalMs = probeIntervalMs,
    // R4's transport signal: reports the outage instantly instead of after a probe interval,
    // and suppresses probing across a channel that cannot carry one.
    transportConnected = client.proxyConnected,
    schemaSkew = schemaSkew,
    schemaFence = schemaFence,
    proxyHost = proxyHost,
    // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
    // it" — an instruction naming an app that is not on the device. `null` (desktop today, every
    // in-process runtime) leaves the outage worded exactly as before.
    controlInstalled = { client.controlInstalled() },
    // One event per call the proxy served, used to WAKE the probe rather than to answer for it —
    // see `ControlReachability.answered`. This is what replaced the repositories' hand-written
    // `markReachable` calls.
    answered = client.proxyAnswered,
    controlHasRealm = controlHasRealm,
)
