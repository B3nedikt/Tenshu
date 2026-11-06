package org.tenshu.storage.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tenshu.storage.proxy.StorageFigures
import org.tenshu.storage.proxy.StorageProvider

/**
 * Test [StorageProvider] backed by a [MutableStateFlow] so a test can simulate
 * an entitlement / usage change with [set] and observe the derived
 * [org.tenshu.storage.StorageStatus] react.
 */
class FakeStorageProvider(
    initial: StorageFigures = StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
) : StorageProvider {

    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<StorageFigures> = state.asStateFlow()

    fun set(figures: StorageFigures) {
        state.value = figures
    }
}
