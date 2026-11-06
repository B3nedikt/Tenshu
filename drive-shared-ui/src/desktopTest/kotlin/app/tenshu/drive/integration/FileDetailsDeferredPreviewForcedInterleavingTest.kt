package app.tenshu.drive.integration

import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.InProcessTaskTransport
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.StreamElement
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.transport.TaskTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The **forced** twin of [FileDetailsDeferredPreviewIntegrationTest] — `docs/tech-debt.md`, gate-flake
 * entry, sighting 2026-09-14 (second of the day).
 *
 * That test asserts the details stream's FIRST emission carries metadata only (`previewBytes ==
 * null`) and the preview arrives as a later patch. Under full-gate load it went red once with the
 * first emission already carrying the bytes.
 *
 * **Where the frames actually travel in this harness.** The client's `TaskExecutor` is the proxy
 * module's in-process `SessionAwareLocalTaskExecutor`, and its `onSessionAdopted` hook registers a
 * listener on the session's `LocalClientConnection` that delivers pushed elements to the client's
 * `DefaultInteractorProvider.dispatchElement` on a scope of its own
 * (`control-shared-proxy/…/ProxyModule.kt`, `single<AsyncTaskExecutor>`). Until 2026-09-14 that
 * listener **launched one coroutine per element**: two launches with no order between them, so
 * whenever the patch's ran first, `TList.flow()` — whose signal channel keeps *enqueue* order —
 * presented the patched row before the snapshot. `forwardStreamElementsInOrder` (one consumer, in
 * arrival order) replaced it. (The harness also serialises every push at a
 * `RemoteBinaryTaskExecutor`'s `onCallback` — which had the identical shape, now
 * `RemoteBinaryTaskExecutorPushOrderTest`'s subject — but no test resolves that executor, so that
 * transport path never registers a callback and carries nothing here.)
 *
 * **The forcing.** The listener's scope (`clientStreamScope`) is bound to [ForcingDispatcher]: the
 * snapshot frame's dispatch is **held** until the patch frame's dispatch has run to completion,
 * then released. The frames are recognised where the
 * proxy hands them to the connection (a whole-list payload is the snapshot; a single entity is the
 * patch), synchronously and *before* the listener sees the frame, so the hold catches exactly the
 * dispatch that frame causes and nothing else. Proxy, wire shape, chain and assertion are untouched.
 *
 * Measured 2026-09-14 against the production listener, same forcing both arms: per-element launch
 * → red (`holds=1`, released by the patch's coroutine, emissions `[69]` — the first frame already
 * carried the bytes, the gate sighting's exact shape); ordered consumer → green (`holds=1`,
 * released by the timeout, emissions `[null, 69]`).
 *
 * ⚠ The forcing must be seen to engage, or a green here is green by construction (the trap the
 * tech-debt entry's P0 records): [ForcingDispatcher.holds] is asserted to be exactly 1. How the
 * hold is *released* is deliberately not asserted — see [ForcingDispatcher]: with the per-element
 * launch the patch's coroutine releases it (and the test is red); with `forwardStreamElementsInOrder`
 * the held dispatch *is* the single consumer, nothing else is ever dispatched, and the safety
 * timeout releases it — after which it delivers snapshot, then patch (green).
 */
class FileDetailsDeferredPreviewForcedInterleavingTest {

    private val clientId = "app.tenshu.drive.test"

    private val forcing = ForcingDispatcher(Dispatchers.Default)

    private lateinit var appKoin: AppIntegrationKoin

    private lateinit var filesRepository: FilesRepository

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(
            clientId = clientId,
            additionalModules = listOf(forcingModule(clientId, forcing)),
        )
        filesRepository = appKoin.get()
    }

    @After
    fun tearDown() {
        forcing.releaseHeld()
        appKoin.stop()
        forcing.close()
    }

    @Test
    fun theMetadataFrameLandsFirst_evenWhenThePatchCoroutineIsScheduledAheadOfTheSnapshotCoroutine() =
        runBlocking<Unit> {
            filesRepository.uploadFile(
                uriString = "mock://photo.jpg?w=8&h=8",
                parentPath = "/",
                fileName = "photo.jpg"
            )

            // Only the details stream's frames are forced: the graph opens other streams while it
            // starts (their snapshots must pass through untouched), so arm just before subscribing.
            forcing.enable()
            val emissions = mutableListOf<FileDetailsModel>()
            val patched = withTimeout(15_000) {
                filesRepository.fileDetails("/photo.jpg")
                    .onEach { details -> details?.let { emissions.add(it) } }
                    .first { (it?.file as? FileModel.File)?.previewBytes != null }
            }

            assertEquals(1, forcing.holds.get(), "the snapshot frame's dispatch was held exactly once")
            println(
                "forced pair: holds=${forcing.holds.get()} " +
                    "releasedByPatch=${forcing.releasedByFollowingDispatch.get()} " +
                    "releasedByTimeout=${forcing.releasedByTimeout.get()} " +
                    "emissions=${emissions.map { (it.file as? FileModel.File)?.previewBytes?.size }}"
            )

            assertNotNull(patched)
            assertNotNull((patched.file as FileModel.File).previewBytes, "the patch delivers the bytes")

            val initialFile = emissions.first().file as FileModel.File
            assertNull(
                initialFile.previewBytes,
                "initial emission must not wait for the preview — the proxy sends snapshot then patch, " +
                    "and the client must not re-order the two"
            )
            assertEquals("photo.jpg", initialFile.name)
        }

    /**
     * Two overrides:
     *
     *  - `clientStreamScope`: the scope `ProxyModule`'s per-session stream listener delivers on,
     *    bound to [forcing] instead of the private `Dispatchers.Default` scope it defaults to. The
     *    listener itself is the production one.
     *  - the client-connection factory: a verbatim copy of the harness's own, plus the arming
     *    hooks — snapshot arms the hold, patch arms the release — which run synchronously *before*
     *    the listener sees the frame.
     */
    private fun forcingModule(clientId: String, forcing: ForcingDispatcher): Module = module {
        // The production listener (ProxyModule's `single<AsyncTaskExecutor>`) delivers on this
        // scope; only its dispatcher changes.
        single<CoroutineScope>(named("clientStreamScope")) { forcing.scope }

        single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
            val transport: TaskTransport = get()
            val factory: (String) -> ClientConnection = { cid ->
                if (cid == clientId) {
                    LocalClientConnection().apply {
                        addListener { data ->
                            val bytes = BinarySerializer(get()).serialize(data)
                            if (data is StreamElement.Value<*>) {
                                // Whole-list payload = the stream's snapshot; single entity = the
                                // deferred patch. Registered before the executor's listener, so
                                // these run first and the next launch is the one they name.
                                if (data.payload is TList<*>) forcing.holdNext() else forcing.releaseAfterNext()
                            }
                            (transport as InProcessTaskTransport).onCallback?.invoke(bytes)
                        }
                    }
                } else {
                    LocalClientConnection()
                }
            }
            factory
        }
    }
}

/**
 * A dispatcher that can hold ONE dispatched block back until a later-dispatched block has run to
 * completion — the interleaving a busy `Dispatchers.Default` produces by chance when two
 * independent coroutines are launched back to back.
 *
 * [holdNext] captures the next dispatch; [releaseAfterNext] wraps the next dispatch so that its
 * completion releases the held one. Anything dispatched in between passes straight through, so a
 * stray coroutine on the same scope cannot release the hold early. A [HOLD_TIMEOUT_MS] safety
 * release keeps the forcing arm-agnostic: a listener that consumes pushes on a single ordered
 * coroutine has no second dispatch to release the held one, so its (correctly ordered) delivery
 * simply waits out the timeout — and the test still sees the hold engage.
 */
private class ForcingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {

    private class Held(val context: CoroutineContext, val block: Runnable)

    private val enabled = AtomicBoolean(false)
    private val holdArmed = AtomicBoolean(false)
    private val releaseArmed = AtomicBoolean(false)
    private val held = AtomicReference<Held?>(null)

    val holds = AtomicInteger(0)
    val releasedByFollowingDispatch = AtomicInteger(0)
    val releasedByTimeout = AtomicInteger(0)

    /** What the proxy module's listener launches on — the scope under test. */
    val scope = CoroutineScope(SupervisorJob() + this)

    private val timeoutScope = CoroutineScope(SupervisorJob() + delegate)

    /** Until this is called, [holdNext] and [releaseAfterNext] are no-ops and everything passes through. */
    fun enable() {
        enabled.set(true)
    }

    fun holdNext() {
        if (enabled.get()) holdArmed.set(true)
    }

    fun releaseAfterNext() {
        if (enabled.get()) releaseArmed.set(true)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holdArmed.compareAndSet(true, false)) {
            val h = Held(context, block)
            held.set(h)
            holds.incrementAndGet()
            timeoutScope.launch {
                delay(HOLD_TIMEOUT_MS)
                if (held.compareAndSet(h, null)) {
                    releasedByTimeout.incrementAndGet()
                    delegate.dispatch(h.context, h.block)
                }
            }
            return
        }
        if (releaseArmed.compareAndSet(true, false)) {
            val pending = held.get()
            delegate.dispatch(context) {
                try {
                    block.run()
                } finally {
                    if (pending != null && held.compareAndSet(pending, null)) {
                        releasedByFollowingDispatch.incrementAndGet()
                        delegate.dispatch(pending.context, pending.block)
                    }
                }
            }
            return
        }
        delegate.dispatch(context, block)
    }

    fun releaseHeld() {
        held.getAndSet(null)?.let { delegate.dispatch(it.context, it.block) }
    }

    fun close() {
        timeoutScope.cancel()
        scope.cancel()
    }

    private companion object {
        const val HOLD_TIMEOUT_MS = 1_500L
    }
}
