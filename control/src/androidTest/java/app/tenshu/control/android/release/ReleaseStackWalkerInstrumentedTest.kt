package app.tenshu.control.android.release

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Does MediaPipe start inside an R8-processed Control?** Written to run against the RELEASE build
 * (`-Ptenshu.control.testBuildType=release`, docs/how-to/android-release.md *R8 and native code*):
 * against the debug build, which R8 never touches, it passes whatever the keep rules say.
 *
 * MediaPipe's `Graph` creates a Flogger logger in its static initialiser, and Flogger names that
 * logger by counting stack frames. When R8 inlined Flogger's caller finder into its caller, the
 * count landed one frame off, `Graph` failed to initialise, and face detection never ran in the
 * shipped Control — while every debug build, unit test and the smoke gate stayed green.
 *
 * Calls MediaPipe's own API, not `MediaPipeFaceBackend`: MediaPipe is kept whole, while R8 is free
 * to reshape the app's own classes, so a test that called those would break for the wrong reason.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseStackWalkerInstrumentedTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaPipeGraphInitialises() {
        // Throws ExceptionInInitializerError ("no caller found on the stack for: …FluentLogger")
        // when Flogger's caller finder has been inlined.
        Class.forName("com.google.mediapipe.framework.Graph", true, target.classLoader)
    }

    /**
     * The real detector over the bundled CC0 face. Needs `face_landmarker.task` and
     * `face-probe-photo.jpg` pushed to Control's external files dir; skips with the reason when
     * they are absent, so the unconditional check above is the guard and this is the evidence.
     */
    @Test
    fun theDetectorFindsTheProbeFace() {
        val root = target.getExternalFilesDir(null)
        val detector = File(root, "face_landmarker.task")
        val photo = File(root, "face-probe-photo.jpg")
        if (!detector.isFile || !photo.isFile) {
            println("ReleaseStackWalker: detector check skipped — push face_landmarker.task and face-probe-photo.jpg to $root")
            return
        }
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(detector.absolutePath).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(4)
            .build()
        FaceLandmarker.createFromOptions(target, options).use { landmarker ->
            val bitmap = BitmapFactory.decodeFile(photo.absolutePath)
            val faces = landmarker.detect(BitmapImageBuilder(bitmap).build()).faceLandmarks().size
            println("ReleaseStackWalker: facesFound=$faces")
            assertTrue("no face found in the probe photo", faces > 0)
        }
    }
}
