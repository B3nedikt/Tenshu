package app.tenshu.mia.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Device-local persistence for the one-time onboarding gate
 * (docs/proposals/home-and-mia-apps.md §4, M5). Deliberately NOT realm state: whether
 * *this device* has walked the intro is a device fact (a second device should show its own
 * onboarding — it has its own assistant-role and widget setup to offer).
 *
 * Platform shells bind their own implementation (Android SharedPreferences, desktop
 * java.util.prefs); [InMemoryOnboardingStore] is the default for tests/previews. iOS
 * gates in SwiftUI (`@AppStorage`) — its onboarding never enters Kotlin.
 */
interface OnboardingStore {

    /** Emits the current onboarded state, immediately on collect. */
    fun onboarded(): Flow<Boolean>

    suspend fun setOnboarded()
}

class InMemoryOnboardingStore(initial: Boolean = false) : OnboardingStore {

    private val flow = MutableStateFlow(initial)

    override fun onboarded(): Flow<Boolean> = flow

    override suspend fun setOnboarded() {
        flow.value = true
    }
}
