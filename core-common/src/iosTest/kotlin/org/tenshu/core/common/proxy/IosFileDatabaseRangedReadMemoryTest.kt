package org.tenshu.core.common.proxy

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
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
 * [IosFileDatabase.readBytesRange] is the bounded read: a caller walking a large file in windows
 * must hold one window, not the file.
 *
 * ⚠ It did not, on any thread without a run loop: each window's autoreleased `NSData` lived until
 * the thread ended. Found on a phone, where unpacking a 555 MB model archive peaked at 1.1 GB
 * (`docs/proposals/ios-on-device-inference.md` §9).
 */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class IosFileDatabaseRangedReadMemoryTest {

    private val root: String = "${NSTemporaryDirectory()}ios-filedb-memory-${Random.nextLong()}"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun walkingALargeFileInWindowsDoesNotHoldTheFile() {
        val db = IosFileDatabase(root = root)
        val window = ByteArray(WINDOW) { it.toByte() }
        for (i in 0 until WINDOWS) db.writeBytesRange("/big", i.toLong() * WINDOW, window)
        GC.collect()
        val before: Long = footprint()

        // The test body runs on a thread that drains no pool until it returns: the phone's case.
        var read = 0L
        for (i in 0 until WINDOWS) read += db.readBytesRange("/big", i.toLong() * WINDOW, WINDOW)!!.size
        GC.collect()
        val grew: Long = footprint() - before

        assertTrue(read == WINDOWS.toLong() * WINDOW)
        assertTrue(
            grew < WINDOWS.toLong() * WINDOW / 4,
            "reading ${WINDOWS} MiB in 1 MiB windows grew the footprint by ${grew / (1 shl 20)} MiB",
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
        const val WINDOW: Int = 1 shl 20
        const val WINDOWS: Int = 96
    }
}
