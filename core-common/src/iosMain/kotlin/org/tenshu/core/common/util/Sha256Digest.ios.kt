package org.tenshu.core.common.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/**
 * iOS incremental SHA-256 over CommonCrypto's streaming context (the same stack the one-shot
 * [sha256] uses, just fed in pieces).
 */
@OptIn(ExperimentalForeignApi::class)
actual class Sha256Digest actual constructor() {

    // Heap-allocated so the context outlives each update() call; freed in digest().
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
    private var finished = false

    actual fun update(bytes: ByteArray, length: Int) {
        check(!finished) { "Sha256Digest already finalised" }
        if (length <= 0) return // CC_SHA256_Update tolerates 0, but addressOf(0) on an empty array throws
        bytes.usePinned { pinned ->
            CC_SHA256_Update(context.ptr, pinned.addressOf(0), length.toUInt())
        }
    }

    actual fun digest(): ByteArray {
        check(!finished) { "Sha256Digest already finalised" }
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { pinned ->
            CC_SHA256_Final(pinned.addressOf(0).reinterpret(), context.ptr)
        }
        finished = true
        nativeHeap.free(context)
        return out
    }
}
