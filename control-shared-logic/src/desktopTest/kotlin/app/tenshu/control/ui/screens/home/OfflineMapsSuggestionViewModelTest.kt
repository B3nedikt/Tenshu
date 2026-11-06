package app.tenshu.control.ui.screens.home

import app.tenshu.control.data.BundledAppManifestSource
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.OfflineMapsSuggestionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * Home's offline-maps discovery card (`maps-region-delivery.md` §7 phase 7) — when it shows, and
 * more importantly when it must not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsSuggestionViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs(dismissed: Boolean = false) : OfflineMapsSuggestionPreferences {
        val state = MutableStateFlow(dismissed)
        var dismissCalls = 0
        override fun observeDismissed() = state
        override suspend fun setDismissed() { dismissCalls++; state.value = true }
    }

    private fun app(id: String) = InstalledApp(
        appId = id, name = id, iconName = "", version = "1", shortDescription = "",
        installedAt = 0L, verified = true, deviceCount = 1,
    )

    private fun vm(
        vms: OwnedViewModels,
        prefs: OfflineMapsSuggestionPreferences = FakePrefs(),
        apps: List<InstalledApp> = listOf(app(BundledAppManifestSource.MAPS_APP_ID)),
        regionInstalled: Boolean? = false,
    ) = vms.own(OfflineMapsSuggestionViewModel(prefs, flowOf(apps)) { regionInstalled })

    @Test
    fun showsWhenAMapAppIsInstalledAndNoRegionIs() = vmTest { vms ->
        assertTrue(vm(vms, ).uiState.value.visible)
    }

    @Test
    fun fitAloneIsEnough() = vmTest { vms ->
        // ⚠ The case that decided the gate's design: Fit renders maps but declares NO map-region
        // permission, so the obvious signal (`AppDetail.services`) would have excluded it — and a
        // Fit-only user is precisely who would never otherwise discover offline regions.
        assertTrue(vm(vms, apps = listOf(app(BundledAppManifestSource.FIT_APP_ID))).uiState.value.visible)
    }

    @Test
    fun noMapAppMeansNoSuggestion() = vmTest { vms ->
        // A Drive-only user has nothing that draws a map; offering them regions is noise.
        assertFalse(vm(vms, apps = listOf(app(BundledAppManifestSource.DRIVE_APP_ID))).uiState.value.visible)
        assertFalse(vm(vms, apps = emptyList()).uiState.value.visible)
    }

    @Test
    fun anAlreadyDownloadedRegionMeansTheFeatureWasFound() = vmTest { vms ->
        assertFalse(vm(vms, regionInstalled = true).uiState.value.visible)
    }

    @Test
    fun anUnknownRegionStateHidesIt() = vmTest { vms ->
        // ⚠ null = the store read failed, which is NOT "no regions". Same rule as
        // RecoveryAffordanceViewModel's "zero means we have not looked": an unknown answer must not
        // be spent as the actionable one.
        assertFalse(vm(vms, regionInstalled = null).uiState.value.visible)
    }

    @Test
    fun dismissingHidesItPermanently() = vmTest { vms ->
        val prefs = FakePrefs()
        val model = vm(vms, prefs = prefs)
        assertTrue(model.uiState.value.visible)

        model.onDismiss()

        assertTrue(prefs.state.value, "the dismissal must be persisted, not just hidden in memory")
        assertFalse(model.uiState.value.visible)
    }

    @Test
    fun anAlreadyDismissedDeviceNeverShowsIt() = vmTest { vms ->
        assertFalse(vm(vms, prefs = FakePrefs(dismissed = true)).uiState.value.visible)
    }
}
