package org.tenshu.inference.harness.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState
import platform.UIKit.UIDevice
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.sysctlbyname
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.memcpy

/** What jetsam counts, and the kernel's own high-water mark of it, in bytes. */
internal data class Footprint(val current: Long, val ledgerPeak: Long)

/**
 * The readings IN-3m-iOS takes from the phone around the model (§4.1).
 *
 * ⚠ Behind an interface only so the report's shape is testable on a simulator, where none of the
 * real readings means anything.
 */
internal interface DeviceProbes {
    fun footprint(): Footprint?
    /** `ProcessInfo.thermalState`: 0 nominal, 1 fair, 2 serious, 3 critical. */
    fun thermalState(): Int
    fun lowPowerMode(): Boolean
    /** 0.0 … 1.0, or `null` when the device does not report one. ⚠ 5 % steps: directional only. */
    fun batteryLevel(): Double?
    fun batteryState(): String
    fun freeBytes(path: String): Long?
    fun osBuild(): String
    fun machine(): String
    /** `ProcessInfo.physicalMemory`, in bytes: what D12 ①'s gate reads (§14.2), not a jetsam budget. */
    fun physicalMemory(): Long
}

@OptIn(ExperimentalForeignApi::class)
internal object SystemDeviceProbes : DeviceProbes {

    override fun footprint(): Footprint? = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).convert()
        val result = task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS) Footprint(info.phys_footprint.toLong(), info.ledger_phys_footprint_peak) else null
    }

    override fun thermalState(): Int = NSProcessInfo.processInfo.thermalState.value.toInt()

    override fun lowPowerMode(): Boolean = NSProcessInfo.processInfo.isLowPowerModeEnabled()

    override fun batteryLevel(): Double? {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        return UIDevice.currentDevice.batteryLevel.toDouble().takeIf { it >= 0.0 }
    }

    override fun batteryState(): String {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        return when (UIDevice.currentDevice.batteryState.value.toInt()) {
            1 -> "unplugged"
            2 -> "charging"
            3 -> "full"
            else -> "unknown"
        }
    }

    override fun freeBytes(path: String): Long? =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null)
            ?.get(NSFileSystemFreeSize) as? Number)?.toLong()

    override fun osBuild(): String = NSProcessInfo.processInfo.operatingSystemVersionString

    override fun physicalMemory(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    override fun machine(): String = memScoped {
        val size = alloc<ULongVar>()
        sysctlbyname("hw.machine", null, size.ptr, null, 0u)
        if (size.value == 0uL) return@memScoped "unknown"
        val buffer = allocArray<ByteVar>(size.value.toInt())
        sysctlbyname("hw.machine", buffer, size.ptr, null, 0u)
        buffer.toKString()
    }
}

/** Regular files directly in [directory], by name. Dot-files are not photographs. */
@OptIn(ExperimentalForeignApi::class)
internal fun filesIn(directory: String): List<String> {
    val manager = NSFileManager.defaultManager
    val names: List<*> = manager.contentsOfDirectoryAtPath(directory, error = null) ?: return emptyList()
    return names.map { it as String }
        .filter { !it.startsWith(".") }
        .filter { manager.attributesOfItemAtPath("$directory/$it", error = null)?.get(NSFileType) == NSFileTypeRegular }
        .sorted()
}

@OptIn(ExperimentalForeignApi::class)
internal fun directoryBytes(path: String): Long {
    val manager = NSFileManager.defaultManager
    val children: List<*> = manager.subpathsOfDirectoryAtPath(path, error = null) ?: return 0L
    var total = 0L
    children.forEach { child ->
        val attributes = manager.attributesOfItemAtPath("$path/${child as String}", error = null)
        if (attributes?.get(NSFileType) == NSFileTypeRegular) {
            total += (attributes?.get(NSFileSize) as? Number)?.toLong() ?: 0L
        }
    }
    return total
}

/**
 * The whole file, as the index pass reads a photo (`ImageDecoder` takes bytes, §1.2).
 *
 * ⚠ The pool is the point: `dataWithContentsOfFile` is autoreleased, and the measuring thread has
 * no run loop to drain it, so without one every photo's bytes stay resident until the row ends and
 * the footprint samples measure the corpus rather than the pass.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun readFile(path: String): ByteArray? = autoreleasepool {
    val data: NSData = NSData.dataWithContentsOfFile(path) ?: return@autoreleasepool null
    val size: Int = data.length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    out
}
