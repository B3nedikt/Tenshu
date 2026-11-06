package org.tenshu.inference.proxy

import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance

/** A unit vector in the model's embedding space, plus what produced it. */
data class ImageEmbedding(
    val vector: FloatArray,
    val producedBy: ModelProvenance,
    /**
     * A face embedder's verdict that this crop is **not a human face** — a cat, a
     * doll, a drawing, food. `false` for every image embedding and for every face that passed.
     *
     * ⚠⚠ A flag on a real embedding, never a `null`: `embedFace` returning `null` means "the model
     * could not run", and the indexer latches the whole face half off on it. A non-face is a fact
     * about one face, recorded by leaving it out.
     */
    val notAFace: Boolean = false,
) {
    // ⚠ FloatArray is identity-compared by default, which would make two equal embeddings unequal
    // and quietly break every `assertEquals` a consumer's test writes.
    override fun equals(other: Any?): Boolean =
        other is ImageEmbedding && producedBy == other.producedBy && notAFace == other.notAFace &&
            vector.contentEquals(other.vector)

    // ⚠ `notAFace` in both, or a changed verdict would compare equal.
    override fun hashCode(): Int = 31 * (31 * vector.contentHashCode() + producedBy.hashCode()) + notAFace.hashCode()
}

/**
 * What one attempt to embed an image came to — [InferenceBackend.embedImageOutcome].
 *
 * ⚠⚠ **Two kinds of "no vector", and only one of them is a fact about the file.** An indexer may
 * remember [Unreadable] against a photo and never read it again; it must never remember [Failed],
 * because a session that could not open or a run that threw says nothing about the photograph — and
 * remembering it would keep every photo that met a bad moment out of the index for good.
 */
sealed interface ImageEmbeddingOutcome {
    data class Embedded(val embedding: ImageEmbedding) : ImageEmbeddingOutcome

    /** These bytes are not an image this device can decode. Stable for this file on this device. */
    data object Unreadable : ImageEmbeddingOutcome

    /** The model could not run — missing, rejected, or failed this time. Says nothing about the file. */
    data object Failed : ImageEmbeddingOutcome
}

/**
 * How a model's raw cosine becomes **the model's own probability**: `sigmoid(scale · cos + bias)`.
 *
 * ⚠⚠ **Without this, a cosine is not a score and a threshold on it is not a threshold.** SigLIP is
 * trained with a sigmoid loss, so its two constants live in the full graph rather than in the towers
 * we load — `models.md` §3.7.2 recovered them by reading it. The scales are not comparable either:
 * over one real library, image↔text cosines run **−0.13 … 0.123** while text↔text cosines over the
 * same vocabulary run **0.57 … 0.98**. A number that looks cautious on one scale admits everything
 * on the other.
 *
 * ⚠ Per **model**, never per capability or per app: two checkpoints have two calibrations, and a
 * constant carried by the consumer would silently describe whichever model it was written against.
 */
data class ScoreCalibration(val scale: Float, val bias: Float) {

    /** The model's own probability that this pair matches. */
    fun probability(cosine: Float): Float = 1f / (1f + kotlin.math.exp(-(scale * cosine + bias)))
}

/** One label the model believes applies, with the confidence it believes it at. */
data class ImageTag(val label: String, val confidence: Float)

/** Text found in an image, and roughly where. Coordinates are fractions of the image's size. */
data class TextRegion(
    val text: String,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
)

/**
 * **Where inference actually happens** (`inference-service.md` IN-D1).
 *
 * ⚠⚠ **Task-shaped, never model-shaped.** A call site that says "run `wd-swinv2-v3.onnx`" cannot
 * be moved to the hub, cannot degrade when the capability is off, and cannot be re-pointed at a
 * better model next year. Everything above this interface asks for *an embedding*; what satisfies
 * that — ONNX Runtime on the phone, Vision on iOS, a co-computer across the LAN — is a binding.
 *
 * ⭐ That is what makes the hub proposal's "with no hub on the LAN, light inference lands on a
 * paired phone/desktop" true **by construction** rather than a rewrite when the hub arrives.
 *
 * ⚠ [supports] is not decoration: a backend that can embed but cannot read text is the normal
 * case (Vision does OCR with no weights of ours; our own model does embeddings). The registry asks
 * per capability, and "no backend for this" is an ordinary answer rather than an error.
 *
 * ⚠ Suspending, and expected to be slow: an embedding pass over a photo library is minutes to
 * hours of work (IN-D6 makes it a background, metered, single task).
 */
interface InferenceBackend {

    /** A stable name for logs and diagnostics — `onnx-runtime`, `vision`, `fake`. */
    val id: String

    fun supports(capability: InferenceCapability): Boolean

    /**
     * @param bytes the image's own bytes. ⚠ Passed rather than a content reference because the
     *   proxy is where content already lives; a backend that had to resolve references would need
     *   the store, and a remote backend would need the *user's* store.
     */
    suspend fun embedImage(bytes: ByteArray): ImageEmbedding?

    /**
     * [embedImage], saying *why* when there is no vector (see [ImageEmbeddingOutcome]).
     *
     * ⚠⚠ The default cannot tell, so it answers [ImageEmbeddingOutcome.Failed] for every `null` —
     * the safe side: a backend that never reports [ImageEmbeddingOutcome.Unreadable] costs a re-read
     * of files it cannot decode, where one that guessed would lose photos from the index for good.
     */
    suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome =
        embedImage(bytes)?.let { ImageEmbeddingOutcome.Embedded(it) } ?: ImageEmbeddingOutcome.Failed

    /**
     * The **query** half of semantic search: an utterance embedded into the *same* space as
     * [embedImage], so a dot product between them means something.
     *
     * ⚠⚠ **Same space is a property of the model, not of this interface.** A backend that embeds
     * images with one model and text with another satisfies these signatures perfectly and returns
     * ranked nonsense — the arithmetic succeeds either way. It is why `ImageEmbedding` carries
     * `producedBy` and why `EmbeddingStore` refuses a foreign stamp: the check has to live where
     * the vectors meet, because it cannot live in a type.
     *
     * ⚠ Nullable for the ordinary case, not the exceptional one: a backend that can tag and read
     * text but has no joint image–text space — Apple's Vision feature print is exactly this — has
     * no answer here, and "no answer" is not an error. It is also why `SEMANTIC_IMAGE_SEARCH` is
     * its own capability rather than a facet of tagging.
     */
    suspend fun embedText(text: String): ImageEmbedding?

    suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag>

    suspend fun recognizeText(bytes: ByteArray): List<TextRegion>

    /**
     * Pick **which of [labels]** an utterance is asking for, or `null` for "I cannot say" (MQ-5).
     *
     * ⚠⚠ **A closed choice, never free text, and that is the security property rather than a
     * convenience.** `mia-query-layer.md` §5 requires "constrained decoding into the callstack
     * composite shape, never free-text parsed into one" — this signature is the cheapest possible
     * form of that: the return value is either a member of a set the *caller* supplied or nothing,
     * so a model cannot express an intent Mia does not already implement. A backend that returns a
     * string outside [labels] is treated exactly like `null`.
     *
     * ⚠ Null is the ordinary case, not the exceptional one — the caller has a deterministic
     * planner to fall back to, so "no answer" costs the user nothing and must not read as an error.
     *
     * @param labels the candidate intents, each already carrying a human description; the caller
     *   decides what may be chosen at all, which is how write intents stay out of reach (§16.2).
     */
    suspend fun classifyIntent(utterance: String, labels: List<IntentLabel>): String? = null

    /**
     * How to turn this backend's cosines into probabilities, or `null` when it cannot say.
     *
     * ⚠⚠ `null` is not "use a sensible default" — there is no sensible default, which is the whole
     * point of [ScoreCalibration]. A consumer that cannot calibrate must not invent a raw-cosine
     * floor to compensate (`inference-service.md` §19.3); it ranks and says so, exactly as it does
     * today.
     */
    fun scoreCalibration(): ScoreCalibration? = null

    /**
     * Find the faces in an image (`photos-people-and-pets.md` §3).
     *
     * ⚠⚠ **Detection and identity are deliberately two calls, because on Apple they are two
     * providers.** Vision detects faces on the Neural Engine for zero model bytes and exposes no
     * public faceprint; the identity vector is always *ours* ([embedFace]) so that vectors are
     * comparable across platforms. IN-D13's table says the same thing: detection from the platform
     * where it is free, clustering ours everywhere.
     *
     * ⚠ Defaulted to [FaceDetectionOutcome.Failed] rather than "no faces": a backend that has not
     * implemented this has not looked, and saying "none" would let a pass record that fact about
     * every photograph in the library.
     */
    suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome = FaceDetectionOutcome.Failed

    /**
     * Embed one face from [bytes], located by [face], into this backend's face-identity space.
     *
     * ⚠⚠ **The whole image plus a detection, never a pre-cropped bitmap.** The backend owns the
     * decode — a caller that cropped would have to own an image codec on five platforms.
     *
     * ⚠⚠ **But it does NOT get to choose the framing.** The crop is defined by
     * [DetectedFace.landmarks] under the shared 5-point similarity transform, not by
     * [DetectedFace.box]: Vision's boxes and our own detector's boxes frame differently, so a
     * backend that cropped to its own taste would land the same person's face in a different region
     * of the same model's space on each platform. See [FaceLandmarks] for why that is the whole
     * comparability argument.
     *
     * ⚠ Nullable, and unlike [embedImage] that is *not* ambiguous here: whether these bytes decode
     * at all was already settled by [detectFaces], which is the only way to obtain a [box]. So a
     * `null` means the model could not run, never "not an image", and a caller must not remember it
     * against the file.
     *
     * ⚠⚠ The vectors this returns live in a **different space** from [embedImage]'s — a face
     * identity space, not an image–text one. They are stamped and stored separately for that reason;
     * `FaceTemplateStore` refuses a foreign stamp exactly as `EmbeddingStore` does.
     */
    suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? = null

    /**
     * [detectFaces] for bytes that carry no EXIF of their own — a preview — with the
     * original's [orientation] (1–8) to apply instead. `null` means "read it from the bytes".
     *
     * ⚠ Defaulted to ignore it, so a backend that never sees previews owes nothing; one that decodes
     * orientation itself (`MediaPipeFaceBackend`) must override both of these, or preview faces are
     * detected sideways.
     */
    suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome = detectFaces(bytes)

    /** [embedFace] with an explicit [orientation] — see the [detectFaces] overload. */
    suspend fun embedFace(bytes: ByteArray, face: DetectedFace, orientation: Int?): ImageEmbedding? =
        embedFace(bytes, face)
}

/**
 * One candidate for [InferenceBackend.classifyIntent]: the stable [id] a caller matches on, and the
 * [description] a model is shown.
 *
 * ⚠ Two fields rather than one because they answer to different readers. `id` is ours and must
 * never change silently — it is what the returned string is compared against. `description` is
 * prose aimed at a model (and, one day, at a localiser), so it may be rewritten freely.
 */
data class IntentLabel(val id: String, val description: String)
