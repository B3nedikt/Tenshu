package app.tenshu.mia.android.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Stub recognizer: the voice-interaction metadata requires naming one, but Mia's STT is
 * the (unbuilt) voice pipeline — until then any recognition request errors out honestly.
 */
class MiaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
