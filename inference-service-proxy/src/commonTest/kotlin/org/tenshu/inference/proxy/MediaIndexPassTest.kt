package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.IndexingPaused
import org.tenshu.inference.MediaIndexPass
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.core.entity.Entity
import org.tenshu.sync.ScheduledRunDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scheduled index pass (IN-D6, 1e) — `MediaIndexer`'s first production call site.
 *
 * ⚠ The media *source* is a seam here, as it is in production: the file walk lives in
 * `:file-service-proxy` and Control's proxy composes the two, so this suite is about the pass, not
 * about the file tree.
 */
class MediaIndexPassTest {

    private val thisDevice = "device-a"
    private val tagging = InferenceCapability.CONTENT_TAGGING

    private fun proxy(
        source: MediaSource,
        registry: InferenceBackendRegistry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
        embeddings: EmbeddingStore = InMemoryEmbeddingStore(),
        conditions: IndexingConditions = IndexingConditions { true },
    ): TestProxy = TestProxy(
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
                indexer = MediaIndexer(
                    registry = registry,
                    deviceId = thisDevice,
                    embeddings = embeddings,
                    // A device plugged in and unattended by default — the pass's own gate is
                    // MediaIndexerTest's.
                    conditions = conditions,
                ),
                mediaSource = source,
            )
        },
    )

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
            }
        )
    }

    private fun consentTo(proxy: TestProxy, capability: InferenceCapability) {
        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private suspend fun runPass(proxy: TestProxy) {
        proxy.executionContext.executeAsync<org.tenshu.core.Data>(
            operationId = MediaIndexPass.ID,
            caller = null,
        )
    }

    private fun media(vararg paths: String, bytes: (String) -> ByteArray? = { it.encodeToByteArray() }) =
        MediaSource { paths.map { p -> IndexableMedia(p) { bytes(p) } } }

    @Test
    fun aPassLabelsEveryImageTheSourceOffers() = runTest {
        val p = proxy(media("/a.jpg", "/b.jpg"))
        consentTo(p, tagging)

        runPass(p)

        // ⚠ The marker is what proves the pass ran over each file — it is written even when the
        // model has no opinion, which is what stops a re-read on every future pass.
        assertTrue(p.executionContext.isCurrentForAnyStamp("/a.jpg"))
        assertTrue(p.executionContext.isCurrentForAnyStamp("/b.jpg"))
    }

    /**
     * ⚠⚠ The property that makes a 12-hourly tick affordable: a second pass over an unchanged
     * library must not re-read a single file. `MediaIndexer` checks the staleness stamp *before*
     * calling `bytes()`, so this counts byte reads rather than asserting on a count it computed.
     */
    @Test
    fun aSecondPassOverAnUnchangedLibraryReadsNoBytes() = runTest {
        val reads = mutableListOf<String>()
        val p = proxy(media("/a.jpg", "/b.jpg") { reads += it; it.encodeToByteArray() })
        consentTo(p, tagging)

        runPass(p)
        val afterFirst = reads.size
        reads.clear()

        runPass(p)

        assertEquals(2, afterFirst, "the first pass reads every file")
        assertEquals(emptyList(), reads, "the second pass reads none")
    }

    /**
     * ⚠⚠ Consent is the pass's own gate, not the scheduler's. A device with no consent must run the
     * pass cheaply and write nothing — the scheduled tick fires regardless of what the user has
     * switched on.
     */
    @Test
    fun withNoConsentThePassWritesNothingAndReadsNoBytes() = runTest {
        val reads = mutableListOf<String>()
        val p = proxy(media("/a.jpg") { reads += it; it.encodeToByteArray() })

        runPass(p)

        assertEquals(emptyList(), reads)
        assertTrue(!p.executionContext.isCurrentForAnyStamp("/a.jpg"))
    }

    /** ⚠ With no backend the pass is a counted no-op, not a failure — the honest state pre-model. */
    @Test
    fun withNoBackendThePassIsANoOp() = runTest {
        val reads = mutableListOf<String>()
        val p = proxy(
            media("/a.jpg") { reads += it; it.encodeToByteArray() },
            registry = InferenceBackendRegistry(emptyList()),
        )
        consentTo(p, tagging)

        runPass(p)

        assertEquals(emptyList(), reads)
    }

    /**
     * ⚠⚠ Registering the tick must not be able to take the proxy down. Every call site is a bare
     * `sessionScope.launch { registerMediaIndexSchedule() }` in the platform proxy init, and that
     * scope carries no `CoroutineExceptionHandler` — so a throw reaches the thread's uncaught
     * handler, which on Android kills Control. This proxy wires no sync factory, so `Schedule` does
     * not resolve, which is exactly the reachable case.
     */
    @Test
    fun aMissingScheduleOperationDoesNotEscapeAsAnUncaughtThrow() = runTest {
        // The assertion IS the absence of a throw: `runTest` fails on one, and the production
        // caller has no catch of its own.
        proxy(media()).executionContext.registerMediaIndexSchedule()
    }

    // --- Media pipeline P4: a deferred pass asks the scheduler to come back soon ---------------

    /**
     * ⚠⚠ F3.1: the pass fired at Control's startup declines because the screen is on, and it used
     * to return exactly like a finished pass — so the scheduler waited the whole 12 h period.
     */
    @Test
    fun aPassTheDeviceStateDeclinesAsksForARetryInFifteenMinutes() = runTest {
        val p = proxy(media("/a.jpg"), conditions = IndexingConditions { false })
        consentTo(p, tagging)

        val deferral = assertFailsWith<ScheduledRunDeferred> { runPass(p) }

        assertEquals(MEDIA_INDEX_RETRY_MS, deferral.retryAfterMs)
        assertEquals(15L * 60 * 1000, deferral.retryAfterMs)
    }

    /** A pass that ran returns normally, so the period stands. */
    @Test
    fun aPassThatRanDoesNotAskForARetry() = runTest {
        val p = proxy(media("/a.jpg"))
        consentTo(p, tagging)

        runPass(p) // no throw
    }

    /**
     * ⚠ No consent and no backend are refusals a retry cannot change; deferring them would wake the
     * device every 15 min for nothing.
     */
    @Test
    fun aRefusalARetryCannotFixDoesNotAskForARetry() = runTest {
        runPass(proxy(media("/a.jpg"))) // no consent
        val noBackend = proxy(media("/a.jpg"), registry = InferenceBackendRegistry(emptyList()))
        consentTo(noBackend, tagging)
        runPass(noBackend)
    }

    /** The user's pause is a "stop", not a "not now". */
    @Test
    fun aPausedPassDoesNotAskForARetry() = runTest {
        val p = proxy(media("/a.jpg"))
        consentTo(p, tagging)
        p.write(IndexingPaused, true, deviceId = thisDevice)

        runPass(p) // no throw
    }
}

/** Did any model stamp mark [path] as indexed? — the marker `MediaIndexer` writes per file. */
private fun ExecutionContext.isCurrentForAnyStamp(path: String): Boolean =
    isCurrent(path, InferenceCapability.CONTENT_TAGGING, org.tenshu.inference.ModelProvenance("fake", 1))
