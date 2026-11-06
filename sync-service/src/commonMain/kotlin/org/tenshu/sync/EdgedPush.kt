package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/*
 * CR-2 B3's client↔relay wire types (`docs/proposals/content-plane-cr2-b3-wire-spec.md` v0.2).
 *
 * ⚠⚠ The ONE definition both halves build on. B3c (relay) and B3d (client) are developed in parallel,
 * so the shapes live here, landed before either half, and neither half may change them alone: they
 * are positional composites, so any change is a reshape for both sides at once.
 *
 * ⚠ New types, so no SyncService version moves (HR 9 bumps on a reshape). They cross only
 * client↔relay, never peer↔peer; a relay reset accompanies B3c (wire spec §0).
 */

/**
 * One content reference a pushed row names (wire spec §3.1). The holder is the enclosing block's
 * `targetEntityIdHash` and the stamp is its HLC — neither is repeated here, so neither can disagree.
 */
interface PushedEdge : Composite {
    override val schema: CompositeSchema get() = Schema

    val propertyId: TString get() = getField(Fields.PROPERTY_ID)

    /** The content the row names here, or absent for **none** (the row has no content in this slot). */
    val contentId: TString? get() = getOptionalField(Fields.CONTENT_ID)

    /** Rebuildable from the primary's bytes (a thumbnail rung): never `content_freed`, never revived. */
    val regenerable: TBoolean get() = getField(Fields.REGENERABLE)

    companion object {
        const val ID = "tenshu/sync/pushedEdge"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.PROPERTY_ID)
                string(Fields.CONTENT_ID, isOptional = true)
                boolean(Fields.REGENERABLE)
            }
        )

        fun create(context: TContext, propertyId: String, contentId: String?, regenerable: Boolean): PushedEdge =
            context.create(
                type = ID,
                fields = listOf(
                    propertyId.toTString(context),
                    contentId?.toTString(context),
                    regenerable.toTBoolean(context),
                ),
            )
    }

    object Fields {
        const val PROPERTY_ID = "$ID/propertyId"
        const val CONTENT_ID = "$ID/contentId"
        const val REGENERABLE = "$ID/regenerable"
    }
}

/**
 * ⚠ **Reserved for B4** (wire spec §3.1): the atomic way out of `content_freed`. A B3 relay refuses a
 * push carrying any. Defined now so B4 is not a reshape of [EdgedPush].
 */
interface ReviveEntry : Composite {
    override val schema: CompositeSchema get() = Schema

    val contentId: TString get() = getField(Fields.CONTENT_ID)
    val refs: TList<TString> get() = getField(Fields.REFS)

    companion object {
        const val ID = "tenshu/sync/reviveEntry"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.CONTENT_ID)
                list(Fields.REFS, of = TString.Schema)
            }
        )

        fun create(context: TContext, contentId: String, refs: List<String>): ReviveEntry = context.create(
            type = ID,
            fields = listOf(contentId.toTString(context), SimpleTList(refs.map { it.toTString(context) })),
        )
    }

    object Fields {
        const val CONTENT_ID = "$ID/contentId"
        const val REFS = "$ID/refs"
    }
}

/**
 * The body of `POST /sync/push` from B3 on (wire spec §3.1): a block and the **complete** edge set of
 * its row, applied in one relay transaction.
 *
 * ⚠ `edges` is non-empty only for a standalone block (non-null `targetEntityIdHash`); the relay 400s
 * otherwise, and on a repeated `propertyId`.
 */
interface EdgedPush : Composite {
    override val schema: CompositeSchema get() = Schema

    val relayProtocolVersion: TInteger get() = getField(Fields.RELAY_PROTOCOL_VERSION)
    val block: SyncBlock get() = getField(Fields.BLOCK)
    val edges: TList<PushedEdge> get() = getField(Fields.EDGES)
    val revive: TList<ReviveEntry> get() = getField(Fields.REVIVE)

    companion object {
        const val ID = "tenshu/sync/edgedPush"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                int(Fields.RELAY_PROTOCOL_VERSION)
                composite(Fields.BLOCK, type = SyncBlock.Schema)
                list(Fields.EDGES, of = PushedEdge.Schema)
                list(Fields.REVIVE, of = ReviveEntry.Schema)
            }
        )

        fun create(
            context: TContext,
            relayProtocolVersion: Int,
            block: SyncBlock,
            edges: List<PushedEdge> = emptyList(),
            revive: List<ReviveEntry> = emptyList(),
        ): EdgedPush = context.create(
            type = ID,
            fields = listOf(
                relayProtocolVersion.toLong().toTInteger(context),
                block,
                SimpleTList(edges),
                SimpleTList(revive),
            ),
        )
    }

    object Fields {
        const val RELAY_PROTOCOL_VERSION = "$ID/relayProtocolVersion"
        const val BLOCK = "$ID/block"
        const val EDGES = "$ID/edges"
        const val REVIVE = "$ID/revive"
    }
}

/**
 * The body of `POST /sync/push-tombstone` from B3 on (wire spec §3.2): a tombstone and the wire names
 * of every descendant its recursive delete removed (from B3b's persisted `TombstoneCascades`).
 */
interface CascadedTombstonePush : Composite {
    override val schema: CompositeSchema get() = Schema

    val relayProtocolVersion: TInteger get() = getField(Fields.RELAY_PROTOCOL_VERSION)
    val block: DeleteBlock get() = getField(Fields.BLOCK)
    val cascade: TList<TString> get() = getField(Fields.CASCADE)

    companion object {
        const val ID = "tenshu/sync/cascadedTombstonePush"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                int(Fields.RELAY_PROTOCOL_VERSION)
                composite(Fields.BLOCK, type = DeleteBlock.Schema)
                list(Fields.CASCADE, of = TString.Schema)
            }
        )

        fun create(
            context: TContext,
            relayProtocolVersion: Int,
            block: DeleteBlock,
            cascade: List<String> = emptyList(),
        ): CascadedTombstonePush = context.create(
            type = ID,
            fields = listOf(
                relayProtocolVersion.toLong().toTInteger(context),
                block,
                SimpleTList(cascade.map { it.toTString(context) }),
            ),
        )
    }

    object Fields {
        const val RELAY_PROTOCOL_VERSION = "$ID/relayProtocolVersion"
        const val BLOCK = "$ID/block"
        const val CASCADE = "$ID/cascade"
    }
}
