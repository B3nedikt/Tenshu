package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR-6, the processing wake (`ios-on-device-inference.md` §16): the library indexed overnight on the
 * charger, in a `BGProcessingTask`. The bridge on fakes, and over the real runner and indexer.
 */
class ProcessingWakeTest {

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    // ─── The fakes ─────────────────────────────────────────────────────────────────────────────

    private class Events {
        val log = mutableListOf<String>()
        operator fun plusAssign(event: String) {
            log += event
        }
    }

    private class Task(private val events: Events) : WakeTask {
        val completions = mutableListOf<Boolean>()
        override fun complete(success: Boolean) {
            completions += success
            events += "complete:$success"
        }
    }

    private class Scheduler(private val events: Events) : WakeScheduler {
        override fun submit() {
            events += "submit"
        }

        override fun withdraw() {
            events += "withdraw"
        }
    }

    private class Window(private val events: Events) : BackgroundWindow {
        override fun begin(name: String): Any? = name.also { events += "window:begin" }
        override fun end(handle: Any?) {
            events += "window:end"
        }
    }

    private fun TestScope.unconfined(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
    private fun TestScope.queued(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    private fun wake(
        scope: CoroutineScope,
        events: Events,
        work: WakeWork = WakeWork.LEFT,
        scheduler: WakeScheduler? = Scheduler(events),
        runPass: suspend () -> PassOutcome = {
            events += "pass"
            PassOutcome.COMPLETED
        },
        stopPass: suspend () -> Unit = { events += "stop" },
    ): ProcessingWake = ProcessingWake(
        scope = scope,
        scheduler = { scheduler },
        work = {
            events += "work"
            work
        },
        runPass = runPass,
        stopPass = stopPass,
        released = { events += "released" },
        window = Window(events),
    )

    // ─── The scheduled bar ─────────────────────────────────────────────────────────────────────

    private fun state(thermal: ThermalState = ThermalState.NOMINAL, lowPower: Boolean = false, charging: Boolean? = true, foreground: Boolean = false) =
        DeviceIndexingState(foreground, thermal, lowPower, batteryLevel = 0.9f, charging = charging)

    /** ⚠⚠ No wake ⇒ the scheduled bar says no, whatever the phone says; inside one, the phone decides. */
    @Test
    fun noWakeMeansTheScheduledBarSaysNo() {
        var phone = state()
        var inWake = false
        assertFalse(ForegroundIndexingConditions { phone }.allowIndexing(), "a scheduled pass outside any wake (no wake exists)")
        val conditions = ForegroundIndexingConditions(read = { phone }, inWake = { inWake })
        assertFalse(conditions.allowIndexing(), "a scheduled pass outside the wake")
        inWake = true
        assertTrue(conditions.allowIndexing(), "a scheduled pass inside the wake, charging, cool")
        phone = state(foreground = true)
        assertTrue(conditions.allowIndexing(), "the foreground is not asked of a wake")
        phone = state(charging = null)
        assertTrue(conditions.allowIndexing(), "an unknown charging state is the grant's word")
        phone = state(thermal = ThermalState.SERIOUS)
        assertFalse(conditions.allowIndexing(), "a wake yields to heat at .serious")
        phone = state(lowPower = true)
        assertFalse(conditions.allowIndexing(), "Low Power Mode")
        phone = state(charging = false)
        assertFalse(conditions.allowIndexing(), "D5: never on battery")
        // ⚠ The manual bar is not widened by a wake: still the foreground's.
        phone = state(foreground = false)
        assertFalse(conditions.allowManualIndexing(), "a wake is not the foreground for a press")
    }

    // ─── Arming ────────────────────────────────────────────────────────────────────────────────

    /** ⚠ Submitted on leaving the foreground only with something asked and photos left; never without a scheduler. */
    @Test
    fun aLeaveArmsTheWakeOnlyWithSomethingAskedAndPhotosLeft() = runTest {
        for (work in WakeWork.entries) {
            val events = Events()
            wake(unconfined(), events, work = work).leftForeground()
            advanceUntilIdle()
            val submitted = "submit" in events.log
            assertEquals(work == WakeWork.LEFT, submitted, "$work: ${events.log}")
            assertEquals(listOf("window:begin", "work") + (if (submitted) listOf("submit") else emptyList()) + "window:end", events.log, "$work")
        }
        val none = Events()
        wake(unconfined(), none, scheduler = null).leftForeground()
        advanceUntilIdle()
        assertEquals(emptyList(), none.log, "with no scheduler nothing is read, held or submitted")
    }

    /** A read that throws is nothing asked: absence is NO. */
    @Test
    fun aWorkReadThatThrowsArmsNothing() = runTest {
        val events = Events()
        ProcessingWake(
            scope = unconfined(),
            scheduler = { Scheduler(events) },
            work = { error("the realm is locked") },
            runPass = { PassOutcome.COMPLETED },
            stopPass = {},
            released = {},
        ).leftForeground()
        advanceUntilIdle()
        assertFalse("submit" in events.log)
    }

    /** ⚠ A leave while a wake runs submits nothing: the wake re-armed itself when it began. */
    @Test
    fun aLeaveDuringAWakeSubmitsNothing() = runTest {
        val events = Events()
        val gate = CompletableDeferred<Unit>()
        val wake = wake(unconfined(), events, runPass = {
            gate.await()
            PassOutcome.COMPLETED
        })
        wake.launched(Task(events))
        events.log.clear()
        wake.leftForeground()
        advanceUntilIdle()
        assertEquals(emptyList(), events.log)
        gate.complete(Unit)
    }

    // ─── The wake ──────────────────────────────────────────────────────────────────────────────

    /** ⚠⚠ The handler re-arms BEFORE it works, then completes with success and withdraws once current. */
    @Test
    fun theWakeReArmsBeforeItWorksAndACompletedPassWithdrawsTheRequest() = runTest {
        val events = Events()
        val task = Task(events)
        wake(unconfined(), events).launched(task, prepare = { events += "prepare" })
        advanceUntilIdle()
        assertEquals(listOf("prepare", "work", "submit", "pass", "withdraw", "released", "complete:true"), events.log)
        assertEquals(listOf(true), task.completions)
    }

    /** ⚠⚠ Two switches, absence is NO: a wake with nothing asked runs no pass, re-arms nothing, completes. */
    @Test
    fun aWakeWithNothingAskedRunsNoPassAndIsNotReArmed() = runTest {
        val events = Events()
        val task = Task(events)
        wake(unconfined(), events, work = WakeWork.NOT_ASKED).launched(task)
        advanceUntilIdle()
        assertEquals(listOf("work", "released", "complete:true"), events.log)
    }

    @Test
    fun aWakeWithNothingLeftRunsNoPassAndWithdraws() = runTest {
        val events = Events()
        wake(unconfined(), events, work = WakeWork.NONE_LEFT).launched(Task(events))
        advanceUntilIdle()
        assertEquals(listOf("work", "withdraw", "released", "complete:true"), events.log)
    }

    /** Each ending, completed once: success on a completed pass alone, the request kept for the next night otherwise. */
    @Test
    fun eachOutcomeCompletesTheTaskOnce() = runTest {
        for (outcome in PassOutcome.entries) {
            val events = Events()
            val task = Task(events)
            wake(unconfined(), events, runPass = { outcome }).launched(task)
            advanceUntilIdle()
            assertEquals(listOf(outcome == PassOutcome.COMPLETED), task.completions, "$outcome")
            assertEquals(outcome == PassOutcome.COMPLETED, "withdraw" in events.log, "$outcome: ${events.log}")
        }
    }

    /** ⚠⚠ A throwing pass completes the task once with failure, and never escapes the coroutine. */
    @Test
    fun aFailingPassCompletesTheTaskOnceWithFailure() = runTest {
        val events = Events()
        val task = Task(events)
        val scope = unconfined()
        wake(scope, events, runPass = { throw IllegalStateException("disk full") }).launched(task)
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions)
        assertTrue(scope.coroutineContext[Job]!!.isActive, "the throw escaped the wake and failed the graph's scope")
        assertTrue(events.log.indexOf("released") < events.log.indexOf("complete:false"))
    }

    /** ⚠ The wake carries the pass while it runs, not after it, and not from its expiry on. */
    @Test
    fun theWakeCarriesThePassWhileItRunsAndNotFromItsExpiry() = runTest {
        val events = Events()
        val gate = CompletableDeferred<Unit>()
        // ⚠ A pass the cancel does not reach at once: the wake is still current after the expiry.
        val wake = wake(unconfined(), events, runPass = {
            withContext(NonCancellable) { gate.await() }
            PassOutcome.COMPLETED
        })
        assertFalse(wake.carriesPass())
        wake.launched(Task(events))
        assertTrue(wake.carriesPass())
        wake.expired()
        assertFalse(wake.carriesPass(), "the scheduled bar must say no at once on an expiry")
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(wake.carriesPass())
    }

    /** ⚠ An expiry that lands before the wake's job is known (inside its first, synchronous run) still cancels it. */
    @Test
    fun anExpiryBeforeTheJobIsKnownStillStopsTheWakeAtOnce() = runTest {
        val events = Events()
        val task = Task(events)
        lateinit var wake: ProcessingWake
        wake = wake(unconfined(), events, runPass = {
            wake.expired()
            awaitCancellation()
        })
        wake.launched(task)
        runCurrent()
        assertEquals(listOf(false), task.completions, "the wake waited for the backstop")
    }

    /** ⚠⚠ An expiry: the pass is stopped, and the task completed with failure only once it has stopped. */
    @Test
    fun anExpiryCompletesTheTaskWithFailureOnlyOnceThePassHasStopped() = runTest {
        val events = Events()
        val task = Task(events)
        val stopped = CompletableDeferred<Unit>()
        val wake = wake(
            queued(),
            events,
            runPass = { awaitCancellation() },
            stopPass = {
                events += "stop"
                stopped.await()
            },
        )
        wake.launched(task)
        runCurrent()
        wake.expired()
        advanceTimeBy(ProcessingWake.EXPIRY_STOP_MS / 2)
        runCurrent()
        assertEquals(emptyList(), task.completions, "completed before the pass stopped")
        assertTrue("stop" in events.log)
        stopped.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions)
        assertTrue(events.log.indexOf("released") < events.log.indexOf("complete:false"), "${events.log}")
    }

    /** ⚠⚠ A pass that never stops (its thread parked at the store's closed gate) does not keep the task open. */
    @Test
    fun anExpiredWakeWhosePassNeverStopsIsStillCompletedOnce() = runTest {
        val events = Events()
        val task = Task(events)
        val wake = wake(queued(), events, runPass = { awaitCancellation() }, stopPass = { awaitCancellation() })
        wake.launched(task)
        runCurrent()
        wake.expired()
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions)
    }

    /** ⚠⚠ …nor does a wake held where its cancel cannot reach: the backstop completes it, once. */
    @Test
    fun anExpiredWakeHeldWhereTheCancelCannotReachIsCompletedByTheBackstop() = runTest {
        val events = Events()
        val task = Task(events)
        val held = CompletableDeferred<Unit>()
        val wake = wake(queued(), events, runPass = {
            withContext(NonCancellable) { held.await() }
            PassOutcome.COMPLETED
        })
        wake.launched(task)
        runCurrent()
        wake.expired()
        advanceTimeBy(ProcessingWake.EXPIRY_STOP_MS + ProcessingWake.EXPIRY_BACKSTOP_MARGIN_MS + 1)
        runCurrent()
        assertEquals(listOf(false), task.completions, "the backstop did not complete the task")
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions, "completed twice")
        assertEquals(1, events.log.count { it == "released" }, "released twice: ${events.log}")
    }

    /** ⚠⚠ An expiry racing a normal finish completes the task exactly once. */
    @Test
    fun anExpiryRacingAFinishCompletesTheTaskOnce() = runTest {
        val events = Events()
        val task = Task(events)
        lateinit var wake: ProcessingWake
        wake = wake(unconfined(), events, runPass = {
            // The pass ended; the expiry lands before the wake's `finally` runs.
            wake.expired()
            PassOutcome.COMPLETED
        })
        wake.launched(task)
        advanceUntilIdle()
        assertEquals(1, task.completions.size, "${task.completions}")
        wake.expired()
        advanceUntilIdle()
        assertEquals(1, task.completions.size, "an expiry after the end completed it again")
    }

    /** And an expiry after a finished wake changes nothing. */
    @Test
    fun anExpiryAfterTheWakeEndedIsNothing() = runTest {
        val events = Events()
        val task = Task(events)
        val wake = wake(unconfined(), events)
        wake.launched(task)
        advanceUntilIdle()
        wake.expired()
        advanceUntilIdle()
        assertEquals(listOf(true), task.completions)
    }

    @Test
    fun aSecondLaunchWhileOneRunsIsCompletedAtOnceWithFailure() = runTest {
        val events = Events()
        val gate = CompletableDeferred<Unit>()
        val wake = wake(unconfined(), events, runPass = {
            gate.await()
            PassOutcome.COMPLETED
        })
        val first = Task(events)
        val second = Task(events)
        wake.launched(first)
        wake.launched(second)
        assertEquals(listOf(false), second.completions)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(true), first.completions)
    }

    /** ⚠⚠ A graph torn down under a live wake completes it with failure. */
    @Test
    fun aTeardownCompletesALiveWakeWithFailure() = runTest {
        val events = Events()
        val task = Task(events)
        val scope = unconfined()
        wake(scope, events, runPass = { awaitCancellation() }).launched(task)
        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions)
    }

    // ─── Over the real runner and indexer ──────────────────────────────────────────────────────

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    ).apply {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
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
                    composite = DeviceSetting.create(context = tContext, deviceId = deviceId, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)),
                )
            },
        )
    }

    /** A library whose photos each wait for their own gate: what is in flight at an expiry. */
    private class PerPhotoSource(private val names: List<String>) : MediaSource {
        val gates: List<CompletableDeferred<Unit>> = names.map { CompletableDeferred() }
        val read = mutableListOf<String>()

        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            names.mapIndexed { i, name ->
                IndexableMedia("/photos/$name") {
                    read += name
                    gates[i].await()
                    "bytes of $name".encodeToByteArray()
                }
            }
    }

    /** Photos' wiring, over the real runner: the scheduled bar reads the wake; the pass is the runner's scheduled one. */
    private inner class Overnight(
        val source: MediaSource,
        scope: CoroutineScope,
        val events: Events = Events(),
        /** Photos in front: a press's pass may run. */
        foreground: Boolean = false,
        /** The wake's pass; Photos' own by default. */
        runPassOf: (Overnight) -> (suspend () -> PassOutcome) = { night -> { runWakePassOver(night.runner, night.context) } },
    ) {
        val context = proxy().executionContext
        val holder = IndexProgressHolder()
        lateinit var wake: ProcessingWake
        val conditions = ForegroundIndexingConditions(
            read = { DeviceIndexingState(foreground, ThermalState.NOMINAL, false, 1f, true) },
            inWake = { wake.carriesPass() },
        )
        val embeddings = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(
            MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = setOf(capability)))),
                deviceId = { thisDevice },
                embeddings = embeddings,
                conditions = conditions,
                progress = holder,
            ),
            source,
            scope,
            progress = holder,
        )

        init {
            wake = ProcessingWake(
                scope = scope,
                scheduler = { Scheduler(events) },
                work = { WakeWork.LEFT },
                runPass = runPassOf(this),
                // ⚠ As Photos wires it: an expiry stops only a scheduled pass.
                stopPass = { runner.cancelRunningScheduledPass() },
                released = { events += "released" },
            )
        }
    }

    /**
     * ⚠⚠ An expiry mid-pass stops it **at the next photo** — the one in flight abandoned, none after it
     * read — the task completes with failure once it has, and the next wake resumes from the store.
     */
    @Test
    fun anExpiryMidPassStopsAtTheNextPhotoAndTheNextWakeResumesFromTheStore() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg", "c.jpg"))
        val night = Overnight(source, unconfined())
        val first = Task(night.events)
        night.wake.launched(first)
        source.gates[0].complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read, "the wake's pass did not run under the scheduled bar")
        assertEquals(PassStanding.Running(1, manual = false), night.runner.standing.value)

        night.wake.expired()
        advanceUntilIdle()
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read, "a photo was read after the expiry")
        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), night.runner.standing.value)
        assertEquals(listOf(false), first.completions)

        val second = Task(night.events)
        night.wake.launched(second)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg", "b.jpg", "c.jpg"), source.read, "the next wake did not resume where the store left off")
        assertEquals(listOf(true), second.completions)
    }

    /** ⚠⚠ With no wake the runner's scheduled pass is refused before it walks anything. */
    @Test
    fun withNoWakeTheScheduledPassWalksNothing() = runTest {
        val source = PerPhotoSource(listOf("a.jpg"))
        val night = Overnight(source, unconfined())
        val result = night.runner.runToCompletion(night.context, manual = false)
        assertTrue(result.deferred)
        assertEquals(emptyList(), source.read)
    }

    /** ⚠⚠ A leave inside a wake cancels nothing; outside one, it still cancels. */
    @Test
    fun aLeaveInsideAWakeCancelsNothingAndOutsideOneItStillDoes() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg"))
        val scope = unconfined()
        val night = Overnight(source, scope)
        var cancels = 0
        val driver = ForegroundIndexing(
            scope = scope,
            conditions = night.conditions,
            changes = emptyFlow(),
            memoryWarnings = emptyFlow(),
            runnerStanding = { night.runner.standing },
            startPass = { StartAnswer.Started },
            cancelPass = {
                cancels++
                night.runner.cancelRunningPass()
            },
            closeSessions = {},
            clock = { 0L },
            wakeCarriesPass = { night.wake.carriesPass() },
        )
        val task = Task(night.events)
        night.wake.launched(task)
        advanceUntilIdle()
        driver.leftForeground()
        advanceUntilIdle()
        assertEquals(0, cancels, "a leave cancelled the wake's pass")
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf(true), task.completions)
        driver.leftForeground()
        advanceUntilIdle()
        assertEquals(1, cancels, "a leave outside a wake no longer stops")
    }

    /** ⚠⚠ A wake that meets a pass the user started leaves it alone: no join, no stop, its request kept. */
    @Test
    fun aWakeLeavesAPassTheUserStartedAlone() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg"))
        val night = Overnight(source, unconfined(), foreground = true)
        assertTrue(night.runner.start(night.context, manual = true))
        val task = Task(night.events)
        night.wake.launched(task)
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions, "the wake did not end at once beside the user's pass")
        assertEquals(PassStanding.Running(1, manual = true), night.runner.standing.value, "the wake touched the user's pass")
        assertFalse("withdraw" in night.events.log, "a wake that did nothing withdrew the request")
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read)
        assertEquals(PassOutcome.COMPLETED, night.runner.outcomeOf(1)?.await())
    }

    /**
     * ⚠⚠ Even a wake that joined the user's pass (a press between its read and the join) never ends it:
     * the expiry's stop is for a scheduled pass only.
     */
    @Test
    fun anExpiryNeverStopsAPassTheUserStarted() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg"))
        val night = Overnight(source, unconfined(), foreground = true, runPassOf = { n -> { wakeOutcomeOf(n.runner.runToCompletion(n.context, manual = false)) } })
        assertTrue(night.runner.start(night.context, manual = true))
        val task = Task(night.events)
        night.wake.launched(task)
        advanceUntilIdle()
        night.wake.expired()
        advanceUntilIdle()
        assertEquals(listOf(false), task.completions)
        assertEquals(PassStanding.Running(1, manual = true), night.runner.standing.value, "the wake's expiry stopped the user's pass")
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(PassOutcome.COMPLETED, night.runner.outcomeOf(1)?.await(), "the user's pass did not run to its end")
    }
}
