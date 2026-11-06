package org.tenshu.mia.proxy

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.toTBoolean
import org.tenshu.core.time.Clock
import org.tenshu.core.file.FileService
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.mia.MiaService
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.proxy.homeDeviceServiceProxyImplementationFactory
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.notifications.proxy.initNotificationService

/**
 * A proxy hosting **both** services, because that is the arrangement Mia's whole design
 * rests on: the query layer lives proxy-side precisely so it can read what other services
 * hold, in-process, with no IPC fan-out (MQ-D2). A harness with only `:mia-service-proxy`
 * registered would prove nothing about the thing being built.
 */
/**
 * ⚠ One [MiaActionRegistry] per proxy — the state MQ-D7's two-call confirmation spans.
 * Exposed so a test can drive the tap.
 */
val TestProxy.miaActions: MiaActionRegistry
    get() = miaActionRegistries.getOrPut(this) { miaActionRegistry { 1_000L } }

private val miaActionRegistries = mutableMapOf<TestProxy, MiaActionRegistry>()

fun createMiaProxy(
    coroutineScope: CoroutineScope,
    /**
     * The inference backend the harness registers.
     *
     * ⚠ Parameterised for MQ-5: `FakeInferenceBackend`'s intent classifier is a word-overlap
     * tie-break, which proves the wiring and cannot express "answer THIS id" — so a test about the
     * planner's *behaviour* supplies a scripted backend instead of bending the shared fake.
     */
    backend: org.tenshu.inference.proxy.InferenceBackend = FakeInferenceBackend(),
    /**
     * ⚠ Defaults to the posture Android and desktop ship, so every action test in this module
     * runs against a runtime that really does offer actions. `AskMiaActionOfferTest` passes
     * [org.tenshu.mia.plan.MiaActionOffer.WITHHELD] — the iOS wiring — over the same fixtures.
     */
    actionOffer: org.tenshu.mia.plan.MiaActionOffer = org.tenshu.mia.plan.MiaActionOffer.OFFERED,
    photoSearch: MiaPhotoSearchHome = MiaPhotoSearchHome.THIS_DEVICE,
): TestProxy =
    TestProxy(
        schemaRegistry = createFileTestRegistry().apply {
            registerConfig(miaServiceCommonModule)
            registerConfig(miaServiceClientModule)
            // MQ-2's sources. Both halves of each, for the reason
            // `miaServiceCommonModule` spells out.
            // RE-5: routines are a Mia source, so their rows must be addressable here.
            registerConfig(org.tenshu.routine.common.routineServiceCommonModule)
            registerConfig(org.tenshu.routine.client.routineServiceClientModule)
            registerConfig(timelineServiceCommonModule)
            registerConfig(timelineServiceClientModule)
            registerConfig(healthServiceCommonModule)
            registerConfig(healthServiceClientModule)
            registerConfig(placeServiceCommonModule)
            registerConfig(placeServiceClientModule)
            registerConfig(homeDeviceServiceCommonModule)
            registerConfig(homeDeviceServiceClientModule)
            // IN-4: the label rows Mia's content search reads.
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
            registerConfig(calendarServiceCommonModule)
            registerConfig(calendarServiceClientModule)
        },
        additionalComponents = mapOf<String, org.tenshu.core.core.TComponent>(
            Clock.COMPONENT_ID to FakeClock(initial = 1L),
        ),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, proxy ->
            miaServiceProxyImplementationFactory(
                id = id,
                actions = proxy.miaActions,
                actionOffer = actionOffer,
                photoSearch = photoSearch,
                newId = { "id-${++miaIdCounter}" },
            )
                // MQ-3a: the confirmed action dispatches to the SHIPPED IssueDeviceCommand,
                // so the harness must offer it or the tap has nothing to run.
                ?: homeDeviceServiceProxyImplementationFactory(
                    id,
                    nowEpochMs = { 1_000L },
                    issuerDeviceId = { "test-device" },
                )
                // MQ-3b: the confirmed event dispatches to the SHIPPED SaveEvent.
                ?: calendarServiceProxyImplementationFactory(id = id, nowEpochMs = { 1_000L })
                // MQ-4: the semantic search op, over the SAME store the harness seeds. ⚠ In
                // production nothing wires this yet (`inference-service.md` §16.6) — a gap this
                // harness cannot see, and the reason MQ-4 is provable and not yet reachable.
                ?: inferenceServiceProxyImplementationFactory(
                    id = id,
                    registry = InferenceBackendRegistry(listOf(backend)),
                    embeddings = proxy.miaEmbeddings,
                    deviceId = MIA_TEST_DEVICE,
                )
                ?: fileServiceProxyImplementationFactory(
                    id = id,
                    fileDatabase = proxy.fileDatabase,
                )
        },
    ).apply {
        executionContext.initFileService()
        executionContext.initNotificationService()
        // ⚠ One install covers every `maintained = true` service-data relation in the
        // registry — Moment, Workout, Place and HomeDevice all ride the generic
        // maintainer (`initHomeDeviceService` is nothing but this call). So a row saved
        // below appears in its service's relation with no per-service init here.
        executionContext.installRelationMaintainer()
    }

fun TestProxy.environment(): TenshuEnvironment {
    val executor = LocalTaskExecutor(executionContext)
    return TenshuEnvironment(
        taskExecutor = executor,
        schemaRegistry = schemaRegistry,
        requestChainFactory = { context -> LocalRequestChain(executor, context) },
        tContext = tContext,
    )
}

fun TestProxy.miaService(): MiaService = environment().getService(MiaService.ID)

fun TestProxy.fileService(): FileService = environment().getService(FileService.ID)


private var miaIdCounter = 0

/** The device id the harness consents as — see [org.tenshu.inference.proxy.hasInferenceConsent]. */
const val MIA_TEST_DEVICE = "test-device"

/**
 * ⚠ One [org.tenshu.inference.proxy.EmbeddingStore] per proxy, for the reason the factory's own
 * KDoc gives: the op reads what the indexer wrote, and two stores give a search that finds nothing
 * while indexing reports success.
 */
val TestProxy.miaEmbeddings: org.tenshu.inference.proxy.EmbeddingStore
    get() = miaEmbeddingStores.getOrPut(this) { org.tenshu.inference.proxy.InMemoryEmbeddingStore() }

private val miaEmbeddingStores = mutableMapOf<TestProxy, org.tenshu.inference.proxy.EmbeddingStore>()

/** MQ-2b grant helper for the action tests. */
fun TestProxy.setDeviceGrant(granted: Boolean) {
    executionContext.saveData(
        org.tenshu.preference.RealmSetting.create(
            context = tContext,
            preferenceId = org.tenshu.mia.MiaSource.DEVICES.preference.id,
            value = granted.toTBoolean(tContext),
        )
    )
}
