package app.tenshu.mia.android.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.tenshu.mia.data.AssistantRoleLauncher

/**
 * Android's [AssistantRoleLauncher] (docs/proposals/home-and-mia-apps.md D7, M4).
 *
 * `ROLE_ASSISTANT` is not dialog-requestable by apps (unlike dialer/SMS), so the entry
 * deep-links the system assistant picker — the same shape Alexa uses. Mia appears there
 * because [MiaVoiceInteractionService] is declared.
 */
class AndroidAssistantRoleLauncher(private val context: Context) : AssistantRoleLauncher {

    override fun isRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    override fun openAssistantSettings() {
        // The "Assist & voice input" / default-assistant surface; fall back to the
        // default-apps list on OEMs that don't resolve it.
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }
}
