package app.tenshu.ui.legal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.terms_of_use
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.legal.TermsOfUse

/**
 * The terms of use's URL in the reader's language, by the privacy policy's rule ([TermsOfUse.url]).
 * Reads Compose's [Locale.current], like [rememberPrivacyPolicyUrl].
 */
@Composable
fun rememberTermsOfUseUrl(): String {
    val tag = Locale.current.toLanguageTag()
    return remember(tag) { TermsOfUse.url(tag) }
}

/**
 * Opens the terms of use in the system browser through [LocalUriHandler]. Like
 * [rememberPrivacyPolicyOpener], a device with no browser makes the platform throw, which is
 * swallowed: a tap that does nothing beats a crash on the sign-up screen.
 */
@Composable
fun rememberTermsOfUseOpener(): () -> Unit {
    val url = rememberTermsOfUseUrl()
    val uriHandler = LocalUriHandler.current
    return remember(url, uriHandler) { { runCatching { uriHandler.openUri(url) } } }
}

/** Opens the terms' withdrawal notice and model form ([TermsOfUse.withdrawalUrl]): the checkout's link. */
@Composable
fun rememberTermsWithdrawalOpener(): () -> Unit {
    val tag = Locale.current.toLanguageTag()
    val url = remember(tag) { TermsOfUse.withdrawalUrl(tag) }
    val uriHandler = LocalUriHandler.current
    return remember(url, uriHandler) { { runCatching { uriHandler.openUri(url) } } }
}

/** The settings row's label, "Terms of use" / "Nutzungsbedingungen". */
@Composable
fun termsOfUseLabel(): String = stringResource(Res.string.terms_of_use)

/**
 * A settings row that opens Tenshu's terms of use in the browser, the twin of
 * [AppPrivacyPolicyListItem] and placed beside it (Control's settings).
 */
@Composable
fun AppTermsOfUseListItem(modifier: Modifier = Modifier) {
    val open = rememberTermsOfUseOpener()
    AppListItem(
        headline = termsOfUseLabel(),
        modifier = modifier,
        onClick = open,
        leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
    )
}
