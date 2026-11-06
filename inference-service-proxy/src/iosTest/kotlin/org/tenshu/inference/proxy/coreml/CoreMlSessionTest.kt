package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.onnx.VisionModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CoreMlSession] against ML Programs Core ML really compiled and loaded, on the simulator
 * (`ios-on-device-inference.md` §5, PR-1).
 *
 * ⚠ The fixtures embed nothing: a 3 → 768 linear layer under the real towers' names and shapes.
 * What is under test is the guards and the tensor plumbing, not a vector's meaning.
 */
class CoreMlSessionTest {

    private val fixtures = CoreMlFixtures()
    private val vision: CoreMlTowerSpec = CoreMlTowerSpec.vision(VisionModel.PinnedSigLip)
    private val text: CoreMlTowerSpec = CoreMlTowerSpec.text(VisionModel.PinnedSigLip)

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    private fun pixels(value: Float): FloatArray = FloatArray(vision.inputCount) { value }

    @Test
    fun theVisionFixturePredictsAVectorOfThePinnedWidth() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return
        val session: CoreMlSession = CoreMlSession.open(path, vision)

        val first: FloatArray = session.predict(pixels(0.25f))
        val again: FloatArray = session.predict(pixels(0.25f))
        val other: FloatArray = session.predict(pixels(-0.5f))

        assertEquals(768, first.size)
        assertTrue(first.all { it.isFinite() })
        assertContentEquals(first, again)
        // The input reaches the graph: a different image is a different vector.
        assertFalse(first.contentEquals(other))
        assertEquals(path, session.compiledPath)
    }

    @Test
    fun theTextFixtureTakesInt32Ids() {
        val path: String = fixtures.compiled("fixture_text", text) ?: return
        val session: CoreMlSession = CoreMlSession.open(path, text)

        val vector: FloatArray = session.predict(IntArray(64) { it % 32 })

        assertEquals(768, vector.size)
        assertFalse(vector.contentEquals(session.predict(IntArray(64) { 1 })))
    }

    @Test
    fun aModelOfAnotherSideIsRejectedAtOpen() {
        // Prepared as itself, so the cache's own first open accepts it; opened as the pinned tower.
        val other = vision.copy(inputShape = listOf(1, 3, 224, 224))
        val path: String = fixtures.compiled("fixture_vision_224", other) ?: return

        val failure = assertFailsWith<CoreMlRejectedModelException> { CoreMlSession.open(path, vision) }

        assertTrue("[1, 3, 224, 224]" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aVisionTowerIsNotATextTower() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return

        assertFailsWith<CoreMlRejectedModelException> { CoreMlSession.open(path, text) }
    }

    @Test
    fun anAllZeroOutputIsRefused() {
        val path: String = fixtures.compiled("fixture_vision_zero", vision) ?: return
        val session: CoreMlSession = CoreMlSession.open(path, vision)

        val failure = assertFailsWith<CoreMlPredictionException> { session.predict(pixels(0.25f)) }

        assertTrue("all zeros" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun anInputOfTheWrongSizeOrTypeIsRefusedBeforeCoreMlSeesIt() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return
        val session: CoreMlSession = CoreMlSession.open(path, vision)

        assertFailsWith<IllegalArgumentException> { session.predict(FloatArray(3 * 224 * 224)) }
        assertFailsWith<IllegalArgumentException> { session.predict(IntArray(64)) }
    }

    @Test
    fun aClosedSessionPredictsNothing() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return
        val session: CoreMlSession = CoreMlSession.open(path, vision)
        session.close()

        assertFailsWith<CoreMlPredictionException> { session.predict(pixels(0.25f)) }
    }

    @Test
    fun everyComputeUnitsSettingOpensOnTheSimulator() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return

        CoreMlComputeUnits.entries.forEach { units ->
            assertEquals(768, CoreMlSession.open(path, vision, units).predict(pixels(0.25f)).size, "$units")
        }
    }

    @Test
    fun aPathWithNoModelIsALoadFailure() {
        assertFailsWith<CoreMlLoadException> { CoreMlSession.open("${fixtures.root}/absent.mlmodelc", vision) }
    }
}
