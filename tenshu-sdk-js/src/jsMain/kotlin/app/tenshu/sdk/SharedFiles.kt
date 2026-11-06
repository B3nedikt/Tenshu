@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.listPlacements
import org.tenshu.core.file.system.listSharedFolder
import org.tenshu.core.file.system.mountSharedRealm
import org.tenshu.core.file.system.readSharedFile
import org.tenshu.core.file.system.shareFolderIntoRealm
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getSharedRealmMemberships
import org.tenshu.sync.client.syncService
import org.tenshu.sync.createSharedRealm
import kotlin.js.Promise

/**
 * Web rendering of the Mode-B file *read* path (`drive-web-mode-b-sharing.md`
 * MB-3 / `drive-shared-realm-file-model.md`). The thin `@JsExport` *operation*
 * surface (House Rule #5): dedicated read ops — `getSharedRealmMemberships`
 * (identity), `listSharedFolder` / `readSharedFile` (file) — dispatched through
 * the in-process [JsRuntime] proxy, the same op code natives run.
 *
 * Inbound is **membership-driven, mount optional** (doc §7): [sharedWithMe]
 * lists the realms a member belongs to, [browseSharedFolder] walks a realm's
 * tree, and [readSharedFile] fetches a file's bytes **lazily on open** through
 * the realm runtime's `OnDemandResolution` (wired in [JsRuntime]; chunks AEAD'd
 * under the realm keys, GR-7 retained-key trial-decrypt across Kr rotations
 * automatic). No `Placement` mount — a mount has no visible home until
 * personal-FS placement rendering lands (deferred on native too).
 */
@JsExport
object SharedFiles {

    /**
     * The realms shared *with* this member — the "Shared with me" list. Each
     * realm's display name is its wrapper-root `SharedFile`'s name, or null if
     * that node hasn't synced in yet (the joiner just joined; the UI shows a
     * fallback label).
     */
    fun sharedWithMe(): Promise<Array<InboundRealmItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            val memberships = client.identityService.getSharedRealmMemberships()
                .getOrNull()
                ?.toList()
                ?: return@promise emptyArray<InboundRealmItem>()
            memberships
                .filter { it.role.get() == SharedRealmMembership.Role.MEMBER }
                .map { membership ->
                    val realmId = membership.sharedRealmId.get()
                    InboundRealmItem(sharedRealmId = realmId, name = wrapperName(realmId))
                }
                .toTypedArray()
        }

    /**
     * The children of folder [parentFileId] in shared realm [realmId]
     * (`SharedFile.ROOT_PARENT` / `""` = the realm root).
     */
    fun browseSharedFolder(realmId: String, parentFileId: String): Promise<Array<SharedEntryItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.fileService.defaultFileSystem
                .listSharedFolder(realmId, parentFileId)
                .fold(
                    onSuccess = { children ->
                        (children?.toList() ?: emptyList()).map { sharedFile ->
                            SharedEntryItem(
                                fileId = sharedFile.fileId.get(),
                                name = sharedFile.name.get(),
                                isFolder = sharedFile.isFolder.get(),
                            )
                        }.toTypedArray()
                    },
                    onFailure = { emptyArray() },
                )
        }

    /**
     * Bytes of file [fileId] inside shared realm [realmId] — fetched lazily on open (no proactive
     * push). Wrap as a Blob on the JS side to download / preview.
     *
     * Resolves `{ ok: true, bytes }` — `bytes` null when the read produced nothing to hand back
     * (not yet available) — or `{ ok: false, error }` when the read failed.
     *
     * ⚠ Both used to be `null`, so a member opening a shared file could not be told apart from a
     * member whose read was refused — on the one path where the *other* realm's permissions are
     * the likeliest cause, and therefore the one where the reason matters most.
     */
    fun readSharedFile(realmId: String, fileId: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.fileService.defaultFileSystem
                .readSharedFile(realmId, fileId)
                .fold(
                    onSuccess = { data -> okResult { it.bytes = (data as? TByteArray)?.get() } },
                    onFailure = { error -> failureResult("read", error.toString()) },
                )
        }

    /**
     * Share folder [folderPath] *out* (MB-4 outbound; doc §10). Bootstraps a shared realm,
     * populates it with the folder's contents (metadata + a content fan-out into the realm's
     * content plane), and self-mounts it so it shows in [sharedFolders]. None of this touches the
     * personal folder. The returned realm id is what `Membership.grant` targets.
     *
     * Resolves `{ ok: true, item }` or `{ ok: false, error: { kind, message } }` where `kind`
     * names **which step** failed — `realm`, `share` or `mount`.
     *
     * ⚠⚠ The step matters more here than anywhere else on this surface, because this is a
     * MULTI-STEP WRITE and the steps are not undone. All four failure paths used to return the
     * same `null`, so a failure at `share` or `mount` left a shared realm created (and possibly
     * populated) on the relay while the user was told only that sharing "didn't work" — with no
     * way for them or us to know a realm was now sitting there. Naming the step does not clean
     * it up, but it stops the partial state being invisible.
     */
    fun shareFolder(folderPath: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            val realmId = (client.syncService.createSharedRealm().getOrNull() as? TString)?.get()
                ?: return@promise failureResult("realm", "could not create a shared realm")

            // shareFolderIntoRealm is Shape A (null on success) — only a Failure is an error.
            val shareResult = client.fileService.defaultFileSystem
                .shareFolderIntoRealm(folderPath = folderPath, realmId = realmId)
            if (shareResult is TResult.Failure) {
                return@promise failureResult(
                    "share",
                    "realm $realmId was created but the folder could not be shared into it",
                )
            }

            val name = folderPath.substringAfterLast('/').ifEmpty { folderPath }
            val parentPath = folderPath.substringBeforeLast('/').ifEmpty { "/" }
            client.fileService.defaultFileSystem
                .mountSharedRealm(sharedRealmId = realmId, parentPath = parentPath, name = name)
                .fold(
                    onSuccess = {
                        okResult {
                            it.item = SharedFolderItem(
                                sharedRealmId = realmId,
                                name = name,
                                parentPath = parentPath,
                            )
                        }
                    },
                    onFailure = { error ->
                        failureResult(
                            "mount",
                            "realm $realmId holds the shared folder but it could not be mounted: $error",
                        )
                    },
                )
        }

    /**
     * The shared folders this owner shares *out* — `listPlacements` filtered to
     * realms they own (a joined realm's mount belongs in [sharedWithMe], not
     * here). Backs the outbound *Shared folders* listing + its Manage-access.
     */
    fun sharedFolders(): Promise<Array<SharedFolderItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            val ownedRealmIds = client.identityService.getSharedRealmMemberships()
                .getOrNull()
                ?.toList()
                ?.filter { it.role.get() == SharedRealmMembership.Role.OWNER }
                ?.map { it.sharedRealmId.get() }
                ?.toSet()
                ?: emptySet()
            client.fileService.defaultFileSystem.listPlacements().fold(
                onSuccess = { placements ->
                    (placements?.toList() ?: emptyList())
                        .filter { it.sharedRealmId.get() in ownedRealmIds }
                        .map { p: Placement ->
                            SharedFolderItem(
                                sharedRealmId = p.sharedRealmId.get(),
                                name = p.name.get(),
                                parentPath = p.parentPath.get(),
                            )
                        }
                        .toTypedArray()
                },
                onFailure = { emptyArray() },
            )
        }

    private suspend fun wrapperName(realmId: String): String? =
        JsRuntime.tenshuClient.fileService.defaultFileSystem
            .listSharedFolder(realmId, SharedFile.ROOT_PARENT)
            .getOrNull()
            ?.toList()
            ?.firstOrNull()
            ?.name?.get()
}

@JsExport
class InboundRealmItem internal constructor(
    /** The shared realm's owner id — the browse key. */
    val sharedRealmId: String,
    /** The wrapper-root folder's name, or null until it syncs in. */
    val name: String?,
)

@JsExport
class SharedEntryItem internal constructor(
    /** Location-independent identity within the realm — the browse / read key. */
    val fileId: String,
    val name: String,
    val isFolder: Boolean,
)

@JsExport
class SharedFolderItem internal constructor(
    /** The shared realm's owner id — the `Membership.grant` target + browse key. */
    val sharedRealmId: String,
    val name: String,
    /** Where in the owner's personal FS the mount lives (display only). */
    val parentPath: String,
)
