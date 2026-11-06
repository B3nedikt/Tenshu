package app.tenshu.home.di

import app.tenshu.home.data.AutomationsRepository
import app.tenshu.home.data.HomeActivityRepository
import app.tenshu.home.data.HomeDevicesRepository
import app.tenshu.home.data.MockAutomationsRepository
import app.tenshu.home.data.MockHomeActivityRepository
import app.tenshu.home.data.EmptyHomeDevicesRepository
import app.tenshu.home.ui.screens.activity.ActivityViewModel
import app.tenshu.home.ui.screens.automations.AutomationEditorViewModel
import app.tenshu.home.ui.screens.automations.AutomationsViewModel
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin assembly for the Home app (docs/proposals/home-and-mia-apps.md §6). Both app shells
 * (`:home-desktop`, `:home-android`) `startKoin { modules(homeModules()) }`.
 *
 * The repositories are bound behind their interfaces — the real service-backed stores
 * replace the mocks here with no VM/UI change, the seam Timeline and Fit proved. This
 * base assembly stays client-less (mock-only, the honest default); a shell that wants
 * the LIVE storage tile additionally installs [homeClientModule] + its platform half
 * AFTER these modules (module order = override order) — `:home-desktop` does today,
 * `:home-android` stays mock-only until the Android Control proxy gains the
 * hub-management wiring.
 *
 * Mia's modules ride along (D1): the embedded "Ask Mia" destination resolves the same
 * [app.tenshu.mia.ui.screens.chat.ChatViewModel] binding the standalone Mia app uses.
 */
fun homeModules(): List<Module> = listOf(mockDataModule(), viewModelsModule()) + app.tenshu.mia.di.miaModules()

fun mockDataModule(): Module = module {
    // ⚠⚠ **Empty, not a mock household** (2026-08-26). This bound a seeded set of rooms and
    // devices that a client module then decorated with live data — so an app that could not reach
    // Control still rendered "Sofa lamp · On · 70%". Demo content, if it returns, belongs behind
    // the proxy as seeded rows a real read returns, never a client-side repository that
    // substitutes itself for one.
    single<HomeDevicesRepository> { EmptyHomeDevicesRepository() }
    single<AutomationsRepository> { MockAutomationsRepository() }
    single<HomeActivityRepository> { MockHomeActivityRepository() }
}

fun viewModelsModule(): Module = module {
    viewModel { DevicesViewModel(get()) }
    viewModel { ActivityViewModel(get()) }
    viewModel { AutomationsViewModel(get(), get()) }
    // The editor id rides as a sentinel "" for create — Koin's typed destructuring
    // doesn't carry nulls through DefinitionParameters reliably.
    viewModel { (rawAutomationId: String) ->
        AutomationEditorViewModel(get(), get(), rawAutomationId.ifEmpty { null })
    }
}
