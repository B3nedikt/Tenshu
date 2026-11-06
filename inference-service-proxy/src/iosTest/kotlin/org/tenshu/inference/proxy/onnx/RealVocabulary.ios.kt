package org.tenshu.inference.proxy.onnx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun realVocabularyBytes(): ByteArray? = hostFileBytes("TENSHU_INFERENCE_TOKENIZER")

/** The bytes of the host file whose path Gradle put in [variable], or `null`. The simulator shares the host's filesystem. */
@OptIn(ExperimentalForeignApi::class)
internal fun hostFileBytes(variable: String): ByteArray? {
    val path: String = NSProcessInfo.processInfo.environment[variable] as? String ?: return null
    return autoreleasepool {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return@autoreleasepool null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length.convert()) }
        bytes
    }
}
