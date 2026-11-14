package app.tenshu.photos.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import app.tenshu.photos.edit.AdjustEdit
import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditParityFixtures
import app.tenshu.photos.edit.EditParityFixtures.HEIGHT
import app.tenshu.photos.edit.EditParityFixtures.WIDTH
import app.tenshu.photos.edit.RenderPlan
import app.tenshu.photos.ui.screens.editor.applyColorOnCpu
import app.tenshu.photos.ui.screens.editor.drawEdited
import app.tenshu.photos.ui.screens.editor.writeArgbRows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ ED-D8's golden-image test for the **SkSL** engine (desktop; and AGSL on Android 33+, which is
 * the same shader text): every [EditParityFixtures] recipe drawn by the shader against the CPU
 * reference, within the stated tolerance. The iOS twin is `EditColorPipelineParityTest`.
 */
class EditShaderParityTest {

    private val identity = RenderPlan(WIDTH, HEIGHT, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

    private fun fixture(): ImageBitmap = ImageBitmap(WIDTH, HEIGHT).apply {
        writeArgbRows(EditParityFixtures.image(), 0, HEIGHT)
    }

    private fun ImageBitmap.argb(): IntArray = IntArray(width * height).also { readPixels(it) }

    @Test
    fun theFixtureRoundTrips() {
        assertContentEquals(EditParityFixtures.image(), fixture().argb(), "a parity test needs its input exact")
    }

    @Test
    fun theShaderMatchesTheReference_withinTheStatedTolerance() {
        val failures = mutableListOf<String>()
        val report = StringBuilder()
        for ((name, stage) in EditParityFixtures.recipes) {
            val out = ImageBitmap(WIDTH, HEIGHT)
            val drawn = Canvas(out).drawEdited(fixture(), identity, stage, FilterQuality.Low)
            assertTrue(drawn, "desktop always has the shader")
            val (worst, mean) = EditParityFixtures.difference(out.argb(), EditParityFixtures.reference(stage))
            report.append("$name: max $worst, mean ${"%.3f".format(mean)}\n")
            if (worst > EditParityFixtures.MAX_DIFF || mean > EditParityFixtures.MEAN_DIFF) failures += "$name (max $worst, mean $mean)"
        }
        println("EditShaderParityTest\n$report")
        assertTrue(failures.isEmpty(), "beyond tolerance: $failures\n$report")
    }

    /** Android below API 33's path: the same reference, through real bitmap pixel IO — exact. */
    @Test
    fun theCpuPathIsTheReference() {
        for ((name, stage) in EditParityFixtures.recipes) {
            val out = fixture().apply { applyColorOnCpu(stage) }
            assertContentEquals(EditParityFixtures.reference(stage), out.argb(), name)
        }
    }

    @Test
    fun anIdentityStageDrawsThePictureUntouched() {
        val out = ImageBitmap(WIDTH, HEIGHT)
        Canvas(out).drawEdited(fixture(), identity, ColorStage(AdjustEdit(), null), FilterQuality.Low)
        assertContentEquals(EditParityFixtures.image(), out.argb())
    }
}
