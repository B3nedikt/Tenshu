package app.tenshu.mia.data

/**
 * Capability flags for surfaces that exist ahead of their machinery
 * (docs/proposals/home-and-mia-apps.md D4, audited in M5).
 *
 * [voiceInput] gates the chat's mic affordance: the conversation UI is identical for text
 * and voice, so the button ships now and lights up when the voice pipeline (audio capture
 * → Voice App Service → STT) lands — flipped per-platform by the shell that binds this.
 * The M-series constant is `false` everywhere.
 */
data class MiaCapabilities(
    val voiceInput: Boolean = false,
)
