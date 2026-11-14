package org.tenshu.sdk.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.fail
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.viewmodel.test.OwnedViewModels
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
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
/**
 * ⚠⚠ A reactive read's first emission, **bounded**. A bare `.first()` on a read that never emits
 * parks `runBlocking` on the main thread for ever, and the whole test binary hangs with it rather
 * than failing — which is how a lost initial snapshot (`core-client-resubscription.md` §4.9) once
 * showed up here: a binary sampled parked in `installingARuntimeReplacesTheNotConnectedSurface`.
 */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstBounded(): T =
    withTimeout(30_000) { first() }

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "contacts-local-runtime-test-" + Random.nextLong().toString(16)

    /** Every runtime this test built, so [closeAssemblies] can stop it. */
    private val assemblies = mutableListOf<ContactsLocalRuntime.Assembly>()

    private fun assemble() = ContactsLocalRuntime.assemble(
        replicaDirectory = tempReplicaDir(),
        secureStorage = InMemorySecureStorageDelegate(),
    ).also { assemblies += it }

    /**
     * ⚠⚠ Stop every runtime the test built. Nothing else does: a test replica has no shared-store
     * host, so a graph assembled here otherwise keeps its proxy scopes and its reachability probe
     * running for the rest of the binary — each later test then runs beside every earlier one's
     * graph, and the binary exits with all of them still working. That binary once wrote its
     * results and then never exited (`docs/tech-debt.md`, *"Contacts iOS test binary never
     * exits"*).
     *
     * The surface is reset first, so no reference to a closed repository is left installed.
     */
    @AfterTest
    fun closeAssemblies() = runBlocking {
        ContactsSurface.installNotConnected()
        // Each closed on its own: one that throws must not leave the rest running.
        assemblies.asReversed().forEach { runCatching { it.close() }.onFailure { println("closeAssemblies: $it") } }
        assemblies.clear()
    }

    @Test
    fun assemblesTheFullRuntimeAndReadsAnEmptyContactGraph() = runBlocking {
        val assembly = assemble()
        assertTrue(
            assembly.contactsRepository.contacts().firstBounded().isEmpty(),
            "a fresh replica lists no contacts",
        )
    }

    @Test
    fun theRuntimeReachesTheInProcessContactService() = runBlocking {
        // ⚠ Emptiness alone proves nothing — an *unwired* service would throw into the
        // repository's catch and return the same empty list. The reachability verdict is what
        // separates "reached a real service, it has nothing" from "never reached one".
        val assembly = assemble()
        assembly.contactsRepository.contacts().firstBounded()
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
        assertEquals("", assembly.contactsRepository.ownProfile().firstBounded().displayName)
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
        assertTrue(ContactsSurface.repository().contacts().firstBounded().isEmpty())
        assertEquals(
            false,
            ContactsSurface.controlReachable().firstBounded(),
            "not-connected is a KNOWN state — null would mean 'still deciding' and show no banner",
        )
        assertNull(ContactsSurface.repository().contact("anyone").firstBounded())
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
        ContactsSurface.repository().contacts().firstBounded()
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
        // ⚠ Assert the write, as `aWrittenContactIsReadBack` does: ignored, a failed write and a
        // view model that never saw a stored row both end in the same timeout.
        val written = assembly.contactsRepository.createContact("Dad")
        assertTrue(
            written is app.tenshu.contacts.data.RepositoryResult.Success,
            "createContact must succeed before the view model can be blamed: $written",
        )
        ContactsSurface.installRepository(assembly.contactsRepository)

        // ⚠⚠ Two things have to be true before a view model can be read here, and neither is by
        // default. `viewModelScope` is `Dispatchers.Main.immediate`, and the K/N test runner runs
        // the test ON the main thread, which `runBlocking` then owns — so a model's collection is
        // posted to a queue that never drains and `uiState` stays at its initial value forever.
        // And even with a live dispatcher the first emission is asynchronous, so `.value` read at
        // construction time is the empty default, not the repository's answer.
        //
        // ⚠ `Main` is redirected to `Dispatchers.Default`, as `DriveLocalRuntimeTest` does, and not
        // to a test dispatcher: this runtime is real threads on a real clock. A test dispatcher's
        // virtual clock is advanced by nobody here, so any delay reaching `Main` would never fire.
        Dispatchers.setMain(Dispatchers.Default)
        val owned = OwnedViewModels()
        try {
            val listViewModel = owned.own(ContactsSurface.contactListViewModel())
            // `loaded` is the flag the app itself uses to tell "still loading" from "no
            // contacts" — waiting on it here is the same distinction, not a sleep.
            // ⚠ `loaded` alone is not enough — the same race as `aWrittenContactIsReadBack`:
            // the model can reach `loaded` on a combine frame that predates the write, so wait for
            // the row itself. A model that never loads still fails on the timeout.
            val state = try {
                withTimeout(30_000) {
                    listViewModel.uiState.first { it.loaded && it.contacts.isNotEmpty() }
                }
            } catch (e: TimeoutCancellationException) {
                // ⚠ Say which half is stuck: a fresh read of the same repository that sees the row
                // clears the store and the read path and leaves the view model's collection; one
                // that does not leaves the repository. Opposite fixes.
                val fresh = withTimeoutOrNull(10_000) {
                    assembly.contactsRepository.contacts().first { it.isNotEmpty() }
                }
                val seen = listViewModel.uiState.value
                fail(
                    "the view model never showed the written row (loaded=${seen.loaded}, " +
                        "contacts=${seen.contacts.map { it.displayName }}); a fresh repository read " +
                        if (fresh != null) "DOES see ${fresh.map { it.displayName }} — the view model's collection is stuck"
                        else "does NOT see it either — the repository never surfaced the row"
                )
            }
            assertEquals(listOf("Dad"), state.contacts.map { it.displayName })
        } finally {
            // ⚠⚠ Join the view model BEFORE `Main` is reset (`docs/testing.md` §11). Left
            // collecting, its next resume after the reset goes to the real main queue, which this
            // binary never drains — the collectors (and the runtime they hold open) then outlive
            // the test and are still parked when the binary exits.
            owned.closeAll()
            Dispatchers.resetMain()
            ContactsSurface.installNotConnected()
        }
        owned.assertNothingActive()
    }
}
