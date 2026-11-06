package org.tenshu.core.file.shared

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.relation.Property
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.relation.entityRelation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A file that lives **inside a shared realm** — its content plus its position in
 * that realm's *own* internal folder tree
 * (`docs/proposals/drive-shared-realm-file-model.md` §3, additive model).
 *
 * **Two distinct links (the model that replaced the original conflated
 * `Placement`):**
 *  - **`SharedFile` (this type)** is the *content* side: the actual file in the
 *    shared realm, located in the realm's internal tree by a stable [fileId]
 *    under a [parentFileId] with a [name]. The same structure for every member —
 *    it is the shared content's own organisation. Realm-namespaced
 *    (`"$realmId::$fileId"`).
 *  - **`Placement`** (the connector, built with the read path in §6 Phase 3c) is a
 *    per-member **mount** of a whole shared realm into that member's personal FS
 *    at a location of their choosing. It references a *realm id* (opaque), never
 *    a `SharedFile` — so it is content-type-agnostic by construction (a future
 *    `ChatService` mounts a realm the same way; see the doc's "generalisation to
 *    other services" §). Lives in the **personal** realm.
 *
 * The personal path-keyed `File` is **untouched** (D1): a `SharedFile` is a
 * separate record in the shared realm, recognised as the same logical file as a
 * personal one (when shared from a personal folder) by sharing its [fileId].
 *
 * Phase 3a re-homed the realm-internal tree from the merged `Placement` onto this
 * type. Content-bearing fields (size, timestamps) and the on-demand bytes
 * (`SharedFile.Data`) land with the content fan-out (§6 Phase 3b step 2); the
 * `SharedFileRelationMaintainer` derives the per-parent children tree exactly as
 * the merged `PlacementRelationMaintainer` did.
 */
interface SharedFile : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Which shared realm this file lives in (the realm's owner id). */
    val realmId: TString
        get() = getField(Field.REALM_ID)

    /**
     * The stable, location-independent identity of the file. Shared from a
     * personal folder, it equals the same `fileId` recorded for the file in
     * other realms — that is the hard-link; content + the manifest binding follow
     * this id, not any path.
     */
    val fileId: TString
        get() = getField(Field.FILE_ID)

    /**
     * The [fileId] of the parent folder within this realm, or [ROOT_PARENT]
     * (empty string) at the realm root. The realm's internal tree is walked by
     * following `parentFileId` links, not by path arithmetic.
     */
    val parentFileId: TString
        get() = getField(Field.PARENT_FILE_ID)

    /** The file's name within this realm's tree (the last path segment). */
    val name: TString
        get() = getField(Field.NAME)

    /**
     * The **content-addressed identity** of this file's bytes — equal to the
     * personal [File.ContentId] of the file being shared (content-addressing
     * Phase 3 / §3.4.1). Empty (`""`) for a folder or a not-yet-content-addressed
     * leaf. When non-empty, the realm's content manifest is addressed by this id
     * (`manifestContentRef(contentId)` on the realm's content plane) exactly as a
     * personal file's is — so `FileBytesPropertyWriter.resolveContentId` returns
     * it and both the emit and the joiner's read route through the content plane
     * instead of the legacy entity-id mailbox block. Carrying the *personal*
     * contentId (not a fresh one) is what lets Phase 3-2b re-wrap the CEK + copy
     * the already-encrypted chunk blobs cross-realm rather than re-encrypting.
     */
    val contentId: TString
        get() = getField(Field.CONTENT_ID)

    /**
     * `true` if this is a folder (navigable — its children are projected via
     * [Children]); `false` for a leaf file (readable via `SharedFile.Data`). The
     * realm root is a folder. Mirrors the personal `Folder` vs `File` distinction;
     * needed so the browse UI knows whether to drill in or open, without inferring
     * it from whether a children array happens to exist.
     */
    val isFolder: TBoolean
        get() = getField(Field.IS_FOLDER)

    /** Derived unique key — see [compositeKey]. */
    val compositeKey: TString
        get() = getField(Field.COMPOSITE_KEY)

    companion object {
        const val ID = "tenshu/file/sharedFile"

        /**
         * Sentinel [parentFileId] meaning "at the realm's root" (the realm has no
         * parent file above its root). Empty string keeps the field non-nullable
         * to fit the existing schema-DSL string field.
         */
        const val ROOT_PARENT = ""

        /**
         * Property id for a folder's **children** array within a realm — the
         * per-parent derived collection the tree is walked over (`SharedFile` →
         * its child `SharedFile`s). Maintained by `SharedFileRelationMaintainer`
         * keyed on the *parent's* DataId (an `EntityPropertyDataId`); mirrors how
         * `File.FileHistory` keys a per-file array. A plain id (not a projectable
         * `Property` handle) until the shared-realm `FileSystem.list` projects it
         * (§6 Phase 3c, alongside the read path).
         */
        const val CHILDREN_RELATION_ID = "$ID/children"

        /**
         * Property id for a `SharedFile`'s **content bytes** — the slot the
         * two-plane content channel (`docs/adr/0017-file-content-two-plane-channel.md`) routes through.
         * `ShareFolderIntoRealm` emits the file's bytes into the realm's content
         * store under this property (chunks AEAD'd under the *realm's* keys; the
         * manifest binds to the `SharedFile`'s DataId); the joiner's
         * `FileBytesPropertyWriter` reassembles them under the same id (Phase 3b.2b
         * read path). Mirrors `File.Data.id` (`"$File.ID/data"`). A plain id for
         * now (the projectable OnDemand `SharedFile.Data` handle lands with the
         * read path, §6 Phase 3b.2b).
         */
        const val DATA_PROPERTY_ID = "$ID/data"

        /**
         * Realm-namespace separator — must equal `RealmRegistry.NAMESPACE_SEP`
         * (`::`) in `:sync-service-proxy`. Hardcoded here rather than depended on:
         * `:file-service` is a low-level schema module and the separator is a wire
         * constant. A `SharedFile`'s DataId key therefore *is* its realm namespace
         * prefix, so `RealmRegistry.realmForDataId` routes it to its shared realm.
         */
        private const val NAMESPACE_SEP = "::"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SharedFileDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                // §6e N1: a shared file's bytes are a content-plane reference like any other, and
                // without a handle they could never be released once the edge plane goes (N6).
                ContentReferencing.Schema,
            ),
            fields = {
                string(id = Field.REALM_ID)
                string(id = Field.FILE_ID)
                string(id = Field.PARENT_FILE_ID)
                string(id = Field.NAME)
                string(id = Field.COMPOSITE_KEY)
                boolean(id = Field.IS_FOLDER)
                string(id = Field.CONTENT_ID)
            }
        )

        /**
         * Derived unique key for a `SharedFile` row: `"$realmId::$fileId"`. A file
         * appears **at most once per realm** in v1.
         *
         * The `realmId::` prefix is **load-bearing, not cosmetic**:
         * `RealmRegistry.realmForDataId` routes a saved entity to its realm purely
         * by `key.substringBefore("::")`, so the realm id *must* be the
         * `::`-prefixed namespace of the key — otherwise the record would route to
         * the personal realm. `fileId` is the realm-local base key. Mirrors how
         * shared-realm entities are keyed everywhere else (`"$ownerId::$baseKey"`).
         */
        fun compositeKey(realmId: String, fileId: String): String =
            "$realmId$NAMESPACE_SEP$fileId"

        fun create(
            context: TContext,
            realmId: String,
            fileId: String,
            parentFileId: String = ROOT_PARENT,
            name: String,
            isFolder: Boolean,
            contentId: String = "",
        ): SharedFile = context.create(
            type = ID,
            fields = listOf(
                realmId,
                fileId,
                parentFileId,
                name,
                compositeKey(realmId, fileId),
                isFolder,
                contentId,
            )
        )
    }

    object Field {
        const val REALM_ID = "$ID/realmId"
        const val FILE_ID = "$ID/fileId"
        const val PARENT_FILE_ID = "$ID/parentFileId"
        const val NAME = "$ID/name"
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val IS_FOLDER = "$ID/isFolder"
        const val CONTENT_ID = "$ID/contentId"
    }
}

val Entity<out SharedFile>.realmId: TString
    get() = getFieldValue(SharedFile.Field.REALM_ID)

val Entity<out SharedFile>.fileId: TString
    get() = getFieldValue(SharedFile.Field.FILE_ID)

val Entity<out SharedFile>.parentFileId: TString
    get() = getFieldValue(SharedFile.Field.PARENT_FILE_ID)

val Entity<out SharedFile>.name: TString
    get() = getFieldValue(SharedFile.Field.NAME)

val Entity<out SharedFile>.isFolder: TBoolean
    get() = getFieldValue(SharedFile.Field.IS_FOLDER)

val Entity<out SharedFile>.contentId: TString
    get() = getFieldValue(SharedFile.Field.CONTENT_ID)

// ── entity relation: a folder SharedFile → its child SharedFiles ──────────────
// The realm's internal tree, projected via `relations(SharedFile.Children) { … }`
// and read via `entity.get(SharedFile.Children)`. A per-parent array keyed by the
// folder's own DataId (an EntityPropertyDataId under CHILDREN_RELATION_ID),
// maintained by `SharedFileRelationMaintainer` — the exact shape of
// `File.FileHistory`. Browsing a shared realm = `sharedRealmRoot(realmId)` then
// nest `relations(SharedFile.Children)` to drill in.
private val sharedFileChildren: Property.Value<TList<SharedFile>> =
    entityRelation(id = SharedFile.CHILDREN_RELATION_ID, element = SharedFile.Schema)

/**
 * This folder's child [SharedFile]s within its realm — the navigable tree edge.
 * Projected via `relations(SharedFile.Children) { … }`, read via
 * `entity.get(SharedFile.Children)`. Empty for a leaf file. Populated by
 * `SharedFileRelationMaintainer`; registered via `entityRelation(...)` in
 * `fileServiceCommonModule`.
 */
val SharedFile.Companion.Children: Property.Value<TList<SharedFile>> get() = sharedFileChildren

interface SharedFileDataIdProvider : CompositeDataIdProvider<SharedFile> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/sharedFile/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SharedFile.Field.COMPOSITE_KEY
        )
    }
}
