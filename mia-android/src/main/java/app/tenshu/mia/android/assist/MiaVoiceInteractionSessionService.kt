package app.tenshu.mia.android.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import app.tenshu.mia.android.MainActivity

class MiaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MiaAssistSession(this)
}

/**
 * The M4 assist session: invoking the assistant (long-press home / corner swipe) opens
 * Mia's chat and dismisses the (contentless) overlay. A richer in-session overlay UI is
 * the voice milestone's job.
 */
private class MiaAssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The assistant-blessed launch path (exempt from background-activity limits).
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent)
        }
        hide()
    }
}
