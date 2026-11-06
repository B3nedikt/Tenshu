package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * One entry in a [FileContentManifestPayload]'s ordered chunk list.
 *
 * - [ref] — the **opaque content-plane key** under which the chunk's
 *   AEAD ciphertext is stored / fetched on the relay's content store.
 *   It is derived from a hash of the *ciphertext* (never the plaintext)
 *   so it cannot be used as a confirmation-of-file / dedup oracle (see
 *   `docs/sync/threat-model.md` §8.3 "Property-ID exposure…" amendment
 *   and proposal §3). The relay sees this string but it reveals nothing
 *   about the plaintext.
 * - [cipherHash] — the SHA-256 of the chunk ciphertext, hex-encoded.
 *   The reader (P2) recomputes it on fetch to detect a relay that
 *   serves a wrong/old blob under a given [ref] before it spends an
 *   AEAD decrypt on it. Today [ref] *is* `"blob-" + cipherHash`, so the
 *   two are redundant by construction; keeping [cipherHash] as its own
 *   field keeps the manifest robust if the ref scheme ever changes to
 *   a random key (proposal §2.2 allows either).
 *
 * Lives entirely *inside* the encrypted manifest payload — the relay
 * only ever sees [ref] as an opaque PUT/GET path segment, never this
 * composite.
 */
interface FileChunkRef : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val ref: TString
        get() = getField(Fields.REF)

    val cipherHash: TString
        get() = getField(Fields.CIPHER_HASH)

    companion object {
        const val ID = "tenshu/sync/fileChunkRef"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.REF)
                string(Fields.CIPHER_HASH)
            }
        )

        fun create(
            context: TContext,
            ref: String,
            cipherHash: String
        ): FileChunkRef = context.create(
            type = ID,
            fields = listOf(
                ref.toTString(context),
                cipherHash.toTString(context)
            )
        )
    }

    object Fields {
        const val REF = "$ID/ref"
        const val CIPHER_HASH = "$ID/cipherHash"
    }
}
