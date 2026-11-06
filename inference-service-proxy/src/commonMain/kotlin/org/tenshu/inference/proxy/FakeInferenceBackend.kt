package org.tenshu.inference.proxy

import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import kotlin.math.sqrt

/**
 * A backend with **no model** (IN-1, IN-D1) — canned, deterministic answers so the entire consumer
 * stack (Drive's indexer, Mia's retrieval, Home's camera events) can be built and tested with zero
 * models and zero hardware.
 *
 * ⭐ This is the `SimulatedDeviceBridge` / `FakeMqttClient` pattern that made HD-1 and HD-2
 * provable before the Pi existed, and it is why IN-1 can ship before a single decision about
 * weights is made.
 *
 * ⚠⚠ **Deterministic from the bytes, not random.** An embedding derived from the content means a
 * consumer's test can assert that the same photo embeds the same way and a *different* photo does
 * not — which is the only property of an embedding a consumer actually depends on. A random vector
 * would make every nearest-neighbour test meaningless while looking like it worked.
 *
 * ⚠⚠ Everything it produces is stamped [ModelProvenance.Fake], so a row it wrote is recognisably
 * not a real one **in production data, forever**. A fixture that stamps like the real thing is a
 * fixture that can contaminate a store.
 */
class FakeInferenceBackend(
    private val capabilities: Set<InferenceCapability> = InferenceCapability.entries.toSet(),
    /** Tags returned for any image, so a consumer test can pin what it expects to see. */
    private val tags: List<ImageTag> = listOf(ImageTag("cat", 0.9f)),
    /** Text returned for any image. */
    private val text: List<TextRegion> = listOf(TextRegion("fake text")),
    /** The dimensionality of the canned vectors. Small — nothing here is measuring quality. */
    private val dimensions: Int = 8,
) : InferenceBackend {

    override val id: String get() = "fake"

    /**
     * ⚠ A deliberately **linear-ish** calibration rather than SigLIP's: a fixture that reproduced
     * the real constants would make every test assert against numbers measured from one checkpoint,
     * and would drift the day that checkpoint changes. `scale = 10, bias = 0` puts p = 0.5 at
     * cos = 0 and moves visibly across the cosine range, which is what a threshold test needs.
     * ⚠⚠ `null` when the search capability is absent — a backend that cannot embed cannot calibrate.
     */
    override fun scoreCalibration(): ScoreCalibration? =
        if (InferenceCapability.SEMANTIC_IMAGE_SEARCH in capabilities) {
            ScoreCalibration(scale = 10f, bias = 0f)
        } else {
            null
        }

    override fun supports(capability: InferenceCapability): Boolean = capability in capabilities

    override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? {
        if (InferenceCapability.SEMANTIC_IMAGE_SEARCH !in capabilities) return null
        // A cheap content hash spread over the dimensions, then normalised: same bytes → same
        // vector, different bytes → a different direction, and every vector is unit length so
        // cosine similarity behaves the way a consumer expects.
        val raw = FloatArray(dimensions) { index ->
            var acc = 17 + index
            bytes.forEach { byte -> acc = acc * 31 + (byte.toInt() and 0xFF) }
            ((acc % 1_000) / 1_000f) + 0.001f
        }
        val length = sqrt(raw.fold(0f) { sum, v -> sum + v * v })
        return ImageEmbedding(
            vector = FloatArray(dimensions) { raw[it] / length },
            producedBy = ModelProvenance.Fake,
        )
    }

    /**
     * ⚠⚠ **Deliberately the same hash over the same bytes as [embedImage]**, so the fake's text and
     * image vectors live in one space by construction — which is what lets a consumer test prove
     * retrieval rather than merely exercise it: give a photo the bytes `"a sunset".encodeToByteArray()`
     * and searching for `"a sunset"` must return it first.
     *
     * ⚠ That trick is a property of **this fixture**, never a claim about a real model. A real
     * model relates text to images through training, and nothing here does. The fake's job is to
     * make the plumbing provable with no weights; whether the plumbing carries good answers is
     * `models.md` §3.7's question and cannot be asked of a hash.
     */
    override suspend fun embedText(text: String): ImageEmbedding? =
        if (InferenceCapability.SEMANTIC_IMAGE_SEARCH in capabilities) {
            embedImage(text.encodeToByteArray())
        } else {
            null
        }

    override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> {
        if (InferenceCapability.CONTENT_TAGGING !in capabilities) return emptyList()
        // ⚠ Filtered by the caller's vocabulary, because a consumer that passes a vocabulary and
        // gets labels outside it would pass its tests and fail against a real model.
        return if (vocabulary.isEmpty()) tags else tags.filter { it.label in vocabulary }
    }

    override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> =
        if (InferenceCapability.TEXT_IN_IMAGES in capabilities) text else emptyList()

    /**
     * Word overlap between the utterance and each label's description — the cheapest thing that is
     * *deterministic and content-derived*, in the same spirit as the canned embeddings above.
     *
     * ⚠⚠ **This is not a small language model; it is a bag-of-words tie-break, and the difference
     * matters for what a test may claim.** It proves the plumbing — consent gate, candidate set,
     * the returned id reaching Mia's planner, the fallback when it says nothing — and it proves
     * nothing whatever about whether a real model would choose well. MQ-5's quality question
     * cannot be asked of a word count.
     *
     * ⚠ Returns `null` on a tie or on no overlap at all, because "I cannot say" is the answer that
     * exercises the caller's fallback, and a fake that always answers would hide the path that
     * matters most.
     */
    /**
     * Faces are read **from the image bytes**: `"anna,bob"` is a two-face photograph.
     *
     * ⚠⚠ Deliberately explicit rather than derived from the bytes' hash. A face fixture whose
     * identities a test cannot name can only assert "some grouping happened", which is exactly the
     * assertion that still passes when the clustering is wrong. ⚠ Empty bytes are a photograph with
     * **no** faces — the majority case in a real library, and the one that must be recordable.
     */
    override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome {
        if (InferenceCapability.FACE_CLUSTERING !in capabilities) return FaceDetectionOutcome.Failed
        if (bytes.decodeToString() == UNREADABLE) return FaceDetectionOutcome.Unreadable
        return FaceDetectionOutcome.Detected(
            faceTokens(bytes).mapIndexed { index, _ ->
                // ⚠ The index rides in `left` so [embedFace] can tell which face it was handed —
                // this fixture's stand-in for a crop.
                val left = index / 10f
                DetectedFace(
                    box = FaceBox(left = left, top = 0f, right = left + 0.1f, bottom = 0.1f),
                    // ⚠ Plausible geometry rather than zeros: a fixture whose landmarks are all the
                    // same point cannot catch an alignment that ignores them.
                    landmarks = FaceLandmarks(
                        leftEye = FacePoint(left + 0.02f, 0.03f),
                        rightEye = FacePoint(left + 0.08f, 0.03f),
                        noseTip = FacePoint(left + 0.05f, 0.05f),
                        mouthLeft = FacePoint(left + 0.03f, 0.08f),
                        mouthRight = FacePoint(left + 0.07f, 0.08f),
                    ),
                )
            },
        )
    }

    /**
     * One **orthogonal** unit vector per distinct name, so "same person" is cosine 1 and "two
     * people" is cosine 0.
     *
     * ⚠ That makes this fixture useless for judging a *threshold* — which is correct: a threshold
     * is calibrated against real faces (`photos-people-and-pets.md` §4), and a test that tuned one
     * against canned orthogonal vectors would be measuring the fixture rather than the model.
     */
    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? {
        if (InferenceCapability.FACE_CLUSTERING !in capabilities) return null
        val tokens = faceTokens(bytes)
        val token = tokens.getOrNull((face.box.left * 10f).toInt()) ?: return null
        val axis = ((token.fold(7) { acc, c -> acc * 31 + c.code } % dimensions) + dimensions) % dimensions
        return ImageEmbedding(
            vector = FloatArray(dimensions) { if (it == axis) 1f else 0f },
            producedBy = ModelProvenance.Fake,
            // ⚠ A token starting with `~` is a detection that is not a human face — `"anna,~cat"`.
            notAFace = token.startsWith(NOT_A_FACE_PREFIX),
        )
    }

    private fun faceTokens(bytes: ByteArray): List<String> =
        bytes.decodeToString().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    override suspend fun classifyIntent(utterance: String, labels: List<IntentLabel>): String? {
        if (InferenceCapability.QUERY_PLANNING !in capabilities) return null
        val words = utterance.lowercase().split(' ', '\t', '\n').filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return null

        val scored = labels.map { label ->
            label to label.description.lowercase()
                .split(' ', '\t', '\n')
                .count { it.length > 2 && it in words }
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second == 0) return null
        if (scored.count { it.second == best.second } > 1) return null
        return best.first.id
    }

    private companion object {
        /** Bytes this fixture cannot decode — the [FaceDetectionOutcome.Unreadable] case. */
        const val UNREADABLE = "not-an-image"
    }
}

/** Prefix of a face token that [FakeInferenceBackend] embeds as not a human face — `"anna,~cat"`. */
const val NOT_A_FACE_PREFIX = "~"
