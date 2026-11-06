package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.share_action_email
import app.tenshu.drive.shared.resources.share_action_share
import org.jetbrains.compose.resources.stringResource

/** What a platform's share-out actually opens — the sheet labels its button from this. */
enum class ShareTargetKind {
    /** No hand-off available; *Copy* is the only action. */
    None,

    /** An OS share sheet — Android's `ACTION_SEND` chooser. */
    SystemSheet,

    /** A mail composer, via `mailto:` (desktop). */
    Email,
}

/**
 * Platform share-out for short text (`drive-manage-access-ux.md` §2.2): hands a
 * link to another app. On Android that is the `ACTION_SEND` chooser, which is
 * the whole of how WhatsApp / Signal / Messages get the link; on desktop there
 * is no OS share sheet, so the hand-off is the local mail composer.
 *
 * Sheets offer **both** the share action and *Copy link* whenever [target] is
 * not [ShareTargetKind.None] — handing the link to a messenger and putting it
 * on the clipboard are genuinely different intents, not a fallback ladder.
 *
 * ⚠ Never route these links through a web share-intent redirector
 * (`wa.me/?text=`, `t.me/share/url?url=`): the URL fragment is only invisible to
 * servers while it stays a *fragment*. Embedded as another site's query
 * parameter it becomes an ordinary request field, and the share key is
 * disclosed to that operator.
 *
 * The `subject` argument is a hint for targets that have one (email, and
 * Android's chooser title). It must never carry the capability key — only the
 * shared `text` does.
 */
class ShareTextLauncher(
    val target: ShareTargetKind,
    private val shareImpl: (text: String, subject: String?) -> Unit,
) {
    val canShareNatively: Boolean get() = target != ShareTargetKind.None

    fun share(text: String, subject: String? = null) = shareImpl(text, subject)
}

/** Button label for [target] — "Share" hides what desktop actually opens. */
@Composable
fun shareActionLabel(target: ShareTargetKind): String = stringResource(
    when (target) {
        ShareTargetKind.Email -> Res.string.share_action_email
        else -> Res.string.share_action_share
    }
)

@Composable
expect fun rememberShareTextLauncher(): ShareTextLauncher

/**
 * Clipboard write for text that carries a capability (a share link's `#k=`
 * fragment, an invite link's payload).
 *
 * Distinct from `LocalClipboardManager` because Android 13+ renders a preview of
 * whatever was copied in a system toast, and the platform's opt-out for that —
 * `ClipDescription.EXTRA_IS_SENSITIVE` — is not reachable through the Compose
 * clipboard API. Showing a share key on screen after the user copied it is the
 * one avoidable disclosure in this flow.
 */
fun interface SensitiveClipboard {
    fun copy(text: String)
}

@Composable
expect fun rememberSensitiveClipboard(): SensitiveClipboard
