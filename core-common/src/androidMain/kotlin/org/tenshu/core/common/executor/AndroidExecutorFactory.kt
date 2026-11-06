package org.tenshu.core.common.executor

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.files.util.ParcelFileDescriptorCache

fun androidImplementationFactory(
    id: String,
    fileDescriptorCache: ParcelFileDescriptorCache,
): OperationImplementation? {
    return null
}
