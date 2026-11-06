package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.pureOperationRunner
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.traitResolver

/**
 * The rows a [flow] folds its stream into: the initial snapshot plus every later `Value` /
 * `Removed`, keyed by row identity ([hashOf]) so a changed row replaces itself in place.
 *
 * ⚠⚠ **Each row is hashed exactly once, when it arrives.** [hashOf] is `getDataIdHash(context)` —
 * a trait lookup, a DI resolve and a pure-operation run per call, ~130 µs on a phone — and the
 * snapshot arrives as ONE element carrying the whole list. Re-hashing the rows already held on
 * each arrival made a snapshot cost Σk hashes: 618 rows took 25 s before the first emit, 830 took
 * 45 s. So hashes are cached beside the rows, [indexByHash] answers "is this row already here",
 * and [remove] compares the cached values without calling [hashOf] at all.
 *
 * Identity rules, unchanged by the caching:
 * - a row whose hash is `null` has no identity: it is always appended and never replaced;
 * - a row whose hash is already held replaces that row at its index (order is first arrival),
 *   so no two rows ever share a non-null hash;
 * - [remove] drops the row carrying the removed id's hash, if any.
 *
 * Not thread-safe — [flow]'s collector loop is its sole mutator.
 */
internal class StreamRows<T>(private val hashOf: (T) -> Long?) {
    private val _rows = mutableListOf<T>()
    private val hashes = mutableListOf<Long?>()

    /** Index of the row holding each non-null hash. */
    private val indexByHash = HashMap<Long, Int>()

    val rows: List<T> get() = _rows

    fun merge(incoming: Collection<T>) {
        incoming.forEach { row ->
            val hash = hashOf(row)
            val existingIndex = hash?.let { indexByHash[it] }
            if (existingIndex != null) {
                _rows[existingIndex] = row
            } else {
                if (hash != null) indexByHash[hash] = _rows.size
                _rows.add(row)
                hashes.add(hash)
            }
        }
    }

    fun remove(removedId: DataId) {
        val index = indexByHash[removedId.getDataIdHash()] ?: return
        _rows.removeAt(index)
        hashes.removeAt(index)
        // Removal shifts every later index; rebuild from the cached hashes (no [hashOf] calls).
        indexByHash.clear()
        hashes.forEachIndexed { i, hash -> if (hash != null) indexByHash[hash] = i }
    }

    fun clear() {
        _rows.clear()
        hashes.clear()
        indexByHash.clear()
    }
}

fun Data.getDataIdHash(context: TContext): Long? {
    val provider = context.traitResolver().getTrait<DataIdProvider<Data>>(
        typeId = this.id,
        traitId = DataIdProvider.ID
    ) ?: return null

    val requirement = provider.traitRequirements.toList().find {
        it.schema.isSubtypeOf(DataIdProvider.Requirements.DATA_ID)
    } ?: return null

    val dataId = context.pureOperationRunner().execute<DataId>(requirement, this)
    return dataId.getDataIdHash()
}
