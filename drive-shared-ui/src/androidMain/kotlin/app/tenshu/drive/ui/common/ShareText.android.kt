package app.tenshu.drive.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareTextLauncher(): ShareTextLauncher {
    val context = LocalContext.current
    return remember(context) {
        ShareTextLauncher(ShareTargetKind.SystemSheet) { text, subject ->
            // The plain `ACTION_SEND` chooser is deliberately the whole mechanism:
            // every messenger (WhatsApp, Signal, Telegram, Messages) registers for
            // `text/plain`, and Direct Share surfaces individual chats for free.
            // Targeting a package by name would need a `<queries>` entry, break
            // when that app is absent, and offer strictly less.
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                subject?.let {
                    putExtra(Intent.EXTRA_SUBJECT, it)
                    // Titles the chooser instead of previewing the link itself.
                    putExtra(Intent.EXTRA_TITLE, it)
                }
            }
            context.startActivity(Intent.createChooser(send, null))
        }
    }
}

@Composable
actual fun rememberSensitiveClipboard(): SensitiveClipboard {
    val context = LocalContext.current
    return remember(context) {
        SensitiveClipboard { text ->
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("", text).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Suppresses the Android 13+ clipboard toast's content preview,
                    // which would otherwise render the capability key on screen.
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
            manager.setPrimaryClip(clip)
        }
    }
}
