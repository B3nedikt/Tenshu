package app.tenshu.sdk.internal

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetPreview
import org.tenshu.core.id
import org.tenshu.core.toTByteArray
import org.tenshu.core.number.TInteger

/**
 * `GetPreview` for the browser — the **generation** half, which is what
 * `UploadFileImplementation.renderThumbnail` calls to mint the stored thumbnail ladder
 * (LIST/LARGE/XLARGE/XXLARGE) at upload. Without it a file uploaded in a browser reached every
 * other device with no thumbnail at all.
 *
 * ⚠⚠ This is an [AsyncOperationImplementation], and it is the only kind it can be: a
 * browser has no synchronous image decode — `createImageBitmap` and
 * `OffscreenCanvas.convertToBlob` are both Promise-returning — so the three native
 * `SyncOperationImplementation`s have no browser twin. That asymmetry is the whole reason
 * `renderThumbnail` awaits via `executeAsync`.
 *
 * ⚠⚠ **Consequence, and the thing to not get wrong:** `GetPreview` has two call paths and
 * only one of them can await.
 *  1. The dispatcher path — `executionContext.execute*(GetPreview.ID, …)`, which
 *     `executeAsync` resolves through an `AsyncReference`. This implementation serves it.
 *  2. The in-chain path — `File.Preview`, a *synchronous* dynamic property. It cannot await,
 *     so against this implementation it resolves an unawaited `AsyncReference` rather than
 *     bytes. **Nothing on web may project `File.Preview`**; read the stored classes
 *     (`File.Thumbnail` / `File.ThumbnailLarge`, both served by the shared
 *     `GetStoredThumbnailImplementation`) instead, as `FileSystem.getPreview` does.
 *     ⚠ This is a convention, not something a test enforces — there is no runtime signal to
 *     assert on, because a leaked `AsyncReference` surfaces as an ordinary null through the
 *     `onFailure` a chain read already has.
 */
internal class JsGetPreviewImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val width: TInteger = params.read(GetPreview.Params.WIDTH)
        val height: TInteger = params.read(GetPreview.Params.HEIGHT)

        // FORMAT is optional and absent means PNG (GetPreview.Params). `params.read` NPEs on
        // an absent optional, so look it up null-safely — the LIST rung passes no format.
        val format: String =
            (params?.get(GetPreview.Params.FORMAT) as? TString)?.get()
                ?: GetPreview.Params.FORMAT_PNG
        // Absent ⇒ the canvas default. Null-safe for the same reason as FORMAT.
        val quality: Int? = (params?.get(GetPreview.Params.QUALITY) as? TInteger)?.get()?.toInt()

        val file = caller as? Composite ?: error("Caller must be a Composite")

        val typeId = if (file is Entity<*>) file.type.get() else file.id
        if (typeId == Folder.ID) return null as T

        // The bytes this device holds. `executeAsync`, not `execute`: it costs nothing when
        // `GetData` resolves to a synchronous implementation (the await loop returns on the
        // first pass) and is correct if it ever does not.
        val bytes = executionContext.executeAsync<TByteArray?>(
            operationId = GetData.ID,
            caller = file,
        )?.get()
        if (bytes == null || bytes.isEmpty()) return null as T

        // Best-effort by contract: a non-image (video poster, PDF first page) fails the
        // `createImageBitmap` decode and comes back null, which leaves that rung unset and
        // falls back to the category icon. Thumbnailing never fails the upload.
        val rendered = generatePreview(
            bytes = bytes,
            width = width.get().toInt(),
            height = height.get().toInt(),
            mimeType = if (format == GetPreview.Params.FORMAT_JPEG) MIME_JPEG else MIME_PNG,
            quality = quality?.let { it.coerceIn(1, 100) / 100.0 } ?: DEFAULT_QUALITY,
        ) ?: return null as T

        return rendered.toTByteArray(executionContext.context) as T
    }
}
