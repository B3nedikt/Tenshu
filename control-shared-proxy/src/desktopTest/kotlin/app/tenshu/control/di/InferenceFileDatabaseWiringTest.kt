package app.tenshu.control.di

import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.InferenceStoreGate
import org.tenshu.inference.proxy.StoredFace
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **Where this process's inference stores live** (`ios-on-device-inference.md` §3.1, PR-3): the
 * shared `FileDatabase` unless a graph says otherwise, and wherever that graph says when it does.
 *
 * ⚠⚠ Both halves fail silently. A default that stopped being the shared database would move every
 * Android and desktop device's vectors to a file nothing reads; a store that ignored the override
 * would leave the iOS owner writing the App Group.
 *
 * ⭐ The real `proxyModule`. The store providers are leaves once a `FileDatabase` is given.
 */
class InferenceFileDatabaseWiringTest {

    private val model = ModelProvenance("siglip", 1)
    private val face = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))

    @Test
    fun `by default the stores live in the shared database and no gate stands before them`() = runBlocking<Unit> {
        val shared = InMemoryFileDatabase()
        val koin = koinApplication { modules(buildProxyModule(), module { single<FileDatabase> { shared } }) }.koin
        try {
            assertSame<FileDatabase>(shared, koin.get(named(INFERENCE_FILE_DATABASE)))
            assertSame(InferenceStoreGate.Unstamped, koin.get<InferenceStoreGate>())

            assertTrue(koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), model))
            assertTrue(koin.get<FaceTemplateStore>().put("/a.jpg", listOf(face), model))

            assertNotNull(shared.fileSize(INFERENCE_EMBEDDINGS_PATH))
            assertNotNull(shared.fileSize(INFERENCE_FACES_PATH))
        } finally {
            koin.close()
        }
    }

    @Test
    fun `a graph that binds its own inference database takes both stores with it`() = runBlocking<Unit> {
        val shared = InMemoryFileDatabase()
        val private = InMemoryFileDatabase()
        val koin = koinApplication {
            modules(
                buildProxyModule(),
                module {
                    single<FileDatabase> { shared }
                    single<FileDatabase>(named(INFERENCE_FILE_DATABASE)) { private }
                },
            )
        }.koin
        try {
            assertTrue(koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), model))
            assertTrue(koin.get<FaceTemplateStore>().put("/a.jpg", listOf(face), model))

            assertNotNull(private.fileSize(INFERENCE_EMBEDDINGS_PATH))
            assertNotNull(private.fileSize(INFERENCE_FACES_PATH))
            assertNull(shared.fileSize(INFERENCE_EMBEDDINGS_PATH), "the vectors were written to the shared database")
            assertNull(shared.fileSize(INFERENCE_FACES_PATH), "the templates were written to the shared database")
        } finally {
            koin.close()
        }
    }
}
