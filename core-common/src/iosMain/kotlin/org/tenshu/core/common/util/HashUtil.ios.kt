package org.tenshu.core.common.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA512

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // SHA-256 of empty input is a well-defined 32-byte value; CC_SHA256 accepts a null
    // pointer with length 0. Without this guard, ByteArray.addressOf(0) on an empty array
    // throws ArrayIndexOutOfBoundsException — JVM/Android handle empty input fine via
    // MessageDigest, so the platform behaviour would otherwise diverge.
    if (data.isEmpty()) {
        digest.usePinned { digestPinned ->
            CC_SHA256(null, 0u, digestPinned.addressOf(0).reinterpret())
        }
        return digest
    }
    data.usePinned { dataPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                dataPinned.addressOf(0),
                data.size.toUInt(),
                digestPinned.addressOf(0).reinterpret()
            )
        }
    }
    return digest
}

@OptIn(ExperimentalForeignApi::class)
actual fun pbkdf2Sha512(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int
): ByteArray {
    val passwordString = password.concatToString()
    val keyLengthBytes = keyLengthBits / 8
    val derivedKey = ByteArray(keyLengthBytes)

    salt.usePinned { saltPinned ->
        derivedKey.usePinned { derivedKeyPinned ->
            CCKeyDerivationPBKDF(
                algorithm = kCCPBKDF2,
                password = passwordString,
                passwordLen = passwordString.encodeToByteArray().size.toULong(),
                salt = saltPinned.addressOf(0).reinterpret(),
                saltLen = salt.size.toULong(),
                prf = kCCPRFHmacAlgSHA512,
                rounds = iterations.toUInt(),
                derivedKey = derivedKeyPinned.addressOf(0).reinterpret(),
                derivedKeyLen = keyLengthBytes.toULong()
            )
        }
    }
    return derivedKey
}
