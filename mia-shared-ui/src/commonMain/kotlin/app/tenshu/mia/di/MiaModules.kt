package app.tenshu.mia.di

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.InMemoryOnboardingStore
import app.tenshu.mia.data.MiaCapabilities
import app.tenshu.mia.data.MockConversationRepository
import app.tenshu.mia.data.OnboardingStore
import app.tenshu.mia.ui.MiaAppViewModel
import app.tenshu.mia.ui.screens.chat.ChatViewModel
import app.tenshu.mia.ui.screens.history.MiaHistoryViewModel
import app.tenshu.mia.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.mia.ui.screens.settings.GrantsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin assembly for Mia (docs/proposals/home-and-mia-apps.md §6). The Mia app shells
 * `startKoin { modules(miaModules()) }`; the HOME app also includes these modules so its
 * embedded "Ask Mia" destination resolves the same bindings (D1 — one conversation
 * surface). The repository is bound behind its interface; the Voice App Service replaces
 * [MockConversationRepository] with no VM/UI change.
 */
fun miaModules(): List<Module> = listOf(miaMockDataModule(), miaViewModelsModule())

fun miaMockDataModule(): Module = module {
    single<ConversationRepository> { MockConversationRepository() }
    // Defaults a platform shell overrides (its module is listed AFTER these, so its
    // binding wins): the durable OnboardingStore (Android prefs / desktop prefs) and,
    // once the voice pipeline lands, a MiaCapabilities with voiceInput on (D4).
    single<OnboardingStore> { InMemoryOnboardingStore() }
    single { MiaCapabilities() }
}

fun miaViewModelsModule(): Module = module {
    // ⚠ `getOrNull` for the announcer: bound only by the client module, so the not-connected
    // shell and tests build the same view model with nothing to announce to.
    viewModel { MiaAppViewModel(get(), getOrNull()) }
    viewModel { OnboardingViewModel(get()) }
    // AV-1: `getOrNull` — a platform that cannot recognise speech ON-DEVICE binds no VoiceInput,
    // and the mic is then absent rather than offering to send audio away (IN-D11).
    viewModel { ChatViewModel(get(), getOrNull(), getOrNull()) }
    viewModel { MiaHistoryViewModel(get()) }
    // MQ-2b: null repository = the not-connected shell; the client module overrides it.
    viewModel { GrantsViewModel(getOrNull()) }
}
