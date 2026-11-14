package org.tenshu.core.common.proxy.db

/**
 * Whether [this] and [other] would read back as the same row — the store-equality predicate of
 * `core-deterministic-invalidation.md` §8.
 *
 * ⚠ Not `==`: [ObjectStore.DbValue.ByteArrayValue] is a `data class` over a `ByteArray`, so its
 * generated `equals` is **reference** equality, and two byte-identical rows would compare unequal.
 * That fails safe (an unequal row is treated as news), but it would hide every redundant save of a
 * byte-carrying row. Everything else in a [ObjectStore.DbEntry] is structurally comparable.
 */
fun ObjectStore.DbEntry.sameContentAs(other: ObjectStore.DbEntry?): Boolean {
    if (other == null) return false
    if (type != other.type || dataIdHash != other.dataIdHash) return false
    if (fields.size != other.fields.size || properties.size != other.properties.size) return false
    for (i in fields.indices) if (!fields[i].sameContentAs(other.fields[i])) return false
    for ((key, value) in properties) {
        if (!value.sameContentAs(other.properties[key] ?: return false)) return false
    }
    return true
}

private fun ObjectStore.DbValue.sameContentAs(other: ObjectStore.DbValue): Boolean = when (this) {
    is ObjectStore.DbValue.ByteArrayValue ->
        other is ObjectStore.DbValue.ByteArrayValue && value.contentEquals(other.value)
    is ObjectStore.DbValue.GenericValue ->
        other is ObjectStore.DbValue.GenericValue && typeId == other.typeId &&
            payload.sameContentAs(other.payload)
    else -> this == other
}
