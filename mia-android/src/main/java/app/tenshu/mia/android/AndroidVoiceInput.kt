package app.tenshu.mia.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import app.tenshu.mia.data.VoiceInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android's **on-device** recogniser, and nothing else (AV-1, `inference-service.md` IN-D11).
 *
 * ⚠⚠ `createOnDeviceSpeechRecognizer`, never `createSpeechRecognizer`. The default recogniser is
 * free to ship the audio to a vendor service; the on-device one is not. Using the default silently
 * would break the product's central claim for a feature nobody asked to be online — so
 * [isAvailable] gates the *binding*, and where it is false Mia has no mic at all rather than one
 * that would send a recording away.
 *
 * ⚠ On-device recognition is API 33+, and even there it depends on a downloaded language pack the
 * user may not have. Both cases answer the same way: unavailable.
 *
 * ⚠ `SpeechRecognizer` is main-thread-only — every call below has to arrive on it, which is why
 * the recogniser is created lazily from the UI's own thread and never touched from a background
 * dispatcher.
 */
class AndroidVoiceInput(private val context: Context) : VoiceInput {

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val transcripts: Flow<String> = _transcripts.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * How the Activity asks for `RECORD_AUDIO` (AV-1).
     *
     * ⚠⚠ Asked on the **first tap of the mic**, not at launch. A microphone prompt on a cold start,
     * before the user has expressed any interest in speaking, is the pattern that teaches people to
     * deny by reflex — and Mia is perfectly usable by typing, so there is nothing to gate.
     */
    private var requestPermission: ((granted: Boolean) -> Unit) -> Unit = { it(false) }

    fun attachPermissionRequester(request: (((Boolean) -> Unit) -> Unit)) {
        requestPermission = request
    }

    override fun start() {
        if (_listening.value) return
        if (!hasPermission()) {
            // ⚠ The grant lands out of band; a granted result starts listening, a refusal does
            // nothing at all — the user tapped a mic, they did not ask to be told off.
            requestPermission { granted -> if (granted) start() }
            return
        }
        val recognizer = recognizer ?: create().also { recognizer = it } ?: return
        _partial.value = ""
        _listening.value = true
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // ⚠ Belt and braces with the on-device recogniser: this flag alone is only a
                // *preference* on the default one, which is why the recogniser choice above is the
                // actual guarantee and this is the second lock.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            },
        )
    }

    override fun stop() {
        if (!_listening.value) return
        // ⚠ `stopListening`, not `cancel`: the user finished speaking and expects what they said.
        // Cancel throws the utterance away, which is the wrong end of the same button.
        recognizer?.stopListening()
        _listening.value = false
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun create(): SpeechRecognizer? = runCatching {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(Listener())
        }
    }.getOrElse {
        Log.w(TAG, "on-device recogniser unavailable", it)
        null
    }

    private inner class Listener : RecognitionListener {
        override fun onPartialResults(results: Bundle?) {
            firstResult(results)?.let { _partial.value = it }
        }

        override fun onResults(results: Bundle?) {
            _listening.value = false
            val text = firstResult(results)?.takeIf { it.isNotBlank() } ?: return
            _partial.value = ""
            _transcripts.tryEmit(text)
        }

        override fun onError(error: Int) {
            // ⚠ Silent about it in the UI on purpose: the common errors here are "no speech" and
            // "no match", which is a user who changed their mind, not a fault to report.
            Log.i(TAG, "recognition error $error")
            _listening.value = false
            _partial.value = ""
        }

        override fun onEndOfSpeech() {
            _listening.value = false
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** Releases the microphone. Called when the app's Mia surface goes away. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }

    companion object {
        private const val TAG = "MiaVoice"

        /**
         * Whether this device can recognise speech **without sending audio anywhere**.
         *
         * ⚠⚠ The gate on the whole feature. False on anything below API 33, and false on a 33+
         * device whose language pack is not installed — both of which mean "no mic", never
         * "fall back to the online recogniser".
         */
        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
