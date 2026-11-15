package app.tenshu.control.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CombinedModelSource] (`ios-face-grouping.md` F6): one Models list over SigLIP's source and the
 * face model's, the list no better than its worst part, and every call to the model's own source.
 */
class CombinedModelSourceTest {

    private class Fake(val id: String, first: ModelsAvailability) : OnDeviceModelSource {
        val list = MutableStateFlow(first)
        val calls = mutableListOf<String>()
        override val models: Flow<ModelsAvailability> = list.transformWhile { emit(it); it != ModelsAvailability.NotConnected }
        override val serves: Flow<Map<String, List<String>>> = MutableStateFlow(mapOf(id to listOf(id)))
        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = ModelActionResult.Accepted.also { calls += "download($modelId)" }
        override suspend fun cancel(modelId: String): ModelActionResult = ModelActionResult.Accepted.also { calls += "cancel($modelId)" }
        override suspend fun checkAgain() { calls += "checkAgain" }
        override suspend fun loadAgain(modelId: String) { calls += "loadAgain($modelId)" }
        override suspend fun consentWithdrawn(capability: InferenceCapability) { calls += "withdrawn(${capability.id})" }
    }

    private fun row(id: String) = ModelRow(id, id, ModelSetup.Absent(OfferedModel(id, id, 1, 1, null, ""), null))
    private fun listed(vararg ids: String, catalog: ModelsAvailability.Catalog = ModelsAvailability.Catalog.READ, downloads: ModelsAvailability.Downloads = ModelsAvailability.Downloads.KNOWN) =
        ModelsAvailability.Listed(catalog, downloads, ids.map(::row))

    private val search = Fake("siglip", listed("siglip"))
    private val faces = Fake("auraface", listed("auraface"))
    private val combined = CombinedModelSource(linkedMapOf("siglip" to search, "auraface" to faces))

    @Test
    fun theRowsAreEachSourcesInOrder() = runTest {
        assertEquals(listed("siglip", "auraface"), combined.models.first())
    }

    @Test
    fun theListIsNoBetterThanItsWorstPart() {
        val ok = listed("a")
        assertEquals(ModelsAvailability.NotConnected, CombinedModelSource.combined(listOf(ok, ModelsAvailability.NotConnected)))
        assertEquals(ModelsAvailability.InstalledUnreadable, CombinedModelSource.combined(listOf(ModelsAvailability.Loading, ModelsAvailability.InstalledUnreadable)))
        assertEquals(ModelsAvailability.Loading, CombinedModelSource.combined(listOf(ok, ModelsAvailability.Loading)))
        assertEquals(
            listed("a", "b", downloads = ModelsAvailability.Downloads.UNREADABLE),
            CombinedModelSource.combined(listOf(ok, listed("b", downloads = ModelsAvailability.Downloads.UNREADABLE))),
        )
        assertEquals(
            ModelsAvailability.Catalog.UNAVAILABLE,
            (CombinedModelSource.combined(listOf(ok, listed(catalog = ModelsAvailability.Catalog.UNAVAILABLE))) as ModelsAvailability.Listed).catalog,
        )
        // ⚠ The face entry is not published yet: its source lists nothing, and the list is search's alone.
        assertEquals(listed("a"), CombinedModelSource.combined(listOf(ok, listed())))
    }

    @Test
    fun theListEndsNotConnectedWhenEitherSourceDoes() = runTest {
        faces.list.value = ModelsAvailability.NotConnected
        assertEquals(listOf<ModelsAvailability>(ModelsAvailability.NotConnected), combined.models.toList())
    }

    @Test
    fun aCallGoesToTheSourceOfItsModelAndAnUnknownModelIsRefused() = runTest {
        combined.download("auraface", allowsCellular = false)
        combined.cancel("siglip")
        combined.loadAgain("auraface")
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.UnknownModel), combined.download("other", allowsCellular = false))
        assertEquals(listOf("cancel(siglip)"), search.calls)
        assertEquals(listOf("download(auraface)", "loadAgain(auraface)"), faces.calls)
    }

    @Test
    fun aCheckAndAWithdrawalReachEverySource() = runTest {
        combined.checkAgain()
        combined.consentWithdrawn(InferenceCapability.FACE_CLUSTERING)
        assertEquals(listOf("checkAgain", "withdrawn(faceClustering)"), search.calls)
        assertEquals(listOf("checkAgain", "withdrawn(faceClustering)"), faces.calls)
    }

    @Test
    fun whatEachModelServesIsJoined() = runTest {
        assertEquals(mapOf("siglip" to listOf("siglip"), "auraface" to listOf("auraface")), combined.serves.transformWhile { emit(it); false }.toList().single())
    }
}
