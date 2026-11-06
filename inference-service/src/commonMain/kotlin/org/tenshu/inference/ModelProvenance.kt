package org.tenshu.inference

/**
 * **What produced a derived row** (IN-1; `inference-service.md` IN-D5's `producedBy`).
 *
 * ⚠⚠ **Every derived row carries this, and it is the difference between a re-index and a wipe.**
 * Labels, vectors and transcripts are opinions of a particular model at a particular version over
 * a particular vocabulary. Without a stamp, "we improved the tagger" means throwing away every row
 * — including the ones the *user* corrected — because nothing can tell which came from where.
 *
 * ⭐ It is also what makes IN-D9's "the taxonomy is data, not weights" real: adding a label next
 * year re-runs only the rows whose [labelSetVersion] is behind, and touches no others.
 *
 * ⚠ `producedBy` is deliberately **not** "which device did it". Two devices running the same model
 * over the same bytes must produce interchangeable rows (IN-D2's reason for one model artifact
 * everywhere); recording the device would invite treating them as different, which is exactly the
 * mistake that makes a synced index unmergeable.
 */
data class ModelProvenance(
    /** A stable model identity, never a filename — `siglip-base-patch16`, not `model_v3_final.onnx`. */
    val modelId: String,
    /** The model's own version. Bumped when the weights change at all. */
    val modelVersion: Int,
    /**
     * The vocabulary's version, for capabilities that have one.
     *
     * ⚠ Separate from [modelVersion] on purpose: a taxonomy revision costs a re-label, a weights
     * revision costs a re-embed, and conflating them means every taxonomy tweak re-runs the
     * expensive half.
     */
    val labelSetVersion: Int = 0,
) {

    /**
     * The stored form — one string, because a derived row wants one column for this and not three.
     *
     * ⚠ Parsed leniently ([parse] returns `null` rather than throwing): the string is read back
     * from rows written by *older* versions of us, and an unreadable stamp means "re-derive", not
     * "crash the indexer".
     */
    fun asString(): String = "$modelId@$modelVersion/$labelSetVersion"

    /** Whether [this] stamps a row the user authored rather than a model (see [User]). */
    val isUserAuthored: Boolean get() = modelId == USER_MODEL_ID

    /**
     * Whether a row stamped [this] is stale against [current].
     *
     * ⚠⚠ **A user-authored row is never stale, and that exception is the whole reason [User]
     * exists.** Without it the plain comparison below answers *true* for every model — a different
     * `modelId` is stale by definition — so the first sweep that re-derives stale rows would delete
     * the names a user typed, in favour of a model's guess. That is the precise failure this
     * class's own note says `producedBy` prevents: *"including the ones the user corrected"*.
     * ⚠ Today nothing sweeps non-marker rows, so this guard is load-bearing for a sweep that does
     * not exist yet. That is the point — it costs nothing now and is invisible to write later.
     */
    fun isStaleAgainst(current: ModelProvenance): Boolean =
        if (isUserAuthored) {
            false
        } else {
            modelId != current.modelId ||
                modelVersion < current.modelVersion ||
                labelSetVersion < current.labelSetVersion
        }

    companion object {
        /**
         * ⚠ What the [FakeInferenceBackend][org.tenshu.inference.proxy] stamps, and the reason it
         * is here rather than in the fake: a row produced by the fake must be **recognisably** not
         * a real one, in production data, forever. A test fixture that stamps like the real thing
         * is a fixture that can contaminate a store.
         */
        val Fake = ModelProvenance(modelId = "fake", modelVersion = 0)

        /** ⚠ Wire-stable: it is written into stored rows, so House Rule 9 governs changing it. */
        const val USER_MODEL_ID = "user"

        /**
         * **A person said so** — the stamp on a row the user authored, not a model (IN-6 F3b).
         *
         * ⚠⚠ The stamp IS the durable distinction IN-D5 is built on. A face group is scaffolding
         * a device rebuilt this second; the name attached to a photograph is the product, and it
         * outranks every model opinion by construction — see [isStaleAgainst], which refuses to
         * call this stale against anything.
         *
         * ⚠ [labelSetVersion] and [modelVersion] stay 0 and mean nothing here. A person has no
         * vocabulary revision, and comparing versions between a human and a model is the category
         * error this whole type exists to keep out of the store.
         */
        val User = ModelProvenance(modelId = USER_MODEL_ID, modelVersion = 0)

        fun parse(stored: String?): ModelProvenance? {
            val text = stored?.takeIf { it.isNotBlank() } ?: return null
            val at = text.lastIndexOf('@').takeIf { it > 0 } ?: return null
            val slash = text.indexOf('/', at).takeIf { it > at } ?: return null
            val modelVersion = text.substring(at + 1, slash).toIntOrNull() ?: return null
            val labelSetVersion = text.substring(slash + 1).toIntOrNull() ?: return null
            return ModelProvenance(
                modelId = text.substring(0, at),
                modelVersion = modelVersion,
                labelSetVersion = labelSetVersion,
            )
        }
    }
}
