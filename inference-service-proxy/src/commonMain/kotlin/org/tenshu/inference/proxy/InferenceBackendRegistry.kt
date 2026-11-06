package org.tenshu.inference.proxy

import org.tenshu.inference.InferenceCapability

/**
 * Which backend, if any, can serve a capability on this device (IN-1).
 *
 * ⚠⚠ **"Nothing can do this here" is the normal answer, not an error.** On a device with no model
 * downloaded — which is every device until IN-2 — every capability is unavailable, and the product
 * has to read as *a feature that has not been set up* rather than as a broken one. Consumers ask
 * [backendFor] and get `null`; nobody throws.
 *
 * ⚠ Order matters: the first registered backend that supports a capability wins, so a platform
 * binding a specialised backend (iOS Vision for OCR) registers it **before** the general one.
 */
class InferenceBackendRegistry(backends: List<InferenceBackend> = emptyList()) {

    private val backends = backends.toList()

    fun backendFor(capability: InferenceCapability): InferenceBackend? =
        backends.firstOrNull { it.supports(capability) }

    fun isAvailable(capability: InferenceCapability): Boolean = backendFor(capability) != null

    /** What this device could do if the user consented — the honest half of any Settings copy. */
    val available: Set<InferenceCapability>
        get() = InferenceCapability.entries.filterTo(mutableSetOf()) { isAvailable(it) }

    companion object {
        /** ⚠ The production default until IN-2 lands a model: nothing is available anywhere. */
        val Empty = InferenceBackendRegistry()
    }
}
