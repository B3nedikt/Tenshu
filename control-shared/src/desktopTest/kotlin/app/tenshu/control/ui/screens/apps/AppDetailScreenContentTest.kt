package app.tenshu.control.ui.screens.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.AppDetail
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.PermissionRow
import app.tenshu.control.data.UninstallScope
import org.tenshu.core.app.CapabilityClass
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stateless `AppDetailScreenContent` rendering + interaction (no VM / proxy). */
class AppDetailScreenContentTest {

    @get:Rule
    val rule = createComposeRule()

    private val detail = AppDetail(
        app = InstalledApp(
            appId = "app.tenshu.drive",
            name = "Drive",
            iconName = "drive",
            version = "1.0.0",
            shortDescription = "Files and folders.",
            installedAt = 1_716_000_000_000L,
            verified = true,
            deviceCount = 1,
        ),
        permissions = listOf(
            PermissionRow(
                serviceId = "tenshu/file/system",
                operationId = "tenshu/file/system/uploadFiles",
                humanLabel = "Add files",
                capabilityClass = CapabilityClass.Picker,
                scopeLabel = null,
                grant = GrantState.AskEachTime,
                lastUsedAt = 0L,
            ),
        ),
        services = listOf("tenshu/file/system"),
    )

    @Test
    fun declaredAccessRendersAsAReadOnlyList() {
        rule.setContent {
            AppDetailScreenContent(detail, loading = false, onUninstall = {}, onServiceClick = {}, onBack = {})
        }
        rule.onNodeWithText("Add files").assertIsDisplayed()
        rule.onNodeWithText("Declared access").assertIsDisplayed()
    }

    /**
     * ⚠ The guard that matters (`control-app-catalogue.md` C0). The tri-state and *Revoke all*
     * wrote a real, synced `AppGrant` row that **no dispatch path reads**, so the screen offered
     * a boundary that does not exist. Re-adding a control here is only correct once grants are
     * enforced — until then this test is what makes the regression loud instead of silent.
     */
    @Test
    fun noPermissionControlIsOffered() {
        rule.setContent {
            AppDetailScreenContent(detail, loading = false, onUninstall = {}, onServiceClick = {}, onBack = {})
        }
        rule.onNodeWithText("Granted").assertDoesNotExist()
        rule.onNodeWithText("Denied").assertDoesNotExist()
        rule.onNodeWithText("Ask each time").assertDoesNotExist()
        rule.onNodeWithText("Revoke all permissions").assertDoesNotExist()
        // …and the screen says so rather than leaving the user to assume.
        rule.onNodeWithText(
            "Declared by the app. Tenshu does not currently restrict what an installed app can do.",
        ).assertIsDisplayed()
    }

    /**
     * C3: the bare appId gets an explanation instead of being left to read as a naming bug —
     * the incident that opened `control-app-catalogue.md`.
     */
    @Test
    fun anUnrecognisedAppExplainsItselfRatherThanShowingABareId() {
        val unknown = detail.copy(
            app = detail.app.copy(
                appId = "dev.example.notes",
                name = "dev.example.notes",
                shortDescription = "",
                version = "",
                recognised = false,
            ),
        )
        rule.setContent {
            AppDetailScreenContent(unknown, loading = false, onUninstall = {}, onServiceClick = {}, onBack = {})
        }
        rule.onNodeWithText("Unrecognised").assertIsDisplayed()
        rule.onNodeWithText(
            "This version of Control does not have details for this app. It may have been added in a newer version.",
        ).assertIsDisplayed()
    }

    /** A recognised app must carry neither the badge nor the note. */
    @Test
    fun aRecognisedAppShowsNoUnrecognisedTreatment() {
        rule.setContent {
            AppDetailScreenContent(detail, loading = false, onUninstall = {}, onServiceClick = {}, onBack = {})
        }
        rule.onNodeWithText("Unrecognised").assertDoesNotExist()
    }

    @Test
    fun uninstall_confirmPath_emitsThisDeviceByDefault() {
        var scope: UninstallScope? = null
        rule.setContent {
            AppDetailScreenContent(detail, loading = false, onUninstall = { scope = it }, onServiceClick = {}, onBack = {})
        }
        // Opens the confirm dialog…
        rule.onNodeWithText("Uninstall").performClick()
        // …then confirm with the default scope (this device).
        rule.onNodeWithText("Remove").performClick()
        assertEquals(UninstallScope.ThisDevice, scope)
    }

    @Test
    fun serviceChip_emitsServiceId() {
        var clickedService: String? = null
        rule.setContent {
            AppDetailScreenContent(detail, loading = false, onUninstall = {}, onServiceClick = { clickedService = it }, onBack = {})
        }
        rule.onNodeWithText("file/system").performClick()
        assertEquals("tenshu/file/system", clickedService)
    }
}
