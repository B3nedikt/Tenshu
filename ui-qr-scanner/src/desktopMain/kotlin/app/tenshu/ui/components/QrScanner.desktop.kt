package app.tenshu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image

/**
 * Desktop QR scanner. Windows/Linux have no camera backend and render the
 * "not available" placeholder; the caller (e.g. the pairing wizard) always
 * offers a manual-code fallback alongside. macOS streams a live camera preview
 * from the bundled AVFoundation helper and renders it *inside this window* (no
 * second OS window — `docs/proposals/desktop-qr-scanning.md` §8), decoding the
 * QR natively.
 *
 * First run on macOS shows a one-time "enable camera" explainer *before* the
 * grant rather than springing it mid-pairing: granting camera access for the
 * very first time can make macOS terminate the host app (it is the responsible
 * TCC subject for the helper's request). After that single grant every later
 * scan finds `.authorized` and runs straight to the preview. See
 * `docs/proposals/desktop-qr-scanning.md` §9.
 */
@Composable
actual fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier
) {
    val scanner = remember { QrCameraScanner.forCurrentOs() }
    if (scanner == null) {
        ScannerMessage(UNAVAILABLE_MESSAGE, modifier)
    } else {
        CameraScannerSurface(scanner, onResult, modifier)
    }
}

@Composable
private fun CameraScannerSurface(
    scanner: QrCameraScanner,
    onResult: (String) -> Unit,
    modifier: Modifier,
) {
    // null = still probing. The probe is a pure status read; it never opens a
    // session, so it can't trigger the first-grant host crash.
    var permission by remember { mutableStateOf<CameraPermission?>(null) }
    // The user accepted the one-time explainer and asked to start the camera
    // (only meaningful while permission is NotDetermined).
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(scanner) { permission = scanner.probePermission() }

    when (permission) {
        null -> ScannerMessage(PROBING_MESSAGE, modifier)
        CameraPermission.Authorized -> LivePreview(scanner, onResult, modifier)
        CameraPermission.Denied -> ScannerMessage(PERMISSION_MESSAGE, modifier)
        CameraPermission.NoCamera -> ScannerMessage(UNAVAILABLE_MESSAGE, modifier)
        CameraPermission.NotDetermined ->
            if (requested) LivePreview(scanner, onResult, modifier)
            else EnableCameraPrompt(onEnable = { requested = true }, modifier)
    }
}

/** Live camera preview + native QR decode, rendered inside this window. */
@Composable
private fun LivePreview(
    scanner: QrCameraScanner,
    onResult: (String) -> Unit,
    modifier: Modifier,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf<String?>(OPENING_MESSAGE) }
    LaunchedEffect(scanner) {
        scanner.scan().collect { event ->
            when (event) {
                is ScanEvent.Frame -> decodeJpegFrame(event.jpeg)?.let {
                    frame = it
                    message = null
                }

                is ScanEvent.RawFrame -> decodeBgraFrame(
                    event.bgra, event.width, event.height, event.bytesPerRow,
                )?.let {
                    frame = it
                    message = null
                }

                is ScanEvent.Result -> when (val outcome = event.outcome) {
                    is ScanOutcome.Decoded -> onResult(outcome.payload)
                    ScanOutcome.PermissionDenied -> message = PERMISSION_MESSAGE
                    else -> message = UNAVAILABLE_MESSAGE
                }
            }
        }
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        message?.let {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it)
            }
        }
    }
}

/** One-time pre-grant explainer shown when camera permission is undecided. */
@Composable
private fun EnableCameraPrompt(onEnable: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        ) {
            Text(ENABLE_TITLE, textAlign = TextAlign.Center)
            Text(ENABLE_BODY, textAlign = TextAlign.Center)
            Button(onClick = onEnable) { Text(ENABLE_ACTION) }
        }
    }
}

@Composable
private fun ScannerMessage(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text)
        }
    }
}

// Literal strings to match the existing ui-core QR components; externalise via
// the ui-core compose-resources catalogue when the desktop i18n sweep reaches
// these (AGENTS.md → ADR 0016).
private const val UNAVAILABLE_MESSAGE = "Camera scanner not available on Desktop."
private const val PROBING_MESSAGE = "Checking camera…"
private const val OPENING_MESSAGE = "Opening camera…"
private const val PERMISSION_MESSAGE =
    "Camera permission denied. Enable it in System Settings, or enter the code manually."
private const val ENABLE_TITLE = "Enable camera scanning"
private const val ENABLE_BODY =
    "macOS will ask permission to use the camera. Approve it to scan QR codes. " +
        "The app may close once right after — just reopen it; this only happens the " +
        "first time. You can also enter the code manually instead."
private const val ENABLE_ACTION = "Enable camera"
