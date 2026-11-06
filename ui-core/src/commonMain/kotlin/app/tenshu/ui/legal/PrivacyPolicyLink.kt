package app.tenshu.ui.legal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.privacy_policy
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.legal.PrivacyPolicy

/**
 * The privacy policy's URL in the reader's language: German for a German locale, French for a
 * French one, English otherwise ([PrivacyPolicy.url] owns the rule).
 *
 * Reads Compose's [Locale.current], which follows the device language and, on Android 13+, the
 * per-app language the user picked for this app.
 */
@Composable
fun rememberPrivacyPolicyUrl(): String {
    val tag = Locale.current.toLanguageTag()
    return remember(tag) { PrivacyPolicy.url(tag) }
}

/**
 * Opens the privacy policy in the system browser: `ACTION_VIEW` on Android, `Desktop.browse` on
 * desktop, both through [LocalUriHandler].
 *
 * ⚠ Needs no `INTERNET` permission — most Tenshu Android apps strip it, and handing a URL to the
 * browser is an intent, not a socket. A device with no browser at all makes the platform throw;
 * that is swallowed, because a tap that does nothing beats a crash on a settings screen.
 */
@Composable
fun rememberPrivacyPolicyOpener(): () -> Unit {
    val url = rememberPrivacyPolicyUrl()
    val uriHandler = LocalUriHandler.current
    return remember(url, uriHandler) { { runCatching { uriHandler.openUri(url) } } }
}

/**
 * The link's label — "Privacy policy" / "Datenschutzerklärung" — for an app whose settings screen
 * draws its own rows (Fit's Profile, Timeline's and Mia's settings) and so cannot use
 * [AppPrivacyPolicyListItem]. Pair it with [rememberPrivacyPolicyOpener].
 */
@Composable
fun privacyPolicyLabel(): String = stringResource(Res.string.privacy_policy)

/**
 * A settings row that opens Tenshu's privacy policy in the browser.
 *
 * ### When to use
 *
 * On each app's settings, profile or about screen — App Store guideline 5.1.1(i) and Google Play's
 * User Data policy want the policy reachable inside the app. Put it beside any other about / support
 * entries, usually last. Don't invent a settings screen to hold it.
 *
 * ### Variants
 *
 * One: a privacy icon, the label "Privacy policy", and an open-in-new glyph saying it leaves the app.
 *
 * ### Accessibility
 *
 * The whole row is the tap target and its label is the accessible name; both icons are decorative.
 */
@Composable
fun AppPrivacyPolicyListItem(modifier: Modifier = Modifier) {
    val open = rememberPrivacyPolicyOpener()
    AppListItem(
        headline = privacyPolicyLabel(),
        modifier = modifier,
        onClick = open,
        leadingContent = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
    )
}

@Preview
@Composable
private fun AppPrivacyPolicyListItemPreview() = PreviewBox {
    AppPrivacyPolicyListItem()
}

@Preview
@Composable
private fun AppPrivacyPolicyListItemDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppPrivacyPolicyListItem()
}
