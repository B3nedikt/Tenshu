package app.tenshu.drive.ui.common.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.toFileIcon
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.image_preview_more_actions
import app.tenshu.drive.shared.resources.preview_open_externally
import app.tenshu.drive.shared.resources.preview_open_externally_failed
import app.tenshu.drive.shared.resources.preview_unavailable
import app.tenshu.drive.shared.resources.video_preview_unsupported
import app.tenshu.drive.ui.common.asImageVector
import app.tenshu.drive.ui.common.rememberOpenFileLauncher
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName

/**
 * Per-file factory for [OpenExternallyViewModel], keyed by path (same reasoning as the
 * other preview factories — without the key `koinViewModel` caches by class so a second
 * open would reuse the first file's VM, and thus its temp file).
 */
val LocalOpenExternallyViewModelFactory =
    staticCompositionLocalOf<@Composable (String) -> OpenExternallyViewModel> {
        { path ->
            koinViewModel(
                key = "open_externally_$path",
                parameters = { parametersOf(path) }
            )
        }
    }

/**
 * Fallback preview surface for files whose category Drive can't render in-app
 * (documents, archives, …). Shown by the `Destination.FilePreview` router for any
 * unhandled file — it shares the image viewer's chrome (title, back, the same "more
 * actions" affordance) so rename / move / details / delete stay reachable, shows the
 * file's type glyph plus an honest "no preview yet" message, and — where the platform
 * supports it (Android, desktop) — offers **Open in another app** (Decision D3,
 * `docs/proposals/drive-content-preview.md` §6): the decrypted bytes are materialised to
 * a private temp file and handed to the OS so a third-party app can open them.
 *
 * ⚠ Two of its arrivals are about the **platform**, not the file type: a HEIC where Skia has no
 * decoder, and a **video on a target with no player** (desktop — the JVM ships no video decoder).
 * Both are renderable files this build can't open, so the message reads by category and the OS
 * hand-off is the whole point of the screen rather than a consolation. That is also why the
 * capability tests live in the router and not in the viewers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnsupportedPreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    viewModel: OpenExternallyViewModel,
) {
    val fileName = filePath.substringAfterLast('/')
    val uiState by viewModel.uiState.collectAsState()
    val openLauncher = rememberOpenFileLauncher()

    // Hand the freshly-written temp file to the OS exactly once, then clear the signal.
    LaunchedEffect(uiState.readyToOpen) {
        val path = uiState.readyToOpen
        if (path != null) {
            openLauncher.open(path, fileName)
            viewModel.onOpenLaunched()
        }
    }

    Column(modifier.fillMaxSize()) {
        AppTopAppBar(
            title = fileName,
            onBack = navigateUp,
            actions = {
                Icon(
                    modifier = Modifier.clickable { showFileActions() },
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.image_preview_more_actions),
                )
            }
        )

        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = fileCategoryForName(fileName).toFileIcon().asImageVector(),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // ⚠ A video lands here only because this platform has no player, never
                    // because the file is unpreviewable — Drive shows a poster frame for it in
                    // every listing. "No preview available" would contradict what the user is
                    // looking at, so the category picks the wording.
                    text = stringResource(
                        if (fileCategoryForName(fileName) == FileCategory.Video) {
                            Res.string.video_preview_unsupported
                        } else {
                            Res.string.preview_unavailable
                        }
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (openLauncher.canOpenExternally) {
                    if (uiState.isPreparing) {
                        CircularProgressIndicator()
                    } else {
                        Button(onClick = { viewModel.prepareOpen() }) {
                            Text(stringResource(Res.string.preview_open_externally))
                        }
                    }
                    if (uiState.failed) {
                        Text(
                            text = stringResource(Res.string.preview_open_externally_failed),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
