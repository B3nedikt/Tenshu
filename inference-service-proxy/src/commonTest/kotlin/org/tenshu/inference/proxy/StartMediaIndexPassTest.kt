package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.IndexingPaused
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.StartMediaIndexPass
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **Index now** (§34) — the op that turns "nothing happened" into a sentence.
 *
 * ⚠⚠ Every refusal is asserted by its **reason**, not merely by "it threw": the whole point of the
 * op over `MediaIndexPass` is that the screen can say *install a model* rather than *try again*, and
 * a test that only checked for a failure would pass while all four collapsed into one.
 */
class StartMediaIndexPassTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val tagging = InferenceCapability.CONTENT_TAGGING

    /** A source whose bytes never arrive, so a started pass stays visibly in flight. */
    private class GatedSource(private val gate: CompletableDeferred<Unit>) : MediaSource {
        var reads = 0
        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            listOf(
                IndexableMedia("/photos/a.jpg") {
                    reads++
                    gate.await()
                    "bytes".encodeToByteArray()
                },
            )
    }

    private fun proxy(
        source: MediaSource,
        registry: InferenceBackendRegistry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
        conditions: IndexingConditions = IndexingConditions { true },
        scope: CoroutineScope,
        faces: FaceTemplateStore? = null,
        launcher: ManualIndexLauncher? = null,
    ): TestProxy {
        val embeddings = InMemoryEmbeddingStore()
        val indexer = MediaIndexer(
            registry = registry,
            deviceId = { thisDevice },
            embeddings = embeddings,
            faces = faces,
            conditions = conditions,
        )
        // ⚠⚠ Built ONCE, outside the factory lambda — the factory runs per operation execution, so a
        // runner constructed inside it is a fresh single-flight per call, which is none. That is
        // also the production requirement (`ProxyModule` declares it a `single`), and this fixture
        // got it wrong first: the second-press test walked the library twice.
        val runner = MediaIndexRunner(indexer, source, scope)
        return TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(preferenceServiceCommonModule)
                registerConfig(inferenceServiceCommonModule)
            },
            implementationFactoryOverride = { id, _ ->
                inferenceServiceProxyImplementationFactory(
                    id = id,
                    registry = registry,
                    embeddings = embeddings,
                    deviceId = thisDevice,
                    indexer = indexer,
                    mediaSource = source,
                    runner = runner,
                    manualIndexLauncher = launcher,
                )
            },
        )
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(
                        context = tContext,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            } else {
                Entity.create<DeviceSetting>(
                    context = tContext,
                    composite = DeviceSetting.create(
                        context = tContext,
                        deviceId = deviceId,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            },
        )
    }

    private fun consentTo(proxy: TestProxy, capability: InferenceCapability) {
        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private suspend fun start(proxy: TestProxy) {
        proxy.executionContext.executeAsync<Data>(
            operationId = StartMediaIndexPass.ID,
            caller = null,
        )
    }

    private suspend fun refusalOf(proxy: TestProxy): String {
        val thrown = assertFailsWith<TenshuException> { start(proxy) }
        val error = thrown.error
        assertTrue(error is IndexPassRefused, "expected IndexPassRefused, got $error")
        return error.reason.get()
    }

    private fun scope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())

    /**
     * `control-background-work-off-fgs.md` §5.6.1: on Android *Index now* is a WorkManager
     * long-running worker. With a launcher wired the press goes to it and nothing runs in-process —
     * a pass here would hold no notification and no job.
     */
    @Test
    fun withALauncherThePressGoesToIt_andNothingRunsInProcess() = runTest {
        val source = GatedSource(CompletableDeferred())
        var launched = 0
        val p = proxy(source, scope = scope(testScheduler), launcher = ManualIndexLauncher { launched++ })
        consentTo(p, search)

        start(p)
        advanceUntilIdle()

        assertEquals(1, launched, "the press is handed to the worker")
        assertEquals(0, source.reads, "and walks nothing in-process")
    }

    @Test
    fun aConsentedDeviceWithAModelStartsAPass() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val p = proxy(source, scope = scope(testScheduler))
        consentTo(p, search)

        start(p)
        advanceUntilIdle()

        assertEquals(1, source.reads, "the pass must actually walk the library")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * ⚠⚠ Pause is checked FIRST, and this is the case that pins the order: a paused device that is
     * also missing consent must be told to resume, because that is the switch the user themselves
     * threw and the one they will look for.
     */
    @Test
    fun aPausedDeviceIsToldToResume_evenWithNoConsent() = runTest {
        val p = proxy(GatedSource(CompletableDeferred()), scope = scope(testScheduler))
        p.write(IndexingPaused, true, deviceId = thisDevice)

        assertEquals(IndexPassRefused.Reasons.PAUSED, refusalOf(p))
    }

    @Test
    fun aDeviceWithNoIndexingModelIsToldToInstallOne() = runTest {
        val p = proxy(
            GatedSource(CompletableDeferred()),
            // A backend that can plan a query but cannot touch an image: the screen's answer is
            // "download a model", not "switch something on".
            registry = InferenceBackendRegistry(
                listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.QUERY_PLANNING))),
            ),
            scope = scope(testScheduler),
        )
        consentTo(p, search)

        assertEquals(IndexPassRefused.Reasons.NO_BACKEND, refusalOf(p))
    }

    @Test
    fun aDeviceWithNoConsentIsToldToSwitchItOn() = runTest {
        val p = proxy(GatedSource(CompletableDeferred()), scope = scope(testScheduler))

        assertEquals(IndexPassRefused.Reasons.NO_CONSENT, refusalOf(p))
    }

    /** ⚠ Half the consent is still consent — a device that may only tag has work to do. */
    @Test
    fun consentToEitherHalfIsEnoughToStart() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val p = proxy(source, scope = scope(testScheduler))
        consentTo(p, tagging)

        start(p)
        advanceUntilIdle()

        assertEquals(1, source.reads)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * ⚠⚠ A device holding only the People model, with faces switched on, has work to do — Index
     * now must start it rather than answer "no model".
     */
    @Test
    fun aFaceOnlyDeviceStartsAPass() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val p = proxy(
            source,
            registry = InferenceBackendRegistry(
                listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.FACE_CLUSTERING))),
            ),
            scope = scope(testScheduler),
            faces = InMemoryFaceTemplateStore(),
        )
        consentTo(p, InferenceCapability.FACE_CLUSTERING)

        start(p)
        advanceUntilIdle()

        assertEquals(1, source.reads, "the face half must actually walk the library")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * ⚠⚠ The realm half alone is not consent (IN-D3): both switches, and absence means no. Without
     * this case the op would start on any device in a consenting realm.
     */
    @Test
    fun theRealmSwitchAloneDoesNotStartAPass() = runTest {
        val source = GatedSource(CompletableDeferred())
        val p = proxy(source, scope = scope(testScheduler))
        p.write(search.realmConsent, true)

        assertEquals(IndexPassRefused.Reasons.NO_CONSENT, refusalOf(p))
        assertEquals(0, source.reads, "a refused press must not walk the library")
    }

    /**
     * ⚠ The MANUAL bar: a flat battery still refuses, even though the user asked. The device being
     * *in use* does not — that is `MediaIndexerTest`'s case, and pressing the button is by
     * definition using the device.
     */
    @Test
    fun aDeviceThatCannotIndexRightNowSaysSo() = runTest {
        val source = GatedSource(CompletableDeferred())
        val p = proxy(
            source,
            conditions = object : IndexingConditions {
                override fun allowIndexing() = true
                override fun allowManualIndexing() = false
            },
            scope = scope(testScheduler),
        )
        consentTo(p, search)

        assertEquals(IndexPassRefused.Reasons.NOT_READY, refusalOf(p))
        assertEquals(0, source.reads)
    }

    /**
     * ⚠⚠ A scheduled pass is held to `allowIndexing`, a pressed one to `allowManualIndexing`, and
     * this is the case that proves the op reads the second: a device that is in use — the normal
     * state of one whose owner just pressed a button — would refuse under the scheduled bar.
     */
    @Test
    fun aPressedPassIgnoresTheScheduledBar() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val p = proxy(
            source,
            conditions = object : IndexingConditions {
                override fun allowIndexing() = false
                override fun allowManualIndexing() = true
            },
            scope = scope(testScheduler),
        )
        consentTo(p, search)

        start(p)
        advanceUntilIdle()

        assertEquals(1, source.reads, "the user's own press must not wait for idle and charging")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /** ⚠ A second press while a pass runs is a SUCCESS — the user asked for indexing to happen. */
    @Test
    fun aSecondPressJoinsTheRunningPassAndSucceeds() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val p = proxy(source, scope = scope(testScheduler))
        consentTo(p, search)

        start(p)
        advanceUntilIdle()
        start(p)
        advanceUntilIdle()

        assertEquals(1, source.reads, "one walk, not two")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * ⚠⚠ The order is a claim, and without a device in BOTH states nothing pins it: swapping the two
     * blocks passes every other case. A device with neither a model nor consent is told to get a
     * model, because that is the first thing it lacks and the one the screen can act on.
     */
    @Test
    fun aDeviceWithNeitherAModelNorConsentIsToldAboutTheModel() = runTest {
        val p = proxy(
            GatedSource(CompletableDeferred()),
            registry = InferenceBackendRegistry(
                listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.QUERY_PLANNING))),
            ),
            scope = scope(testScheduler),
        )

        assertEquals(IndexPassRefused.Reasons.NO_BACKEND, refusalOf(p))
    }

    /** ⚠ And a device that is not ready is told so only once the model and the consent are there. */
    @Test
    fun aFlatBatteryIsReportedAfterTheModelAndTheConsent() = runTest {
        val p = proxy(
            GatedSource(CompletableDeferred()),
            registry = InferenceBackendRegistry(
                listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.QUERY_PLANNING))),
            ),
            conditions = object : IndexingConditions {
                override fun allowIndexing(): Boolean = false
                override fun allowManualIndexing(): Boolean = false
            },
            scope = scope(testScheduler),
        )

        // No model AND a flat battery: the model is the thing to say, because it is the thing to do.
        assertEquals(IndexPassRefused.Reasons.NO_BACKEND, refusalOf(p))
    }

    /**
     * ⚠⚠ A backend for one capability and consent for **another** is not permission to index. Asked as
     * two independent ORs — "some backend?" and "some consent?" — this device passes both and has
     * nothing it may actually do: the pass starts, refuses every file and reports success.
     */
    @Test
    fun aBackendForOneCapabilityAndConsentForAnotherIsNotConsent() = runTest {
        val source = GatedSource(CompletableDeferred())
        val p = proxy(
            source,
            registry = InferenceBackendRegistry(
                listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))),
            ),
            scope = scope(testScheduler),
        )
        // Consent to the half this device has no backend for.
        consentTo(p, tagging)

        assertEquals(IndexPassRefused.Reasons.NO_CONSENT, refusalOf(p))
        assertEquals(0, source.reads, "and nothing is walked to find that out")
    }
}
