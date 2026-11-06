package app.tenshu.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
actual fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
        // The decoder reports the code on EVERY analyzed frame, so one physical scan arrives
        // as a burst of identical results until the scanner leaves composition. Latch the
        // last value so each distinct code is emitted once per scanner instance — stateful
        // consumers (the pairing wizard's offer branch mints an ephemeral key per result)
        // must not be driven once per frame.
        val lastEmitted = remember { AtomicReference<String?>(null) }

        AndroidView(
            factory = { context ->
                val previewView = PreviewView(context).apply {
                    // COMPATIBLE (TextureView) instead of the default PERFORMANCE (SurfaceView):
                    // a SurfaceView renders on a separate hardware layer that isn't clipped to its
                    // Compose bounds, so the camera feed can bleed over sibling content laid out
                    // beneath it (the "Hold the QR code…" hint). A TextureView composites in-window
                    // and is clipped/z-ordered correctly by Compose.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val executor = Executors.newSingleThreadExecutor()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val decoder = QrFrameDecoder()
                    // Results are delivered on the main thread, as ML Kit's success listener did:
                    // callers update UI state from `onResult`.
                    val mainExecutor = ContextCompat.getMainExecutor(context)

                    val imageAnalysis = ImageAnalysis.Builder()
                        // ~720p rather than CameraX's 640x480 (4:3) analysis default: a dense
                        // pairing code read from arm's length needs the extra pixels, and ZXing
                        // decodes a 720p luminance plane in tens of milliseconds. The 16:9 ratio
                        // strategy is what makes 1280x720 reachable — without it the selector
                        // stays within 4:3 sizes. Frames that arrive while one is being decoded
                        // are dropped (KEEP_ONLY_LATEST).
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                )
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    )
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(executor) { imageProxy ->
                                try {
                                    val plane = imageProxy.planes[0]
                                    // Never throws (QrFrameDecoder's class doc).
                                    decoder.decodeFrame(
                                        plane.buffer,
                                        plane.rowStride,
                                        plane.pixelStride,
                                        imageProxy.width,
                                        imageProxy.height,
                                    )?.let { raw ->
                                        if (lastEmitted.getAndSet(raw) != raw) {
                                            mainExecutor.execute { onResult(raw) }
                                        }
                                    }
                                } finally {
                                    imageProxy.close()
                                }
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))

                previewView
            },
            modifier = modifier
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required")
        }
    }
}
