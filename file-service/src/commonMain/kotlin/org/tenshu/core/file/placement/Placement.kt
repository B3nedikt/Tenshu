package org.tenshu.core.file.placement

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The **connector** of the shared-realm file model
 * (`docs/proposals/drive-shared-realm-file-model.md` §3 D3): a per-member **mount**
 * of a whole shared realm into that member's personal file system, at a location of
 * their choosing.
 *
 * **Two distinct links** (the model that replaced the original conflated record):
 *  - **[org.tenshu.core.file.shared.SharedFile]** is the *content* side — a file that
 *    lives **inside** a shared realm, in that realm's own internal tree. Realm-
 *    namespaced (`"$realmId::$fileId"`), encrypted under the realm's keys, the same
 *    structure for every member.
 *  - **`Placement` (this type)** is the *connector* — it records that *this member*
 *    has mounted the shared realm [sharedRealmId] into their personal FS under
 *    [parentPath], shown as [name]. It references a **realm id** (opaque) — never a
 *    `SharedFile`, never a content type — so it is content-type-agnostic **by
 *    construction**: a future `ChatService` / `SharedDocument` mounts a realm the
 *    exact same way (see the doc's "generalisation to other services" §4). It lives
 *    in the member's **personal** realm.
 *
 * **Keyed by [sharedRealmId]** (one mount per realm per member in v1), via
 * `PlacementDataIdProvider`. The key is **unprefixed** (a bare realm-owner id, no
 * `"::"`), so `RealmRegistry.realmForDataId` routes it to the **personal** realm —
 * the connector is the member's own private bookkeeping, not shared content. Two
 * members can mount the same realm at different personal locations; each holds their
 * own `Placement` in their own personal realm, while the realm's internal `SharedFile`
 * tree is identical for both.
 *
 * `Placement` carries only `(sharedRealmId, parentPath, name)` — a realm id its holder
 * already possesses + a personal-FS location + a display name — so it leaks nothing new
 * (threat model §7); it is an ordinary personal-realm Syncable.
 *
 * "Open a mount" = browse that realm via the shared-realm read ops
 * (`listSharedFolder(sharedRealmId, …)` / `readSharedFile(sharedRealmId, …)`).
 */
interface Placement : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The shared realm this placement mounts (the realm's owner id). The key. */
    val sharedRealmId: TString
        get() = getField(Field.SHARED_REALM_ID)

    /**
     * Where in the member's **personal** FS the realm is mounted — the parent path
     * of the mount point (e.g. `/Shared with me`). The mount appears at
     * `"$parentPath/$name"`. A personal-FS location, not a shared-realm one.
     */
    val parentPath: TString
        get() = getField(Field.PARENT_PATH)

    /** The display name of the mount within the member's personal FS. */
    val name: TString
        get() = getField(Field.NAME)

    companion object {
        const val ID = "tenshu/file/placement"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PlacementDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(id = Field.SHARED_REALM_ID)
                string(id = Field.PARENT_PATH)
                string(id = Field.NAME)
            }
        )

        fun create(
            context: TContext,
            sharedRealmId: String,
            parentPath: String,
            name: String,
        ): Placement = context.create(
            type = ID,
            fields = listOf(
                sharedRealmId,
                parentPath,
                name,
            )
        )
    }

    object Field {
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val PARENT_PATH = "$ID/parentPath"
        const val NAME = "$ID/name"
    }
}

val Entity<out Placement>.sharedRealmId: TString
    get() = getFieldValue(Placement.Field.SHARED_REALM_ID)

val Entity<out Placement>.parentPath: TString
    get() = getFieldValue(Placement.Field.PARENT_PATH)

val Entity<out Placement>.name: TString
    get() = getFieldValue(Placement.Field.NAME)

/**
 * Keys a [Placement] by its [Placement.Field.SHARED_REALM_ID] — one mount per realm
 * per member. The key is a bare realm-owner id (no `"::"` prefix) so the record routes
 * to the **personal** realm.
 */
interface PlacementDataIdProvider : CompositeDataIdProvider<Placement> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/placement/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Placement.Field.SHARED_REALM_ID
        )
    }
}
