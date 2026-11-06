package org.tenshu.inference.harness.ios

import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.inference.proxy.coreml.CoreMlComputeUnits
import org.tenshu.inference.proxy.coreml.CoreMlModelCache
import org.tenshu.inference.proxy.coreml.CoreMlPredictionException
import org.tenshu.inference.proxy.coreml.CoreMlPrepareStep
import org.tenshu.inference.proxy.coreml.CoreMlSession
import org.tenshu.inference.proxy.coreml.CoreMlTowerSpec
import org.tenshu.inference.proxy.coreml.IOS_PREPROCESSING_REVISION
import org.tenshu.inference.proxy.coreml.IosImageDecoder
import org.tenshu.inference.proxy.harness.HarnessMath
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.VisionModel
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.TimeSource

/** How one launch of the harness ended. Every outcome but [REFUSED_SIMULATOR] wrote the report. */
enum class In3mOutcome {
    REFUSED_SIMULATOR,
    UNKNOWN_ROW,
    NO_MODEL,
    NO_PHOTOS,

    /** The photos on the phone are not the corpus the manifest names, or there is no manifest. */
    CORPUS_REFUSED,
    MEASURED,
    FAILED,
}

/**
 * IN-3m-iOS: one row of the Core ML measurement, in one process
 * (`docs/proposals/ios-on-device-inference.md` §4).
 *
 * It drives the **shipped** classes — [CoreMlModelCache], [CoreMlSession], [IosImageDecoder],
 * [ImagePreprocessing] — in the order an index pass does, and times each. Everything is
 * side-loaded into the app's `Documents` (§4.4): the two `.mlpackage.tar` archives, the corpus
 * under `photos/` and the [CorpusManifest] that says which of those files the corpus is. There is
 * no catalog and no download here.
 *
 * ⚠⚠ **One process per row.** A footprint peak is a process-lifetime high-water mark, so a row
 * measured after another inherits it (§2). Relaunch for each row; the report appends.
 *
 * ⚠ The compiled models land in `Documents/coreml/`, not the app-private Application Support the
 * product uses (§3.1): the cache takes its archive from the same [FileDatabase] it writes to, and
 * `Documents` is the one directory `devicectl` copies in and out of.
 */
@OptIn(NativeRuntimeApi::class)
class In3mHarness internal constructor(
    private val documents: String,
    private val log: (String) -> Unit,
    private val isSimulator: Boolean,
    private val probes: DeviceProbes,
) {
    /** The harness as the app builds it: the real target, the real readings. */
    constructor(documents: String, log: (String) -> Unit) : this(documents, log, builtForSimulator, SystemDeviceProbes)

    private val model: VisionModel = VisionModel.PinnedSigLip
    private val files: FileDatabase = IosFileDatabase(root = documents)
    private val report = StringBuilder()

    /**
     * @param row `coreml-ane` (what ships), `coreml-all` or `coreml-cpu` (the control).
     * @param tower `vision`, over the corpus, or `text`, over fixed token ids.
     * @param recompile drop the compiled models first, so this launch times the compile again.
     */
    fun run(row: String, tower: String, recompile: Boolean): In3mOutcome {
        // ⚠⚠ Nothing else: no report, no header. The skip paths below write the report; this one
        // stays silent, so no file exists that could be mistaken for a measurement.
        if (isSimulator) {
            log(SIMULATOR_REFUSAL)
            return In3mOutcome.REFUSED_SIMULATOR
        }
        val outcome: In3mOutcome = try {
            measure(row, tower, recompile)
        } catch (failure: Throwable) {
            line("FAILED: ${failure::class.simpleName}: ${failure.message}")
            In3mOutcome.FAILED
        }
        line("outcome: $outcome")
        line("")
        files.writeBytesRange(REPORT, files.fileSize(REPORT) ?: 0L, report.toString().encodeToByteArray())
        return outcome
    }

    private fun measure(row: String, tower: String, recompile: Boolean): In3mOutcome {
        line("== IN-3m-iOS  row=$row tower=$tower ==")
        line("device: ${probes.machine()}  os: ${probes.osBuild()}")
        line("physicalMemory: ${probes.physicalMemory()} bytes (${gibibytes(probes.physicalMemory())})")
        line("model: ${model.modelId} v${model.modelVersion}  preprocessing revision: $IOS_PREPROCESSING_REVISION")
        line(
            "battery: ${probes.batteryLevel()?.let { percent(it) } ?: "unavailable"} (${probes.batteryState()})" +
                "  low power mode: ${probes.lowPowerMode()}  thermalState: ${probes.thermalState()}",
        )

        if (probes.thermalState() >= THERMAL_SERIOUS) {
            line("⚠ started at thermalState ${probes.thermalState()}: this row is throttled from its first photo, NOT a planning figure")
        }

        val units: CoreMlComputeUnits = ROWS[row] ?: run {
            line("SKIPPED: unknown row '$row' — expected one of ${ROWS.keys.sorted()}")
            return In3mOutcome.UNKNOWN_ROW
        }
        val spec: CoreMlTowerSpec = when (tower) {
            CoreMlTowerSpec.VISION_ROLE -> CoreMlTowerSpec.vision(model)
            CoreMlTowerSpec.TEXT_ROLE -> CoreMlTowerSpec.text(model)
            else -> {
                line("SKIPPED: unknown tower '$tower' — expected vision or text")
                return In3mOutcome.UNKNOWN_ROW
            }
        }

        val archive: String = "${tower}_fp16.mlpackage.tar"
        val archiveBytes: Long = files.fileSize(archive) ?: run {
            line("SKIPPED: no model — expected Documents/$archive")
            return In3mOutcome.NO_MODEL
        }
        val corpus: CorpusManifest.Verified? = if (tower == CoreMlTowerSpec.VISION_ROLE) {
            val verifying = TimeSource.Monotonic.markNow()
            val verified: CorpusManifest.Verified =
                CorpusManifest.verify(files, PHOTOS, filesIn("$documents/$PHOTOS")).getOrElse { refusal ->
                    line("REFUSED: ${refusal.message}")
                    return In3mOutcome.CORPUS_REFUSED
                }
            if (verified.names.isEmpty()) {
                line("SKIPPED: no photos — the corpus manifest names none")
                return In3mOutcome.NO_PHOTOS
            }
            // ⚠ Every named file was just read to be hashed, so the pass's file reads are warm.
            line(
                "corpus: ${verified.id}  ${verified.names.size} photos, each checked by size and sha256 " +
                    "in ${ms(verifying.elapsedNow().inWholeMicroseconds)} ms",
            )
            if (verified.unnamed.isNotEmpty()) {
                line(
                    "not in the corpus manifest, ignored: ${verified.unnamed.size} file(s) in Documents/$PHOTOS — " +
                        verified.unnamed.take(UNNAMED_LISTED).joinToString(", ") +
                        if (verified.unnamed.size > UNNAMED_LISTED) ", …" else "",
                )
            }
            verified
        } else {
            null
        }

        val hashing = TimeSource.Monotonic.markNow()
        val digest: String = CorpusManifest.sha256Of(files, archive)
        line("archive: $archive  ${megabytes(archiveBytes)}  sha256 $digest  (hashed in ${ms(hashing.elapsedNow().inWholeMicroseconds)} ms)")

        val cache = CoreMlModelCache(files, documents)
        if (recompile) cache.sweep(emptySet())
        val compiledPath: String = prepare(cache, spec, archive, archiveBytes, digest)

        // ⚠ Hashing and unpacking leave hundreds of megabytes of dead chunk arrays on the Kotlin
        // heap. Collected here, so the footprint readings below describe the model and the pass.
        GC.collect()
        val opening = TimeSource.Monotonic.markNow()
        val session: CoreMlSession = CoreMlSession.open(compiledPath, spec, units)
        line("open ($units): ${ms(opening.elapsedNow().inWholeMicroseconds)} ms  phys_footprint after open: ${footprintText()}")

        if (corpus != null) visionPass(session, row, corpus, digest) else textPass(session)
        session.close()

        val end: Footprint? = probes.footprint()
        line("phys_footprint at end: ${footprintText()}  ledger peak: ${end?.let { megabytes(it.ledgerPeak) } ?: "unavailable"}")
        line("battery at end: ${probes.batteryLevel()?.let { percent(it) } ?: "unavailable"}  thermalState: ${probes.thermalState()}")
        return In3mOutcome.MEASURED
    }

    /** The prepare step of §3.4, with each step's time and what it did to the volume's free space. */
    private fun prepare(
        cache: CoreMlModelCache,
        spec: CoreMlTowerSpec,
        archive: String,
        archiveBytes: Long,
        digest: String,
    ): String {
        if (cache.compiledPath(spec.role, digest) != null) {
            line("prepare: already compiled (marker present); the compile was NOT re-run — launch with recompile to time it")
        }
        class Started(val step: CoreMlPrepareStep, val atUs: Long, val freeBytes: Long?)

        val started = ArrayList<Started>()
        val clock = TimeSource.Monotonic.markNow()
        val path: String = runBlocking {
            cache.prepare(spec, archive, digest) { step ->
                started += Started(step, clock.elapsedNow().inWholeMicroseconds, probes.freeBytes(documents))
            }
        }
        val endUs: Long = clock.elapsedNow().inWholeMicroseconds
        val endFree: Long? = probes.freeBytes(documents)
        started.forEachIndexed { index, entry ->
            val next: Started? = started.getOrNull(index + 1)
            val took: Long = (next?.atUs ?: endUs) - entry.atUs
            val after: Long? = if (next != null) next.freeBytes else endFree
            val delta: String = if (entry.freeBytes != null && after != null) megabytes(after - entry.freeBytes) else "unavailable"
            line("  ${entry.step.name.padEnd(14)} ${ms(took).padStart(10)} ms   free space change $delta")
        }
        if (started.isNotEmpty()) {
            val first: Long? = started.first().freeBytes
            line(
                "prepare total: ${ms(endUs)} ms   free space change " +
                    (if (first != null && endFree != null) megabytes(endFree - first) else "unavailable"),
            )
        }
        line("compiled model: ${megabytes(directoryBytes(path))} on disk (archive ${megabytes(archiveBytes)})")
        return path
    }

    /**
     * Every photo once, in order, with no pauses: read, decode, preprocess, predict (§4.1).
     *
     * ⚠ Decode is timed here, which Android's IN-3m does not do (`DeviceMeasurement.kt:132`). At a
     * few milliseconds a prediction, decode is expected to be most of a pass, and it is this
     * loop's duty cycle — not predictions back to back — that the thermal reading describes.
     */
    private fun visionPass(session: CoreMlSession, row: String, corpus: CorpusManifest.Verified, archiveDigest: String) {
        // ⚠ The manifest's names, never the directory's: see [CorpusManifest].
        val photos: List<String> = corpus.names
        val side: Int = model.side
        val readUs = ArrayList<Long>(photos.size)
        val decodeUs = ArrayList<Long>(photos.size)
        val preprocessUs = ArrayList<Long>(photos.size)
        val predictUs = ArrayList<Long>(photos.size)
        val photoUs = ArrayList<Long>(photos.size)
        val vectors = LinkedHashMap<String, FloatArray>()
        val thermal = ArrayList<String>()
        var unreadable: Int = 0
        var failed: Int = 0
        var firstFailure: String? = null
        var peak: Long = probes.footprint()?.current ?: 0L

        val pass = TimeSource.Monotonic.markNow()
        photos.forEachIndexed { index, name ->
            if (index % WINDOW == 0) thermal += "${probes.thermalState()}@$index"

            val reading = TimeSource.Monotonic.markNow()
            val bytes: ByteArray? = readFile("$documents/$PHOTOS/$name")
            readUs += reading.elapsedNow().inWholeMicroseconds
            if (bytes == null) {
                unreadable++
                return@forEachIndexed
            }
            val decoding = TimeSource.Monotonic.markNow()
            val argb: IntArray? = IosImageDecoder.decode(bytes, side, model.profile.centreCrop)
            val decodeTook: Long = decoding.elapsedNow().inWholeMicroseconds
            if (argb == null) {
                unreadable++
                return@forEachIndexed
            }
            val preprocessing = TimeSource.Monotonic.markNow()
            val tensor: FloatArray = ImagePreprocessing.preprocessPixels(argb, side, model.profile)
            val preprocessTook: Long = preprocessing.elapsedNow().inWholeMicroseconds

            val predicting = TimeSource.Monotonic.markNow()
            val vector: FloatArray = try {
                session.predict(tensor)
            } catch (failure: CoreMlPredictionException) {
                failed++
                if (firstFailure == null) firstFailure = "$name: ${failure.message}"
                return@forEachIndexed
            }
            val predictTook: Long = predicting.elapsedNow().inWholeMicroseconds

            decodeUs += decodeTook
            preprocessUs += preprocessTook
            predictUs += predictTook
            photoUs += decodeTook + preprocessTook + predictTook
            vectors[name] = ImagePreprocessing.normalise(vector)
            peak = maxOf(peak, probes.footprint()?.current ?: 0L)
        }
        val wallUs: Long = pass.elapsedNow().inWholeMicroseconds
        thermal += "${probes.thermalState()}@end"

        line("photos: ${photos.size} named by corpus ${corpus.id.take(12)}  embedded ${vectors.size}  unreadable $unreadable  failed $failed")
        firstFailure?.let { line("first failure: $it") }
        if (vectors.size < MIN_IMAGES) {
            line("⚠ fewer than $MIN_IMAGES embedded: NOT an IN-3m figure, the throttle question cannot be asked")
        }
        line("predict ms:     ${windows(predictUs)}")
        line("decode ms:      ${windows(decodeUs)}")
        line("preprocess ms:  median ${ms(HarnessMath.percentile(preprocessUs, 50))}")
        line("file read ms:   median ${ms(HarnessMath.percentile(readUs, 50))}")
        line("per photo ms (decode + preprocess + predict): ${windows(photoUs)}")
        line(
            "wall clock: ${ms(wallUs)} ms for ${photos.size} photos, " +
                "${ms(if (photos.isEmpty()) 0L else wallUs / photos.size)} ms/photo, file read included",
        )
        val lastWindow: Long = HarnessMath.percentile(photoUs.takeLast(WINDOW), 50)
        val fires: Boolean = lastWindow > ROW_THREE_US || throttle(photoUs) > ROW_THREE_THROTTLE
        line("§15.9 row 3 (last-$WINDOW per photo > 500 ms, or throttle > 2): ${if (fires) "FIRES" else "does not fire"}")
        line("thermalState (state@photo): ${thermal.joinToString(" ")}")
        line("phys_footprint peak over the pass, sampled per photo: ${megabytes(peak)}")

        val fingerprint = VectorFile.Fingerprint(
            tower = CoreMlTowerSpec.VISION_ROLE,
            row = row,
            osBuild = probes.osBuild(),
            archiveSha256 = archiveDigest,
            corpusId = corpus.id,
            preprocessingRevision = IOS_PREPROCESSING_REVISION,
        )
        val mine: String = VectorFile.nameFor(fingerprint)
        VectorFile.write(files, mine, fingerprint, vectors)
        line("vectors: Documents/$mine (${vectors.size}) — kept; re-run this row after an iOS update")
        VectorFile.compareWithKept(files, filesIn(documents), mine, fingerprint, vectors).forEach { line(it) }
    }

    /** The text tower on fixed token ids: the tokenizer reaches iOS in PR-2, and this needs none. */
    private fun textPass(session: CoreMlSession) {
        val ids = IntArray(model.textSequenceLength) { FIXED_CAPTION.getOrElse(it) { TEXT_PAD } }
        repeat(TEXT_WARM_UP) { session.predict(ids) }
        val predictUs = ArrayList<Long>(TEXT_RUNS)
        var peak: Long = probes.footprint()?.current ?: 0L
        repeat(TEXT_RUNS) {
            val predicting = TimeSource.Monotonic.markNow()
            session.predict(ids)
            predictUs += predicting.elapsedNow().inWholeMicroseconds
            peak = maxOf(peak, probes.footprint()?.current ?: 0L)
        }
        line("text: $TEXT_RUNS predictions of one fixed caption after $TEXT_WARM_UP warm-up")
        line("predict ms:     ${windows(predictUs)}")
        line("phys_footprint peak over the runs, sampled per prediction: ${megabytes(peak)}")
    }

    /** Median, p90, and the first-window and last-window medians whose ratio is the throttle reading. */
    private fun windows(timings: List<Long>): String =
        "median ${ms(HarnessMath.percentile(timings, 50))}  p90 ${ms(HarnessMath.percentile(timings, 90))}" +
            "  first$WINDOW ${ms(HarnessMath.percentile(timings.take(WINDOW), 50))}" +
            "  last$WINDOW ${ms(HarnessMath.percentile(timings.takeLast(WINDOW), 50))}" +
            "  throttle ${fixed(throttle(timings), 2)}"

    private fun throttle(timings: List<Long>): Double {
        val first: Long = HarnessMath.percentile(timings.take(WINDOW), 50)
        return if (first == 0L) Double.NaN else HarnessMath.percentile(timings.takeLast(WINDOW), 50).toDouble() / first
    }

    private fun footprintText(): String = probes.footprint()?.let { megabytes(it.current) } ?: "unavailable"

    private fun line(text: String) {
        log(text)
        report.append(text).append('\n')
    }

    companion object {
        /** `inference-service.md` §15.1's wording, for the iOS simulator. */
        const val SIMULATOR_REFUSAL: String = "simulator — timings meaningless, ignoring"

        /** The report, in the app's `Documents`. Each launch appends its row. */
        const val REPORT: String = "in3m-report.txt"
        const val PHOTOS: String = "photos"

        /** `DeviceMeasurement.MIN_IMAGES` / `WINDOW`: the same N and windows as Android's IN-3m. */
        const val MIN_IMAGES: Int = 200
        const val WINDOW: Int = 50

        val ROWS: Map<String, CoreMlComputeUnits> = mapOf(
            "coreml-ane" to CoreMlComputeUnits.CPU_AND_NEURAL_ENGINE,
            "coreml-all" to CoreMlComputeUnits.ALL,
            "coreml-cpu" to CoreMlComputeUnits.CPU_ONLY,
        )

        private const val THERMAL_SERIOUS: Int = 2
        private const val ROW_THREE_US: Long = 500_000
        private const val ROW_THREE_THROTTLE: Double = 2.0
        private const val UNNAMED_LISTED: Int = 20
        private const val TEXT_WARM_UP: Int = 10
        private const val TEXT_RUNS: Int = 200

        /** `a sunset over water`, from `SiglipTextTokenizerTest`'s golden cases; padded with `</s>`. */
        private val FIXED_CAPTION: List<Int> = listOf(259, 262, 259, 114441, 910, 4582, 1)
        private const val TEXT_PAD: Int = 1

        internal fun ms(microseconds: Long): String = fixed(microseconds / 1000.0, 2)

        internal fun megabytes(bytes: Long): String = "${fixed(bytes / 1_000_000.0, 1)} MB"

        internal fun gibibytes(bytes: Long): String = "${fixed(bytes / 1_073_741_824.0, 2)} GiB"

        private fun percent(level: Double): String = "${(level * 100).toInt()} %"

        internal fun fixed(value: Double, decimals: Int): String {
            if (value.isNaN()) return "n/a"
            var scale = 1L
            repeat(decimals) { scale *= 10 }
            val scaled: Long = kotlin.math.round(kotlin.math.abs(value) * scale).toLong()
            val sign: String = if (value < 0 && scaled != 0L) "-" else ""
            return if (decimals == 0) "$sign$scaled" else "$sign${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
        }
    }
}
