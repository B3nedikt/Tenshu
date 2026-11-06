package org.tenshu.inference.proxy.onnx

import kotlinx.coroutines.runBlocking
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D4 / P3 (`media-processing-pipeline-sweep.md`): **every ORT session opens with intra-op = 2,
 * inter-op = 1 and spinning off in both pools.** ORT's defaults are a thread per core with
 * spin-waiting on.
 *
 * ⚠ No model is loaded anywhere here. ORT's Java API has no getter for either thread count, so the
 * counts are read through the factory's recording seam, and the spinning entries from the real
 * `SessionOptions`. The backend half captures the options at `createSession` and refuses, which is
 * enough to see which options a session would have opened with.
 */
class OnnxSessionFactoryTest {

    private class Recording : OnnxSessionFactory.OptionsTarget {
        var intra: Int? = null
        var inter: Int? = null
        val entries = mutableMapOf<String, String>()
        override fun setIntraOpNumThreads(threads: Int) { intra = threads }
        override fun setInterOpNumThreads(threads: Int) { inter = threads }
        override fun addConfigEntry(key: String, value: String) { entries[key] = value }
    }

    private val spinningOff = mapOf(
        "session.intra_op.allow_spinning" to "0",
        "session.inter_op.allow_spinning" to "0",
    )

    @Test
    fun theDefaultPolicyIsTwoIntraOneInterAndNoSpinning() {
        val recorded = Recording().also { OnnxSessionFactory().configure(it) }
        assertEquals(2, recorded.intra, "intra-op threads")
        assertEquals(1, recorded.inter, "inter-op threads")
        assertEquals(spinningOff, recorded.entries)
    }

    /** D4's fallback knob: *Index now* may raise the intra-op count, and nothing else moves with it. */
    @Test
    fun theOneKnobRaisesOnlyIntraOpThreads() {
        val recorded = Recording().also { OnnxSessionFactory(intraOpThreads = 4).configure(it) }
        assertEquals(4, recorded.intra)
        assertEquals(1, recorded.inter)
        assertEquals(spinningOff, recorded.entries)
    }

    @Test
    fun theRealOptionsCarryTheSpinningEntries() {
        OnnxSessionFactory().options().use { options ->
            assertEquals(spinningOff, options.configEntries.filterKeys { it in spinningOff })
        }
    }

    /**
     * ⚠⚠ The backend must open its session through the factory, not with a bare `SessionOptions()`.
     * A factory nobody calls would pass every test above.
     */
    @Test
    fun theVisionBackendOpensItsSessionThroughTheFactory() = runBlocking {
        val opened = mutableListOf<Pair<String, Map<String, String>>>()
        val factory = OnnxSessionFactory(create = { _, path, options ->
            opened += path to options.configEntries.toMap()
            error("no model in this test")
        })
        val backend = OnnxInferenceBackend(
            decoder = AwtImageDecoder,
            locate = { "/models/vision.onnx" },
            sessions = factory,
        )

        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("/models/vision.onnx"), opened.map { it.first })
        assertEquals(spinningOff, opened.single().second.filterKeys { it in spinningOff })
    }
}
