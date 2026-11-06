@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.WebAuthnVerifier
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.khronos.webgl.Int8Array
import org.tenshu.core.common.security.MasterKeyUnwrapException
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.sync.forgetThisDevice
import org.tenshu.sync.client.syncService
import kotlin.js.Date
import kotlin.js.Promise

// Session detection + persistence for the apex's three-state landing per
// `web-onboarding-ux.md` §2. Backed by a small dedicated IndexedDB
// database (`tenshu-sdk-session`) — separate from the protocol store and
// file bytes so wiping the session doesn't touch realm data and vice-versa.
//
// JS contract: `web/sdk/src/onboarding.ts`'s `SessionApi`.
//
// Detect logic (UX §2 "Three Landing States"):
//   - device-name in IDB + token in IDB + token not expired → `active`
//   - device-name in IDB + (no token | expired) → `locked`
//   - no device-name in IDB → `firstTime`
//
// Device-name is written to IDB by the onboarding wrappers (`Realm.create`
// / `Pairing.submit` / `Recovery.claim`) on success; see `internal/
// SessionRecorder.kt`.
//
// `registerPasskey(reg)` stores the SPKI public key + credentialId from a
// fresh `navigator.credentials.create()` ceremony so subsequent unlocks
// have something to verify against. `unlock(assertion)` verifies the
// assertion against the stored passkey via WebCrypto and mints a 32-byte
// random session token with a 24h expiry.
//
// Phase 5 PRF-wrap path (`docs/web/roadmap.md`):
//   - When `registration.prfOutput` is present (the apex's
//     `webauthn.ts` `registerPasskey` returns it from the PRF-extension
//     `clientExtensionResults.prf.results.first`), and a master keypair
//     is currently in memory (`SyncManagerImpl.currentMasterKeyPair`,
//     populated by a successful Realm.create / Recovery.claim /
//     Pairing.submit), `registerPasskey` wraps that keypair under the
//     PRF output and persists it to `tenshu-sdk-master-keys` keyed by
//     the realm `ownerId`. Browsers without PRF support fall back to
//     today's opaque-session-token path silently — the user can still
//     unlock, just without the at-rest master-key protection per
//     `docs/web/architecture.md` §4.4. Tracked in tech-debt as a
//     "graceful-degradation surface" follow-up.
//   - `unlock(assertion)` mirrors: when `assertion.prfOutput` is present
//     and a wrapped blob exists for the current realm, it unwraps the
//     master keypair under the PRF output and reseats it on
//     `SyncManagerImpl.currentMasterKeyPair`, restoring asset A1 in
//     memory so subsequent JWT issuance / pairing / recovery ops have
//     the signing key. The session token continues to live alongside
//     the wrapped key — the apex's `Session.persist` writes the token
//     after `unlock`.
@JsExport
object Session {

    fun detect(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val store = JsRuntime.sessionStore
        // Keep the landing page's runtime-free hint in step with the authoritative answer.
        store.refreshHint()
        val deviceName = store.getDeviceName()
        if (deviceName == null) {
            firstTimeState()
        } else {
            val token = store.getSessionToken()
            if (token != null && token.expiresAtMs > Date.now()) {
                activeState(deviceName)
            } else {
                lockedState(deviceName)
            }
        }
    }

    fun persist(token: dynamic): Promise<Unit> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val bytes = token.bytes.unsafeCast<Int8Array>()
        val expiresAtMs = (token.expiresAtMs as Number).toDouble()
        val store = JsRuntime.sessionStore
        store.setSessionToken(bytes, expiresAtMs)
        store.flush()
    }

    fun clear(options: dynamic): Promise<Unit> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val purgeRealm = options?.purgeRealm == true
        val store = JsRuntime.sessionStore
        store.clear()
        // Mia's conversation is in memory only (`MiaBook.kt` rule 1), and a thread of answers about
        // a realm this browser just left is not something to keep (`MiaLocalRuntime.deprovision`).
        MiaConversations.clear()
        if (purgeRealm) {
            // W13. The shared op FIRST, then the browser-only stores.
            //
            // ⚠⚠ The order inside `ForgetThisDevice` is the whole implementation, and the web
            // used to skip all of it: retire the device row and let its tombstone leave *while
            // the key that signs it still exists*, purge the outbound queues *before* custody
            // goes (minting an ExecutionContext afterwards silently yields nothing — observed
            // on iOS 2026-08-19), drop the local store *before* the identity (custody-gone-
            // store-present is the half-state D3 forbids: a fresh device id, 401 on every push,
            // and an app that still looks signed in to the realm it just left), and only then
            // null the in-memory keys so the session stops signing with keys that no longer
            // exist at rest. A `clearAll()` reproduces none of that ordering.
            //
            // ⚠ Best-effort by the op's own contract: "a failed retirement never blocks the
            // wipe" — people forget a device precisely when something is broken. The outcome
            // names what actually happened (including a store it could not drop), so a half
            // reset is reported rather than silent.
            val outcome = runCatching { JsRuntime.tenshuClient.syncService.forgetThisDevice() }
            app.tenshu.diagnostics.Diagnostics.i(
                "Session",
                "forgetThisDevice: " + (outcome.getOrNull()?.toString() ?: "failed: ${outcome.exceptionOrNull()}"),
            )

            // ⚠ Still needed, and still AFTER: the op wipes the object store and the custody
            // bucket, but knows nothing about the browser's other IndexedDB stores — file
            // bytes above all. Web's at-rest coverage stays a superset (§1.2h), which is what
            // kept it correct while it was hand-rolled.
            JsRuntime.purgeRealmStorage()
        }
    }

    fun registerPasskey(registration: dynamic): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val credentialId = registration.credentialId.unsafeCast<Int8Array>()
        val publicKey = registration.publicKey.unsafeCast<Int8Array>()
        val algorithm = (registration.algorithm as Number).toInt()
        if (credentialId.length == 0 || publicKey.length == 0) {
            return@promise resultErr("invalid registration: empty credentialId or publicKey")
        }
        val store = JsRuntime.sessionStore
        store.setPasskey(credentialId, publicKey, algorithm)
        store.flush()

        // Phase 5 producer: wrap the master keypair (asset A1) under the
        // PRF output if both are available. The master keypair is in
        // memory after Realm.create / Recovery.claim / Pairing.submit —
        // i.e. the onboarding path the apex runs immediately before the
        // §4a/§4b "set up passkey" tail screen calls registerPasskey.
        // If either side is missing (no PRF support, or registration
        // happened outside the onboarding flow), skip the wrap silently
        // and let the existing opaque-session-token path carry the unlock.
        val prfOutput = readInt8ArrayOrNull(registration, "prfOutput")
        if (prfOutput != null && prfOutput.length > 0) {
            wrapMasterKeyForCurrentRealm(prfOutput)
        }
        resultOk(undef())
    }

    fun unlock(assertion: dynamic): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val store = JsRuntime.sessionStore
        val passkey = store.getPasskey()
            ?: return@promise unlockErr("noPasskey")

        val verifyResult = WebAuthnVerifier.verify(
            registeredCredentialId = passkey.credentialId,
            registeredPublicKeySpki = passkey.publicKey,
            registeredAlgorithm = passkey.algorithm,
            assertionCredentialId = assertion.credentialId.unsafeCast<Int8Array>(),
            clientDataJson = assertion.clientDataJson.unsafeCast<Int8Array>(),
            authenticatorData = assertion.authenticatorData.unsafeCast<Int8Array>(),
            signature = assertion.signature.unsafeCast<Int8Array>(),
        )

        when (verifyResult) {
            WebAuthnVerifier.Result.Ok -> {
                // Phase 5 unwrap: if the assertion carries a PRF output and
                // a wrapped master keypair exists for the realm, unwrap it
                // and reseat onto SyncManagerImpl. A failure here surfaces
                // through the `other` branch with kind="masterKeyUnwrap"
                // so the apex can route to the recovery-phrase fallback
                // (UX §3 "Use recovery phrase"). Browsers without PRF
                // support don't carry a prfOutput; the unlock still mints
                // an opaque session token (today's behaviour) so the
                // session-token side of unlock continues to work, but the
                // master keypair stays unloaded — apex can detect that
                // via SyncManager state and prompt for recovery.
                val prfOutput = readInt8ArrayOrNull(assertion, "prfOutput")
                if (prfOutput != null && prfOutput.length > 0) {
                    val unwrapErr = unwrapMasterKeyForCurrentRealm(prfOutput)
                    if (unwrapErr != null) {
                        return@promise unwrapErr
                    }
                }
                val token: dynamic = js("({})")
                token.bytes = randomBytes(SESSION_TOKEN_BYTES)
                token.expiresAtMs = Date.now() + SESSION_TTL_MS
                resultOk(token)
            }

            WebAuthnVerifier.Result.WrongCredential -> unlockErr("wrongCredential")
            is WebAuthnVerifier.Result.UnsupportedAlgorithm -> {
                val err: dynamic = js("({})")
                err.kind = "unsupportedAlgorithm"
                err.algorithm = verifyResult.algorithm
                resultErrObject(err)
            }

            is WebAuthnVerifier.Result.Other -> {
                val err: dynamic = js("({})")
                err.kind = "other"
                err.message = verifyResult.message
                resultErrObject(err)
            }
        }
    }

    // Phase 5.3 — recovery-phrase fallback for the locked-flow unlock
    // path. Used when the user's passkey doesn't work (lost, never set up,
    // PRF unavailable, masterKeyUnwrap) but the device still has its
    // realm IDB state. Distinct from `Recovery.claim` (the firstTime-flow
    // path that does a relay round-trip to fetch the encrypted backup
    // blob): this method does no relay calls — the master keypair and
    // shared derived keys are re-derived locally from the mnemonic.
    //
    // Slow operation by design: the Argon2id stretch in
    // `cryptoManager.deriveKeys` is ~1–2 s on web. The apex routes this
    // through a "deriving master key…" screen that mirrors the existing
    // RecoveryFlow's ProcessingScreen UX.
    //
    // Wrong-realm guard: if the device has a wrapped master keypair in
    // `tenshu-sdk-master-keys` (i.e. PRF wrap was set up earlier), the
    // derived ownerId must match — otherwise the user typed a phrase for
    // a different realm and we'd silently overwrite the wrong realm's
    // state. Returns `wrongRealm`. When no wrapped blob exists (PRF
    // unsupported, or the wrap path was never run), the guard is silent
    // and we trust the phrase.
    fun unlockWithPhrase(mnemonic: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()

        // Step 1: client-side BIP-39 validation (same pattern as
        // Recovery.claim). Surface typed errors before any expensive
        // crypto.
        val entropy = try {
            Bip39.decode(mnemonic)
        } catch (e: Bip39InvalidMnemonicException) {
            return@promise phraseUnlockErrInvalidMnemonic(e)
        }

        // Step 2: derive the master keypair (HKDF, fast). We need it now
        // so the wrong-realm guard can compare ownerIds before the slow
        // Argon2id step kicks in.
        val cryptoManager = JsRuntime.syncCryptoManager
        val masterKeyPair = cryptoManager.deriveMasterKeyPair(mnemonic)
        val derivedOwnerId = ownerIdForMasterPublicKey(masterKeyPair.publicKey)

        // Step 3: wrong-realm guard. If a wrapped master-key blob exists
        // for a different ownerId, the user typed a phrase for a
        // different realm. Reject before stomping on local state.
        val masterKeyStore = JsRuntime.masterKeyStore
        val existing = masterKeyStore.singleEntryOrNull()
        if (existing != null && existing.first != derivedOwnerId) {
            return@promise phraseUnlockErrWrongRealm()
        }

        // Step 4: derive shared keys (Argon2id, slow). These populate
        // `derivedKeys` on SyncManager so `isEnabled()` returns true and
        // background sync runs.
        val derivedKeys = cryptoManager.deriveKeys(mnemonic)

        // Step 5: reseat onto SyncManager. `initializeWithKeys` sets
        // derivedKeys + ownerId + persists to secure storage;
        // `reseatMasterKeyPair` adds the master keypair (asset A1) which
        // initializeWithKeys doesn't touch. Together they bring the
        // SyncManager to the same state `initialize(phrase)` would
        // produce, but without persisting the plaintext phrase.
        val syncManager = JsRuntime.syncManager
        syncManager.initializeWithKeys(derivedKeys, derivedOwnerId)
        syncManager.reseatMasterKeyPair(masterKeyPair)
        // Hold the entropy so `Recovery.revealPhrase` works this session and
        // the next passkey re-registration upgrades the wrap blob to v2.
        JsRuntime.currentEntropy = entropy

        // Step 6: mint and return session token. Mirrors `Session.unlock`'s
        // 24h opaque token; the apex `Session.persist`s it after this
        // resolves.
        val token: dynamic = js("({})")
        token.bytes = randomBytes(SESSION_TOKEN_BYTES)
        token.expiresAtMs = Date.now() + SESSION_TTL_MS
        resultOk(token)
    }

    private const val SESSION_TOKEN_BYTES = 32
    private const val SESSION_TTL_MS: Double = 24.0 * 60.0 * 60.0 * 1000.0
}

// Wrap the realm's current master keypair under [wrapKey] and persist it
// to the IDB master-key store. No-op when no master keypair is in memory
// or the realm doesn't have an ownerId yet — both are signals that the
// caller invoked registerPasskey outside the onboarding window
// (e.g. re-registering on a returning device whose master key isn't
// loaded). We don't surface that as an error: the credential is still
// stored, and the next unlock either has a stale wrapped blob (correct
// for the old keypair) or no blob (caller will fall back to recovery).
private suspend fun wrapMasterKeyForCurrentRealm(wrapKey: Int8Array) {
    val syncManager = JsRuntime.syncManager
    val keyPair = syncManager.currentMasterKeyPair ?: return
    val ownerId = syncManager.currentOwnerId ?: return
    val wrapKeyBytes = int8ArrayToByteArray(wrapKey)
    // Carry the BIP-39 entropy in the blob (v2) when it's in memory, so
    // `Recovery.revealPhrase` can reconstruct the mnemonic after a future
    // PRF unlock. Falls back to the v1 keypair-only blob when the entropy
    // isn't held (e.g. a joiner, or a returning device unlocked from a
    // pre-v2 blob whose entropy was never recovered).
    val entropy = JsRuntime.currentEntropy
    val blob = if (entropy != null) {
        JsRuntime.masterKeyWrapper.wrap(keyPair, entropy, wrapKeyBytes)
    } else {
        JsRuntime.masterKeyWrapper.wrap(keyPair, wrapKeyBytes)
    }
    JsRuntime.masterKeyStore.put(ownerId, blob)
    JsRuntime.masterKeyStore.flush()
}

// Unwrap the realm's master keypair under [wrapKey] and reseat it onto
// SyncManager. Returns a result-shaped error object on failure (auth-tag
// mismatch, missing blob, etc.); returns null on success. Called from
// Session.unlock after WebAuthn verification succeeds and a PRF output
// is present on the assertion.
private fun unwrapMasterKeyForCurrentRealm(wrapKey: Int8Array): dynamic {
    val store = JsRuntime.masterKeyStore
    // Page-reload unlock path: SyncManager has no in-memory state yet, so
    // we don't know the ownerId. Single-realm-per-browser is the v1 spec
    // (`docs/web/architecture.md` §4.3); pick the lone entry.
    val entry = store.singleEntryOrNull() ?: return null
    val (_, wrappedBlob) = entry
    val wrapKeyBytes = int8ArrayToByteArray(wrapKey)
    val keyPair = try {
        // v2 blobs also carry the BIP-39 entropy; recover it into the
        // web-side holder so `Recovery.revealPhrase` works this session.
        // v1 (pre-entropy) blobs return a null entropy — reveal stays
        // "unavailable" until the next passkey re-registration re-wraps to
        // v2 (which needs the entropy in memory, i.e. after a phrase unlock).
        val (kp, entropy) = JsRuntime.masterKeyWrapper.unwrapKeyPairAndEntropy(wrappedBlob, wrapKeyBytes)
        JsRuntime.currentEntropy = entropy
        kp
    } catch (_: MasterKeyUnwrapException) {
        // Auth-tag mismatch or corrupted blob — surface the typed error so
        // the apex can route to recovery rather than silently leaving the
        // master key unloaded. `kind="masterKeyUnwrap"` is a v1-only error
        // surface; the SDK contract documents it under UnlockError.
        val err: dynamic = js("({})")
        err.kind = "masterKeyUnwrap"
        return resultErrObject(err)
    }
    JsRuntime.syncManager.reseatMasterKeyPair(keyPair)
    return null
}

private fun readInt8ArrayOrNull(obj: dynamic, key: String): Int8Array? {
    val raw = obj[key]
    // Kotlin/JS treats both `null` and `undefined` as null under `== null`,
    // so a single check covers both the omitted-field case (PRF unsupported
    // by the browser) and the explicitly-null case.
    if (raw == null) return null
    return raw.unsafeCast<Int8Array>()
}

// Kotlin/JS represents ByteArray as Int8Array under the hood (cf. existing
// usage in `internal/CanvasPreview.kt` line 52). The static types are
// walled off but the runtime representation is identical, so the unsafe
// cast is the canonical conversion for this codebase.
private fun int8ArrayToByteArray(arr: Int8Array): ByteArray =
    arr.unsafeCast<ByteArray>()

// Mirrors `Recovery.claim`'s invalidMnemonicResult shape so the apex can
// share the existing `Bip39DecodeError` discriminated-union handler in
// the recovery / phrase-unlock screens.
private fun phraseUnlockErrInvalidMnemonic(e: Bip39InvalidMnemonicException): dynamic {
    val reason: dynamic = js("({})")
    when (e) {
        is Bip39InvalidMnemonicException.UnknownWord -> {
            reason.kind = "unknownWord"
            reason.word = e.word
        }

        is Bip39InvalidMnemonicException.InvalidLength -> {
            reason.kind = "invalidLength"
            reason.wordCount = e.wordCount
        }

        is Bip39InvalidMnemonicException.ChecksumMismatch -> {
            reason.kind = "checksumMismatch"
        }
    }
    val errorObj: dynamic = js("({})")
    errorObj.kind = "invalidMnemonic"
    errorObj.reason = reason
    return resultErrObject(errorObj)
}

private fun phraseUnlockErrWrongRealm(): dynamic {
    val errorObj: dynamic = js("({})")
    errorObj.kind = "wrongRealm"
    return resultErrObject(errorObj)
}

private fun randomBytes(size: Int): Int8Array {
    val bytes = Int8Array(size)
    js("crypto.getRandomValues(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength))")
    return bytes
}

private fun firstTimeState(): dynamic {
    val s: dynamic = js("({})")
    s.kind = "firstTime"
    return s
}

private fun lockedState(deviceName: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "locked"
    s.deviceName = deviceName
    return s
}

private fun activeState(deviceName: String): dynamic {
    val s: dynamic = js("({})")
    s.kind = "active"
    s.deviceName = deviceName
    return s
}

private fun resultOk(value: dynamic): dynamic {
    val r: dynamic = js("({})")
    r.ok = true
    r.value = value
    return r
}

private fun resultErr(message: String): dynamic {
    val r: dynamic = js("({})")
    r.ok = false
    r.error = message
    return r
}

private fun resultErrObject(error: dynamic): dynamic {
    val r: dynamic = js("({})")
    r.ok = false
    r.error = error
    return r
}

private fun unlockErr(kind: String): dynamic {
    val err: dynamic = js("({})")
    err.kind = kind
    return resultErrObject(err)
}

private fun undef(): dynamic = js("undefined")
