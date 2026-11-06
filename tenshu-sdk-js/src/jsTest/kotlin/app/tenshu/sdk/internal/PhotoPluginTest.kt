@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.promise
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.RelationMaintainer
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.SetPhotoFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds `PhotoPlugin` (Photos run A) to its KDoc: the photo ops and the relation maintainer, nothing
 * that runs on its own. ⚠ A tab is a poor executor (`web-app-suite.md` §2): no indexer, no backfill,
 * no inference.
 */
class PhotoPluginTest {

    private val photoOps = listOf(
        SaveAlbum.ID, DeleteAlbum.ID, AddToAlbum.ID, RemoveFromAlbum.ID,
        SetPhotoFlags.ID, RecordTrashedPhoto.ID, ClearTrashedPhoto.ID,
    )

    @Test
    fun photoIsRegisteredOnce_asThePluginNotAsASyncOnlyEntry() {
        assertEquals(1, JsRuntimeBuilder.orderedPlugins.count { it.id == "photo" })
        assertTrue(JsRuntimeBuilder.orderedPlugins.single { it.id == "photo" } === PhotoPlugin)
        assertFalse(SyncedServicePlugins.any { it.id == "photo" }, "the F7 sync-only entry must be gone")
    }

    @Test
    fun itSitsBeforeSync_soAPhotoDispatchNeverForcesTheRelay() {
        val ids = JsRuntimeBuilder.orderedPlugins.map { it.id }
        assertTrue(ids.indexOf("photo") < ids.indexOf("sync"), "got $ids")
    }

    @Test
    fun itsFactoryAnswersExactlyThePhotoOps() {
        val factory = assertNotNull(PhotoPlugin.operationFactory(JsRuntime))
        for (id in photoOps) assertNotNull(factory(id), "$id must dispatch on web")
        // A file op, and the expensive ones Photos deliberately does not reach on web.
        assertNull(factory("tenshu/file/system/uploadFiles"))
        assertNull(factory(org.tenshu.inference.StartMediaIndexPass.ID))
    }

    @Test
    fun itContributesNoMergers_asNativeRegistersNone() {
        assertTrue(PhotoPlugin.mergers.isEmpty())
    }

    @Test
    fun initializeInstallsOnlyTheRelationMaintainer_andLaunchesNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        // A context of its own, so the registry read is the plugin's doing alone — the live one
        // holds every plugin's observers.
        val live = JsRuntime.executionContext
        val session = Job()
        val fresh = ExecutionContext(
            context = live.context,
            database = live.database,
            traitResolver = live.traitResolver,
            implementationFactory = live.implementationFactory,
            clientConnection = live.clientConnection,
            pureOperationRunner = live.pureOperationRunner,
            materializer = live.materializer,
            sessionScope = CoroutineScope(session),
        )
        try {
            PhotoPlugin.initialize(fresh, JsRuntime)
            val observers = buildList { fresh.observerRegistry.any { add(it); false } }
            assertEquals(1, observers.size, "exactly one observer; got ${observers.map { it::class.simpleName }}")
            assertTrue(observers.single() is RelationMaintainer, "and it is the relation maintainer")
            assertTrue(session.children.none(), "no job launched on the session scope")

            PhotoPlugin.initialize(fresh, JsRuntime)
            assertEquals(1, buildList { fresh.observerRegistry.any { add(it); false } }.size, "idempotent")
        } finally {
            session.cancel()
        }
    }
}
