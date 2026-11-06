package app.tenshu.sdk.internal

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise

// WebAuthn assertion verifier for Session.unlock. Imports the registered
// SPKI public key into WebCrypto, computes the signed message
// (`authenticatorData || SHA-256(clientDataJson)`), and verifies the
// signature against it. Single-passkey model: the registered credentialId
// must match the assertion's credentialId before verification runs.
//
// COSE algorithms supported: ES256 (-7) and RS256 (-257) — the two the
// apex's `webauthn.ts` requests at registration. ES256 signatures arrive
// as DER-encoded `SEQUENCE { INTEGER r, INTEGER s }`; WebCrypto wants raw
// `r || s` zero-padded to 32 bytes each, so we convert before `verify`.
// RS256 signatures are raw byte strings already.
internal object WebAuthnVerifier {

    sealed interface Result {
        data object Ok : Result
        data object WrongCredential : Result
        data class UnsupportedAlgorithm(val algorithm: Int) : Result
        data class Other(val message: String) : Result
    }

    suspend fun verify(
        registeredCredentialId: Int8Array,
        registeredPublicKeySpki: Int8Array,
        registeredAlgorithm: Int,
        assertionCredentialId: Int8Array,
        clientDataJson: Int8Array,
        authenticatorData: Int8Array,
        signature: Int8Array,
    ): Result {
        if (!bytesEqual(registeredCredentialId, assertionCredentialId)) {
            return Result.WrongCredential
        }

        val params = algorithmParams(registeredAlgorithm)
            ?: return Result.UnsupportedAlgorithm(registeredAlgorithm)

        val subtle: dynamic = js("crypto.subtle")
        val verifyUsages: Array<String> = arrayOf("verify")

        val cryptoKey = try {
            subtle.importKey(
                "spki",
                registeredPublicKeySpki,
                params.importParams,
                false,
                verifyUsages,
            ).unsafeCast<Promise<dynamic>>().await()
        } catch (t: Throwable) {
            return Result.Other("importKey failed: ${t.message ?: t::class.simpleName}")
        }

        val clientDataHash = try {
            subtle.digest("SHA-256", clientDataJson)
                .unsafeCast<Promise<ArrayBuffer>>().await()
        } catch (t: Throwable) {
            return Result.Other("SHA-256 digest failed: ${t.message ?: t::class.simpleName}")
        }
        val message = concat(authenticatorData, Int8Array(clientDataHash))

        val sigForVerify = when (registeredAlgorithm) {
            COSE_ES256 -> derEcdsaToRaw(signature, P256_COMPONENT_SIZE)
                ?: return Result.Other("malformed ECDSA signature")

            else -> signature
        }

        val ok = try {
            subtle.verify(params.verifyParams, cryptoKey, sigForVerify, message)
                .unsafeCast<Promise<Boolean>>().await()
        } catch (t: Throwable) {
            return Result.Other("verify failed: ${t.message ?: t::class.simpleName}")
        }
        return if (ok) Result.Ok else Result.WrongCredential
    }

    private data class AlgorithmParams(val importParams: dynamic, val verifyParams: dynamic)

    private fun algorithmParams(algorithm: Int): AlgorithmParams? = when (algorithm) {
        COSE_ES256 -> AlgorithmParams(
            importParams = js("({ name: 'ECDSA', namedCurve: 'P-256' })"),
            verifyParams = js("({ name: 'ECDSA', hash: 'SHA-256' })"),
        )

        COSE_RS256 -> AlgorithmParams(
            importParams = js("({ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' })"),
            verifyParams = js("({ name: 'RSASSA-PKCS1-v1_5' })"),
        )

        else -> null
    }

    private fun bytesEqual(a: Int8Array, b: Int8Array): Boolean {
        if (a.length != b.length) return false
        for (i in 0 until a.length) {
            if (a.unsafeCast<dynamic>()[i] != b.unsafeCast<dynamic>()[i]) return false
        }
        return true
    }

    private fun concat(a: Int8Array, b: Int8Array): Int8Array {
        val result = Int8Array(a.length + b.length)
        val resultDyn: dynamic = result
        resultDyn.set(a, 0)
        resultDyn.set(b, a.length)
        return result
    }

    // DER `SEQUENCE { INTEGER r, INTEGER s }` → raw `r || s`, each
    // component left-padded to `componentSize` bytes (32 for P-256).
    // Returns null on malformed input. Mirrors the canonical conversion
    // every WebAuthn server library ships — kept hand-rolled here to
    // avoid pulling in a JS dep for one function.
    private fun derEcdsaToRaw(der: Int8Array, componentSize: Int): Int8Array? {
        val view = der.unsafeCast<dynamic>()
        var off = 0
        if (view[off++].unsafeCast<Int>().toUByte().toInt() != 0x30) return null
        val seqLen = readDerLength(view, off) ?: return null
        off += seqLen.consumed
        if (off + seqLen.value > der.length) return null

        val r = readDerInteger(view, off, der.length) ?: return null
        off = r.nextOffset
        val s = readDerInteger(view, off, der.length) ?: return null
        if (s.nextOffset != der.length) return null

        val rRaw = leftPad(r.bytes, componentSize) ?: return null
        val sRaw = leftPad(s.bytes, componentSize) ?: return null

        val out = Int8Array(componentSize * 2)
        val outDyn: dynamic = out
        outDyn.set(rRaw, 0)
        outDyn.set(sRaw, componentSize)
        return out
    }

    private data class DerLength(val value: Int, val consumed: Int)

    private fun readDerLength(view: dynamic, offset: Int): DerLength? {
        val first = view[offset].unsafeCast<Int>().toUByte().toInt()
        if (first < 0x80) return DerLength(first, 1)
        val numBytes = first and 0x7f
        if (numBytes == 0 || numBytes > 4) return null
        var len = 0
        for (i in 0 until numBytes) {
            len = (len shl 8) or view[offset + 1 + i].unsafeCast<Int>().toUByte().toInt()
        }
        return DerLength(len, 1 + numBytes)
    }

    private data class DerInteger(val bytes: Int8Array, val nextOffset: Int)

    private fun readDerInteger(view: dynamic, offset: Int, total: Int): DerInteger? {
        if (offset >= total) return null
        if (view[offset].unsafeCast<Int>().toUByte().toInt() != 0x02) return null
        val len = readDerLength(view, offset + 1) ?: return null
        val start = offset + 1 + len.consumed
        val end = start + len.value
        if (end > total) return null
        // Strip the optional leading 0x00 sign byte ASN.1 inserts when the
        // high bit of the first byte is set (i.e. when the integer would
        // otherwise be parsed as negative).
        val effectiveStart = if (
            len.value > 1 && view[start].unsafeCast<Int>().toUByte().toInt() == 0x00
        ) start + 1 else start
        val bytes = sliceInt8(view, effectiveStart, end)
        return DerInteger(bytes, end)
    }

    private fun sliceInt8(view: dynamic, start: Int, end: Int): Int8Array {
        // `Int8Array.subarray(start, end)` returns a view over the same
        // buffer. We want a fresh allocation since the caller may pad it.
        val len = end - start
        val out = Int8Array(len)
        val outDyn: dynamic = out
        outDyn.set(view.subarray(start, end), 0)
        return out
    }

    private fun leftPad(bytes: Int8Array, target: Int): Int8Array? {
        if (bytes.length > target) return null
        if (bytes.length == target) return bytes
        val out = Int8Array(target)
        val outDyn: dynamic = out
        outDyn.set(bytes, target - bytes.length)
        return out
    }

    private const val COSE_ES256 = -7
    private const val COSE_RS256 = -257
    private const val P256_COMPONENT_SIZE = 32
}
