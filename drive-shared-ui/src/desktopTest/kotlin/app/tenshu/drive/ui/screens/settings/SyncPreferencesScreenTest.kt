package app.tenshu.drive.ui.screens.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class SyncPreferencesScreenTest {

    private val fakeOfflinePreferences = object : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) {}
    }

    @Before
    fun setup() {
        startKoin {
            modules(module {
                viewModel { SyncPreferencesViewModel(fakeOfflinePreferences, NoopSyncRulesPreferences) }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun syncPreferencesDisplaysCorrectly() = composeVmTest { vms ->
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = vms.ownStore(ViewModelStore())
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncPreferencesScreen(onBack = {})
                }
            }
        }

        onNodeWithText("Sync Preferences").assertIsDisplayed()
        // Drive's settings carry no Wi-Fi-only or mobile-data rows; the Wi-Fi-only control
        // lives in Control's settings.
        onNodeWithText("Synced folders").assertIsDisplayed()
        onNodeWithText("Keep files on this device").assertIsDisplayed()
        onNodeWithText("Files kept offline").assertIsDisplayed()
    }
}
