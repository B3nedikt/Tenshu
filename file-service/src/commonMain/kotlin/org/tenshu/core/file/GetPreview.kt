package org.tenshu.core.file

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

fun File.getPreview(width: Int, height: Int, preferOriginal: Boolean = false): TByteArray =
    findOperation<GetPreview>(GetPreview.ID).getPreview(
        file = this,
        width = width,
        height = height,
        preferOriginal = preferOriginal
    )

interface GetPreview : Operation {

    override val schema: OperationSchema
        get() = Schema

    /**
     * ⚠ [preferOriginal] is D-17's [Params.PREFER_ORIGINAL], and this is the **only** route a client
     * has to it: the proxy actuals read it out of the operation's parameter map, which a
     * `File.Preview(width, height)` projection never populates. `File.ViewerPreview` is the property
     * that passes `true` (`drive-xxlarge-thumbnail-rung.md`).
     */
    fun getPreview(file: File, width: Int, height: Int, preferOriginal: Boolean = false): TByteArray

    object Params {
        const val FILE = "$ID/file"
        const val WIDTH = "$ID/width"
        const val HEIGHT = "$ID/height"

        /**
         * Encode format of the rendered preview: [FORMAT_PNG] or [FORMAT_JPEG].
         * **Optional** — absent means [defaultFormatFor] the requested size: PNG up to
         * 1024 px and JPEG above. Only the upload-time `LARGE` thumbnail render passes `"jpeg"`
         * (`drive-synced-thumbnails.md` Q1 / §10.2). This is an op param, **not** a new
         * `File.Preview` arity — the 2-param dynamic property is preserved.
         */
        const val FORMAT = "$ID/format"

        /** Lossless PNG — the default [FORMAT] and the `LIST` thumbnail encode. */
        const val FORMAT_PNG = "png"

        /** Lossy JPEG — the `LARGE` (~256 px) thumbnail encode (smaller for photos). */
        const val FORMAT_JPEG = "jpeg"

        /**
         * The encoding a caller that names no [FORMAT] gets: PNG up to the hero rung's edge, JPEG
         * above it (`drive-synced-thumbnails.md` §17.6).
         *
         * ⚠⚠ Above 1024 px only the `XXLARGE` rung answers, and above 2048 px nothing but a render
         * from the original does — and a viewer that asks for the size it draws (~3000 px on a
         * retina panel) would otherwise be shipped a PNG: measured on the JVM at a 3072 px edge,
         * **7.6 MB and 528 ms to encode, against 234 KB and 127 ms** as JPEG — for a smooth
         * photograph; a detailed one is larger. Android's actual already defaults to lossy WebP and
         * does not read this.
         *
         * ⚠ Not a new `File.Preview` arity: the two-parameter property is preserved, which is also
         * why the choice is made from the size rather than asked for.
         */
        fun defaultFormatFor(requestedMaxEdge: Int): String =
            if (requestedMaxEdge > GetStoredThumbnail.XLARGE_PX) FORMAT_JPEG else FORMAT_PNG

        /**
         * JPEG encode quality, 1–100. **Optional** — absent means the platform's own default
         * (80 on Android and iOS, 75 in the JVM's ImageIO, 85 in a browser). Ignored for PNG,
         * which has no quality knob.
         *
         * ⚠ Only the mint passes it (`ThumbnailClassMinter.renderStill`), and only after a render
         * at the default came back over its class's byte cap: a detailed frame — a screenshot, a
         * page of text — is ~500 KB at 1024 px q80 and would be declined outright, leaving every
         * byte-less device on the 256 px class for ever (`drive-synced-thumbnails.md` §17).
         */
        const val QUALITY = "$ID/quality"

        /**
         * Compute from the file's bytes and ignore every stored thumbnail class — the
         * **generation** call, as opposed to an ordinary read.
         *
         * ⚠⚠ This exists because the distinction cannot be inferred from the caller:
         * `ReferenceResolver.resolveDynamicProperty` unwraps an entity through `fromEntity(data)`
         * before invoking any operation, so **every** caller arrives as a bare composite and a
         * `file is Entity<*>` guard can never tell a read from the upload-time render.
         *
         * ⚠ Absence means *read* — the common case, and the one that must stay cheap. Only
         * `UploadFileImplementation.renderThumbnail` passes `true`, which is what stops a
         * re-upload from minting a new class out of an older stored class and quietly re-encoding
         * the ladder downhill.
         */
        const val GENERATE_FRESH = "$ID/generateFresh"

        /**
         * ⚠⚠ **Prefer the original over a stretched rung** (`drive-xxlarge-thumbnail-rung.md` D-17):
         * with this set, the resolver serves a stored class only when one **covers** the request,
         * and otherwise renders from the file's own bytes at the size asked for.
         *
         * For the device that holds the original, which would otherwise be handed a rung stretched
         * up to [GetStoredThumbnail.COVER_TOLERANCE] of its edge. ⚠ It is not "always render": a
         * covering rung is a downscale of the same picture and far cheaper than reading a whole
         * file, so it still wins. ⚠ On a device without the bytes it changes nothing — there is
         * nothing to render from, and the ladder answers as it would without it.
         */
        const val PREFER_ORIGINAL = "$ID/preferOriginal"
    }

    companion object {
        const val ID = "tenshu/file/getPreview"

        val Schema = operation(
            id = ID,
            receiver = File.Schema.toType(),
            params = {
                composite(id = Params.FILE, type = File.Schema)
                int(id = Params.WIDTH)
                int(id = Params.HEIGHT)
                // Optional: absence ⇒ PNG. Declared optional so `checkRequiredParameters`
                // skips it for the 2-param File.Preview callers, while
                // OperationDto still accepts it as a known key when the LARGE render
                // passes "jpeg". docs/proposals/drive-synced-thumbnails.md Q1.
                string(id = Params.FORMAT, isOptional = true)
                // Optional for the same reason as FORMAT: absence ⇒ read semantics, so a
                // 2-param `File.Preview` caller is unaffected and `checkRequiredParameters`
                // skips it.
                boolean(id = Params.GENERATE_FRESH, isOptional = true)
                // Optional for the same reason again: absence ⇒ the platform's default quality.
                int(id = Params.QUALITY, isOptional = true)
                // Optional again: absence ⇒ the stored ladder, tolerance and all (D-17).
                boolean(id = Params.PREFER_ORIGINAL, isOptional = true)
            },
            traits = listOf(TerminalOperation.Schema)
        )
    }
}
