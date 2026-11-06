package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.MemorySegment

/**
 * In-process bridge to macOS ServiceManagement.
 *
 * The caller must be the containing Control.app process: SMAppService resolves a login item
 * relative to the caller's bundle, so the nested login-item executable must only launch Control.
 */
public object MacLoginItemService {
    public const val STATUS_ENABLED: String = "enabled"
    public const val STATUS_REQUIRES_APPROVAL: String = "requiresApproval"
    public const val STATUS_NOT_REGISTERED: String = "notRegistered"
    public const val STATUS_UNKNOWN: String = "unknown"

    public fun isAvailable(): Boolean =
        ObjC.isAvailable && ObjC.framework("ServiceManagement") != null

    public fun status(identifier: String): String? = callService(identifier) { service ->
        when (ObjC.sendLong(service, "status")) {
            0L -> STATUS_NOT_REGISTERED
            1L -> STATUS_ENABLED
            2L -> STATUS_REQUIRES_APPROVAL
            else -> STATUS_UNKNOWN
        }
    }

    public fun register(identifier: String): Boolean = mutateService(identifier, "registerAndReturnError:")

    public fun unregister(identifier: String): Boolean = mutateService(identifier, "unregisterAndReturnError:")

    private fun <T> callService(identifier: String, block: (MemorySegment) -> T): T? =
        if (!isAvailable()) {
            null
        } else {
            runCatching {
                ObjC.autoreleasePool {
                    val service = serviceFor(identifier)
                    if (ObjC.isNull(service)) null else block(service)
                }
            }.getOrNull()
        }

    private fun mutateService(identifier: String, selector: String): Boolean =
        callService(identifier) { service ->
            ObjC.sendBool(service, selector, MemorySegment.NULL)
        } == true

    private fun serviceFor(identifier: String): MemorySegment =
        ObjC.send(
            ObjC.cls("SMAppService"),
            "loginItemServiceWithIdentifier:",
            ObjC.nsString(identifier),
        )
}
