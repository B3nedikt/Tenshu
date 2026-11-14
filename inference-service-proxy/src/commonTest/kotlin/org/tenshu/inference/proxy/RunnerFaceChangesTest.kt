package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MediaIndexRunner.faceChanges] and [faceChangingPasses] — what the iOS Collections tab re-reads
 * People for (`ios-face-grouping.md` §17.6): one move per pass that changed the face template store,
 * however the pass ended, and none for a pass that wrote nothing.
 */
class RunnerFaceChangesTest {

    private val thisDevice = "device-a"
    private val faces = InferenceCapability.FACE_CLUSTERING

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    ).apply {
        write(faces.realmConsent, true)
        write(faces.deviceConsent, true, deviceId = thisDevice)
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(context = tContext, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)),
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

    private fun TestScope.passScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())

    private val store = InMemoryFaceTemplateStore()

    private fun runner(source: MediaSource, scope: CoroutineScope) = MediaIndexRunner(
        MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = setOf(faces)))),
            deviceId = { thisDevice },
            faces = store,
            conditions = IndexingConditions { true },
        ),
        source,
        scope,
    )

    /** ⚠ The fake reads face identities out of the bytes; `""` is a photograph with nobody in it. */
    private fun library(vararg photos: Pair<String, String>) = MediaSource {
        photos.map { (path, who) -> IndexableMedia(path) { who.encodeToByteArray() } }
    }

    @Test
    fun aPassThatWroteTemplatesMovesTheCount_andAPassOverACurrentLibraryDoesNot() = runTest {
        val runner = runner(library("/a.jpg" to "anna"), passScope())
        val proxy = proxy()

        runner.runToCompletion(proxy.executionContext)
        assertEquals(1L, runner.faceChanges.value)

        runner.runToCompletion(proxy.executionContext)
        assertEquals(1L, runner.faceChanges.value, "nothing new: the skip the tab keeps")
    }

    /**
     * ⚠⚠ Codex's case: a first pass over a library with NO faces writes only empty records. People
     * then answers "nobody found", not "index first" — so it is a change.
     */
    @Test
    fun aFirstPassThatFindsNoFacesAnywhereIsAChange() = runTest {
        val runner = runner(library("/landscape.jpg" to "", "/sea.jpg" to ""), passScope())

        val result = runner.runToCompletion(proxy().executionContext)

        assertEquals(0, result.facesEmbedded)
        assertEquals(2, result.faceRecordsWritten)
        assertTrue(result.mayHaveChangedFaces)
        assertEquals(1L, runner.faceChanges.value)
    }

    /** ⚠⚠ A pass stopped after it wrote — an iOS backgrounding mid-library — still changed People. */
    @Test
    fun aPassStoppedAfterWritingMovesTheCount() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = MediaSource {
            listOf(
                IndexableMedia("/a.jpg") { "anna".encodeToByteArray() },
                IndexableMedia("/b.jpg") { gate.await(); "bob".encodeToByteArray() },
            )
        }
        val runner = runner(source, passScope())
        runner.start(proxy().executionContext)
        advanceUntilIdle()
        assertTrue(store.visited("/a.jpg"), "precondition: the first photograph was written")

        runner.cancelRunningPass()

        assertEquals(1L, runner.faceChanges.value)
    }

    /** ⚠ And the skip's side of it: a pass stopped before it wrote anything is no walk (LOW-3). */
    @Test
    fun aPassStoppedBeforeWritingDoesNotMoveTheCount() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = MediaSource { listOf(IndexableMedia("/a.jpg") { gate.await(); "anna".encodeToByteArray() }) }
        val runner = runner(source, passScope())
        runner.start(proxy().executionContext)
        advanceUntilIdle()

        runner.cancelRunningPass()

        assertEquals(0L, runner.faceChanges.value)
    }

    /** ⚠⚠ A refused pass whose withdrawal cleared the store changed People too. */
    @Test
    fun aRefusedPassThatClearedTheStoreMovesTheCount() = runTest {
        val proxy = proxy()
        val runner = runner(library("/a.jpg" to "anna"), passScope())
        runner.runToCompletion(proxy.executionContext)
        proxy.write(faces.deviceConsent, false, deviceId = thisDevice)

        runner.runToCompletion(proxy.executionContext)

        assertEquals(0, store.size())
        assertEquals(2L, runner.faceChanges.value)
    }

    // ── the reader ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCountAtSubscribeIsABaseline_andEachLaterMoveIsAnEmission() = runTest {
        val count = MutableStateFlow(3L)
        var emissions = 0
        val job = launch { faceChangingPasses(count).collect { emissions++ } }
        runCurrent()
        assertEquals(0, emissions, "the count at subscribe predates the reader's own read")
        count.value = 4L
        runCurrent()
        assertEquals(1, emissions)
        job.cancel()
    }

    /**
     * ⚠⚠ MED-1: the standing's ending and the next pass's start, back to back, delivered only the
     * start to a reader on another dispatcher. Two passes here, with no dispatch between them and the
     * reader's: the reader is still told.
     */
    @Test
    fun passesThatEndBackToBackWithNoDispatchBetweenAreStillAnEmission() = runTest {
        val runner = runner(library("/a.jpg" to "anna"), passScope())
        val proxy = proxy()
        var emissions = 0
        val job = launch { faceChangingPasses(runner.faceChanges).collect { emissions++ } }
        runCurrent()

        // Both passes run without the reader being dispatched in between: the first writes, the
        // second finds the library current. A reader of endings saw only the second — no change.
        runner.runToCompletion(proxy.executionContext)
        runner.runToCompletion(proxy.executionContext)
        runCurrent()

        assertEquals(1, emissions, "the first pass's change is not lost behind the second")
        assertEquals(1L, runner.faceChanges.value)
        job.cancel()
    }
}
