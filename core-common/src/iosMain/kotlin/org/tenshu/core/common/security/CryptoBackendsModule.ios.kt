package org.tenshu.core.common.security

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.koin.core.module.Module
import org.koin.dsl.module
import org.tenshu.core.common.security.libsodium.argon2id_hash_raw
import org.tenshu.core.common.security.libsodium.crypto_aead_xchacha20poly1305_ietf_decrypt
import org.tenshu.core.common.security.libsodium.crypto_aead_xchacha20poly1305_ietf_encrypt
import org.tenshu.core.common.security.libsodium.crypto_scalarmult
import org.tenshu.core.common.security.libsodium.crypto_scalarmult_base
import org.tenshu.core.common.security.libsodium.crypto_sign_ed25519_detached
import org.tenshu.core.common.security.libsodium.crypto_sign_ed25519_seed_keypair
import org.tenshu.core.common.security.libsodium.crypto_sign_ed25519_verify_detached
import org.tenshu.core.common.security.libsodium.randombytes_buf
import org.tenshu.core.common.security.libsodium.sodium_init

// iOS crypto backends, libsodium-backed (Phase 6a of
// docs/proposals/ios-proxy-architecture.md). These replace the throwing
// stubs that satisfied the `expect val` while no iOS app consumed the SDK.
//
// Why libsodium (and not CryptoKit): CryptoKit is Swift-only and not
// reachable from Kotlin/Native's Objective-C interop. libsodium is a single
// C library that covers all four primitives this module needs, and produces
// bytes identical to the JVM (BouncyCastle) and JS (@noble) backends, so an
// iOS client and a desktop/Android proxy interoperate over the same wire
// formats. The static lib is built per-target from pinned source by
// scripts/build-libsodium-apple.sh and bound through
// src/nativeInterop/cinterop/libsodium.def.
//
// All inputs/outputs use the canonical sizes from the backend interfaces
// (`Ed25519Backend`, `X25519Backend`, `AeadBackend`, `KdfBackend`); anything
// off-size throws rather than silently truncating, matching the JVM impls.

// libsodium must be initialised once before any other call (it bootstraps
// the CSPRNG and runtime feature detection). `sodium_init()` is idempotent
// (returns 1 if already initialised) and the `lazy` is SYNCHRONIZED, so this
// is safe across the multiple backend instances Koin may construct.
@OptIn(ExperimentalForeignApi::class)
private val sodiumReady: Unit by lazy {
    check(sodium_init() >= 0) { "libsodium failed to initialise (sodium_init returned < 0)" }
}

@OptIn(ExperimentalForeignApi::class)
internal class LibsodiumEd25519Backend : Ed25519Backend {

    override fun sign(privateKeySeed: ByteArray, message: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        sodiumReady
        // libsodium signs with the 64-byte secret key (seed || public);
        // expand the 32-byte seed first to match the RFC 8032 contract.
        val secretKey = expandSeedToSecretKey(privateKeySeed)
        val signature = ByteArray(Ed25519Backend.ED25519_SIGNATURE_SIZE)
        signature.usePinned { sig ->
            secretKey.usePinned { sk ->
                message.withUBytePtr { msgPtr ->
                    crypto_sign_ed25519_detached(
                        sig.addressOf(0).reinterpret(),
                        null,
                        msgPtr,
                        message.size.convert(),
                        sk.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        return signature
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519Backend.ED25519_PUBLIC_KEY_SIZE) return false
        if (signature.size != Ed25519Backend.ED25519_SIGNATURE_SIZE) return false
        sodiumReady
        val result = signature.usePinned { sig ->
            publicKey.usePinned { pk ->
                message.withUBytePtr { msgPtr ->
                    crypto_sign_ed25519_verify_detached(
                        sig.addressOf(0).reinterpret(),
                        msgPtr,
                        message.size.convert(),
                        pk.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        return result == 0
    }

    override fun derivePublicKey(privateKeySeed: ByteArray): ByteArray {
        require(privateKeySeed.size == Ed25519Backend.ED25519_SEED_SIZE) {
            "Ed25519 seed must be ${Ed25519Backend.ED25519_SEED_SIZE} bytes; got ${privateKeySeed.size}"
        }
        sodiumReady
        return derivePublicKeyFromSeed(privateKeySeed)
    }

    // crypto_sign_ed25519_seed_keypair derives both halves from the seed;
    // sign() needs the 64-byte secret key, derivePublicKey() the 32-byte
    // public key. Share the one call.
    private fun expandSeedToSecretKey(seed: ByteArray): ByteArray {
        val secretKey = ByteArray(ED25519_SECRET_KEY_SIZE)
        val publicKey = ByteArray(Ed25519Backend.ED25519_PUBLIC_KEY_SIZE)
        seedKeypair(seed, secretKey, publicKey)
        return secretKey
    }

    private fun derivePublicKeyFromSeed(seed: ByteArray): ByteArray {
        val secretKey = ByteArray(ED25519_SECRET_KEY_SIZE)
        val publicKey = ByteArray(Ed25519Backend.ED25519_PUBLIC_KEY_SIZE)
        seedKeypair(seed, secretKey, publicKey)
        return publicKey
    }

    private fun seedKeypair(seed: ByteArray, secretKeyOut: ByteArray, publicKeyOut: ByteArray) {
        secretKeyOut.usePinned { sk ->
            publicKeyOut.usePinned { pk ->
                seed.usePinned { s ->
                    crypto_sign_ed25519_seed_keypair(
                        pk.addressOf(0).reinterpret(),
                        sk.addressOf(0).reinterpret(),
                        s.addressOf(0).reinterpret(),
                    )
                }
            }
        }
    }

    private companion object {
        // RFC 8032 §5.1.5: libsodium's expanded secret key is seed || public.
        const val ED25519_SECRET_KEY_SIZE = 64
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class LibsodiumX25519Backend : X25519Backend {

    override fun generateKeyPair(): SyncKeyPair {
        sodiumReady
        val privateKey = ByteArray(X25519Backend.X25519_KEY_SIZE)
        privateKey.usePinned { priv ->
            // randombytes_buf takes void* — pass the CPointer<ByteVar> directly.
            randombytes_buf(priv.addressOf(0), privateKey.size.convert())
        }
        return SyncKeyPair(publicKey = scalarMultBase(privateKey), privateKey = privateKey)
    }

    override fun deriveKeyPair(seed: ByteArray): SyncKeyPair {
        require(seed.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 seed must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${seed.size}"
        }
        sodiumReady
        // The 32 bytes are the raw scalar; libsodium clamps on use (RFC 7748),
        // exactly as BouncyCastle does, so the stored private key round-trips
        // through `calculateSharedSecret` and the derived public key matches
        // the JVM/JS backends for the same scalar.
        val privateKey = seed.copyOf()
        return SyncKeyPair(publicKey = scalarMultBase(privateKey), privateKey = privateKey)
    }

    override fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 private key must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${privateKey.size}"
        }
        require(otherPublicKey.size == X25519Backend.X25519_KEY_SIZE) {
            "X25519 public key must be ${X25519Backend.X25519_KEY_SIZE} bytes; got ${otherPublicKey.size}"
        }
        sodiumReady
        val shared = ByteArray(X25519Backend.X25519_SHARED_SECRET_SIZE)
        val result = shared.usePinned { out ->
            privateKey.usePinned { priv ->
                otherPublicKey.usePinned { pub ->
                    crypto_scalarmult(
                        out.addressOf(0).reinterpret(),
                        priv.addressOf(0).reinterpret(),
                        pub.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        // -1 means the peer public key is a low-order point (the shared secret
        // would be all-zero). Reject rather than hand back a degenerate key —
        // a malformed or hostile peer key in the handshake.
        require(result == 0) { "X25519 shared secret: peer public key is a low-order point" }
        return shared
    }

    private fun scalarMultBase(privateKey: ByteArray): ByteArray {
        val publicKey = ByteArray(X25519Backend.X25519_KEY_SIZE)
        publicKey.usePinned { pub ->
            privateKey.usePinned { priv ->
                crypto_scalarmult_base(
                    pub.addressOf(0).reinterpret(),
                    priv.addressOf(0).reinterpret(),
                )
            }
        }
        return publicKey
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class LibsodiumAeadBackend : AeadBackend {

    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        sodiumReady
        val nonce = ByteArray(AeadBackend.AEAD_NONCE_SIZE)
        nonce.usePinned { n ->
            randombytes_buf(n.addressOf(0), nonce.size.convert())
        }
        // libsodium combined mode writes ciphertext || tag (|plaintext| + 16).
        val ctAndTag = ByteArray(plaintext.size + AeadBackend.AEAD_TAG_SIZE)
        ctAndTag.usePinned { c ->
            plaintext.withUBytePtr { mPtr ->
                nonce.usePinned { n ->
                    key.usePinned { k ->
                        crypto_aead_xchacha20poly1305_ietf_encrypt(
                            c.addressOf(0).reinterpret(),
                            null,
                            mPtr,
                            plaintext.size.convert(),
                            null, // no additional data
                            0u,
                            null, // nsec is unused for this construction
                            n.addressOf(0).reinterpret(),
                            k.addressOf(0).reinterpret(),
                        )
                    }
                }
            }
        }
        // Output layout: nonce (24) || ciphertext (|plaintext|) || tag (16) —
        // the self-contained format the AeadBackend contract specifies.
        return nonce + ctAndTag
    }

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        require(ciphertext.size >= AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE) {
            "AEAD ciphertext too short: ${ciphertext.size} < ${AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE}"
        }
        sodiumReady
        val nonce = ciphertext.copyOfRange(0, AeadBackend.AEAD_NONCE_SIZE)
        val body = ciphertext.copyOfRange(AeadBackend.AEAD_NONCE_SIZE, ciphertext.size)
        val plaintext = ByteArray(body.size - AeadBackend.AEAD_TAG_SIZE)
        val result = plaintext.withUBytePtr { mPtr ->
            body.usePinned { c ->
                nonce.usePinned { n ->
                    key.usePinned { k ->
                        crypto_aead_xchacha20poly1305_ietf_decrypt(
                            mPtr,
                            null,
                            null, // nsec unused
                            c.addressOf(0).reinterpret(),
                            body.size.convert(),
                            null, // no additional data
                            0u,
                            n.addressOf(0).reinterpret(),
                            k.addressOf(0).reinterpret(),
                        )
                    }
                }
            }
        }
        if (result != 0) {
            throw AeadAuthenticationException(
                "XChaCha20-Poly1305 decrypt failed: authentication tag mismatch",
            )
        }
        return plaintext
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class LibsodiumKdfBackend : KdfBackend {

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        memoryKiB: Int,
        iterations: Int,
        outputLength: Int,
    ): ByteArray {
        sodiumReady
        // We bind the reference phc-argon2 `argon2id_hash_raw` rather than
        // libsodium's `crypto_pwhash`, which is locked to a 16-byte salt; our
        // recovery KDF uses a 22-byte domain-tagged salt. `argon2id_hash_raw`
        // accepts arbitrary salt + parallelism and is byte-identical to the
        // BouncyCastle/@noble RFC 9106 v0x13 implementations. See libsodium.def.
        val out = ByteArray(outputLength)
        val result = out.withUBytePtr { outPtr ->
            password.withUBytePtr { pwdPtr ->
                salt.withUBytePtr { saltPtr ->
                    argon2id_hash_raw(
                        iterations.convert(),
                        memoryKiB.convert(),
                        parallelism.convert(),
                        pwdPtr,
                        password.size.convert(),
                        saltPtr,
                        salt.size.convert(),
                        outPtr,
                        outputLength.convert(),
                    )
                }
            }
        }
        // ARGON2_OK == 0; anything else is an out-of-range cost parameter
        // (salt too short, output too short, memory below the floor, …).
        require(result == 0) {
            "Argon2id failed (code $result): check salt/output length and cost parameters"
        }
        return out
    }
}

// Pins [this] and hands the block a `const unsigned char *` view, passing a
// null pointer for an empty array — `addressOf(0)` throws on an empty
// Kotlin/Native array, and libsodium accepts NULL with a zero length for the
// message/plaintext/ad/output arguments.
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.withUBytePtr(block: (CPointer<UByteVar>?) -> R): R =
    if (isEmpty()) {
        block(null)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>()) }
    }

actual fun cryptoBackendsModule(): Module = module {
    single<Ed25519Backend> { LibsodiumEd25519Backend() }
    single<X25519Backend> { LibsodiumX25519Backend() }
    single<AeadBackend> { LibsodiumAeadBackend() }
    single<KdfBackend> { LibsodiumKdfBackend() }
}
