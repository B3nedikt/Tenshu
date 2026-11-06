package app.tenshu.mia.data

/**
 * Platform seam for the "set as default assistant" Settings entry
 * (docs/proposals/home-and-mia-apps.md D7, M4).
 *
 * Android binds a real implementation: Mia qualifies for the assistant role via a stub
 * `VoiceInteractionService`, and — because `ROLE_ASSISTANT` is **not** dialog-requestable
 * by apps — [openAssistantSettings] deep-links the system "Default digital assistant"
 * picker (the same shape Alexa uses). Desktop and iOS bind nothing (the entry stays
 * informational; iOS cannot replace Siri — Action Button / App Intents are the ceiling).
 */
interface AssistantRoleLauncher {

    /** True when Mia currently holds the platform's assistant role. */
    fun isRoleHeld(): Boolean

    /** Opens the system surface where the user can pick Mia as the default assistant. */
    fun openAssistantSettings()
}
