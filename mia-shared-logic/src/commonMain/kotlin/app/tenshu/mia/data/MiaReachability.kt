package app.tenshu.mia.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll

/**
 * Mia's **one** Control-reachability engine (`ADR 0025`; the `fit-app.md` §9.14 recipe).
 *
 * ⚠⚠ Mia has no store of its own and no mock fallback — `MiaClientModule` says so outright
 * ("*the mock is not a fallback*"), and `TenshuConversationRepository` answers every question with
 * an honest "I can't reach Control" when the proxy is gone. What was missing is the *shell's* half:
 * the app still rendered "Hi, I'm Mia — ask me to find photos from your Drive" over a live input
 * box, inviting a question it already knew it could not answer.
 *
 * ⚠ The probe is a realm-settings read rather than an `askMia` round-trip: it must be cheap,
 * read-only and free of side effects, and asking the assistant a question to find out whether the
 * assistant is reachable would put a message in the user's conversation every five seconds.
 *
 * [probeScope] `null` (tests, previews) disables the probe; the signal then tracks only the
 * transport edge and organic outcomes.
 */
fun miaControlReachability(
    client: TenshuClient,
    probeScope: CoroutineScope?,
    probeIntervalMs: Long = 5_000,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b), so the shell can say *update
     * Control* rather than *can't reach Control* when the proxy is answering and refusing.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (SK-3) and the proxy's published host identity (SK-2).
     * ⚠⚠ Absent, a fenced device renders nothing at all — it answers every read and looks healthy
     * while silently receiving nothing.
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
            client.preferenceService.realmSettings.getAll() is TResult.Success<*>
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold.
            false
        }
    },
    scope = probeScope,
    probeIntervalMs = probeIntervalMs,
    // R4's transport signal: reports the outage instantly instead of after a probe interval, and
    // suppresses probing across a channel that cannot carry one.
    transportConnected = client.proxyConnected,
    schemaSkew = schemaSkew,
    schemaFence = schemaFence,
    proxyHost = proxyHost,
    // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
    // it" — an instruction naming an app that is not on the device.
    controlInstalled = { client.controlInstalled() },
    // One event per call the proxy served, used to WAKE the probe rather than to answer for it —
    // see `ControlReachability.answered`. This is what replaced the repositories' hand-written
    // `markReachable` calls.
    answered = client.proxyAnswered,
    controlHasRealm = controlHasRealm,
)
