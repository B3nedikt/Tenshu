package org.tenshu.core.common.util

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun generateId(): String {
    return Uuid.random().toString()
}

fun idToUuid(id: String): ByteArray {
    val namespace = nameUUIDFromBytes("tenshu".encodeToByteArray())
    val name = id.encodeToByteArray()
    val combined = namespace + name
    val hash = md5Hash(combined)

    // Set version to 3 (name-based UUID using MD5)
    hash[6] = (hash[6].toInt() and 0x0F or 0x30).toByte()

    // Set variant to IETF
    hash[8] = (hash[8].toInt() and 0x3F or 0x80).toByte()

    return hash.copyOfRange(0, 16)
}

@OptIn(ExperimentalUuidApi::class)
fun idToKotlinUuid(id: String): Uuid {
    return Uuid.fromByteArray(idToUuid(id))
}

private fun nameUUIDFromBytes(name: ByteArray): ByteArray {
    val hash = md5Hash(name)

    // Set version to 3 (name-based UUID using MD5)
    hash[6] = (hash[6].toInt() and 0x0F or 0x30).toByte()

    // Set variant to IETF
    hash[8] = (hash[8].toInt() and 0x3F or 0x80).toByte()

    return hash.copyOfRange(0, 16)
}

