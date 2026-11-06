package app.tenshu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared camera QR scanner (moved from `control-shared` for Drive's invite
 * flow — `drive-manage-access-ux.md` §5). The host app's manifest must
 * declare the CAMERA permission.
 *
 * Its own module (`:ui-qr-scanner`), not `:ui-core`, so that only the apps that scan — Control
 * and Drive — package the camera stack (CameraX).
 *
 * - **Android** — CameraX preview + ZXing decoding of the analysis frames (`QrFrameDecoder`):
 *   pure Java, on the device. ⚠ Not ML Kit — it logs scan usage to Google (see the module's
 *   build file).
 * - **Desktop** — macOS streams a live preview from the bundled AVFoundation
 *   helper and decodes natively (`desktop-qr-scanning.md`); Windows and Linux
 *   have no camera backend and render the "not available" message, so a caller
 *   must always offer the manual-code fallback alongside.
 *
 * No iOS target: every iOS app here scans in its own Swift layer.
 */
@Composable
expect fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier
)
