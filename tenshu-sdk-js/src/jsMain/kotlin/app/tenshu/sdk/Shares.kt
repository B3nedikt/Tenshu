@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.share.createdAtMillis
import org.tenshu.core.file.share.expiresAtMillis
import org.tenshu.core.file.share.fileId
import org.tenshu.core.file.share.maxOpens
import org.tenshu.core.file.share.revoked
import org.tenshu.core.file.share.revokeShareLink
import org.tenshu.core.file.share.shareId
import org.tenshu.core.file.share.shareLink
import org.tenshu.core.file.share.urlFragment
import org.tenshu.core.fold
import org.tenshu.file.client.fileService
import kotlin.js.Promise

/**
 * Mode-A **management** surface — the *authenticated* counterpart of the
 * anonymous [ShareViewer] (`docs/file-sharing.md` / `control-ia.md` §5.3).
 * Backs the web Data → Shares management view: the realm owner sees + revokes
 * their own outbound link shares.
 *
 * Unlike [ShareViewer] (no session — a recipient just opened a link), [Shares]
 * runs in the authenticated apex over [JsRuntime.tenshuClient]: it reads the
 * realm-wide `FileService.shares` projection (`Share` is `Syncable(Eager)`, so
 * every realm device sees the same list) and dispatches `RevokeShareLink`. The
 * recipient-side fetch/decrypt stays in [ShareViewer].
 *
 * Mirrors `:control-shared`'s `TenshuShareManager` (the Compose half of the same
 * surface): newest-first, revoked rows kept for the badge, `opensSoFar` absent
 * in v1 (relay-only, not synced into the `Share` entity).
 */
@JsExport
object Shares {

    /**
     * Mint a fresh anonymous Mode-A link for the file at [path]
     * (`docs/file-sharing.md`). Resolves `{ shareId, urlFragment }` — the
     * caller assembles the copy-able URL `https://tenshu.app/s/{shareId}#k={urlFragment}`
     * (the fragment IS the read capability, A11 — never goes on the wire) — or
     * `null` on any error. [expiresAtMillis] / [maxOpens] use `0` for "no expiry"
     * / "no cap". Mirrors `TenshuFilesRepository.shareLink`; the op also writes a
     * local Eager `Share` entity, so [list] reflects the new link immediately.
     */
    fun create(path: String, expiresAtMillis: Double, maxOpens: Int): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            client.fileService.defaultFileSystem.shareLink(
                path = path,
                expiresAtMillis = expiresAtMillis.toLong(),
                maxOpens = maxOpens.toLong(),
            ).fold(
                onSuccess = { result ->
                    if (result == null) {
                        null
                    } else {
                        val obj: dynamic = js("({})")
                        obj.shareId = result.shareId.get()
                        obj.urlFragment = result.urlFragment.get()
                        obj
                    }
                },
                onFailure = { null },
            )
        }

    /**
     * Snapshot of every outbound link share for the realm, newest-first
     * (revoked rows included). Promise-shaped (one emission) so JS can use it as
     * a `queryFn` / `await` on mount and re-fetch after a [revoke].
     */
    fun list(): Promise<Array<ShareListItem>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        client.fileService.shares.flow().first().fold(
            onSuccess = { rows ->
                rows.toList()
                    .map { entity ->
                        val filePath = entity.fileId.get()
                        ShareListItem(
                            shareId = entity.shareId.get(),
                            fileName = filePath.substringAfterLast('/'),
                            filePath = filePath,
                            createdAtMillis = entity.createdAtMillis.get().toDouble(),
                            expiresAtMillis = entity.expiresAtMillis.get()
                                .takeIf { it != 0L }?.toDouble(),
                            maxOpens = entity.maxOpens.get().toInt().takeIf { it != 0 },
                            revoked = entity.revoked.get(),
                        )
                    }
                    .sortedByDescending { it.createdAtMillis }
                    .toTypedArray()
            },
            onFailure = { emptyArray() },
        )
    }

    /**
     * Revoke a link share by id (Eager-flips `revoked` + `DELETE`s the relay
     * record). Resolves `true` on success, `false` on any error. The caller
     * should re-[list] afterwards (or the realm's reactive projection updates
     * other devices) — the flipped row stays in the list with a Revoked badge.
     */
    fun revoke(shareId: String): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.fileService.defaultFileSystem.revokeShareLink(shareId).fold(
            onSuccess = { true },
            onFailure = { false },
        )
    }
}

/**
 * One realm-wide outbound link share, surfaced to JS. `expiresAtMillis` /
 * `maxOpens` are `null` for "no expiry" / "no cap" (the `0L` schema sentinel
 * normalised away); there is no `opensSoFar` — the open-count lives only on the
 * relay's `ShareRecord` and is not synced into the `Share` entity in v1.
 */
@JsExport
class ShareListItem internal constructor(
    val shareId: String,
    val fileName: String,
    val filePath: String,
    val createdAtMillis: Double,
    val expiresAtMillis: Double?,
    val maxOpens: Int?,
    val revoked: Boolean,
)
