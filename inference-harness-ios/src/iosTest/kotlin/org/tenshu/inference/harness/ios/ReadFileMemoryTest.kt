package org.tenshu.inference.harness.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [readFile] is called once per corpus photo from a thread with no run loop, and the row's
 * footprint samples are what set the device floor (D12). A read that leaves each photo's
 * autoreleased `NSData` resident measures the corpus, not the pass.
 */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class ReadFileMemoryTest {

    private val path: String = "${NSTemporaryDirectory()}in3m-readfile-${Random.nextLong()}.bin"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    @Test
    fun readingManyPhotosDoesNotAccumulateTheirBytes() {
        val photo = ByteArray(PHOTO) { it.toByte() }
        val written: Boolean = photo.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = PHOTO.convert()).writeToFile(path, atomically = true)
        }
        assertTrue(written)
        GC.collect()
        val before: Long = footprint()

        var read = 0L
        for (i in 0 until PHOTOS) read += readFile(path)!!.size
        GC.collect()
        val grew: Long = footprint() - before

        assertTrue(read == PHOTOS.toLong() * PHOTO)
        assertTrue(
            grew < PHOTOS.toLong() * PHOTO / 4,
            "reading $PHOTOS photos of ${PHOTO shr 20} MiB grew the footprint by ${grew shr 20} MiB",
        )
    }

    private fun footprint(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).convert()
        check(task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr) == KERN_SUCCESS)
        info.phys_footprint.toLong()
    }

    private companion object {
        const val PHOTO: Int = 4 shl 20
        const val PHOTOS: Int = 48
    }
}
