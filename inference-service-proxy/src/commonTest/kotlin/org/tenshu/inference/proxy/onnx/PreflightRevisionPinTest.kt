package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.PREFLIGHT_REVISION
import org.tenshu.inference.proxy.PreflightAttempts
import org.tenshu.inference.proxy.PreflightBudget
import org.tenshu.inference.proxy.PreflightFinding
import org.tenshu.inference.proxy.PreflightJudge
import org.tenshu.inference.proxy.PreflightMeasurement
import org.tenshu.inference.proxy.PreflightPolicy
import org.tenshu.inference.proxy.PreflightProbe
import org.tenshu.inference.proxy.PreflightProbes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PREFLIGHT_REVISION` is bumped by hand, and a hand-bumped constant decays
 * (`ios-on-device-inference.md` §14.2). This pins what the tokenizer, the vector guards, the
 * preflight's judge and its retry rule **do**, and what each probe record the build carries
 * **holds**, beside the number, so a change to any of them that forgets the bump fails here.
 * ⚠ Adding a record for another export is not such a change, and is told apart from one.
 *
 * ⚠ Behaviour, never source text: a refactor that tokenises the same passes untouched.
 * ⚠ It cannot make anyone bump. It can only stop the change from being silent: whoever re-pins
 * the digest below reads why, on the line above it.
 * ⚠ Not covered: the Core ML session's layout and names, and the order of the preflight's steps
 * on a phone. Neither has behaviour a `commonTest` can observe; the constant's KDoc is their
 * only guard.
 */
class PreflightRevisionPinTest {

    private val vocabulary = UnigramVocabulary.of(
        listOf(
            "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f,
            "\u2581" to -3.0f,
            "\u2581sun" to -1.0f, "set" to -1.0f, "\u2581sunset" to -5.0f,
            "\u2581a" to -1.0f, "\u2581dog" to -1.0f, "\u2581in" to -1.0f,
            "<0xC3>" to -20f, "<0xA9>" to -20f,
        ),
    )

    /**
     * One input per rule the tokenizer has: segmentation by score, lower-casing, the punctuation
     * set, whitespace, the byte fallback, an astral character outside the vocabulary, a lone
     * surrogate, truncation, the empty string. ⚠ None of the 158 code points NFKC disagrees on
     * across platforms (§10.3): this digest is the same on the JVM, the simulator and a browser.
     */
    private val queries = listOf(
        "sunset",
        "A DOG in a sunset",
        "a dog, in: a (sunset)!",
        "  a \t dog \n in  ",
        "é",
        "a \uD83E\uDD8A dog",
        "a \uD83E dog",
        "a dog ".repeat(40),
        "",
    )

    private val vectors: List<Pair<FloatArray, Int>> = listOf(
        floatArrayOf(0.5f, -0.5f) to 2,
        floatArrayOf(0f, 0f) to 2,
        floatArrayOf(Float.NaN, 1f) to 2,
        floatArrayOf(Float.POSITIVE_INFINITY, 1f) to 2,
        floatArrayOf(1f) to 2,
    )

    private val probe = PreflightProbe(
        id = "test",
        visionArchiveSha256 = "", textArchiveSha256 = "", vocabularySha256 = "", imageRgbSha256 = "", imageTensorSha256 = "",
        captionDescribes = "", captionDoesNot = "", tokensDescribes = IntArray(0), tokensDoesNot = IntArray(0),
        hostMargin = 0.5, minMargin = 0.25, hostVisionNorm = 10.0, hostDescribesNorm = 20.0, hostDoesNotNorm = 20.0, normBand = 2.0,
    )

    /** `[x, y]` of length [length] whose cosine with the x axis is [along]. */
    private fun pair(length: Float, along: Float = 1f): FloatArray =
        floatArrayOf(length * along, length * kotlin.math.sqrt(1f - along * along))

    private fun measured(
        predictMs: Double = 10.0,
        vision: FloatArray = pair(10f),
        doesNot: FloatArray = pair(20f, 0.1f),
        available: Long? = null,
        thermal: Int = 0,
        warned: Boolean = false,
        failure: String? = null,
    ) = PreflightMeasurement(
        thermalAtStart = thermal, memoryWarned = warned, failure = failure,
        vision = listOf(vision), describes = listOf(pair(20f, 0.6f)), doesNot = listOf(doesNot),
        visionPredictMs = listOf(predictMs), textPredictMs = listOf(10.0), availableBytes = available,
    )

    /** One measurement on each side of every line the judge draws. */
    private val probes: List<PreflightMeasurement> = listOf(
        measured(),
        measured(predictMs = 500.0),
        measured(predictMs = 501.0),
        measured(available = 256L * 1024 * 1024),
        measured(available = 256L * 1024 * 1024 - 1),
        measured(doesNot = pair(20f, 0.34f)),
        measured(doesNot = pair(20f, 0.36f)),
        measured(vision = pair(5.1f)),
        measured(vision = pair(4.9f)),
        measured(vision = pair(20.1f)),
        measured(vision = floatArrayOf(Float.NaN, 1f)),
        measured(failure = "did not load"),
        measured(predictMs = 501.0, thermal = 1),
        measured(predictMs = 501.0, thermal = 2),
        measured(predictMs = 501.0, warned = true),
        measured(failure = "did not load", thermal = 3),
        measured(thermal = 3, warned = true),
    )

    private fun code(finding: PreflightFinding): Int = when (finding) {
        is PreflightFinding.Passed -> 1
        is PreflightFinding.Slow -> 2
        is PreflightFinding.Failed -> 3
        is PreflightFinding.Transient -> 4
    }

    /** FNV-1a over everything observed, in order. */
    private class Digest {
        private var hash: Long = -3750763034362895579L // 0xcbf29ce484222325

        fun add(value: Int) {
            for (shift in 0 until 32 step 8) {
                hash = (hash xor ((value ushr shift) and 0xFF).toLong()) * 1099511628211L
            }
        }

        fun hex(): String = hash.toULong().toString(16).padStart(16, '0')
    }

    private fun observed(): String {
        val digest = Digest()
        listOf(8, 64).forEach { length ->
            val tokenizer = SiglipTextTokenizer(vocabulary, length)
            queries.forEach { query ->
                val ids: IntArray = tokenizer.encode(query)
                digest.add(ids.size)
                ids.forEach(digest::add)
            }
        }
        vectors.forEach { (vector, width) ->
            digest.add(if (TowerGuards.unusableReason(vector, width) == null) 1 else 0)
        }
        probes.forEach { digest.add(code(PreflightJudge.judge(it, probe, width = 2))) }
        digest.add(PreflightBudget.PREDICTIONS_PER_TOWER)
        digest.add(PreflightPolicy.SLOW_PROBES_FOR_UNSUITABLE)
        digest.add(PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL)
        (0..8).forEach { misses ->
            digest.add((PreflightPolicy.delayMs(PreflightAttempts(slow = misses, failed = 0, transient = 0, lastAtEpochMs = 1)) / 1000).toInt())
        }
        return digest.hex()
    }

    /** Everything of one record a pass on its export vouches for. */
    private fun observed(record: PreflightProbe): String {
        val digest = Digest()
        listOf(
            record.visionArchiveSha256, record.textArchiveSha256, record.vocabularySha256,
            record.imageRgbSha256, record.imageTensorSha256, record.captionDescribes, record.captionDoesNot,
        ).forEach { text ->
            digest.add(text.length)
            text.forEach { digest.add(it.code) }
        }
        record.tokensDescribes.forEach(digest::add)
        record.tokensDoesNot.forEach(digest::add)
        listOf(record.hostMargin, record.minMargin, record.hostVisionNorm, record.hostDescribesNorm, record.hostDoesNotNorm, record.normBand)
            .forEach { digest.add((it * 10_000).toInt()) }
        return digest.hex()
    }

    /**
     * ⚠⚠ **Adding an export's record needs no bump; altering one does.** The verdict's key holds
     * the three artifact digests, so a new export cannot stand on an old verdict, and a bump
     * would void every phone's verdict for the exports it still holds, for nothing. A record
     * that changes, though, changes what a pass on **its** export meant.
     */
    @Test
    fun noRecordThisBuildCarriesHasChangedUnderThisRevision() {
        val carried: Map<String, String> = PreflightProbes.ids.associateWith { observed(PreflightProbes.byId(it)!!) }
        (carried.keys - PINNED_RECORDS.keys).forEach { added ->
            kotlin.test.fail(
                "Record $added is new. Add `\"$added\" to \"${carried[added]}\"` to PINNED_RECORDS. " +
                    "No bump of PREFLIGHT_REVISION: a new export cannot reuse another export's verdict.",
            )
        }
        (PINNED_RECORDS.keys - carried.keys).forEach { removed ->
            kotlin.test.fail(
                "Record $removed is gone. A phone that still holds that export gets no new verdict from this build " +
                    "(docs/how-to/models-pipeline.md §2b): removing a record is the maintainer's decision. If it was taken, " +
                    "remove the line from PINNED_RECORDS; no bump.",
            )
        }
        PINNED_RECORDS.forEach { (id, pinned) ->
            assertEquals(
                pinned,
                carried[id],
                "Record $id changed: its captions, rows, digests, margin or norms. A pass on that export vouched for the " +
                    "old record: bump PREFLIGHT_REVISION (AiReadiness.kt), then re-pin it here.",
            )
        }
    }

    @Test
    fun whatTheTokenizerAndTheGuardsDoHasNotMovedUnderThisRevision() {
        assertEquals(
            PINNED_DIGEST,
            observed(),
            "The tokenizer, the vector guards or the preflight's judge or retry rule now behave " +
                "differently. A passing preflight verdict on a phone vouched for the old behaviour: bump PREFLIGHT_REVISION (AiReadiness.kt), then set " +
                "PINNED_REVISION and PINNED_DIGEST here to the new pair.",
        )
        assertEquals(
            PINNED_REVISION,
            PREFLIGHT_REVISION,
            "PREFLIGHT_REVISION moved: set PINNED_REVISION to it, so the next change is measured against this one.",
        )
    }

    private companion object {
        // ⚠⚠ These two lines change together, and only with a bump of PREFLIGHT_REVISION.
        // The shipped records are not in this digest; they are pinned in PINNED_RECORDS below.
        const val PINNED_REVISION = 3
        const val PINNED_DIGEST = "5535f094448e138e"

        // ⚠⚠ One line per record the build carries. A line is ADDED with a new export, with no
        // bump; a line's digest CHANGES only with a bump.
        val PINNED_RECORDS: Map<String, String> = mapOf(
            "3fd1918106d4" to "c2451a7d84a93af3",
        )
    }
}
