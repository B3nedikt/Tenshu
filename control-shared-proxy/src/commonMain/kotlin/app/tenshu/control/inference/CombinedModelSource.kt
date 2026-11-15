package app.tenshu.control.inference

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.tenshu.inference.InferenceCapability

/**
 * One [OnDeviceModelSource] over **one source per model** (`ios-face-grouping.md` F6): Photos on
 * iOS with faces bound has SigLIP's and the face embedder's, each a [JoinedModelSource] over its own
 * readiness, preparer and probe, and one Models screen lists both.
 *
 * ⚠⚠ **It adds nothing about a model.** Each row is its own source's, unchanged; what this decides
 * is only what the *list as a whole* says when the sources disagree, and the rule is the safe one —
 * the list is no better than its worst part:
 * - any source *not connected* → the list is (and it ends): one runtime is behind every source;
 * - any source whose store could not be read → *installed unreadable*: nothing is offered over an
 *   index nobody could read, for either model;
 * - any source *loading* → *loading*;
 * - otherwise the rows, in [byModel]'s order, the journal *unreadable* if either says so, and the
 *   catalog's answer the worst of the two ([worst]).
 *
 * ⚠ A call about a model goes to the source of that model, and a model no source has is refused as
 * *unknown*, never handed to the first source.
 *
 * @param byModel the sources by the model each is about, in the order the rows are shown.
 */
class CombinedModelSource(private val byModel: Map<String, OnDeviceModelSource>) : OnDeviceModelSource {

    init {
        require(byModel.isNotEmpty()) { "a combined source of no source" }
    }

    override val models: Flow<ModelsAvailability> =
        combine(byModel.values.map { it.models }) { all -> combined(all.toList()) }
            .distinctUntilChanged()
            // ⚠ Ends once it has said so: one source ending *not connected* is the runtime gone.
            .transformWhile { value ->
                emit(value)
                value != ModelsAvailability.NotConnected
            }

    override val serves: Flow<Map<String, List<String>>> =
        combine(byModel.values.map { it.serves }) { all -> all.fold(emptyMap<String, List<String>>()) { joined, one -> joined + one } }
            .distinctUntilChanged()

    private fun owner(modelId: String): OnDeviceModelSource? = byModel[modelId]

    override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult =
        owner(modelId)?.download(modelId, allowsCellular) ?: ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)

    override suspend fun cancel(modelId: String): ModelActionResult =
        owner(modelId)?.cancel(modelId) ?: ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)

    /** ⚠ Every source, side by side: each reads its own store, journal and catalog again. */
    override suspend fun checkAgain() = coroutineScope {
        byModel.values.forEach { source -> launch { source.checkAgain() } }
    }

    override suspend fun loadAgain(modelId: String) {
        owner(modelId)?.loadAgain(modelId)
    }

    /** ⚠ Every source is told: each lets go of what it loaded for [capability], and only that. */
    override suspend fun consentWithdrawn(capability: InferenceCapability) {
        byModel.values.forEach { it.consentWithdrawn(capability) }
    }

    companion object {
        /** What the list as a whole says, from each source's answer. See the class. */
        fun combined(all: List<ModelsAvailability>): ModelsAvailability {
            if (all.any { it == ModelsAvailability.NotConnected }) return ModelsAvailability.NotConnected
            // ⚠ A source never says it (`OnDeviceModelSource.models`); were one to, it is said whole.
            all.firstOrNull { it is ModelsAvailability.NotOffered }?.let { return it }
            if (all.any { it == ModelsAvailability.InstalledUnreadable }) return ModelsAvailability.InstalledUnreadable
            if (all.any { it == ModelsAvailability.Loading }) return ModelsAvailability.Loading
            val listed: List<ModelsAvailability.Listed> = all.filterIsInstance<ModelsAvailability.Listed>()
            return ModelsAvailability.Listed(
                catalog = worst(listed.map { it.catalog }),
                downloads = if (listed.any { it.downloads == ModelsAvailability.Downloads.UNREADABLE }) {
                    ModelsAvailability.Downloads.UNREADABLE
                } else {
                    ModelsAvailability.Downloads.KNOWN
                },
                models = listed.flatMap { it.models },
            )
        }

        /**
         * The catalog's answer for the list: *unavailable* over *stale* over *not read yet* over
         * *read*. ⚠ Each source reads the same catalog, so they differ only for a moment or on a
         * failure; the list never claims a read that one of them did not have.
         */
        fun worst(catalogs: List<ModelsAvailability.Catalog>): ModelsAvailability.Catalog = when {
            ModelsAvailability.Catalog.UNAVAILABLE in catalogs -> ModelsAvailability.Catalog.UNAVAILABLE
            ModelsAvailability.Catalog.STALE in catalogs -> ModelsAvailability.Catalog.STALE
            ModelsAvailability.Catalog.NOT_READ_YET in catalogs -> ModelsAvailability.Catalog.NOT_READ_YET
            else -> ModelsAvailability.Catalog.READ
        }
    }
}
