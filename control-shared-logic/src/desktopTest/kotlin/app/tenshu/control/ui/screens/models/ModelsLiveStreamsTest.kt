package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.IndexPassPosition
import app.tenshu.control.inference.IndexProgressSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * The two streams the Models screen subscribes to once and never again, tested at the seams
 * `ClientModelsRepository` itself delegates to — so what runs here is the production composition,
 * with only the proxy holder and the preference reads replaced.
 *
 * ⚠⚠ Both used to need the screen's Refresh button: the index stream was the bounded `IndexProgress`
 * op, and the consents bound the device id once at subscribe time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsLiveStreamsTest {

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private class HolderLike : IndexProgressSource {
        val state = MutableStateFlow<IndexPassPosition?>(null)
        override val progress: Flow<IndexPassPosition?> = state
    }

    /**
     * ⚠⚠ Idle, a pass, idle, a second pass — on ONE collection, which is still open at the end. The
     * old repository read the bounded op, which ends after its first idle tick: this collection would
     * have held one `indexing = false` and completed, missing both passes.
     */
    @Test
    fun passesThatStartLaterAreReportedOnTheSameSubscription() = runTest {
        val holder = HolderLike()
        val seen = mutableListOf<IndexProgressState>()
        val collecting = launch { indexProgressStates(holder).collect { seen += it } }
        runCurrent()

        holder.state.value = IndexPassPosition(done = 0, total = 9); runCurrent()
        holder.state.value = IndexPassPosition(done = 9, total = 9); runCurrent()
        holder.state.value = null; runCurrent()
        holder.state.value = IndexPassPosition(done = 1, total = 4); runCurrent()

        assertEquals(
            listOf(
                IndexProgressState(indexing = false),
                IndexProgressState(indexing = true, done = 0, total = 9),
                IndexProgressState(indexing = true, done = 9, total = 9),
                IndexProgressState(indexing = false),
                IndexProgressState(indexing = true, done = 1, total = 4),
            ),
            seen,
        )
        assertFalse(collecting.isCompleted, "the stream completed — a later pass would go unreported")
        collecting.cancel()
    }

    /** ⚠ The id is polled on a capped backoff while absent, and once found it is emitted and the poll ends. */
    @Test
    fun aLateDeviceIdIsEmittedOnceItResolves() = runTest {
        val answers = ArrayDeque(listOf(null, null, "dev-1"))
        var asked = 0
        val ids = deviceIdOnceKnown(resolve = { asked++; answers.removeFirst() }).toList()

        assertEquals(listOf(null, "dev-1"), ids)
        assertEquals(3, asked)
    }

    /** ⚠ A known id costs one ask and one emission — nothing polls behind a device that has one. */
    @Test
    fun aKnownDeviceIdIsAskedForOnce() = runTest {
        var asked = 0
        assertEquals(listOf<String?>("dev-1"), deviceIdOnceKnown(resolve = { asked++; "dev-1" }).toList())
        assertEquals(1, asked)
    }

    /**
     * ⚠⚠ The device half flips to what is stored once the id appears, with NO re-subscribe. Against
     * the old repository this failed: it resolved the id once, found none, and read the device half
     * as `flowOf(default)` for as long as the subscription lived — the switch stayed off over a
     * stored yes until the user pressed Refresh.
     */
    @Test
    fun aDeviceIdThatAppearsLaterFlipsTheDeviceHalfWithoutAReSubscribe() = runTest {
        var currentId: String? = null
        val storedDevice = mapOf("dev-1" to true)
        val seen = mutableListOf<CapabilityConsent>()

        val collecting = launch {
            consentsOver(
                deviceIds = deviceIdOnceKnown(resolve = { currentId }),
                // ⚠ Never-completing, like the real preference reads — a finite fake would end the
                // `combine` and make the open-subscription assertion below meaningless.
                realm = { MutableStateFlow(true) },
                device = { _, id -> MutableStateFlow(storedDevice[id] ?: false) },
            ).map { list -> list.single { it.capability == search } }.collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf(CapabilityConsent(search, realm = true, device = false)), seen)

        currentId = "dev-1"
        advanceTimeBy(1.seconds.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(
            listOf(
                CapabilityConsent(search, realm = true, device = false),
                CapabilityConsent(search, realm = true, device = true),
            ),
            seen,
        )
        assertFalse(collecting.isCompleted)
        collecting.cancel()
    }

    /** ⚠ An id known at subscribe time never shows the default first: no off-then-on flick. */
    @Test
    fun aKnownDeviceIdNeverShowsTheDefaultFirst() = runTest {
        val seen = mutableListOf<CapabilityConsent>()
        val collecting = launch {
            consentsOver(
                deviceIds = deviceIdOnceKnown(resolve = { "dev-1" }),
                realm = { flowOf(true) },
                device = { _, _ -> flowOf(true) },
            ).map { list -> list.single { it.capability == search } }.collect { seen += it }
        }
        runCurrent()

        assertEquals(listOf(CapabilityConsent(search, realm = true, device = true)), seen)
        collecting.cancel()
    }

    /**
     * §38 — the feature screen's device list: every authorised device but this one, with its own
     * stored answer, live.
     *
     * ⚠⚠ Nothing is listed until this device knows its id. The roster includes this device, and
     * listed among the others it would sit under a switch that writes another device's row.
     */
    @Test
    fun theOtherDevicesAreListedOnceThisDeviceKnowsWhichOneItIs() = runTest {
        var ownId: String? = null
        val roster = MutableStateFlow(listOf("dev-1" to "Phone", "dev-2" to "Laptop"))
        val laptopSearch = MutableStateFlow(true)
        val seen = mutableListOf<List<OtherDeviceConsent>?>()

        val collecting = launch {
            otherDeviceConsentsOver(
                ownDeviceIds = deviceIdOnceKnown(resolve = { ownId }),
                roster = roster,
                device = { capability, id ->
                    if (capability == search && id == "dev-2") laptopSearch else MutableStateFlow(false)
                },
            ).map { known -> known?.let { it[search].orEmpty() } }.collect { seen += it }
        }
        runCurrent()
        // ⚠⚠ `null`, not empty: "not known yet" must not read as "no other device".
        assertEquals(listOf<List<OtherDeviceConsent>?>(null), seen)

        ownId = "dev-1"
        advanceTimeBy(1.seconds.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(listOf(OtherDeviceConsent("dev-2", "Laptop", allowed = true)), seen.last())

        // A row switched off — from here or from that device — moves on the same subscription.
        laptopSearch.value = false
        runCurrent()
        assertEquals(listOf(OtherDeviceConsent("dev-2", "Laptop", allowed = false)), seen.last())

        // And so does a device that joins the realm.
        roster.value = roster.value + ("dev-3" to "Tablet")
        runCurrent()
        assertEquals(listOf("dev-2", "dev-3"), seen.last()?.map { it.deviceId })
        assertFalse(collecting.isCompleted)
        collecting.cancel()
    }

    /** ⚠ A realm of one device says "no others" outright — `combine` over no flows would never emit. */
    @Test
    fun aRealmOfOneDeviceListsNoOthers() = runTest {
        val seen = mutableListOf<Map<InferenceCapability, List<OtherDeviceConsent>>?>()
        val collecting = launch {
            otherDeviceConsentsOver(
                ownDeviceIds = flowOf("dev-1"),
                roster = MutableStateFlow(listOf("dev-1" to "Phone")),
                device = { _, _ -> flowOf(true) },
            ).collect { seen += it }
        }
        runCurrent()

        assertEquals(listOf<Map<InferenceCapability, List<OtherDeviceConsent>>?>(emptyMap()), seen)
        collecting.cancel()
    }

    /**
     * ⚠⚠ A roster that could not be read is *not known*, like a device id that is not: it used
     * to be an empty list, which says *no other device*, and *Turn off everywhere* then cleared
     * no peer and wrote the realm no. ⚠ And it is known again once it reads.
     */
    @Test
    fun aRosterThatCouldNotBeReadIsNotKnownNotEmpty() = runTest {
        val roster = MutableStateFlow<List<Pair<String, String>>?>(null)
        val seen = mutableListOf<Map<InferenceCapability, List<OtherDeviceConsent>>?>()
        val collecting = launch {
            otherDeviceConsentsOver(
                ownDeviceIds = flowOf("dev-1"),
                roster = roster,
                device = { _, _ -> flowOf(true) },
            ).collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf<Map<InferenceCapability, List<OtherDeviceConsent>>?>(null), seen)

        roster.value = listOf("dev-1" to "Phone", "dev-2" to "Laptop")
        runCurrent()
        assertEquals(listOf("dev-2"), seen.last()?.get(search)?.map { it.deviceId })

        roster.value = null
        runCurrent()
        assertEquals(null, seen.last())
        collecting.cancel()
    }
}
