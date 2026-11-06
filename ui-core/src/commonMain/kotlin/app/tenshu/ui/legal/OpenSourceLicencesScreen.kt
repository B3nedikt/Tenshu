package app.tenshu.ui.legal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.oss_licences_close
import app.tenshu.ui.resources.oss_licences_intro
import app.tenshu.ui.resources.oss_licences_no_text
import app.tenshu.ui.resources.oss_licences_open_online
import app.tenshu.ui.resources.oss_licences_row_support
import app.tenshu.ui.resources.oss_licences_section_data
import app.tenshu.ui.resources.oss_licences_section_libraries
import app.tenshu.ui.resources.oss_licences_title
import app.tenshu.ui.resources.oss_licences_unavailable
import org.jetbrains.compose.resources.stringResource

// `app.tenshu.ui.legal` holds every in-app legal entry — the privacy policy (`PrivacyPolicyLink.kt`)
// and these open-source notices — because the apps always place them together, in that order.

/**
 * The row's label — "Open-source licences" / "Open-Source-Lizenzen" — for a settings screen that
 * draws its own rows (Fit's Profile, Timeline's Settings) and so cannot use
 * [OpenSourceLicencesListItem]. The [privacyPolicyLabel] twin; place it directly after that one.
 */
@Composable
fun openSourceLicencesLabel(): String = stringResource(Res.string.oss_licences_title)

/**
 * The settings row that opens [OpenSourceLicencesScreen] — one label, icon and sentence for every app.
 *
 * ### When to use
 * In each app's settings, about or profile screen, directly after [AppPrivacyPolicyListItem].
 * It is the visible half of shipping the Apache-2.0 / MIT / BSD notices with the binary.
 *
 * ### Variants
 * One. The app decides where it sits, not what it says.
 *
 * ### Accessibility
 * The whole row is the click target; the icon is decorative (`contentDescription = null`) because
 * the headline names the destination.
 *
 * @param onClick navigates to [OpenSourceLicencesScreen].
 */
@Composable
fun OpenSourceLicencesListItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppListItem(
        headline = stringResource(Res.string.oss_licences_title),
        supportingText = stringResource(Res.string.oss_licences_row_support),
        leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null) },
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * This app's open-source notices: the data and fonts it credits, then every library its binary
 * links, each with its licence. Selecting a library shows the licence text.
 *
 * ### When to use
 * The destination of [OpenSourceLicencesListItem], in every app on Android and desktop. (iOS shows
 * the same notices in SwiftUI — `ios-about/`.)
 *
 * ### Variants
 *  - **[dataAttributions] empty** — an app that shows no third-party data; the section is omitted.
 *  - **Map-bearing apps** pass [DataAttributions.Basemap] (plus [DataAttributions.Photon] where
 *    place names are looked up).
 *  - **No bundled list** — a build that skipped the Gradle convention says so instead of showing
 *    an empty list that would read as "this app uses nothing".
 *
 * ### Accessibility
 * Section titles are headings; every row is one click target. The licence dialog's body scrolls,
 * and its link button opens the licence in the browser.
 *
 * @param onBack pops back to the screen that opened this one.
 * @param dataAttributions the non-library credits this app owes (map data, fonts, geocoder results).
 * @param modifier ⚠ In a shell that passes `contentWindowInsets = WindowInsets.systemBars` (Fit,
 *   Home, Mia) pass `Modifier.consumeWindowInsets(WindowInsets.systemBars)`: the shell has already
 *   padded the status bar, and this screen's top bar would pad it a second time (`ui-agent.md` §8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicencesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dataAttributions: List<DataAttribution> = emptyList(),
) {
    val state by rememberOssLicences()
    OpenSourceLicencesContent(state = state, onBack = onBack, dataAttributions = dataAttributions, modifier = modifier)
}

/** [OpenSourceLicencesScreen] over an explicit [state] — previews and tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicencesContent(
    state: OssLicencesState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dataAttributions: List<DataAttribution> = emptyList(),
) {
    val uriHandler = LocalUriHandler.current
    var shown by remember { mutableStateOf<OssComponent?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { AppTopAppBar(title = stringResource(Res.string.oss_licences_title), onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxSize().appContentWidth().testTag(TAG_LIST)) {
                item {
                    Text(
                        text = stringResource(Res.string.oss_licences_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
                    )
                }
                if (dataAttributions.isNotEmpty()) {
                    item { SectionTitle(stringResource(Res.string.oss_licences_section_data)) }
                    items(dataAttributions, key = { "data:" + it.url + it.licence + it.title.key }) { data ->
                        AppListItem(
                            headline = stringResource(data.title),
                            supportingText = stringResource(data.credit) + "\n" + data.licence,
                            onClick = data.url?.let { url -> { uriHandler.openUri(url) } },
                        )
                    }
                }
                item { SectionTitle(stringResource(Res.string.oss_licences_section_libraries)) }
                when (state) {
                    OssLicencesState.Loading -> item {
                        Box(Modifier.fillMaxWidth().padding(MaterialTheme.spacing.xl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    OssLicencesState.Missing -> item {
                        Text(
                            text = stringResource(Res.string.oss_licences_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
                        )
                    }
                    is OssLicencesState.Loaded -> items(state.components, key = { it.id }) { component ->
                        AppListItem(
                            headline = component.name + (component.version?.let { " $it" } ?: ""),
                            supportingText = component.licences.joinToString(" · ") { it.name }
                                .ifEmpty { component.id },
                            onClick = { shown = component },
                        )
                    }
                }
            }
        }
    }

    shown?.let { component -> LicenceDialog(component, onDismiss = { shown = null }) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = MaterialTheme.spacing.l,
            end = MaterialTheme.spacing.l,
            top = MaterialTheme.spacing.l,
            bottom = MaterialTheme.spacing.xs,
        )
            .semantics { heading() },
    )
}

@Composable
private fun LicenceDialog(component: OssComponent, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val link = component.licences.firstNotNullOfOrNull { it.url } ?: component.website
    AppDialog(
        title = component.name,
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.oss_licences_close)) } },
        dismissButton = link?.let { url ->
            { TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(Res.string.oss_licences_open_online)) } }
        },
    ) {
        val body = component.licences.joinToString("\n\n") { licence ->
            licence.text?.let { "${licence.name}\n\n$it" } ?: licence.name
        }
        val noText = component.licences.isEmpty() || component.licences.any { it.text == null }
        Box(Modifier.heightIn(max = LICENCE_TEXT_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            Text(
                text = if (noText) body + "\n\n" + stringResource(Res.string.oss_licences_no_text) else body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal const val TAG_LIST = "oss-licences-list"

/** Component geometry: the Apache-2.0 text is ~10 KB, so the dialog body scrolls inside this. */
private val LICENCE_TEXT_MAX_HEIGHT = 420.dp

private val previewComponents = listOf(
    OssComponent(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core", "kotlinx-coroutines-core", "1.11.0",
        "https://github.com/Kotlin/kotlinx.coroutines",
        listOf(OssLicence("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html", "Apache License…")),
    ),
    OssComponent("io.ktor:ktor-client-core", "ktor-client-core", "3.6.0", null, listOf(OssLicence("Apache License 2.0", null, null))),
)

@Preview
@Composable
private fun OpenSourceLicencesPreview() = PreviewBox {
    OpenSourceLicencesContent(OssLicencesState.Loaded(previewComponents), onBack = {}, dataAttributions = DataAttributions.Basemap)
}

@Preview
@Composable
private fun OpenSourceLicencesDarkPreview() = PreviewBox(useDarkTheme = true) {
    OpenSourceLicencesContent(OssLicencesState.Missing, onBack = {})
}

@Preview
@Composable
private fun OpenSourceLicencesListItemPreview() = PreviewBox { OpenSourceLicencesListItem(onClick = {}) }
