package app.tenshu.mia.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speaking to Mia instead of typing (AV-1, `inference-service.md` IN-D11).
 *
 * ⭐ **Voice needs no query-layer change at all** — MQ-1 was built for this: *a transcript is just
 * an utterance*. There is no new intent, no new op and no new validator surface; this interface
 * ends at the text field.
 *
 * ⚠⚠ **On-device recognition or nothing.** Android's default `SpeechRecognizer` may route audio to
 * a vendor service, and using that path silently would break the product's central claim for a
 * feature nobody asked to be online. So a platform binds this **only** when it can recognise
 * on-device; where it cannot, the binding is absent, the mic does not appear, and Settings says
 * why — exactly as a de-Googled device gets no fused-location provider rather than a broken one.
 *
 * ⚠ A DI-bound interface rather than `expect`/`actual`, the same idiom as Fit's `HealthFilePicker`
 * and Timeline's `TimelineImportControl`: absence is the "not available here" signal, and it is
 * checked at the binding site rather than at every call site.
 */
interface VoiceInput {

    /** Whether the recogniser is listening right now — drives the mic's state. */
    val listening: StateFlow<Boolean>

    /**
     * The transcript as it is being spoken, or `""`.
     *
     * ⚠ Partial results are shown in the composer as they arrive, because a recogniser that
     * appears to do nothing for four seconds reads as broken.
     */
    val partial: StateFlow<String>

    /**
     * Final transcripts, one per completed utterance.
     *
     * ⚠⚠ A transcript is **put in the composer, not sent**. A misheard word is the failure voice
     * actually has, and MQ-D7's confirmation only covers the *write* intents — a misheard search
     * would simply answer the wrong question with no way for the user to see that it had. One tap
     * is not a burden; a silently wrong answer is.
     */
    val transcripts: Flow<String>

    /** Begins listening. A second call while [listening] is a no-op. */
    fun start()

    /** Stops listening and emits whatever was recognised. */
    fun stop()
}
