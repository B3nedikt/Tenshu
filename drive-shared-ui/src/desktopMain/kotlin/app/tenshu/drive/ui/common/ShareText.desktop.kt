package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
actual fun rememberShareTextLauncher(): ShareTextLauncher {
    return remember {
        // No desktop OS has a share sheet — macOS's NSSharingServicePicker needs
        // AppKit, Windows' DataTransferManager needs WinRT interop, and Linux has
        // nothing (xdg-desktop-portal offers OpenURI, not a share portal). The one
        // hand-off available everywhere is the local mail composer, which also has
        // the property we need: `mailto:` is dispatched by the OS to an installed
        // client, so the link never traverses a third party's server on the way.
        // Capability probed once: headless / no mail client hides the action.
        val supported = Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.MAIL)
        val target = if (supported) ShareTargetKind.Email else ShareTargetKind.None
        ShareTextLauncher(target) { text, subject ->
            try {
                Desktop.getDesktop().mail(URI(mailtoUri(text, subject)))
            } catch (_: Throwable) {
                // Malformed URI / no registered client / IO error — swallow so it
                // never crashes the sheet. *Copy link* is always right there.
            }
        }
    }
}

@Composable
actual fun rememberSensitiveClipboard(): SensitiveClipboard {
    // No desktop OS previews the clipboard's contents, so the plain Compose
    // clipboard is already the discreet write here.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    return remember(clipboardManager) {
        SensitiveClipboard { text -> clipboardManager.setText(AnnotatedString(text)) }
    }
}

/**
 * `mailto:?subject=…&body=…` with both header fields percent-encoded (RFC 6068).
 *
 * ⚠ The encoding is load-bearing, not cosmetic: a share URL carries its key in a
 * `#…` fragment, and an unencoded `#` inside the body would terminate the
 * `mailto:` URI there — the mail client would open with the key silently
 * truncated off the link, producing a share that looks fine and does not work.
 */
internal fun mailtoUri(body: String, subject: String?): String {
    val fields = buildList {
        subject?.let { add("subject=" + encodeHeaderField(it)) }
        add("body=" + encodeHeaderField(body))
    }
    return "mailto:?" + fields.joinToString("&")
}

/** `URLEncoder` is form-encoding, whose one deviation from RFC 3986 is `+` for space. */
private fun encodeHeaderField(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
