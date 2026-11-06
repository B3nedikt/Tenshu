package app.tenshu.photos.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.file.client.fileService
import org.tenshu.core.fold

/**
 * Photos' **one** Control-reachability engine (the `fit-app.md` §9.14 recipe, `:app-reachability`)
 * — a Koin single, so the feed's pages and the probe feed one signal rather than one per read.
 *
 * ⚠ The probe matters more than it looks: a read against a dead transport does not reliably fail,
 * it **hangs**, so waiting for the feed to report the problem waits forever.
 *
 * ⚠⚠ And it must be the cheapest round trip available — **one row of the root folder**, not a feed
 * page. A page is a full-tree walk of the realm; running one every five seconds to answer "is
 * Control up" would make the health check the most expensive thing the app does.
 *
 * [probeScope] `null` (tests, previews) disables the probe; the signal then tracks only organic
 * read outcomes.
 */
fun photosControlReachability(
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
            client.fileService.defaultFileSystem.root
                .files
                .take(1)
                .select { fields(File.Field.PATH) }
                .getAll()
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
    // ⚠⚠ The second route to a skew verdict, and the one that catches an ADMITTED session that
    // later meets a type it cannot read. Without it that surfaces as "can't reach Control" — an
    // outage the user cannot fix, instead of the update they can.
    answerUnreadable = client.proxyAnswerUnreadable,
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
