package org.tenshu.sdk.ios

import org.tenshu.core.FileUploader

/**
 * The route-1 [FileUploader]: it does nothing, and doing nothing is the whole point.
 *
 * [FileUploader] is an **out-of-band transport**. `UploadSourceFileRo` and its three siblings push
 * the picked source across a process boundary before the request chain runs, so that the proxy can
 * resolve the URI it is handed — on Android an FD into `ParcelFileDescriptorCache`, over EPC a
 * transport frame. Route 1 links the proxy **in-process**, and the iOS implementations
 * (`drive-upload-source-read.md`) read the source themselves through `IosUploadSourceReader`. There is
 * no boundary, and nothing to push.
 *
 * ⚠⚠ **Why a binding at all, rather than making the ROs' call null-safe.** `TContext.fileUploader()`
 * is `components[ID] as FileUploader` over a `getOrNull` lookup, so an absent binding is a
 * `NullPointerException` on K/N: every route-1 upload and replace, foreground and background,
 * would NPE without it. Softening the call site
 * to `?.upload(...)` would fix iOS by making a **missing uploader on Android silently skip the
 * push** — trading a loud crash on one platform for a silent failed upload on the platform that
 * genuinely needs it. So the requirement stays mandatory and route 1 states, in one line, that its
 * answer is "nothing to do".
 */
internal object InProcessFileUploader : FileUploader {
    override fun upload(uri: String) = Unit
}
