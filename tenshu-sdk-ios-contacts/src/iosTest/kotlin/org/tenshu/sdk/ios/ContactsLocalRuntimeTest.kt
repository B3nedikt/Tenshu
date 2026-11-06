package org.tenshu.sdk.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.fail
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ph10 coverage (`contacts-app.md` §9.9): the route-1 in-process Contacts runtime assembles the
 * FULL production proxy graph in this process and serves the contact graph over a
 * test replica (`TestReplicas`). The Contacts twin of [MapsLocalRuntimeTest] /
 * [TimelineLocalRuntimeTest] / [FitLocalRuntimeTest] / [DriveLocalRuntimeTest].
 *
 * What it proves is the claim the phase rests on: Control's iOS proxy graph *already* hosts
 * `:contact-service` and `:preference-service`, so a route-1 Contacts needed **no new service
 * wiring** — only the one repository built over the in-process client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "contacts-local-runtime-test-" + Random.nextLong().toString(16)

    private fun assemble() = ContactsLocalRuntime.assemble(
        replicaDirectory = tempReplicaDir(),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    @Test
    fun assemblesTheFullRuntimeAndReadsAnEmptyContactGraph() = runBlocking {
        val assembly = assemble()
        assertTrue(
            assembly.contactsRepository.contacts().first().isEmpty(),
            "a fresh replica lists no contacts",
        )
    }

    @Test
    fun theRuntimeReachesTheInProcessContactService() = runBlocking {
        // ⚠ Emptiness alone proves nothing — an *unwired* service would throw into the
        // repository's catch and return the same empty list. The reachability verdict is what
        // separates "reached a real service, it has nothing" from "never reached one".
        val assembly = assemble()
        assembly.contactsRepository.contacts().first()
        // ⚠⚠ WAIT for the verdict, do not assert on `first()`. `controlReachable()` is
        // `Flow<Boolean?>` and that **null is the "not yet known" state** — a fresh
        // `ControlReachability` reports it until a read folds or the 5 s probe ticks, so the first
        // emission legitimately predates the answer — the same trap `aWrittenContactIsReadBack`
        // for the row itself. This is not a weaker assertion: a proxy that never becomes
        // reachable still fails, on the timeout rather than on a race.
        val reachable = withTimeout(30_000) {
            assembly.contactsRepository.controlReachable().first { it == true }
        }
        assertEquals(true, reachable, "the in-process proxy IS reachable — an unwired service would not be")
    }

    @Test
    fun thePreferenceBackedOwnNameIsReadable() = runBlocking {
        // D6's card reads the PLATFORM name (`OwnerPreferences.Name`, the one Drive and Fit's
        // profiles share), not an app-private copy. That read has to work on route 1 too, and it
        // is the second of the two services this phase claims are already wired.
        val assembly = assemble()
        assertEquals("", assembly.contactsRepository.ownProfile().first().displayName)
    }

    @Test
    fun aWrittenContactIsReadBack() = runBlocking {
        // The in-process write path end to end: an op through the client, into the proxy, onto
        // the replica, and back out of the reactive read.
        val assembly = assemble()
        // ⚠ Assert the WRITE, not just the read. Ignoring this result cannot tell "the op failed"
        // from "the read never saw it", and those need opposite fixes.
        val written = assembly.contactsRepository.createContact("Mira Okonkwo")
        assertTrue(
            written is app.tenshu.contacts.data.RepositoryResult.Success,
            "createContact must succeed before the read can be blamed: $written",
        )
        // ⚠⚠ WAIT for the row, do not assert on `first()`. `contacts()` is a `combine` of four
        // independent reactive row flows on `Dispatchers.Default`; its FIRST emission is assembled
        // from whatever each source happens to have when the subscription lands, which need not yet
        // include a write that completed a moment earlier. Asserting on that frame made this test
        // fail roughly half the time (`docs/tech-debt.md`).
        //
        // ⚠ This is not a weaker assertion: the contact must still arrive, with that exact name,
        // inside the timeout — a write that never lands still fails, it just fails on the timeout
        // rather than on a race.
        //
        // ⚠⚠ On timeout, RE-SUBSCRIBE before reporting. That is the one question the next person
        // needs answered and a bare timeout cannot: a fresh subscription that DOES see the row
        // means the write committed and the first subscription missed its invalidation (the
        // "write during the subscribe window is lost" class); one that does NOT means the write
        // never became observable despite its op returning Success. Opposite fixes.
        val contacts = try {
            withTimeout(30_000) {
                assembly.contactsRepository.contacts().first { it.isNotEmpty() }
            }
        } catch (e: TimeoutCancellationException) {
            val fresh = withTimeoutOrNull(10_000) {
                assembly.contactsRepository.contacts().first { it.isNotEmpty() }
            }
            fail(
                if (fresh != null) {
                    "the row IS committed — a FRESH subscription sees ${fresh.map { it.displayName }}, " +
                        "so the first subscription missed its invalidation (subscribe-window race)"
                } else {
                    "the write returned Success but the row is not observable on a fresh " +
                        "subscription either — it never became visible at all"
                }
            )
        }
        assertEquals(listOf("Mira Okonkwo"), contacts.map { it.displayName })
    }

    @Test
    fun theSurfaceIsNotConnectedUntilARuntimeInstallsOne() = runBlocking {
        // ⚠ The default matters more here than in the sibling apps: a mock behind the shipping
        // gate would put invented people in front of a user who has connected nothing, with
        // trust badges implying key material that does not exist.
        ContactsSurface.installNotConnected()
        assertTrue(ContactsSurface.repository().contacts().first().isEmpty())
        assertEquals(
            false,
            ContactsSurface.controlReachable().first(),
            "not-connected is a KNOWN state — null would mean 'still deciding' and show no banner",
        )
        assertNull(ContactsSurface.repository().contact("anyone").first())
    }

    @Test
    fun theNotConnectedSurfaceRefusesWritesWithAReason() = runBlocking {
        ContactsSurface.installNotConnected()
        val result = ContactsSurface.repository().createContact("Mira")
        assertTrue(
            result is app.tenshu.contacts.data.RepositoryResult.Failure,
            "a write with no runtime must fail visibly, not silently do nothing",
        )
    }

    @Test
    fun installingARuntimeReplacesTheNotConnectedSurface() = runBlocking {
        ContactsSurface.installNotConnected()
        val assembly = assemble()
        ContactsSurface.installRepository(assembly.contactsRepository)

        // ⚠ The verdict is not synchronous. A freshly built `ControlReachability` reports `null`
        // — "not yet known" — until either an organic read folds or the 5 s probe ticks, and the
        // shell deliberately shows no banner in that window. So the read has to come first: what
        // this test is entitled to assert is that a read through the surface now reaches the
        // installed runtime, not that installing alone produces a verdict.
        ContactsSurface.repository().contacts().first()
        // ⚠ Wait for the verdict rather than sampling the first frame — see the sibling case; the
        // "not yet known" opening state is exactly what this used to read on a fast run.
        assertEquals(
            true,
            withTimeout(30_000) { ContactsSurface.controlReachable().first { it == true } },
            "installing a runtime makes the surface reachable",
        )

        // And teardown restores it, which is what the deprovision path relies on.
        ContactsSurface.installNotConnected()
        assertEquals(
            false,
            withTimeout(30_000) { ContactsSurface.controlReachable().first { it == false } },
            "teardown restores the not-connected surface — the deprovision path relies on it",
        )
    }

    @Test
    fun viewModelsBindWhateverRepositoryIsInstalled() = runBlocking {
        val assembly = assemble()
        assembly.contactsRepository.createContact("Dad")
        ContactsSurface.installRepository(assembly.contactsRepository)

        // ⚠⚠ Two things have to be true before a view model can be read here, and neither is by
        // default. `viewModelScope` is `Dispatchers.Main.immediate`, and the K/N test runner runs
        // the test ON the main thread, which `runBlocking` then owns — so a model's collection is
        // posted to a queue that never drains and `uiState` stays at its initial value forever.
        // And even with a live dispatcher the first emission is asynchronous, so `.value` read at
        // construction time is the empty default, not the repository's answer.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val listViewModel = ContactsSurface.contactListViewModel()
            // `loaded` is the flag the app itself uses to tell "still loading" from "no
            // contacts" — waiting on it here is the same distinction, not a sleep.
            // ⚠ `loaded` alone is not enough — the same race as `aWrittenContactIsReadBack`:
            // the model can reach `loaded` on a combine frame that predates the write, so wait for
            // the row itself. A model that never loads still fails on the timeout.
            val state = withTimeout(30_000) {
                listViewModel.uiState.first { it.loaded && it.contacts.isNotEmpty() }
            }
            assertEquals(listOf("Dad"), state.contacts.map { it.displayName })
        } finally {
            Dispatchers.resetMain()
            ContactsSurface.installNotConnected()
        }
    }
}
