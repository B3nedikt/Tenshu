package org.tenshu.core.file.share

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.file.FilePath
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A Mode-A "share link" for a single [org.tenshu.core.file.File] — the local,
 * realm-replicated record that one or more anonymous-link shares exist for the
 * file (`docs/file-sharing.md`).
 *
 * `Syncable(Eager)` so every realm device sees the share list consistently
 * (e.g. revoking on Phone disappears the link on Desktop's *My shares*).
 *
 * **`Share` is NOT the relay-side `ShareRecord`** — that is a separate storage
 * shape on `:sync-relay-server` that the relay needs to enforce
 * expiry/use-cap/revocation. This composite is the *client's* record of "I
 * made a share with shareId=X for file=Y." The per-share key `K_share` is
 * intentionally NOT a field here — it lives only in the URL fragment the user
 * copies, never on the wire and never on relay storage (A11, §5.1).
 */
interface Share : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Opaque relay-assigned id (~16 bytes base64url). Matches `ShareRecord.shareId`. */
    val shareId: TString
        get() = getField(Field.SHARE_ID)

    /** The shared file's path in the local realm. Matches `File.Field.PATH`. */
    val fileId: FilePath
        get() = getField(Field.FILE_ID)

    /** Wall-clock millis the share was created (authoring device's clock). */
    val createdAtMillis: TInteger
        get() = getField(Field.CREATED_AT_MILLIS)

    /** Authoring device id ("shared from Phone" attribution). */
    val createdOnDeviceId: TString
        get() = getField(Field.CREATED_ON_DEVICE_ID)

    /**
     * Optional wall-clock millis when this share auto-expires. `0` is the
     * sentinel for "no expiry" (we keep the field non-nullable to fit the
     * existing schema-DSL int field; revisit when nullable scalars land).
     */
    val expiresAtMillis: TInteger
        get() = getField(Field.EXPIRES_AT_MILLIS)

    /**
     * Optional cap on how many distinct opens the relay should permit before
     * returning 410. `0` is the sentinel for "no cap".
     */
    val maxOpens: TInteger
        get() = getField(Field.MAX_OPENS)

    /**
     * Soft revocation flag. Revocation also issues a `DELETE /share/{shareId}`
     * to the relay; this field is the local source of truth used to hide the
     * share from *My shares* immediately, even before the relay confirms.
     */
    val revoked: TBoolean
        get() = getField(Field.REVOKED)

    companion object {
        const val ID = "tenshu/file/share"

        /** "no expiry" / "no cap" sentinel for the optional Int fields. */
        const val UNSET_INT_FIELD: Long = 0L

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(ShareDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(id = Field.SHARE_ID)
                string(id = Field.FILE_ID, type = FilePath.Schema)
                int(id = Field.CREATED_AT_MILLIS)
                string(id = Field.CREATED_ON_DEVICE_ID)
                int(id = Field.EXPIRES_AT_MILLIS)
                int(id = Field.MAX_OPENS)
                boolean(id = Field.REVOKED)
            }
        )

        fun create(
            context: TContext,
            shareId: String,
            fileId: String,
            createdAtMillis: Long,
            createdOnDeviceId: String,
            expiresAtMillis: Long = UNSET_INT_FIELD,
            maxOpens: Long = UNSET_INT_FIELD,
            revoked: Boolean = false,
        ): Share = context.create(
            type = ID,
            fields = listOf(
                shareId,
                fileId,
                createdAtMillis,
                createdOnDeviceId,
                expiresAtMillis,
                maxOpens,
                revoked,
            )
        )
    }

    object Field {
        const val SHARE_ID = "$ID/shareId"
        const val FILE_ID = "$ID/fileId"
        const val CREATED_AT_MILLIS = "$ID/createdAtMillis"
        const val CREATED_ON_DEVICE_ID = "$ID/createdOnDeviceId"
        const val EXPIRES_AT_MILLIS = "$ID/expiresAtMillis"
        const val MAX_OPENS = "$ID/maxOpens"
        const val REVOKED = "$ID/revoked"
    }
}

// Entity-level accessors mirroring the File / FileModification convention so
// chain DSL (`relations(...)` projections) can resolve fields without the
// composite being materialised.
val Entity<out Share>.shareId: TString
    get() = getFieldValue(Share.Field.SHARE_ID)

val Entity<out Share>.fileId: FilePath
    get() = getFieldValue(Share.Field.FILE_ID)

val Entity<out Share>.createdAtMillis: TInteger
    get() = getFieldValue(Share.Field.CREATED_AT_MILLIS)

val Entity<out Share>.createdOnDeviceId: TString
    get() = getFieldValue(Share.Field.CREATED_ON_DEVICE_ID)

val Entity<out Share>.expiresAtMillis: TInteger
    get() = getFieldValue(Share.Field.EXPIRES_AT_MILLIS)

val Entity<out Share>.maxOpens: TInteger
    get() = getFieldValue(Share.Field.MAX_OPENS)

val Entity<out Share>.revoked: TBoolean
    get() = getFieldValue(Share.Field.REVOKED)

/**
 * Keys `Share` entities by their globally-unique `shareId` (the relay mints
 * it; the relay also enforces uniqueness). Mirrors `FileDataIdProvider`.
 */
interface ShareDataIdProvider : CompositeDataIdProvider<Share> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/share/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Share.Field.SHARE_ID
        )
    }
}
