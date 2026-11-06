package app.tenshu.maps.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.ui.chrome.AppTopAppBar

/**
 * Shared chrome for the place-detail screen (Ph4, handoff §1.2): a back-chevron top bar over a
 * vertically-scrolling body. Reusable so Timeline & Photos can wrap the same detail body (D2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopAppBar(title = title, onBack = onBack, actions = actions) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}
