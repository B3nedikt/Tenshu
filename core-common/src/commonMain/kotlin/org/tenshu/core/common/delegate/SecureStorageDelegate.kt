package org.tenshu.core.common.delegate

/**
 * Platform-specific delegate for secure key-value storage.
 *
 * Consumed exclusively via Koin constructor injection (e.g. `SyncManagerImpl`); it is not a
 * [org.tenshu.core.core.TComponent] because nothing resolves it from `TContext.components`.
 * See `docs/agent.md` §4 for the TComponent-vs-plain-DI test.
 */
interface SecureStorageDelegate {

    /**
     * Stores a secret string (e.g., recovery phrase) securely.
     */
    fun storeSecret(key: String, value: String)

    /**
     * Retrieves a stored secret string.
     */
    fun getSecret(key: String): String?

    /**
     * Stores a non-sensitive string (e.g., device ID).
     */
    fun storeString(key: String, value: String)

    /**
     * Retrieves a stored string.
     */
    fun getString(key: String): String?

    /**
     * Removes a stored value.
     */
    fun remove(key: String)
}
