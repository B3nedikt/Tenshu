package org.tenshu.core.common.delegate

class InMemorySecureStorageDelegate : SecureStorageDelegate {
    private val storage = mutableMapOf<String, String>()

    override fun storeSecret(key: String, value: String) {
        storage[key] = value
    }

    override fun getSecret(key: String): String? {
        return storage[key]
    }

    override fun storeString(key: String, value: String) {
        storage[key] = value
    }

    override fun getString(key: String): String? {
        return storage[key]
    }

    override fun remove(key: String) {
        storage.remove(key)
    }
}
