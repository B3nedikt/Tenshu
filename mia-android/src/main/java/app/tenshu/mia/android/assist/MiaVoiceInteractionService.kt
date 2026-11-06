package app.tenshu.mia.android.assist

import android.service.voice.VoiceInteractionService

/**
 * The assistant-role qualification stub (docs/proposals/home-and-mia-apps.md D7, M4).
 *
 * Holding Android's "Default digital assistant" role requires a `VoiceInteractionService`;
 * declaring this (plus the session/recognition services in `@xml/mia_voice_interaction_service`)
 * is what makes Mia appear in the system picker. M4 keeps it deliberately minimal — the
 * session just opens the chat. The real voice pipeline (wake word on the speaker/Hub,
 * on-device STT) is a later milestone and slots into this same service.
 */
class MiaVoiceInteractionService : VoiceInteractionService()
