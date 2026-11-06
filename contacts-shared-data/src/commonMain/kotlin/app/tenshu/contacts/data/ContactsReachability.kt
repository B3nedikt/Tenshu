package app.tenshu.contacts.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.tenshu.contact.Person
import org.tenshu.contact.client.contactService
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold

/**
 * Contacts' **one** Control-reachability engine (`fit-app.md` §9.14 recipe,
 * `:app-reachability`) — a Koin single, so the contact reads, the profile read and the probe
 * all feed one shell banner rather than one signal per read.
 *
 * The probe matters more than it looks: a reactive `.flow()` read against a dead transport
 * does not reliably fail, it **hangs**, so waiting for a read to report the problem waits
 * forever. This is the cheapest possible round-trip — one row, one field.
 *
 * [probeScope] `null` (tests, previews) disables the probe; the signal then tracks only
 * organic read outcomes.
 */
fun contactsControlReachability(
    client: TenshuClient,
    probeScope: CoroutineScope?,
    probeIntervalMs: Long = 5_000,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b), so the shell banner can say
     * *update Control* rather than *can't reach Control* when the proxy is answering and refusing.
     * ⚠ Absent (tests, previews) every outage reads as a transport problem, not a crash.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (SK-3) and the proxy's published host identity (SK-2).
     * WARN: absent, a fenced device renders no banner at all - it answers every read and looks
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
            client.contactService.persons.take(1)
                .select { fields(Person.Field.PERSON_ID) }.getAll()
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
    // ⚠ R4's transport signal: it reports an outage instantly instead of a probe interval later,
    // and suppresses probing a channel that cannot carry one. Without it an outage waits for the
    // next probe — the read folds report nothing to the engine.
    transportConnected = client.proxyConnected,
    schemaSkew = schemaSkew,
    schemaFence = schemaFence,
    proxyHost = proxyHost,
    // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
    // it" — an instruction naming an app that is not on the device. `null` (desktop, every
    // in-process runtime) leaves the outage worded generically.
    controlInstalled = { client.controlInstalled() },
    // One event per call the proxy served, used to WAKE the probe rather than to answer for it —
    // see `ControlReachability.answered`. The repositories make no `markReachable` calls of their
    // own.
    answered = client.proxyAnswered,
    controlHasRealm = controlHasRealm,
)
