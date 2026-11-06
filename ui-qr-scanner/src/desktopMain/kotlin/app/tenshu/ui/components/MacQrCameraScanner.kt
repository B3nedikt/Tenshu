package app.tenshu.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import app.tenshu.macos.HelperBinary
import java.io.File

/**
 * macOS QR scanner. Spawns the bundled `qr-scan-helper` (AVFoundation; built
 * from `src/desktopMain/swift/qr-scan-helper.swift`, mirrors
 * `control-ios/Sources/QRKit.swift`) and streams its framed stdout — preview
 * JPEG frames + a terminal result — into [ScanEvent]s, using the same
 * `ProcessBuilder` convention as `DesktopMasterKeyStore`'s `/usr/bin/security`
 * bridge. Cancelling collection kills the helper. See
 * `docs/proposals/desktop-qr-scanning.md` §5–§8.
 *
 * @param spawn injectable for tests; the default resolves and launches the
 * bundled helper.
 */
internal class MacQrCameraScanner(
    private val spawn: (List<String>) -> Process? = ::spawnHelper,
) : QrCameraScanner {
    override suspend fun probePermission(): CameraPermission = withContext(Dispatchers.IO) {
        // `probe` mode reads the auth status and exits without opening a
        // session — safe to call before showing the live preview.
        val proc = spawn(listOf("probe")) ?: return@withContext CameraPermission.NoCamera
        try {
            parsePermissionResult(readFirstResult(proc.inputStream))
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    override fun scan(): Flow<ScanEvent> = flow {
        // At most two passes: a first-grant pass exits with NeedsRetry (no
        // preview while the permission dialog is up), then a second pass finds
        // `.authorized` and streams the preview.
        var pass = 0
        while (true) {
            val proc = spawn(emptyList())
            if (proc == null) {
                emit(ScanEvent.Result(ScanOutcome.NoCamera))
                return@flow
            }
            var retry = false
            try {
                parseFramedStream(proc.inputStream).collect { event ->
                    if (event is ScanEvent.Result && event.outcome is ScanOutcome.NeedsRetry) {
                        retry = true
                    } else {
                        emit(event)
                    }
                }
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
            if (retry && pass++ == 0) continue
            if (retry) emit(ScanEvent.Result(ScanOutcome.Cancelled))
            return@flow
        }
    }.flowOn(Dispatchers.IO)
}

private val QR_SCAN_HELPER = HelperBinary("qr-scan-helper")

private fun spawnHelper(args: List<String>): Process? {
    val bin = QR_SCAN_HELPER.resolve() ?: return null
    // null (→ NoCamera) rather than throwing if the helper can't be launched,
    // so a spawn failure can never crash the collecting LaunchedEffect.
    // stdin stays the default pipe and is never closed or redirected: the
    // helper treats EOF on it as "parent gone" and exits.
    return runCatching {
        ProcessBuilder(listOf(bin.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
    }.getOrNull()
}
