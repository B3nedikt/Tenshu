package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.Flow

/**
 * The figures behind a [org.tenshu.storage.StorageStatus]: whether storage is
 * available at all, and the capacity numbers. Bytes are `Long` (a 50 GiB limit
 * overflows `Int`).
 */
data class StorageFigures(
    val hasStorage: Boolean,
    val usedBytes: Long,
    val limitBytes: Long,
)

/**
 * Proxy-side seam decoupling [StorageService][org.tenshu.storage.StorageService]
 * from where its figures come from (`docs/proposals/storage-capacity-gating.md`
 * §3.1, §3.3). The service reads the **limit from the entitlement** and the
 * **usage from the relay** through this interface and depends on neither's code.
 *
 * - **v1 (mock):** bound in Control's DI to a source that reads the mocked
 *   entitlement figures — `:storage-service` stays free of `:control-shared`.
 * - **later (real):** `usedBytes` from relay-reported per-owner metadata,
 *   `limitBytes` / `hasStorage` from the real `Entitlement`. The binding swaps;
 *   nothing else changes.
 */
interface StorageProvider {
    fun observe(): Flow<StorageFigures>
}
