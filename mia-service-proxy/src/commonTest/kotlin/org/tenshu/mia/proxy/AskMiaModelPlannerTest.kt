package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.toTBoolean
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.IntentLabel
import org.tenshu.inference.proxy.TextRegion
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.mia.plan.MiaIntent
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MQ-5: **a model chooses the question, the grammar still fills it in** (§16).
 *
 * ⚠⚠ The property under test is not "a model improves answers" — nothing here contains a model, and
 * a word-count cannot be asked whether its choices are good. It is that the seam is *safe*: off by
 * default, consulted only where the grammar gave up, incapable of reaching a write, unable to
 * return an intent Mia does not implement, and unable to escape a revoked grant. Every one of those
 * is a property of the plumbing, which is exactly what a fake can prove.
 */
class AskMiaModelPlannerTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L

    /**
     * A backend that answers with whatever it was told to, so a test can pin the *consequence* of a
     * choice rather than the choice itself.
     *
     * ⚠ [seen] records what it was offered — the only way to assert the candidate set never
     * contains a write intent.
     */
    private class ScriptedBackend(
        private val answer: String?,
        val seen: MutableList<List<IntentLabel>> = mutableListOf(),
    ) : InferenceBackend {
        override val id: String get() = "scripted"
        override fun supports(capability: InferenceCapability): Boolean = true
        override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null
        override suspend fun embedText(text: String): ImageEmbedding? = null
        override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> =
            emptyList()

        override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

        override suspend fun classifyIntent(
            utterance: String,
            labels: List<IntentLabel>,
        ): String? {
            seen += labels
            return answer
        }
    }

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    /** IN-D3's two switches, both of which must be yes. */
    private fun TestProxy.consentToPlanning(realm: Boolean = true, device: Boolean = true) {
        val capability = InferenceCapability.QUERY_PLANNING
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = capability.realmConsent.id,
                value = realm.toTBoolean(tContext),
            )
        )
        executionContext.saveData(
            DeviceSetting.create(
                context = tContext,
                deviceId = MIA_TEST_DEVICE,
                preferenceId = capability.deviceConsent.id,
                value = device.toTBoolean(tContext),
            )
        )
    }

    @Test
    fun withoutConsentTheModelIsNeverAsked() = runTest {
        // ⚠⚠ The default. Every capability is off until asked for (IN-D3), so a fresh realm gets
        // the deterministic answer and the backend is not consulted at all.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_WORKOUTS.id)
        val proxy = createMiaProxy(backgroundScope, backend)

        val answer = ask(proxy, "kayaking last week")

        assertTrue(backend.seen.isEmpty(), "the model was consulted without consent")
        assertTrue(
            answer.explanation.get().contains("files", ignoreCase = true),
            answer.explanation.get(),
        )
    }

    @Test
    fun withConsentTheModelOverridesTheCatchAllFileSearch() = runTest {
        // "kayaking" is in no vocabulary, so the grammar reaches its last-resort arm and searches
        // FILE NAMES for it — the §14.4/§14.5 failure shape. A model that recognises the sentence
        // as a workout question replaces exactly that.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_WORKOUTS.id)
        val proxy = createMiaProxy(backgroundScope, backend).apply { consentToPlanning() }

        val answer = ask(proxy, "kayaking last week")

        assertEquals(1, backend.seen.size)
        assertTrue(
            answer.explanation.get().contains("workouts", ignoreCase = true),
            answer.explanation.get(),
        )
        // ⭐ The grammar still resolved the window — the model chose the question, not its slots.
        assertTrue(
            answer.explanation.get().contains("last week"),
            "the window came from the grammar: ${answer.explanation.get()}",
        )
    }

    @Test
    fun aWorkingParseIsNeverSecondGuessed() = runTest {
        // ⚠⚠ The guard that keeps MQ-5 optional: a plan the grammar reached through a positive
        // signal stands, whatever a model would have said.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_DEVICES.id)
        val proxy = createMiaProxy(backgroundScope, backend).apply { consentToPlanning() }

        val answer = ask(proxy, "photos from last summer")

        assertTrue(backend.seen.isEmpty(), "a working parse was sent to the model")
        assertTrue(
            answer.explanation.get().contains("summer 2025"),
            answer.explanation.get(),
        )
    }

    @Test
    fun theCandidateSetContainsNoWriteIntent() = runTest {
        // ⚠⚠ §16.2. `CreateEvent` and `ControlDevice` exist and are reachable by the grammar's
        // verb rule; a model is never even shown them, so it cannot pick one.
        val backend = ScriptedBackend(answer = null)
        val proxy = createMiaProxy(backgroundScope, backend).apply { consentToPlanning() }

        ask(proxy, "kayaking last week")

        val offered = backend.seen.single().map { it.id }
        assertTrue(offered.isNotEmpty())
        assertTrue(
            offered.none { it.contains("create", ignoreCase = true) },
            "a write intent was offered: $offered",
        )
        assertTrue(
            offered.none { it.contains("control", ignoreCase = true) },
            "a device command was offered: $offered",
        )
    }

    @Test
    fun anIntentMiaDoesNotImplementIsDiscarded() = runTest {
        // ⚠⚠ The constrained-decoding property. A backend that invents a label gets the same
        // treatment as one that said nothing — the caller can only ever be handed its own ids.
        val backend = ScriptedBackend(answer = "deleteEverything")
        val proxy = createMiaProxy(backgroundScope, backend).apply { consentToPlanning() }

        val answer = ask(proxy, "kayaking last week")

        assertTrue(
            answer.explanation.get().contains("files", ignoreCase = true),
            "an invented intent changed the answer: ${answer.explanation.get()}",
        )
    }

    @Test
    fun aChoiceTheUtteranceCannotFillFallsBackRatherThanRefusing() = runTest {
        // ⚠⚠ "kayaking last week" names no place, so `findPhotosAtPlace` cannot be built. A wrong
        // guess must not COST the user the answer the grammar could still give.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_PHOTOS_AT_PLACE.id)
        val proxy = createMiaProxy(backgroundScope, backend).apply { consentToPlanning() }

        val answer = ask(proxy, "kayaking last week")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertTrue(
            answer.explanation.get().contains("files", ignoreCase = true),
            answer.explanation.get(),
        )
    }

    @Test
    fun aModelChosenSourceStillObeysTheGrant() = runTest {
        // ⚠⚠ MQ-D10's promise made concrete: the generator changed, the boundary did not. A
        // revoked source is refused BY NAME whether the grammar or a model chose it.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_WORKOUTS.id)
        val proxy = createMiaProxy(backgroundScope, backend).apply {
            consentToPlanning()
            executionContext.saveData(
                RealmSetting.create(
                    context = tContext,
                    preferenceId = MiaSource.FITNESS.preference.id,
                    value = false.toTBoolean(tContext),
                )
            )
        }

        val answer = ask(proxy, "kayaking last week")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(
            answer.explanation.get().contains("workouts", ignoreCase = true),
            "should name the source it refused: ${answer.explanation.get()}",
        )
        assertEquals(emptyList<MiaHit>(), answer.hits.toList().filterIsInstance<MiaHit>())
    }

    @Test
    fun oneSwitchOffIsACompleteAnswer() = runTest {
        // IN-D3: realm AND device. Either being no stops the model running.
        val backend = ScriptedBackend(answer = MiaIntent.FIND_WORKOUTS.id)
        val proxy = createMiaProxy(backgroundScope, backend)
            .apply { consentToPlanning(realm = true, device = false) }

        ask(proxy, "kayaking last week")

        assertTrue(backend.seen.isEmpty(), "the device switch did not gate the model")
    }
}
