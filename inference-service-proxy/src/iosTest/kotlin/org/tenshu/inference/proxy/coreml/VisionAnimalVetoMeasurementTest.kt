package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.proxy.AnimalVeto
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.onnx.FaceModel
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **M5 on iOS** (`photos-pets.md` §10.4): which faces would PT-D9 remove through Apple Vision, and are
 * any of them people? Android's pass (§10.2) does not transfer: Vision's face boxes are not
 * MediaPipe's, so the by-eye verdicts have to be made again on what Vision finds.
 *
 * **Measure-only, through the product's own code.** [VisionCoreMlFaceBackend] with the veto off finds
 * the faces as the library was indexed, through `IosFaceImageDecoder` and the photo's orientation.
 * [SystemVisionAnimalDetector] then runs on the same upright decode. Every face is written with its
 * best animal box (`faces.tsv`), every overlapping face–animal pair (`pairs.tsv`), and the decision
 * [AnimalVeto.Proposed] makes. A second backend with the veto ON runs the same photographs, and its
 * `insideAnimals` total must equal this pass's count: the product path and the table agree.
 *
 * ```
 * ./gradlew :inference-service-proxy:iosSimulatorArm64Test --tests '*VisionAnimalVetoMeasurementTest*' \
 *     -Dtenshu.inference.vetoPhotos=<dir of the previews> \
 *     -Dtenshu.inference.vetoOrientations=<"name orientation" per line> \
 *     -Dtenshu.inference.vetoOut=<dir to write faces.tsv, pairs.tsv, report.txt>
 * ```
 *
 * ⚠ Skips without the flags, and says so. ⚠⚠ The simulator runs iOS's Vision on the Mac's hardware,
 * which is not a phone's (`ios-face-grouping.md` §11.3), so no timing is taken here and the device run
 * is a separate confirmation. ⚠⚠ The photographs are the operator's own: they and everything written
 * from them stay off the repository.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class VisionAnimalVetoMeasurementTest {

    private fun env(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

    @Test
    fun measureTheVetoThroughVision() = runBlocking {
        val photos = env("TENSHU_VETO_PHOTOS")
        val out = env("TENSHU_VETO_OUT")
        if (photos == null || out == null) {
            println("SKIPPED: no -Dtenshu.inference.vetoPhotos / -Dtenshu.inference.vetoOut, see the KDoc")
            return@runBlocking
        }
        val orientations: Map<String, Int> = env("TENSHU_VETO_ORIENTATIONS")?.let { path ->
            checkNotNull(text(path)) { "the orientation manifest $path does not read" }.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2) parts[0] to parts[1].toInt() else null
            }.toMap()
        }.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val names: List<String> = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(photos, null) as List<String>?)
            .orEmpty().filter { !it.startsWith(".") }.sorted()
        check(names.isNotEmpty()) { "no photographs under $photos" }
        NSFileManager.defaultManager.createDirectoryAtPath(out, true, null, null)

        val off = VisionCoreMlFaceBackend(locate = { "/nowhere" }, open = { _, _ -> error("detection opens no embedder") })
        val on = VisionCoreMlFaceBackend(
            locate = { "/nowhere" },
            open = { _, _ -> error("detection opens no embedder") },
            animalVeto = AnimalVeto.Proposed,
        )
        val veto = AnimalVeto.Proposed
        val table = StringBuilder("# path left top right bottom faceConfidence bestSpecies bestConfidence faceInside wouldVeto\n")
        val pairs = StringBuilder("# path faceIndex faceLeft faceTop faceRight faceBottom species animalConfidence faceInside\n")
        var faces = 0
        var withFaces = 0
        var removed = 0
        var productRemoved = 0
        var failed = 0
        for (name in names) {
            val bytes = bytes("$photos/$name") ?: run { failed++; null } ?: continue
            val orientation = orientations[name]
            val detected = off.detectFaces(bytes, orientation) as? FaceDetectionOutcome.Detected ?: run { failed++; null } ?: continue
            if (detected.faces.isEmpty()) continue
            withFaces++
            val image = IosFaceImageDecoder.decodeUpright(bytes, orientation) ?: run { failed++; null } ?: continue
            val animals: List<DetectedAnimal> = SystemVisionAnimalDetector.detect(image).animals
            for ((index, face) in detected.faces.withIndex()) {
                faces++
                animals.forEach { animal ->
                    val share = inside(face.box, animal)
                    if (share > 0f) {
                        pairs.appendLine("$name $index ${face.box.left} ${face.box.top} ${face.box.right} ${face.box.bottom} ${animal.species} ${animal.confidence} $share")
                    }
                }
                val best = animals.maxByOrNull { inside(face.box, it) * it.confidence }
                val would = veto.vetoes(face.box, animals)
                if (would) removed++
                table.appendLine(
                    "$name ${face.box.left} ${face.box.top} ${face.box.right} ${face.box.bottom} ${face.box.confidence} " +
                        "${best?.species ?: "-"} ${best?.confidence ?: 0f} ${best?.let { inside(face.box, it) } ?: 0f} $would",
                )
            }
            val product = on.detectFaces(bytes, orientation) as? FaceDetectionOutcome.Detected ?: run { failed++; null } ?: continue
            productRemoved += product.insideAnimals
        }
        val report = """
            |iOS veto (M5) — simulator, Vision faces rev ${FaceModel.VISION_LANDMARKS_REVISION} + animals rev ${FaceModel.VISION_ANIMALS_REVISION}
            |photos ${names.size}, with faces $withFaces, faces $faces
            |would remove at $veto: $removed (product backend, veto on: $productRemoved)
            |failures: $failed
        """.trimMargin()
        write("$out/faces.tsv", table.toString())
        write("$out/pairs.tsv", pairs.toString())
        write("$out/report.txt", report + "\n")
        report.lines().forEach { println("VETO-IOS $it") }
        assertEquals(removed, productRemoved, "the table and the product backend disagree on what the veto removes")
    }

    private fun inside(face: FaceBox, animal: DetectedAnimal): Float {
        val area = (face.right - face.left) * (face.bottom - face.top)
        if (area <= 0f) return 0f
        val iw = (minOf(face.right, animal.right) - maxOf(face.left, animal.left)).coerceAtLeast(0f)
        val ih = (minOf(face.bottom, animal.bottom) - maxOf(face.top, animal.top)).coerceAtLeast(0f)
        return iw * ih / area
    }

    private fun bytes(path: String): ByteArray? = autoreleasepool {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return@autoreleasepool null
        ByteArray(data.length.toInt()).also { out ->
            if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length.convert()) }
        }
    }

    private fun text(path: String): String? = bytes(path)?.decodeToString()

    private fun write(path: String, content: String) {
        check(NSString.create(string = content).writeToFile(path, true, NSUTF8StringEncoding, null)) { "could not write $path" }
    }
}
