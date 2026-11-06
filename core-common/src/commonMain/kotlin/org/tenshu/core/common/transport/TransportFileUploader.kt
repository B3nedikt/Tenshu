package org.tenshu.core.common.transport

import org.tenshu.core.FileUploader

class TransportFileUploader(
    private val transport: TaskTransport
) : FileUploader {
    override fun upload(uri: String) = transport.sendFile(uri)
}
