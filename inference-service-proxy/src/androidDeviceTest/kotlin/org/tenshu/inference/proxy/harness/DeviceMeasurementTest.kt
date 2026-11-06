package org.tenshu.inference.proxy.harness

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **IN-3m — wall time and battery, on real hardware** (`inference-service.md` §15).
 *
 * `models.md` §3.5 measured SigLIP v1 on a desktop Mac: uint8 at 24 ms/image, a 5 000-photo pass in
 * ~2 minutes. **That is a floor.** This is the number IN-3b actually needs.
 *
 * ⚠⚠ **This is not a gate and must never become one** (§15.8). It skips with a printed reason when
 * the model or the photo directory is absent, it is excluded from `scripts/android-smoke.sh`, and
 * it is unreachable from `./gradlew desktopTest jvmTest test`. A measurement that can fail a build
 * is a benchmark pretending to be a test.
 *
 * ⚠⚠ **On an emulator it reports nothing but a pass** (§15.1). The AVD executes on the host CPU, so
 * a figure taken there is a desktop number wearing an Android costume, and unlike a missing number
 * a wrong one gets quoted.
 *
 * ## Running it
 *
 * Nothing is committed — not the weights, not the photos, not the report; the repo is public (§15.3).
 * Side-load both into the app-specific external directory, which needs **no runtime permission**:
 *
 * ⚠⚠ **Not via `connectedAndroidTest`** (§15.12.6): AGP uninstalls the test APK when the run ends,
 * and that deletes `/sdcard/Android/data/<pkg>/files` — the corpus you pushed *and* the report the
 * run just wrote. Install it once and drive it with `am instrument`, so the corpus also survives
 * between the cold and the warm run.
 *
 * ```
 * P=/sdcard/Android/data/org.tenshu.inference.proxy.test/files
 * ./gradlew :inference-service-proxy:packageAndroidDeviceTest
 * A=inference-service-proxy/build/outputs/apk/androidTest
 * adb install -r $A/inference-service-proxy-androidTest.apk
 * adb push vision_model_uint8.onnx $P/model.onnx
 * adb push ~/photos/. $P/photos/
 * adb shell chmod -R 777 $P/photos                       # ⚠⚠ see below
 * adb shell am instrument -w -e class \
 *   org.tenshu.inference.proxy.harness.DeviceMeasurementTest \
 *   org.tenshu.inference.proxy.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull $P/in3m-report.txt
 * ```
 *
 * ⚠⚠ **The `chmod` is not optional.** `adb push` creates *files* mode 0666 but *directories* mode
 * 0770 owned by `shell`, and the app cannot traverse those — so the model is found, the photos
 * beside it are not, and [File.walkTopDown] swallows the permission error, which makes an
 * unreadable directory and an empty one the same event.
 *
 * ⚠ Run it **twice** (§15.4): once from cold, once immediately after. The second run's first-window
 * median is the "device already warm" case, which is what an incremental re-index experiences.
 *
 * ⚠ `println` from here reaches logcat under the `System.out` tag and **not** `am instrument`'s
 * output, so `adb logcat -d | grep IN-3m` is how a run that skipped explains itself.
 */
@RunWith(AndroidJUnit4::class)
class DeviceMeasurementTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val files: File?
        get() = context.getExternalFilesDir(null)

    @Test
    fun measureFirstIndexPass() {
        val log = StringBuilder()
        fun say(line: String) {
            println("IN-3m: $line")
            log.appendLine(line)
        }

        say("IN-3m — wall time and battery, on real hardware (inference-service.md §15)")
        say("device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.HARDWARE} / sdk ${Build.VERSION.SDK_INT}")

        // ⚠⚠ §15.1. Before anything else, and it reports NOTHING but a pass.
        if (DeviceMeasurement.isEmulator()) {
            println("IN-3m: emulator — timings meaningless, ignoring")
            return
        }

        val root = files
        if (root == null) {
            say("skipped: no external files dir on this device")
            return
        }
        val model = File(root, MODEL_NAME)
        val photoDir = File(root, PHOTO_DIR)
        if (!model.isFile) {
            say("skipped: no model at ${model.absolutePath} — see the KDoc for the adb push")
            writeReport(root, log)
            return
        }
        val photos = photoDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.absolutePath }
            .toList()
        if (photos.isEmpty()) {
            say("skipped: no photos under ${photoDir.absolutePath}")
            // ⚠⚠ §15.12.6: this is also what an *unreadable* directory looks like, because
            // `walkTopDown` swallows the permission error. `ls -ld` it before believing "empty".
            say("⚠ if you pushed photos there, check the directory is traversable: chmod -R 777")
            writeReport(root, log)
            return
        }
        // ⚠⚠ Below this the throttle question cannot be ASKED, and answering it from a short pass
        // is the specific mistake §15.4 exists to prevent. It runs anyway and says so, because a
        // short pass still gives a usable median — it just cannot give a ratio.
        if (photos.size < DeviceMeasurement.MIN_IMAGES) {
            say(
                "⚠ only ${photos.size} photos — §15.4 wants ≥ ${DeviceMeasurement.MIN_IMAGES}. " +
                    "The median below is usable; the throttle ratio is NOT.",
            )
        }

        val side = intProperty("tenshu.inference.side", DEFAULT_SIDE)
        val profile = ImagePreprocessing.Profile.SIGLIP
        say("model: ${model.name} (${model.length() / 1_000_000} MB), side=$side, profile=$profile")
        say("photos: ${photos.size} under ${photoDir.name}")
        say("")

        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (battery == null) say("⚠ no BatteryManager — charge deltas unavailable (§15.6)")

        val environment = OrtEnvironment.getEnvironment()
        val passes = LinkedHashMap<DeviceMeasurement.Provider, DeviceMeasurement.Pass>()

        DeviceMeasurement.Provider.entries.forEach { provider ->
            val pass = DeviceMeasurement.runPass(
                environment = environment,
                modelPath = model.absolutePath,
                photos = photos,
                provider = provider,
                side = side,
                profile = profile,
                battery = battery,
            )
            if (pass == null) {
                // §15.5: a real answer about IN-D13's ladder, not a failure.
                say("${provider.label}: session did not initialise — this rung of IN-D13's ladder is absent here")
                return@forEach
            }
            passes[provider] = pass
            say(
                "${provider.label}: n=${pass.timings.size} median=${pass.median}ms " +
                    "first${DeviceMeasurement.WINDOW}=${pass.firstWindow}ms " +
                    "last${DeviceMeasurement.WINDOW}=${pass.lastWindow}ms " +
                    "throttle=${"%.2f".format(pass.throttleRatio)} " +
                    "wall=${pass.wallClockMs}ms failures=${pass.failures} " +
                    "charge=${pass.chargeCounterDeltaUah?.let { "${it}uAh" } ?: "unavailable"}",
            )
        }

        say("")
        // ⚠⚠ §15.5's tell, stated rather than left for the reader to spot: an accelerated provider
        // whose median matches CPU did not place the graph, whatever "initialised" suggested.
        val cpu = passes[DeviceMeasurement.Provider.CPU]
        if (cpu != null) {
            passes.filterKeys { it != DeviceMeasurement.Provider.CPU }.forEach { (provider, pass) ->
                if (kotlin.math.abs(pass.median - cpu.median) <= CPU_MATCH_TOLERANCE_MS) {
                    say(
                        "⚠⚠ ${provider.label} median (${pass.median}ms) matches cpu (${cpu.median}ms) — " +
                            "it initialised but almost certainly ran on CPU (§15.5)",
                    )
                }
            }
            say(interpretation(cpu, tailIsReliable = photos.size >= DeviceMeasurement.MIN_IMAGES))
        }

        writeReport(root, log)
    }

    /**
     * §15.7: a file survives; instrumentation stdout is truncated and awkward to read.
     *
     * ⚠⚠ **Written on the skip paths too** (§15.12.6). A skip is exactly the case with no numbers
     * to read, `println` does not reach `am instrument`'s output, and a run that measured nothing
     * still exits green — so leaving the skips silent made the one failure mode that needs
     * explaining the only one that explained nothing.
     *
     * ⚠ The emulator path is deliberately still silent (§15.1): there, saying nothing is the point.
     */
    private fun writeReport(root: File, log: CharSequence) {
        val report = File(root, REPORT_NAME)
        runCatching { report.writeText(log.toString()) }
            .onSuccess { println("IN-3m: report written to ${report.absolutePath}") }
            .onFailure { println("IN-3m: could not write report — ${it.message}") }
    }

    /**
     * §15.9's table, applied to the number that was actually measured.
     *
     * ⚠⚠ **Read from the tail, not the median** (§15.12.4). The first version of this line chose a
     * row from [DeviceMeasurement.Pass.median], which on the 2026-09-01 run was the most flattering
     * of the six figures the pass collected: it named *"between rows"* while the last-50 median —
     * the closest thing to steady state — sat inside row 2. A 5 000-photo index spends almost all
     * of its time in the condition the tail measures and almost none in the one the median does.
     *
     * ⚠ [tailIsReliable] is false below [DeviceMeasurement.MIN_IMAGES], where a 50-image tail is
     * noise; there it falls back to the median and says so, because a wrong row is worse than a
     * hedged one.
     *
     * ⚠ Printed rather than asserted. The consequence of a slow device is a **design change**
     * upstream, not a red build — and turning §15.9 into an assertion is exactly how this stops
     * being a measurement (§15.8).
     */
    private fun interpretation(cpu: DeviceMeasurement.Pass, tailIsReliable: Boolean): String {
        val sustained = if (tailIsReliable) cpu.lastWindow else cpu.median
        val source = if (tailIsReliable) {
            "last-${DeviceMeasurement.WINDOW} median ${sustained}ms"
        } else {
            "median ${sustained}ms (⚠ too few photos for a tail — §15.4)"
        }
        return when {
            sustained > 500 || cpu.throttleRatio > 2.0 ->
                "⚠⚠ §15.9 row 3 [$source]: SigLIP base looks too big for this tier — revisit " +
                    "models.md §3.2's size class. ⚠ The family designed for this is MobileCLIP, " +
                    "which §3.1.1 eliminated on LICENCE, so this is a conversation with Apple " +
                    "rather than an engineering task."
            sustained >= 150 ->
                "§15.9 row 2 [$source]: a 5 000-photo pass is ~${sustained * 5_000 / 60_000} min " +
                    "of sustained compute ⇒ IN-D6's idle+charging gate becomes REQUIRED, and the " +
                    "UI owes a resumable progress surface."
            sustained <= 60 && cpu.throttleRatio <= 1.3 ->
                "§15.9 row 1 [$source]: first index is an ordinary background task; IN-D6's gate " +
                    "is prudence rather than necessity."
            else ->
                "between §15.9's rows — $source, throttle " +
                    "${"%.2f".format(cpu.throttleRatio)}. Read the table, do not let this line " +
                    "decide. ⚠ Run it a second time while the device is warm: the 2026-09-01 run " +
                    "moved from here into row 2 on the second pass (§15.12.2)."
        }
    }

    private fun intProperty(key: String, fallback: Int): Int =
        System.getProperty(key)?.toIntOrNull() ?: fallback

    private companion object {
        const val MODEL_NAME = "model.onnx"
        const val PHOTO_DIR = "photos"
        const val REPORT_NAME = "in3m-report.txt"

        /** ⚠ `-256-multilingual` is the pinned checkpoint (`models.md` §3.8), and it is 256². */
        const val DEFAULT_SIDE = 256

        /** Within this, two medians are the same number and the EP placed nothing. */
        const val CPU_MATCH_TOLERANCE_MS = 2L

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    }
}
