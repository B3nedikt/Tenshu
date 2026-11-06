package org.tenshu.inference.proxy.coreml

import kotlin.concurrent.AtomicLong
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import org.tenshu.inference.proxy.PreflightDevice
import org.tenshu.inference.proxy.PreflightTower
import org.tenshu.inference.proxy.PreflightTowers
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.thermalState
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.dlsym

/**
 * The preflight's towers: the same compiled models, the same guards and the same compute units
 * the backend would open, through [open], and **not** through the backend
 * (`ios-on-device-inference.md` §7 D12 ②). A model that loads and is another model is thrown by
 * [CoreMlSession.open] like one that does not load: either way it is not a tower to probe.
 */
class CoreMlPreflightTowers(
    private val open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    private val model: VisionModel = VisionModel.PinnedSigLip,
) : PreflightTowers {

    override fun openVision(compiledPath: String): PreflightTower = Tower(open(compiledPath, CoreMlTowerSpec.vision(model)))

    override fun openText(compiledPath: String): PreflightTower = Tower(open(compiledPath, CoreMlTowerSpec.text(model)))

    private class Tower(private val session: CoreMlSessionPort) : PreflightTower {
        override fun predict(floats: FloatArray): FloatArray = session.predict(floats)

        override fun predict(ids: IntArray): FloatArray = session.predict(ids)

        override fun close() = session.close()
    }
}

/**
 * This phone, as the preflight reads it.
 *
 * ⚠ None of it means anything on a simulator: the thermal state is the Mac's, and the footprint
 * and the limit are a Mac process's.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosPreflightDevice : PreflightDevice {

    private val warnings = AtomicLong(0)

    /**
     * ⚠ Registered by the first read, which is the start of the first probe: a warning during
     * that probe is counted. With no queue the block runs on the posting thread, so nothing
     * here waits for the main run loop.
     */
    private val observing: Any by lazy {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidReceiveMemoryWarningNotification,
            `object` = null,
            queue = null,
        ) { _ -> warnings.incrementAndGet() }
    }

    override fun thermalState(): Int = NSProcessInfo.processInfo.thermalState.value.toInt()

    override fun memoryWarnings(): Long {
        observing
        return warnings.value
    }

    override fun footprintBytes(): Long? = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).convert()
        val result = task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS) info.phys_footprint.toLong() else null
    }

    /**
     * `os_proc_available_memory()`: what the process may still allocate before its limit.
     *
     * ⚠ Looked up by name: Kotlin/Native's platform libraries do not carry `<os/proc.h>`. A
     * system without the symbol, and the 0 the call returns where there is no limit (a Mac, the
     * simulator), both read as unknown, which the judge does not hold against the phone.
     */
    override fun availableBytes(): Long? {
        val symbol: CPointer<COpaque> = dlsym(RTLD_DEFAULT, "os_proc_available_memory")?.reinterpret() ?: return null
        val available: ULong = symbol.reinterpret<CFunction<() -> ULong>>()()
        return available.toLong().takeIf { it > 0 }
    }

    override fun nowEpochMs(): Long = autoreleasepool { (NSDate().timeIntervalSince1970 * 1000.0).toLong() }

    /** `RTLD_DEFAULT` on Darwin, `((void *) -2)`: a macro the platform library does not carry. */
    private val RTLD_DEFAULT: CPointer<COpaque>? = (-2L).toCPointer()
}
