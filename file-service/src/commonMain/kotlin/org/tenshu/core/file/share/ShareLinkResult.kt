package org.tenshu.core.file.share

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * The success return shape of [ShareLink]: the opaque `shareId` the relay
 * minted + the per-share key `K_share` (as base64url) the *caller* (Drive UI)
 * needs to assemble the canonical share URL —
 * `https://tenshu.app/s/{shareId}#k={urlFragment}` (the apex serves the viewer
 * route until the web roadmap's Phase 8 `drive.tenshu.app` subdomain ships; the
 * host lives in `ShareLinkUrl.DEFAULT_HOST`, `:drive-shared`).
 *
 * **Why a typed composite, not a plain `TString`** — the URL has two
 * separate-trust pieces (the path goes to the relay; the fragment never
 * leaves the device), and the UI needs both: the path is what the *My shares*
 * list shows, the fragment is what *Copy link* writes to the clipboard. Two
 * fields is the schema-faithful way to say "these are conceptually distinct."
 *
 * `K_share` round-trips back to the caller via this return value and is
 * **not** persisted anywhere on the wire or in the realm (`Share` composite
 * deliberately omits it — see `docs/file-sharing.md`).
 * Once the user has copied the URL, the field is intended to be discarded;
 * `:drive-shared` ViewModels MUST NOT retain it across configuration changes.
 */
interface ShareLinkResult : Composite {

    override val schema: CompositeSchema get() = Schema

    val shareId: TString
        get() = getField(Field.SHARE_ID)

    val urlFragment: TString
        get() = getField(Field.URL_FRAGMENT)

    companion object {
        const val ID = "tenshu/file/share/shareLinkResult"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(id = Field.SHARE_ID)
                string(id = Field.URL_FRAGMENT)
            }
        )

        fun create(
            context: TContext,
            shareId: String,
            urlFragment: String,
        ): ShareLinkResult = context.create(
            type = ID,
            fields = listOf(shareId, urlFragment)
        )
    }

    object Field {
        const val SHARE_ID = "$ID/shareId"
        const val URL_FRAGMENT = "$ID/urlFragment"
    }
}

val Entity<out ShareLinkResult>.shareId: TString
    get() = getFieldValue(ShareLinkResult.Field.SHARE_ID)

val Entity<out ShareLinkResult>.urlFragment: TString
    get() = getFieldValue(ShareLinkResult.Field.URL_FRAGMENT)
