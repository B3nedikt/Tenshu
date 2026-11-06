package app.tenshu.mia.android

import android.app.Application
import app.tenshu.mia.android.assist.AndroidAssistantRoleLauncher
import app.tenshu.mia.data.AssistantRoleLauncher
import app.tenshu.mia.data.OnboardingStore
import app.tenshu.mia.di.miaClientModule
import app.tenshu.mia.di.miaModules
import app.tenshu.mia.di.miaPlatformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import app.tenshu.mia.data.MiaCapabilities
import app.tenshu.mia.data.VoiceInput
import org.koin.dsl.module
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Mia Android Application (docs/proposals/home-and-mia-apps.md §6). Mock modules — still no
// Control client — plus the Android-only assistant-role binding (M4): Settings' "Set as
// default assistant" entry lights up only where this module provides the launcher.
class MiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (the smoke gate asserts Control + clients share this).
        // ⚠ Unlike Home, Mia parses no Control-authored rows today, so it cannot actually be
        // out of lockstep in a way that garbles anything — it emits this so the gate reads a
        // version from *every* installed app rather than warning about a silent one, and so
        // the beacon is already in place if Mia ever gains a real client.
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))

        startKoin {
            androidLogger()
            androidContext(this@MiaApplication)
            // ⚠ Order is override order: the client modules come AFTER miaModules, so
            // TenshuConversationRepository replaces the M3 mock (MQ-1).
            modules(miaModules() + listOf(miaClientModule, miaPlatformClientModule) + miaAndroidModule())
        }
    }

    private fun miaAndroidModule() = module {
        single<AssistantRoleLauncher> { AndroidAssistantRoleLauncher(this@MiaApplication) }

        // AV-1 (`inference-service.md` IN-D11): voice input, bound ONLY where the device can
        // recognise speech without sending audio anywhere. ⚠⚠ Where it cannot — below API 33, or
        // with no language pack installed — there is no binding, no mic, and Settings says why.
        // The alternative (the default recogniser) may ship the audio to a vendor service, which
        // is not a trade this product gets to make quietly.
        if (AndroidVoiceInput.isAvailable(this@MiaApplication)) {
            single { AndroidVoiceInput(this@MiaApplication) }
            single<VoiceInput> { get<AndroidVoiceInput>() }
            single { MiaCapabilities(voiceInput = true) }
        }
        // Listed after miaModules → overrides the in-memory default (M5 durable gate).
        single<OnboardingStore> { SharedPreferencesOnboardingStore(this@MiaApplication) }
    }
}
