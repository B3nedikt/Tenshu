package app.tenshu.mia.android

import android.content.Context
import app.tenshu.mia.data.OnboardingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android's durable onboarding flag (home-and-mia-apps.md §4, M5): plain
 * SharedPreferences — a device-local UI fact, deliberately not realm state (each device
 * shows its own intro; it has its own assistant-role + widget setup to offer).
 */
class SharedPreferencesOnboardingStore(context: Context) : OnboardingStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))

    override fun onboarded(): Flow<Boolean> = flow

    override suspend fun setOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        flow.value = true
    }

    private companion object {
        const val PREFS = "tenshu_mia_ui"
        const val KEY_ONBOARDED = "onboarded"
    }
}
