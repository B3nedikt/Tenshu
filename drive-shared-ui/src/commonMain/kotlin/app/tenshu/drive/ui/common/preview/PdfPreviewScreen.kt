package app.tenshu.drive.ui.common.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.image_preview_more_actions
import app.tenshu.drive.shared.resources.pdf_preview_page_indicator
import app.tenshu.drive.ui.error.driveLabel
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ui.chrome.AppTopAppBar
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Per-file factory for [PdfPreviewViewModel], keyed by path so each opened PDF
 * gets its own VM instance (same reasoning as [LocalImagePreviewViewModelFactory]
 * — without the path key `koinViewModel` caches by class and a second open
 * returns the first file's VM).
 */
val LocalPdfPreviewViewModelFactory =
    staticCompositionLocalOf<@Composable (String) -> PdfPreviewViewModel> {
        { path ->
            koinViewModel(
                key = "pdf_preview_$path",
                parameters = { parametersOf(path) }
            )
        }
    }

/**
 * Paginated PDF viewer (`docs/proposals/drive-content-preview.md` §4): one page
 * per pager screen, each rendered lazily to a bitmap and pinch-zoomable, with a
 * "page / total" indicator. Shares the image viewer's chrome + actions sheet.
 */
@Composable
fun PdfPreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    viewModel: PdfPreviewViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    PdfPreviewScreen(
        modifier = modifier,
        filePath = filePath,
        navigateUp = navigateUp,
        showFileActions = showFileActions,
        pageCount = uiState.pageCount,
        isLoading = uiState.isLoading,
        error = uiState.error,
        renderPage = viewModel::renderPage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    pageCount: Int,
    isLoading: Boolean,
    error: ErrorCopyKey?,
    renderPage: suspend (pageIndex: Int, targetWidth: Int) -> ImageBitmap?,
) {
    Column(modifier.fillMaxSize()) {
        AppTopAppBar(
            title = filePath.substringAfterLast('/'),
            onBack = navigateUp,
            actions = {
                Icon(
                    modifier = Modifier.clickable { showFileActions() },
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.image_preview_more_actions),
                )
            }
        )

        when {
            error != null -> CenteredMessage(error.driveLabel(), MaterialTheme.colorScheme.error)
            pageCount > 0 -> PdfPager(pageCount = pageCount, renderPage = renderPage)
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PdfPager(
    pageCount: Int,
    renderPage: suspend (pageIndex: Int, targetWidth: Int) -> ImageBitmap?,
) {
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("pdfPager"),
        ) { pageIndex ->
            PdfPage(pageIndex = pageIndex, renderPage = renderPage)
        }

        // "page / total" indicator, bottom-centre over the page.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp,
        ) {
            Text(
                text = stringResource(
                    Res.string.pdf_preview_page_indicator,
                    pagerState.currentPage + 1,
                    pageCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(
    pageIndex: Int,
    renderPage: suspend (pageIndex: Int, targetWidth: Int) -> ImageBitmap?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val targetWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        var bitmap by remember(pageIndex) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(pageIndex, targetWidthPx) {
            if (targetWidthPx > 0) bitmap = renderPage(pageIndex, targetWidthPx)
        }

        val rendered = bitmap
        if (rendered != null) {
            Image(
                bitmap = rendered,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomable(rememberZoomState()),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color)
    }
}
