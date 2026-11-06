package org.tenshu.photo.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory

fun createPhotoTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(photoServiceCommonModule)
    // ⚠ The *client* module registers the service RO factory; without it
    // `getService(PhotoService.ID)` dies on a missing request-object factory rather than on
    // anything naming the omission.
    registerConfig(photoServiceClientModule)
}

/** Single-client fixture for the photo-service ops. */
fun createPhotoProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    nowEpochMs: () -> Long = { 0L },
) = TestProxy(
    schemaRegistry = createPhotoTestRegistry(),
    coroutineScope = coroutineScope,
    implementationFactoryOverride = { id, _ ->
        photoServiceProxyImplementationFactory(id = id, nowEpochMs = nowEpochMs)
    },
).also { it.executionContext.initPhotoService() }

/** The `TenshuEnvironment` a test drives the SDK chain through. */
fun environmentFor(proxy: TestProxy): TenshuEnvironment {
    val executor = proxy.localTaskExecutor
    return TenshuEnvironment(
        taskExecutor = executor,
        schemaRegistry = proxy.schemaRegistry,
        requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
        tContext = proxy.tContext,
    )
}

fun TestProxy.asTenshuClient(): TenshuClient = DefaultTenshuClient(environmentFor(this))

/**
 * Reads a row straight out of the store by key.
 *
 * ⚠ Deliberately bypasses the chain DSL — the `CalendarTestFixtures` precedent: these assertions
 * are about **what the op wrote**, and going through a query would let a read-path bug mask a
 * write-path one, or the reverse.
 */
fun TestProxy.albumRow(albumId: String): Album? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = Album.ID,
            keyValue = albumId,
        )
    ) as? Album

fun TestProxy.albumEntryRow(albumId: String, contentId: String): AlbumEntry? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = AlbumEntry.ID,
            keyValue = AlbumEntry.keyOf(albumId, contentId),
        )
    ) as? AlbumEntry

fun TestProxy.photoFlagsRow(contentId: String): PhotoFlags? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = PhotoFlags.ID,
            keyValue = contentId,
        )
    ) as? PhotoFlags
