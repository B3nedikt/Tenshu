package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.util.Sha256Digest

/**
 * The proxy-side model-delivery components, built once from injected primitives — the shape
 * `mapRegionComponents` already uses, so the DI site stays a list of primitives rather than a
 * construction sequence.
 */
data class ModelDeliveryComponents(
    val catalogSource: ModelCatalogSource,
    val store: ModelStore,
    val downloader: ModelDownloader,
    val progress: ModelDownloadProgressHolder,
    val inventory: ModelInventory,
    /** What wraps a commit or a removal — see [ModelStoreChanges]. */
    val storeChanges: ModelStoreChanges = ModelStoreChanges.Direct,
)

/**
 * Builds the platform's own [ModelDownloader] over the components this module makes.
 *
 * ⚠⚠ Contributed by exactly one graph, the iOS owner's, and **together with its runtime**: there
 * the in-process fetch below is not allowed to carry a model at all (it cannot be held to Wi-Fi
 * and it dies with the process; `ios-on-device-inference.md` D6, §14.11).
 */
fun interface ModelDownloaderFactory {
    fun create(
        catalogSource: ModelCatalogSource,
        store: ModelStore,
        progress: ModelDownloadProgressHolder,
        baseUrl: String,
    ): ModelDownloader
}

/**
 * Wires IN-2b's store and catalog fetch into something reachable (the 1b slice).
 *
 * ⚠⚠ **Reachable is not the same as functional.** With a blank [catalogPublicKeyHex] or
 * [catalogBaseUrl], `catalogSource.load()` returns `null` without making a request and
 * `downloader.download(...)` throws [ModelCatalogUnavailableException] — *nothing downloads, loudly*
 * (`models.md`). A configured URL with nothing published at it lands in the same place, one 404 later.
 *
 * ⚠ Both values are **deployment** facts, not code ones: the key's private half is held offline, and
 * the catalog is published beside its artifacts (`docs/how-to/models-pipeline.md`). They are this
 * function's only inputs that change when that happens.
 */
fun modelDeliveryComponents(
    httpClient: HttpClient,
    storageDelegate: SecureStorageDelegate,
    fileDatabase: FileDatabase,
    ed25519: Ed25519Backend,
    /**
     * A process-lifetime scope the download runs on, so it survives the app backgrounding — the
     * per-request scope would be torn down mid-fetch. Kept alive meanwhile by [backgroundLiveness].
     */
    scope: CoroutineScope,
    backgroundLiveness: BackgroundLiveness,
    /** ⚠ Blank until a catalog is published; blank means "do not even ask". */
    catalogBaseUrl: String = "",
    /** ⚠ Blank until a release key is minted; blank refuses every catalog. */
    catalogPublicKeyHex: String = MODEL_CATALOG_PUBLIC_KEY_HEX,
    /**
     * The artifact formats **this platform** can execute (IN-D13).
     *
     * ⚠⚠ **Empty is the default, and it means "offer nothing".** [SUPPORTED_RUNTIMES] is the
     * `onnxMain` source set's claim, and `onnxMain` compiles into the JVM and Android artifacts
     * only — so a `commonMain` constant read on iOS or web asserts a runtime that binary does not
     * contain. Harmless while nothing could download; the moment IN-2c wired `DownloadModel` it
     * became an offer to spend ~383 MB on a checkpoint the device can never run, which is the exact
     * failure IN-D13's filter exists to prevent. Contributed per platform, exactly like
     * `named("inferenceBackends")`, so **absence is the safe answer** rather than the permissive one.
     */
    supportedRuntimes: Set<String> = emptySet(),
    /**
     * How this platform keeps the store to one writing process. ⚠⚠ No default on purpose: see
     * [ModelStoreOwnership].
     */
    storeOwnership: ModelStoreOwnership,
    modelsDir: String = DEFAULT_MODELS_DIR,
    /**
     * Where the store keeps its index. ⚠ `null` is [storageDelegate], every platform's home for it
     * but one: the iOS owner passes a [FileModelIndexStorage] over [fileDatabase], so the index is
     * lost with the bytes (`ios-on-device-inference.md` §3.1).
     */
    indexStorage: ModelIndexStorage? = null,
    /** ⚠ `null` is the in-process, streamed fetch: every platform's but the iOS owner's. */
    downloaderFactory: ModelDownloaderFactory? = null,
    storeChanges: ModelStoreChanges = ModelStoreChanges.Direct,
): ModelDeliveryComponents {
    val verifier = ModelCatalogVerifier(
        verifier = { publicKey, message, signature -> ed25519.verify(publicKey, message, signature) },
        publicKeyHex = catalogPublicKeyHex,
        supportedRuntimes = supportedRuntimes,
    )
    val catalogSource = HttpModelCatalogSource(httpClient, catalogBaseUrl, verifier)
    val store = DefaultModelStore(
        storageDelegate = indexStorage ?: SecureStorageModelIndex(storageDelegate),
        fileDatabase = fileDatabase,
        modelsDir = modelsDir,
        digestOf = ::sha256Hex,
        ownership = storeOwnership,
    )
    val progress = ModelDownloadProgressHolder()
    val downloader = downloaderFactory?.create(catalogSource, store, progress, catalogBaseUrl)
        ?: DefaultModelDownloader(
            httpClient = httpClient,
            baseUrl = catalogBaseUrl,
            catalogSource = catalogSource,
            store = store,
            scope = scope,
            liveness = backgroundLiveness,
            progress = progress,
        )
    return ModelDeliveryComponents(
        catalogSource = catalogSource,
        store = store,
        downloader = downloader,
        progress = progress,
        inventory = ModelInventory(store),
        storeChanges = storeChanges,
    )
}

/**
 * ⚠ Under the `FileDatabase` root beside `/inference/embeddings.log` and the side-loaded artifacts,
 * so a device's inference state lives in one place and `retention-quota-model.md`'s local-cache
 * tier can find all of it.
 */
const val DEFAULT_MODELS_DIR: String = "inference/models"

@OptIn(ExperimentalStdlibApi::class)
private fun sha256Hex(bytes: ByteArray): String {
    val digest = Sha256Digest()
    digest.update(bytes, bytes.size)
    return digest.digest().toHexString()
}
