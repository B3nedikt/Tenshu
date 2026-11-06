package app.tenshu.control.ui.screens.settings.advanced

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.advanced_security_headline
import app.tenshu.control.shared.resources.advanced_security_support
import app.tenshu.control.shared.resources.advanced_title
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Settings → Advanced (`docs/proposals/control-keep-device-unlocked.md` §4.4).
 * The expert-settings hub. Ph1 holds a single **Security** entry (the soft
 * app-lock + hard-mode placeholder); future advanced settings live here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onSecurityClick: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.advanced_title), onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .appContentWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AppListItem(
                    headline = stringResource(Res.string.advanced_security_headline),
                    supportingText = stringResource(Res.string.advanced_security_support),
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                    onClick = onSecurityClick,
                )
            }
        }
    }
}

@Preview
@Composable
fun AdvancedSettingsScreenPreview() {
    Surface {
        AdvancedSettingsScreen(onSecurityClick = {}, onBack = {})
    }
}
