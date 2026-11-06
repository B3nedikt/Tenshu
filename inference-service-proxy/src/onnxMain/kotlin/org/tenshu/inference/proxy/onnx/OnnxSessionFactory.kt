package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * **Every ORT session this module opens goes through here** (`media-processing-pipeline-sweep.md`
 * F7 / D4, phase P3): the SigLIP vision and text towers and the face embedder.
 *
 * ⚠⚠ The default `SessionOptions()` is an intra-op pool of one thread per core, **with spin-waiting
 * on**. An index pass therefore saturated every core and burned CPU between ops, inside the
 * process that serves every app's IPC. D4 caps it at [intraOpThreads] = 2, inter-op at 1 (these are
 * single-path graphs, so a second inter-op thread only idles), and turns spinning off in both pools.
 *
 * ⚠ [intraOpThreads] is the one knob, and it exists for D4's fallback: if the device re-measure is
 * more than 30 % slower per image, *Index now* (and only *Index now*) gets a factory with a higher
 * count. The rest of the policy is fixed.
 *
 * ⚠ MediaPipe's face *detector* is not ORT and is not configured here. Its threads are MediaPipe's
 * own, and it runs on the media lane like the rest of the face half.
 */
class OnnxSessionFactory(
    val intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
    private val environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
    /** The native call, and the test seam: a test captures the options instead of loading a model. */
    private val create: (OrtEnvironment, String, OrtSession.SessionOptions) -> OrtSession =
        { env, path, options -> env.createSession(path, options) },
) {

    /** The options a session is opened with. Exposed so a test can read them back. */
    fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply { configure(OptionsTarget.of(this)) }

    /**
     * ⚠ Written against [OptionsTarget] rather than `SessionOptions` directly, because ORT's Java
     * API has no getter for either thread count. A recording target is the only way a test can
     * see them.
     */
    internal fun configure(target: OptionsTarget) {
        target.setIntraOpNumThreads(intraOpThreads)
        target.setInterOpNumThreads(INTER_OP_THREADS)
        target.addConfigEntry(INTRA_OP_SPINNING, "0")
        target.addConfigEntry(INTER_OP_SPINNING, "0")
    }

    /**
     * Opens a session over the model at [path]. ⚠ Throws as `createSession` does; callers already
     * wrap the open in `runCatching`.
     */
    fun open(path: String): OrtSession = create(environment(), path, options())

    internal interface OptionsTarget {
        fun setIntraOpNumThreads(threads: Int)
        fun setInterOpNumThreads(threads: Int)
        fun addConfigEntry(key: String, value: String)

        companion object {
            fun of(options: OrtSession.SessionOptions): OptionsTarget = object : OptionsTarget {
                override fun setIntraOpNumThreads(threads: Int) = options.setIntraOpNumThreads(threads)
                override fun setInterOpNumThreads(threads: Int) = options.setInterOpNumThreads(threads)
                override fun addConfigEntry(key: String, value: String) = options.addConfigEntry(key, value)
            }
        }
    }

    companion object {
        /** D4. */
        const val DEFAULT_INTRA_OP_THREADS = 2
        const val INTER_OP_THREADS = 1
        const val INTRA_OP_SPINNING = "session.intra_op.allow_spinning"
        const val INTER_OP_SPINNING = "session.inter_op.allow_spinning"
    }
}
