package org.tenshu.core.file

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.serviceRelation
import org.tenshu.core.schema.typeOf

interface FileService : Service {

    override val schema: ServiceSchema
        get() = Schema

    val defaultFileSystem: FileSystem

    /**
     * The realm-wide, append-only [FileModification] log
     * (file-service-history.md §3.1). A flat collection of every recorded change
     * across all files, populated by the emit hooks + the generic relation
     * maintainer. Filter it per file with
     * `modificationsForFile(path)`; sort/project at the call site.
     */
    val modifications: TList<FileModification>

    /**
     * The realm-wide list of Mode-A [Share] links the user has minted
     * (`docs/file-sharing.md` — *My shares*). `Share` is
     * `Syncable(Eager)`, so every realm device sees the same list; a `revoked`
     * row stays in the collection so the UI can render its history (or be
     * filtered out for the active-shares list). Populated by `ShareLink` + the
     * generic relation maintainer.
     */
    val shares: TList<Share>

    companion object {
        const val ID = "tenshu/file/service"

        val Schema: ServiceSchema = service(
            id = ID,
            // v1 is the release baseline (`core-schema-evolution.md` Appendix A).
            version = 1,
            relations = listOf(
                serviceRelation(
                    id = Relations.DEFAULT_FILE_SYSTEM,
                ),
                // Realm-wide append-only modification log
                // (docs/proposals/file-service-history.md §3.1, §5.1). Mirrors
                // NotificationService.Relations.NOTIFICATIONS: a top-level
                // collection of independently-keyed entities populated by the
                // emit hooks + the generic relation maintainer; read through
                // `fileService.modifications`.
                serviceDataRelation(
                    id = Relations.MODIFICATIONS,
                    type = TList.typeOf(FileModification.Schema),
                    maintained = true,
                ),
                // Realm-wide *My shares* collection
                // (docs/file-sharing.md). Same shape as
                // MODIFICATIONS — top-level realm-scoped TList derived locally
                // (the wire never carries the membership; `Share` is
                // `Syncable(Eager)` so the entity itself syncs and each device
                // re-derives the list).
                serviceDataRelation(
                    id = Relations.SHARES,
                    type = TList.typeOf(Share.Schema),
                    maintained = true,
                ),
                // The member's flat collection of mounted shared realms. Read
                // through the dedicated `listPlacements` op, never the chain —
                // `chainReadable = false` says so, and is what lets the write
                // side be declared here at all.
                serviceDataRelation(
                    id = Relations.PLACEMENTS,
                    type = TList.typeOf(Placement.Schema),
                    maintained = true,
                    chainReadable = false,
                )
            )
        )
    }

    object Relations {
        const val DEFAULT_FILE_SYSTEM = "tenshu/file/service/defaultFileSystem"
        const val MODIFICATIONS = "$ID/modifications"
        const val SHARES = "$ID/shares"

        /**
         * The member's flat collection of `Placement` connectors — every shared
         * realm they have mounted into their personal FS (`drive-shared-realm-file-model.md`
         * §6). The array key behind the `listPlacements` op; derived on
         * every (local or sync-arriving) `Placement` save by the generic relation
         * maintainer, exactly as `SHARES`/`MODIFICATIONS` are. No chain
         * accessor — the dedicated op is the read surface (same rationale as
         * `listSharedFolder`), which the schema above says with
         * `chainReadable = false`.
         */
        const val PLACEMENTS = "$ID/placements"
    }
}
