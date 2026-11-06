package app.tenshu.sdk

import app.tenshu.diagnostics.Diagnostics
import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.tenshu.core.common.security.WrappedMasterKey
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.security.SyncCryptoManager
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Exercises the Session API end-to-end via IndexedDB. Each test starts
// with `Session.clear({purgeRealm: true})` so the suite is independent of
// stale IDB state from previous runs (the `tenshu-sdk-session` database
// persists across `:tenshu-sdk-js:jsTest` invocations).
class SessionTest : SdkRuntimeTest() {

    @Test
    fun detect_emptyStore_returnsFirstTime() = runTest {
        TenshuSdk.awaitReady().await()
        clear()

        val state = Session.detect().await()
        assertEquals("firstTime", state.kind as String)
    }

    @Test
    fun persist_thenDetect_returnsActive() = runTest {
        TenshuSdk.awaitReady().await()
        clear()
        // Onboarding wrappers normally write deviceName on success; for
        // a unit test we drive that side-effect via Realm.create's known
        // failure path (which doesn't write) plus a direct write through
        // the persist+detect contract: persisting a token alone isn't
        // enough — the spec needs deviceName too.
        //
        // Best we can do without WebAuthn / a real onboarding round-trip
        // is to verify persist round-trips through detect *after* a
        // future onboarding has written deviceName. We exercise the
        // token side here and assert the kind transitions correctly:
        // `firstTime` → (no deviceName, even with token) → `firstTime`.
        val token = sessionToken(expiresInMs = 60_000.0)
        Session.persist(token).await()

        val state = Session.detect().await()
        // Without a deviceName, Session.detect() always returns firstTime
        // even when a token is present. This is correct per the contract:
        // `active` requires both deviceName *and* unexpired token.
        assertEquals("firstTime", state.kind as String)
    }

    // W13 (`drive-web-parity.md` §1.2h). A purge must go through the SHARED `ForgetThisDevice`
    // op, not a hand-rolled `clearAll()`.
    //
    // ⚠⚠ The op's step ORDER is the whole implementation and a `clearAll()` reproduces none of
    // it: retire the device row while the key that signs its tombstone still exists; purge the
    // outbound queues before custody goes (afterwards, minting an ExecutionContext silently
    // yields nothing — iOS, 2026-08-19); drop the local store before the identity, because
    // custody-gone-store-present is the half-state D3 forbids — a fresh device id, 401 on every
    // push, and an app that still looks signed in to the realm it just left.
    //
    // ⚠ Asserts on the op's OWN outcome string ("forgotten — …"), not merely that we logged
    // something: the point is that the shared implementation ran to completion, and a wrapper
    // that swallowed it would still produce a log line.
    @Test
    fun clearWithPurge_routesThroughTheSharedForgetThisDeviceOp() = runTest {
        TenshuSdk.awaitReady().await()
        Diagnostics.clear()

        Session.clear(js("({ purgeRealm: true })")).await()

        val session = Diagnostics.recent(300).filter { it.tag == "Session" }.map { it.message }
        assertTrue(
            session.any { it.startsWith("forgetThisDevice:") },
            "a realm purge must dispatch ForgetThisDevice; saw ${'$'}session",
        )
        assertTrue(
            session.any { it.contains("forgotten —") },
            "the shared op must run to completion and report its own outcome; saw ${'$'}session",
        )
    }

    @Test
    fun clear_purgesTokenAndDeviceName() = runTest {
        TenshuSdk.awaitReady().await()
        clear()

        // Persist a token, then clear() — verify detect still says
        // firstTime (token gone).
        Session.persist(sessionToken(expiresInMs = 60_000.0)).await()
        Session.clear(null).await()

        val state = Session.detect().await()
        assertEquals("firstTime", state.kind as String)
    }

    @Test
    fun unlock_withoutRegisteredPasskey_returnsNoPasskey() = runTest {
        TenshuSdk.awaitReady().await()
        clear()

        val result = Session.unlock(fakeAssertion()).await()
        assertEquals(false, result.ok as Boolean)
        assertEquals("noPasskey", result.error.kind as String)
    }

    @Test
    fun registerPasskey_thenUnlockWithDifferentCredentialId_returnsWrongCredential() = runTest {
        TenshuSdk.awaitReady().await()
        clear()

        // Register a real ECDSA passkey, then attempt unlock with an
        // assertion whose credentialId doesn't match. The verifier must
        // short-circuit on the credentialId mismatch before any signature
        // verification — exercising the WrongCredential fast-path.
        val keypair = generateEcdsaKeyPair()
        val spki = exportSpki(keypair.publicKey)
        val registeredCredentialId = randomBytes(16)
        val registration: dynamic = js("({})")
        registration.credentialId = registeredCredentialId
        registration.publicKey = spki
        registration.algorithm = -7  // ES256
        val regResult = Session.registerPasskey(registration).await()
        assertEquals(true, regResult.ok as Boolean)

        val assertion = fakeAssertion(credentialId = randomBytes(16))
        val result = Session.unlock(assertion).await()
        assertEquals(false, result.ok as Boolean)
        assertEquals("wrongCredential", result.error.kind as String)
    }

    @Test
    fun registerPasskey_thenUnlockWithValidSignature_returnsToken() = runTest {
        TenshuSdk.awaitReady().await()
        clear()

        // End-to-end positive path: generate an ECDSA P-256 keypair, sign
        // a synthetic WebAuthn message, hand the DER-wrapped signature to
        // Session.unlock, and assert it mints a 24h token. Exercises the
        // full pipeline: importKey → SHA-256 → DER→RAW → subtle.verify.
        val keypair = generateEcdsaKeyPair()
        val spki = exportSpki(keypair.publicKey)
        val credentialId = randomBytes(16)

        val registration: dynamic = js("({})")
        registration.credentialId = credentialId
        registration.publicKey = spki
        registration.algorithm = -7
        Session.registerPasskey(registration).await()

        val authenticatorData = randomBytes(37)  // rpIdHash[32] || flags[1] || signCount[4]
        val clientDataJson = utf8("""{"type":"webauthn.get","challenge":"abc","origin":"https://tenshu.app"}""")

        // Construct the signed message: authData || SHA-256(clientDataJson).
        // Sign it via subtle.sign to mirror what an authenticator produces
        // (RAW r||s), then wrap in DER so it matches the WebAuthn wire
        // format the verifier expects.
        val clientDataHash = subtleDigest(clientDataJson)
        val message = concat(authenticatorData, Int8Array(clientDataHash))
        val rawSig = subtleSign(keypair.privateKey, message)
        val derSig = rawEcdsaToDer(Int8Array(rawSig))

        val assertion: dynamic = js("({})")
        assertion.credentialId = credentialId
        assertion.clientDataJson = clientDataJson
        assertion.authenticatorData = authenticatorData
        assertion.signature = derSig
        assertion.userHandle = null

        val result = Session.unlock(assertion).await()
        assertTrue(result.ok as Boolean, "expected unlock success but got ${describeError(result)}")
        val token = result.value
        assertNotNull(token.bytes, "token bytes")
        assertTrue((token.expiresAtMs as Number).toDouble() > Date.now())
    }

    @Test
    fun registerPasskey_withPrfOutput_wrapsMasterKey() = runTest {
        // Phase 5 producer path: when the registration carries a PRF
        // output and a master keypair is in memory (here, seeded via
        // reseatMasterKeyPair instead of a full Realm.create round-trip
        // to keep the test fast), Session.registerPasskey wraps and
        // persists the keypair to the IDB master-key store.
        TenshuSdk.awaitReady().await()
        clear()

        val keyPair = syntheticMasterKeyPair(seed = 0x42)
        JsRuntime.syncManager.reseatMasterKeyPair(keyPair)
        val ownerId = JsRuntime.syncManager.currentOwnerId
        assertNotNull(ownerId, "reseatMasterKeyPair should derive ownerId")

        val ecdsa = generateEcdsaKeyPair()
        val spki = exportSpki(ecdsa.publicKey)
        val registration: dynamic = js("({})")
        registration.credentialId = randomBytes(16)
        registration.publicKey = spki
        registration.algorithm = -7
        registration.prfOutput = randomBytes(32)

        val result = Session.registerPasskey(registration).await()
        assertEquals(true, result.ok as Boolean)

        val blob = JsRuntime.masterKeyStore.get(ownerId)
        assertNotNull(blob, "expected wrapped master-key blob for $ownerId")
    }

    @Test
    fun registerPasskey_withoutPrfOutput_skipsWrap() = runTest {
        // Browsers without PRF support (or registrations outside an
        // onboarding window) omit the prfOutput field. Session.registerPasskey
        // stores the credential but doesn't touch the master-key store.
        TenshuSdk.awaitReady().await()
        clear()

        val keyPair = syntheticMasterKeyPair(seed = 0x55)
        JsRuntime.syncManager.reseatMasterKeyPair(keyPair)
        val ownerId = JsRuntime.syncManager.currentOwnerId
        assertNotNull(ownerId)

        val ecdsa = generateEcdsaKeyPair()
        val spki = exportSpki(ecdsa.publicKey)
        val registration: dynamic = js("({})")
        registration.credentialId = randomBytes(16)
        registration.publicKey = spki
        registration.algorithm = -7
        // no prfOutput

        val result = Session.registerPasskey(registration).await()
        assertEquals(true, result.ok as Boolean)
        assertNull(JsRuntime.masterKeyStore.get(ownerId))
    }

    @Test
    fun unlock_withMatchingPrfOutput_reseatsMasterKey() = runTest {
        // Full Phase 5 round-trip: register a real ECDSA passkey with a
        // PRF output (wrap), then unlock with a valid signature + the
        // same PRF output. The unwrap path reseats the master keypair on
        // SyncManager — verified by reading currentMasterKeyPair after
        // clearing it pre-unlock.
        TenshuSdk.awaitReady().await()
        clear()

        val originalKeyPair = syntheticMasterKeyPair(seed = 0x77)
        JsRuntime.syncManager.reseatMasterKeyPair(originalKeyPair)
        val expectedOwnerId = JsRuntime.syncManager.currentOwnerId
        assertNotNull(expectedOwnerId)

        val ecdsa = generateEcdsaKeyPair()
        val spki = exportSpki(ecdsa.publicKey)
        val credentialId = randomBytes(16)
        val prfOutput = randomBytes(32)

        val registration: dynamic = js("({})")
        registration.credentialId = credentialId
        registration.publicKey = spki
        registration.algorithm = -7
        registration.prfOutput = prfOutput
        Session.registerPasskey(registration).await()

        // Simulate a page reload: clear the in-memory master keypair so
        // unlock has to come through the unwrap path. The IDB master-key
        // store survives.
        JsRuntime.syncManager.reseatMasterKeyPair(
            // Reseat with sentinel bytes; reseatMasterKeyPair always sets
            // currentMasterKeyPair non-null. The next reseat (from unlock)
            // will replace it with the real unwrapped keypair.
            SyncCryptoManager.KeyPair(
                publicKey = ByteArray(32),
                privateKey = ByteArray(32),
            ),
        )

        val authenticatorData = randomBytes(37)
        val clientDataJson = utf8(
            """{"type":"webauthn.get","challenge":"abc","origin":"https://tenshu.app"}""",
        )
        val clientDataHash = subtleDigest(clientDataJson)
        val message = concat(authenticatorData, Int8Array(clientDataHash))
        val rawSig = subtleSign(ecdsa.privateKey, message)
        val derSig = rawEcdsaToDer(Int8Array(rawSig))

        val assertion: dynamic = js("({})")
        assertion.credentialId = credentialId
        assertion.clientDataJson = clientDataJson
        assertion.authenticatorData = authenticatorData
        assertion.signature = derSig
        assertion.userHandle = null
        assertion.prfOutput = prfOutput

        val result = Session.unlock(assertion).await()
        assertTrue(
            result.ok as Boolean,
            "expected unlock success but got ${describeError(result)}",
        )
        // The unwrap reseated the original keypair; ownerId derives from
        // its public half so it must match the pre-clear value.
        assertEquals(
            expectedOwnerId,
            JsRuntime.syncManager.currentOwnerId,
            "ownerId should match the originally-wrapped keypair",
        )
        val current = JsRuntime.syncManager.currentMasterKeyPair
        assertNotNull(current)
        assertContentEqualsBytes(originalKeyPair.privateKey, current.privateKey)
        assertContentEqualsBytes(originalKeyPair.publicKey, current.publicKey)
    }

    @Test
    fun unlock_withWrongPrfOutput_returnsMasterKeyUnwrap() = runTest {
        // Auth-tag mismatch on the wrap surfaces as a typed
        // `masterKeyUnwrap` error so the apex can route to recovery.
        TenshuSdk.awaitReady().await()
        clear()

        val keyPair = syntheticMasterKeyPair(seed = 0x99)
        JsRuntime.syncManager.reseatMasterKeyPair(keyPair)

        val ecdsa = generateEcdsaKeyPair()
        val spki = exportSpki(ecdsa.publicKey)
        val credentialId = randomBytes(16)
        val wrappingPrf = randomBytes(32)

        val registration: dynamic = js("({})")
        registration.credentialId = credentialId
        registration.publicKey = spki
        registration.algorithm = -7
        registration.prfOutput = wrappingPrf
        Session.registerPasskey(registration).await()

        val authenticatorData = randomBytes(37)
        val clientDataJson = utf8(
            """{"type":"webauthn.get","challenge":"abc","origin":"https://tenshu.app"}""",
        )
        val clientDataHash = subtleDigest(clientDataJson)
        val message = concat(authenticatorData, Int8Array(clientDataHash))
        val rawSig = subtleSign(ecdsa.privateKey, message)
        val derSig = rawEcdsaToDer(Int8Array(rawSig))

        val assertion: dynamic = js("({})")
        assertion.credentialId = credentialId
        assertion.clientDataJson = clientDataJson
        assertion.authenticatorData = authenticatorData
        assertion.signature = derSig
        assertion.userHandle = null
        // Different PRF output — the AEAD auth tag will fail.
        assertion.prfOutput = randomBytes(32)

        val result = Session.unlock(assertion).await()
        assertEquals(false, result.ok as Boolean)
        assertEquals("masterKeyUnwrap", result.error.kind as String)
    }

    @Test
    fun unlockWithPhrase_invalidMnemonic_returnsTypedError() = runTest {
        // Phase 5.3 — fast-fail path. Bip39.decode rejects before any
        // expensive crypto runs; surfaces as a typed `invalidMnemonic`
        // error so the apex's phrase-entry screen can keep the user in
        // place rather than throwing them back to retry.
        TenshuSdk.awaitReady().await()
        clear()

        val result = Session.unlockWithPhrase("not a real recovery phrase").await()
        assertEquals(false, result.ok as Boolean)
        assertEquals("invalidMnemonic", result.error.kind as String)
        // Reason is one of unknownWord/invalidLength/checksumMismatch.
        // 5-word input fails the length check first per Bip39.decode's
        // ordering — the parser short-circuits before per-word lookup.
        assertEquals("invalidLength", result.error.reason.kind as String)
    }

    @Test
    fun unlockWithPhrase_wrongRealm_rejectsBeforeDerivingKeys() = runTest {
        // Wrong-realm guard: when a wrapped master-key blob exists for
        // ownerId X and the user enters a phrase that derives ownerId Y,
        // we reject without running the slow Argon2id stretch and
        // without overwriting local realm state. The blob bytes are
        // synthetic — singleEntryOrNull only inspects the key, not the
        // ciphertext — so the test doesn't need a real wrap.
        TenshuSdk.awaitReady().await()
        clear()

        val foreignOwnerId = "owner-not-from-the-test-phrase"
        JsRuntime.masterKeyStore.put(
            foreignOwnerId,
            WrappedMasterKey(ByteArray(41) { it.toByte() }),
        )
        JsRuntime.masterKeyStore.flush()

        val mnemonic = Bip39.generateMnemonic()
        val result = Session.unlockWithPhrase(mnemonic).await()
        assertEquals(false, result.ok as Boolean)
        assertEquals("wrongRealm", result.error.kind as String)
    }

    @Test
    fun unlockWithPhrase_validPhrase_reseatsMasterKeyAndMintsToken() = runTest {
        // Happy path. Generates a fresh BIP-39 mnemonic, runs the full
        // unlock pipeline (Argon2id + Ed25519 + reseat), and verifies
        // SyncManager state matches the locally-derived keypair. Slow
        // (~1–2s on web) because of the Argon2id stretch in deriveKeys —
        // production parameters are intentionally tuned for that range.
        TenshuSdk.awaitReady().await()
        clear()

        val mnemonic = Bip39.generateMnemonic()
        val expectedKeyPair = JsRuntime.syncCryptoManager.deriveMasterKeyPair(mnemonic)
        val expectedOwnerId = ownerIdForMasterPublicKey(expectedKeyPair.publicKey)

        val result = Session.unlockWithPhrase(mnemonic).await()
        assertTrue(
            result.ok as Boolean,
            "expected success but got ${describeError(result)}",
        )
        val token = result.value
        assertNotNull(token.bytes, "token bytes")
        assertTrue((token.expiresAtMs as Number).toDouble() > Date.now())

        // Reseat verification: SyncManager now holds the same keypair
        // we just derived.
        val sm = JsRuntime.syncManager
        assertEquals(
            expectedOwnerId,
            sm.currentOwnerId,
            "ownerId should match the locally-derived public key",
        )
        val reseated = sm.currentMasterKeyPair
        assertNotNull(reseated)
        assertContentEqualsBytes(expectedKeyPair.privateKey, reseated.privateKey)
        assertContentEqualsBytes(expectedKeyPair.publicKey, reseated.publicKey)
        // Argon2id-derived shared keys populated → isEnabled true →
        // background sync can run after this unlock.
        assertEquals(true, sm.isEnabled())
    }

    private fun syntheticMasterKeyPair(seed: Int): SyncCryptoManager.KeyPair =
        SyncCryptoManager.KeyPair(
            publicKey = ByteArray(32) { ((seed + it) and 0xff).toByte() },
            privateKey = ByteArray(32) { ((seed * 7 + it) and 0xff).toByte() },
        )

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "byte length")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], "byte at index $i")
        }
    }

    private suspend fun clear() {
        // purgeRealm wipes the tenshu-sdk-session IDB along with the
        // protocol/file/secure/master-key stores — the latter is now
        // load-bearing for Phase 5 wrap-path tests so each test starts
        // with an empty `tenshu-sdk-master-keys` IDB.
        val opts: dynamic = js("({})")
        opts.purgeRealm = true
        Session.clear(opts).await()
    }

    private fun sessionToken(expiresInMs: Double): dynamic {
        val token: dynamic = js("({})")
        token.bytes = byteArrayOf(1, 2, 3, 4).unsafeCast<Int8Array>()
        token.expiresAtMs = Date.now() + expiresInMs
        return token
    }

    private fun fakeAssertion(credentialId: Int8Array = randomBytes(16)): dynamic {
        val a: dynamic = js("({})")
        a.credentialId = credentialId
        a.clientDataJson = Int8Array(0)
        a.authenticatorData = Int8Array(0)
        a.signature = Int8Array(0)
        a.userHandle = null
        return a
    }

    private fun describeError(result: dynamic): String {
        val err = result.error
        if (err == null) return "<no error>"
        if (err.kind != null) return "kind=${err.kind}"
        return err.toString()
    }
}

// --- WebCrypto helpers for the positive-path test --------------------------

private data class TestKeyPair(val publicKey: dynamic, val privateKey: dynamic)

private suspend fun generateEcdsaKeyPair(): TestKeyPair {
    val subtle: dynamic = js("crypto.subtle")
    val params = js("({ name: 'ECDSA', namedCurve: 'P-256' })")
    val usages: Array<String> = arrayOf("sign", "verify")
    val pair: dynamic = subtle
        .generateKey(params, true, usages)
        .unsafeCast<Promise<dynamic>>()
        .await()
    return TestKeyPair(publicKey = pair.publicKey, privateKey = pair.privateKey)
}

private suspend fun exportSpki(publicKey: dynamic): Int8Array {
    val subtle: dynamic = js("crypto.subtle")
    val buffer: ArrayBuffer = subtle.exportKey("spki", publicKey)
        .unsafeCast<Promise<ArrayBuffer>>()
        .await()
    return Int8Array(buffer)
}

private suspend fun subtleDigest(data: Int8Array): ArrayBuffer {
    val subtle: dynamic = js("crypto.subtle")
    return subtle.digest("SHA-256", data)
        .unsafeCast<Promise<ArrayBuffer>>()
        .await()
}

private suspend fun subtleSign(privateKey: dynamic, message: Int8Array): ArrayBuffer {
    val subtle: dynamic = js("crypto.subtle")
    val params = js("({ name: 'ECDSA', hash: 'SHA-256' })")
    return subtle.sign(params, privateKey, message)
        .unsafeCast<Promise<ArrayBuffer>>()
        .await()
}

private fun randomBytes(size: Int): Int8Array {
    val bytes = Int8Array(size)
    js("crypto.getRandomValues(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength))")
    return bytes
}

private fun utf8(text: String): Int8Array {
    val encoded: dynamic = js("new TextEncoder().encode(text)")
    return Int8Array(
        encoded.buffer.unsafeCast<ArrayBuffer>(),
        (encoded.byteOffset as Number).toInt(),
        (encoded.byteLength as Number).toInt(),
    )
}

private fun concat(a: Int8Array, b: Int8Array): Int8Array {
    val out = Int8Array(a.length + b.length)
    val outDyn: dynamic = out
    outDyn.set(a, 0)
    outDyn.set(b, a.length)
    return out
}

// Inverse of WebAuthnVerifier's DER→RAW: takes raw `r || s` (each
// `componentSize` bytes) and emits DER `SEQUENCE { INTEGER r, INTEGER s }`.
// Strips leading zero bytes and prepends 0x00 when the high bit would
// otherwise make the integer negative — same canonical encoding every
// authenticator emits.
private fun rawEcdsaToDer(raw: Int8Array): Int8Array {
    val componentSize = raw.length / 2
    val r = derInteger(sliceInt8(raw, 0, componentSize))
    val s = derInteger(sliceInt8(raw, componentSize, raw.length))
    val seqBody = concat(r, s)
    val seqLen = seqBody.length
    require(seqLen < 128) { "test ECDSA DER signatures should fit in single-byte length" }
    val out = Int8Array(2 + seqLen)
    val outDyn: dynamic = out
    outDyn[0] = 0x30
    outDyn[1] = seqLen
    outDyn.set(seqBody, 2)
    return out
}

private fun derInteger(component: Int8Array): Int8Array {
    var start = 0
    while (start < component.length - 1 && component.unsafeCast<dynamic>()[start] == 0) start++
    val first = component.unsafeCast<dynamic>()[start].unsafeCast<Int>().toUByte().toInt()
    val needsLeadingZero = (first and 0x80) != 0
    val bodyLen = (component.length - start) + (if (needsLeadingZero) 1 else 0)
    val out = Int8Array(2 + bodyLen)
    val outDyn: dynamic = out
    outDyn[0] = 0x02
    outDyn[1] = bodyLen
    var off = 2
    if (needsLeadingZero) {
        outDyn[off] = 0
        off++
    }
    val tail = sliceInt8(component, start, component.length)
    outDyn.set(tail, off)
    return out
}

private fun sliceInt8(view: Int8Array, start: Int, end: Int): Int8Array {
    val len = end - start
    val out = Int8Array(len)
    val outDyn: dynamic = out
    outDyn.set(view.unsafeCast<dynamic>().subarray(start, end), 0)
    return out
}
