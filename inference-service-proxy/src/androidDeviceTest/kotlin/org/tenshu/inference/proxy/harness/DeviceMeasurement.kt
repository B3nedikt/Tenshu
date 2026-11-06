package org.tenshu.inference.proxy.harness

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * The parts of IN-3m that are not the test: reading the side-loaded inputs, running one pass, and
 * shaping the report (`inference-service.md` §15).
 *
 * ⚠ Split from [DeviceMeasurementTest] so the *decisions* — what counts as a phone, what a pass
 * requires, what the report must say — are readable without instrumentation boilerplate around
 * them.
 */
internal object DeviceMeasurement {

    /** §15.4: fewer than this and the throttle question cannot be asked at all. */
    const val MIN_IMAGES = 200

    /** §15.4: the first N and last N medians whose ratio is the throttle reading. */
    const val WINDOW = 50

    /**
     * ⚠⚠ **The emulator must never produce a headline figure** (§15.1).
     *
     * The AVD the smoke gate uses executes on the **host CPU**, so a measurement taken there is a
     * Mac number wearing an Android costume — and unlike a missing number, a wrong one gets quoted.
     * This is not a nicety: it is the difference between a spec that gets used correctly and one
     * that produces folklore.
     *
     * ⚠ Checked against several fields rather than one. `Build.FINGERPRINT` is the usual tell, but
     * a Google APIs image and a Firebase Test Lab device both fail different halves of it, and a
     * check that only looked at `generic` would let a `sdk_gphone` image through.
     */
    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    /**
     * One execution provider to try, and whether ORT would even build a session with it.
     *
     * ⚠⚠ **"Initialised" and "ran the model" are different claims** (§15.5). ORT's NNAPI EP falls
     * back to CPU per-operator for anything it cannot place, silently — so a session that builds
     * proves nothing on its own, and **a per-EP median that matches CPU exactly is the tell** that
     * nothing was placed. The report prints both numbers side by side for precisely that reason;
     * it does not try to decide the question, because ORT does not expose the placement.
     */
    enum class Provider(val label: String) {
        CPU("cpu"),
        NNAPI("nnapi"),
        XNNPACK("xnnpack"),
    }

    /** ⚠ `null` when this provider is not available in this build of ORT — a real answer, not an error. */
    fun sessionOptions(provider: Provider): OrtSession.SessionOptions? =
        runCatching {
            OrtSession.SessionOptions().apply {
                when (provider) {
                    Provider.CPU -> Unit
                    Provider.NNAPI -> addNnapi(EnumSet.noneOf(NNAPIFlags::class.java))
                    Provider.XNNPACK -> addXnnpack(emptyMap())
                }
            }
        }.getOrNull()

    /**
     * Decode and resize one photo to `size × size`, then hand the pixels to the shared arithmetic.
     *
     * ⚠⚠ **`inSampleSize` first, then the exact scale.** A 12-megapixel phone photo decoded at full
     * resolution is ~48 MB of `Bitmap`, and 200 of them in a loop is an OOM rather than a
     * measurement. Sub-sampling to roughly twice the target keeps the resample quality while
     * bounding residency.
     *
     * ⚠ The resampler is Android's, not AWT's — see [ImagePreprocessing.preprocessPixels]. The timings are
     * comparable across platforms; the vectors are not.
     */
    fun decode(file: File, size: Int, profile: ImagePreprocessing.Profile): FloatArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size * 2) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val source = if (profile.centreCrop) {
            val side = minOf(decoded.width, decoded.height)
            Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side,
            )
        } else {
            decoded
        }

        val scaled = Bitmap.createScaledBitmap(source, size, size, /* filter = */ true)
        val argb = IntArray(size * size)
        scaled.getPixels(argb, 0, size, 0, 0, size, size)

        if (scaled !== source) scaled.recycle()
        if (source !== decoded) source.recycle()
        decoded.recycle()

        return ImagePreprocessing.preprocessPixels(argb, size, profile)
    }

    /** What one pass produced. ⚠ [timings] excludes decode: the question is what the *model* costs. */
    data class Pass(
        val provider: Provider,
        val timings: List<Long>,
        val wallClockMs: Long,
        val chargeCounterDeltaUah: Long?,
        val failures: Int,
    ) {
        val median: Long get() = HarnessMath.percentile(timings, 50)
        val firstWindow: Long get() = HarnessMath.percentile(timings.take(WINDOW), 50)
        val lastWindow: Long get() = HarnessMath.percentile(timings.takeLast(WINDOW), 50)

        /**
         * §15.4's headline. ⚠⚠ Near 1.0 means §3.5's desktop extrapolation holds; 2× means the pass
         * is throttle-bound and **every scheduling decision downstream changes**. It is the reason
         * this slice exists at all — a percentile snapshot over 11 images cannot see it.
         */
        val throttleRatio: Double
            get() = if (firstWindow == 0L) Double.NaN else lastWindow.toDouble() / firstWindow
    }

    /**
     * Embed every photo once, in order, with no pauses (§15.4).
     *
     * ⚠ Decode is deliberately inside the loop and outside the timer. Inside, because a real index
     * pass decodes too and the thermal load is what is being measured; outside, because the number
     * IN-3b needs is the model's cost and decode varies with the camera that took the photo.
     */
    fun runPass(
        environment: OrtEnvironment,
        modelPath: String,
        photos: List<File>,
        provider: Provider,
        side: Int,
        profile: ImagePreprocessing.Profile,
        battery: BatteryManager?,
    ): Pass? {
        val options = sessionOptions(provider) ?: return null
        val timings = ArrayList<Long>(photos.size)
        var failures = 0
        val chargeBefore = battery?.chargeCounterUah()

        val startedAt = System.nanoTime()
        runCatching {
            environment.createSession(modelPath, options).use { session ->
                val inputName = session.inputNames.first()
                val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
                photos.forEach { photo ->
                    val input = decode(photo, side, profile)
                    if (input == null) {
                        failures++
                        return@forEach
                    }
                    val began = System.nanoTime()
                    val ok = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape)
                        .use { tensor -> session.run(mapOf(inputName to tensor)).use { it.size() > 0 } }
                    timings += (System.nanoTime() - began) / 1_000_000
                    if (!ok) failures++
                }
            }
        }.onFailure { return null }

        val wallClockMs = (System.nanoTime() - startedAt) / 1_000_000
        val chargeAfter = battery?.chargeCounterUah()
        return Pass(
            provider = provider,
            timings = timings,
            wallClockMs = wallClockMs,
            chargeCounterDeltaUah = if (chargeBefore != null && chargeAfter != null) {
                chargeBefore - chargeAfter
            } else {
                null
            },
            failures = failures,
        )
    }

    /**
     * ⚠ `BatteryManager` returns `Long.MIN_VALUE` — not an exception — when the property is
     * unsupported, and plenty of devices do not implement the charge counter. Treated as absent so
     * the report says "unavailable" rather than printing a nonsense delta (§15.6).
     */
    private fun BatteryManager.chargeCounterUah(): Long? =
        getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
}
