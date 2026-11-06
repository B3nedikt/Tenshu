package org.tenshu.core

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Out-of-band channel for uploading a file (referenced by URI) from the client to
 * the proxy.
 *
 * Used by Operation ROs whose corresponding proxy implementations need access to a
 * file that is too large or otherwise unfit to travel inside the request chain.
 * The file is pushed before the chain is executed; the proxy-side cache (e.g.
 * `ParcelFileDescriptorCache`) is populated as a side effect and the chain
 * references the file by its URI.
 */
interface FileUploader : TComponent {

    fun upload(uri: String)

    companion object {
        const val COMPONENT_ID = "core/FILE_UPLOADER"
    }
}

fun TContext.fileUploader(): FileUploader =
    components[FileUploader.COMPONENT_ID] as FileUploader
