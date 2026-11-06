@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.diagnostics.Diagnostics
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.errorMessage
import app.tenshu.sdk.internal.looksLikeNetworkError
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.TResult
import org.tenshu.core.id
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.client.syncService
import org.tenshu.sync.recoveryClaim
import kotlin.js.Promise

// Recovery flow (UX `web-onboarding-ux.md` §6 — recovery-phrase entry on a
// device with no other authorised devices). Wraps `:sync-service`'s
// `RecoveryClaim`; see `docs/proposals/sync-relay-api.md` §3.4 for the
// challenge / claim handshake.
//
// JS contract: `web/sdk/src/onboarding.ts`'s `RecoveryApi.claim`:
//   claim({mnemonic, deviceName}) → Promise<TResult<void, RecoveryError>>
//   RecoveryError =
//     | {kind: 'invalidMnemonic', reason: Bip39DecodeError}
//     | {kind: 'noBackupOnRelay'}
//     | {kind: 'kdfParametersOutOfDate'}
//     | {kind: 'deviceAlreadySetUp'}   // this browser holds a DIFFERENT realm (T-RC-09)
//     | {kind: 'network'} | {kind: 'other', message: string}
//   Bip39DecodeError =
//     | {kind: 'invalidLength', wordCount}
//     | {kind: 'unknownWord', word}
//     | {kind: 'checksumMismatch'}
//
// Bip39 validation runs client-side first so a typo surfaces as a typed
// `invalidMnemonic` reason without any relay round-trip. The server-side
// equivalent in `RecoveryClaimImplementation` does the same — we duplicate
// it here to skip the dispatch entirely on a malformed phrase.
//
// `noBackupOnRelay` and `kdfParametersOutOfDate` aren't surfaced as
// distinct relay status codes today — the relay returns 4xx with a string
// message. We map a 404 to `noBackupOnRelay`: since F8 the only 404 on the
// recovery path is `/sync/recovery-claim`'s "Realm not found", told only
// after the relay verified this phrase's master signature (the challenge
// names no realm and never 404s). `kdfParametersOutOfDate` has no detectable
// surface yet — it'll always fall through to `other` until the relay
// emits a typed status code. TODO(crypto/relay): add a typed surface so
// this branch can fire.
// ⚠ `MasterKeyWrapper.ENTROPY_SIZE`, duplicated because that companion is private.
// A v2 wrap blob carries exactly this many entropy bytes; anything else must not
// be seated (see `claim`).
private const val WRAPPER_ENTROPY_SIZE = 32

@JsExport
object Recovery {

    fun claim(mnemonic: String, deviceName: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()

            // Step 1: client-side BIP-39 validation. Skip the relay
            // round-trip if the phrase is obviously malformed.
            //
            // ⚠ Keep the decoded entropy — it is the same 32 bytes
            // `Recovery.revealPhrase` needs and `Session.registerPasskey`
            // wraps into the v2 blob. Discarding it here is what made every
            // recovered browser resolve `unavailable`, then write a
            // keypair-only v1 blob on its next passkey registration.
            val entropy = try {
                Bip39.decode(mnemonic)
            } catch (e: Bip39InvalidMnemonicException) {
                return@promise invalidMnemonicResult(e)
            }

            // Step 2: dispatch RecoveryClaim. Server-side `RecoveryClaim`
            // re-validates Bip39 and produces a `GenericError` if it
            // fails — that's a defence-in-depth against this client-side
            // check drifting; we wouldn't expect to hit it.
            try {
                val client = JsRuntime.tenshuClient
                val result = client.syncService.recoveryClaim(mnemonic, deviceName)
                when (result) {
                    is TResult.Success<*> -> {
                        // ⚠⚠ THREE conditions, each load-bearing: only on success,
                        // only when the phrase derives the keypair actually seated,
                        // and only when the wrapper can encode the entropy.
                        //
                        // On success: `RecoveryClaim` is what makes this device the
                        // realm's owner (`SyncManagerImpl.initialize` sets the master
                        // keypair and ownerId); until it returns, the phrase is just
                        // 24 words the user typed. Seating the entropy before the
                        // dispatch would let `revealPhrase` hand back a phrase for a
                        // realm this browser never joined — including after
                        // `noBackupOnRelay`, where the realm may not exist at all.
                        // Pinned by `claim_relayFailure_doesNotRetainTheEntropy`.
                        //
                        // ⚠⚠ On match: seating this phrase's entropy beside ANOTHER
                        // realm's keypair would make `wrapMasterKeyForCurrentRealm`
                        // persist that mismatch as a v2 blob, and a later PRF unlock
                        // would reveal a phrase for a realm the keypair does not belong
                        // to. `RecoveryClaimImplementation` now seats only after an
                        // admitted claim (T-RC-09) and refuses a browser resident in a
                        // different realm (`deviceAlreadySetUp`), so a mismatch here
                        // should be unreachable — kept as the backstop, since the cost
                        // of being wrong is a lying blob. `deriveMasterKeyPair` is HKDF,
                        // so this costs a hash, not the Argon2id round.
                        //
                        // ⚠⚠ And only entropy the wrapper can encode. `Bip39.decode`
                        // accepts 12/15/18/21/24 words and returns 16/20/24/28/32
                        // bytes, while `MasterKeyWrapper.wrap`'s entropy overload
                        // `require`s exactly 32. Seating a shorter one would make the
                        // next PRF-backed `registerPasskey` pick that overload and
                        // THROW — after the credential is already persisted — where
                        // before it fell back to the v1 keypair-only blob. So a short
                        // phrase must keep the old path, not crash on a new one.
                        // ⚠ Spelled here because `ENTROPY_SIZE` is in `MasterKeyWrapper`'s
                        // *private* companion; keep the two in step by hand.
                        val seatedOwnerId = JsRuntime.syncManager.currentOwnerId
                        val phraseOwnerId = ownerIdForMasterPublicKey(
                            JsRuntime.syncCryptoManager.deriveMasterKeyPair(mnemonic).publicKey,
                        )
                        val wrappableEntropy = entropy.size == WRAPPER_ENTROPY_SIZE
                        if (wrappableEntropy && (seatedOwnerId == null || seatedOwnerId == phraseOwnerId)) {
                            JsRuntime.currentEntropy = entropy
                        } else {
                            // ⚠ Degraded, not wrong: reveal stays `unavailable` and the
                            // next passkey write is a v1 blob — the pre-fix behaviour —
                            // rather than a blob that lies about which realm it is for.
                            // The session itself is already inconsistent here; that is
                            // the shared op's to fix, not this wrapper's.
                            val why = if (!wrappableEntropy) {
                                "entropy is ${entropy.size} bytes, the wrapper needs $WRAPPER_ENTROPY_SIZE"
                            } else {
                                "claim succeeded for $phraseOwnerId but $seatedOwnerId is seated " +
                                    "(stale SyncManager state from an earlier claim)"
                            }
                            Diagnostics.w("Recovery", "not retaining entropy: $why")
                        }
                        // Persist deviceName so a future Session.detect()
                        // distinguishes returning users (`locked`) from
                        // first-time users (`firstTime`).
                        JsRuntime.sessionStore.setDeviceName(deviceName)
                        JsRuntime.sessionStore.flush()
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = undefined
                        ok
                    }

                    // ⚠ Typed, so matched by schema id (as `LockedViewModel` does) — never by message. Raised before any relay
                    // call when this browser already belongs to a different realm; the remedy
                    // is the user's (disconnect this browser first), not a retry.
                    is TResult.Failure<*> ->
                        if (result.error.id == DeviceAlreadySetUpError.ID) errorResult("deviceAlreadySetUp", null)
                        else mapFailureMessage(result.error.errorMessage())
                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                if (looksLikeNetworkError(msg)) errorResult("network", null)
                else errorResult("other", msg)
            }
        }

    // §7.4 "Set up account recovery" — reconstruct the 24-word BIP-39 phrase
    // this device already holds (low-friction onboarding defers writing it
    // down to Settings). Reads the in-memory entropy held since `Realm.create`
    // / `Session.unlockWithPhrase` / a v2-blob PRF unlock, and re-encodes it
    // to the mnemonic. No relay round-trip; the phrase never persisted in the
    // clear. JS contract (`web/sdk/src/onboarding.ts` `RecoveryApi`):
    //   revealPhrase() → Promise<TResult<string, RevealPhraseError>>
    //   RevealPhraseError = {kind: 'locked' | 'unavailable' | 'joinerDevice'}
    //                     | {kind: 'other', message}
    fun revealPhrase(): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val entropy = JsRuntime.currentEntropy
            if (entropy != null) {
                return@promise try {
                    val ok: dynamic = js("({})")
                    ok.ok = true
                    ok.value = Bip39.encode(entropy)
                    ok
                } catch (t: Throwable) {
                    errorResult("other", t.message ?: "failed to encode entropy")
                }
            }
            // No entropy in memory — classify why so the apex shows the right
            // copy. `locked`: no realm loaded (sign in first). `joinerDevice`:
            // this device joined via pairing and never held the phrase, so
            // there's nothing to reveal here (reveal on the originating
            // device). `unavailable`: this host cannot reconstruct the phrase —
            // either its wrap blob predates the entropy-carrying v2 format (a
            // passkey re-registration after a phrase unlock upgrades it), or the
            // realm's phrase is shorter than 24 words, whose entropy the v2 blob
            // cannot carry, so `claim` above declines to hold it.
            // ⚠⚠ That second case is specific to the `claim` path.
            // `Session.unlockWithPhrase` and `Realm.create` seat the entropy with
            // no length check, so a short-phrase realm entered that way reveals
            // fine and throws later, inside `registerPasskey`. Do not read this
            // branch as "short phrases are always unavailable" —
            // `docs/tech-debt.md` has the cross-path picture.
            // ⚠ Every production caller of `Bip39.generateMnemonic()` takes its
            // 32-byte default, so in practice a short phrase is one brought in
            // from elsewhere — but the function accepts a smaller `entropySize`,
            // so such a realm is not structurally impossible.
            //
            // ⚠ A host restored through `claim` above used to land here too, and
            // always did: `claim` decoded the phrase to validate it and threw the
            // entropy away, so a freshly recovered browser cleared both checks
            // above and still had nothing to encode — then wrote a keypair-only
            // v1 blob on its next passkey registration, which is the legacy case
            // arriving by the new path. Fixed 2026-09-14; `claim` now retains the
            // entropy on success like its two siblings. So `unavailable` really
            // is the pre-v2 blob again.
            val syncManager = JsRuntime.syncManager
            when {
                syncManager.currentOwnerId == null -> errorResult("locked", null)
                syncManager.currentMasterKeyPair == null -> errorResult("joinerDevice", null)
                else -> errorResult("unavailable", null)
            }
        }
}

private fun invalidMnemonicResult(e: Bip39InvalidMnemonicException): dynamic {
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
    val err: dynamic = js("({})")
    err.ok = false
    val errorObj: dynamic = js("({})")
    errorObj.kind = "invalidMnemonic"
    errorObj.reason = reason
    err.error = errorObj
    return err
}

// `RATE_LIMITED_REJECTION_MARKER` in `:sync-service-proxy`, spelled here because it is internal there.
private const val RATE_LIMITED_MARKER = "relay-rate-limited-429"

// The relay surfaces "Challenge fetch failed: 404 Not Found" / "Recovery
// claim failed: 4xx ..." style strings. We grep for the suggestive
// substring; that's brittle but it's the only signal we have until the
// relay emits typed error codes (TODO(relay)).
//
// ⚠ A relay 429 (the per-IP cap on `/sync/recovery-challenge`, threat model T-RC-08) is checked
// FIRST and maps to `network`, whose copy is "try again": the same call succeeds once the window
// refills. It used to reach `network` only because "Challenge fetch failed" contains "fetch".
private fun mapFailureMessage(message: String): dynamic {
    val kind = when {
        RATE_LIMITED_MARKER in message -> "network"
        "404" in message -> "noBackupOnRelay"
        looksLikeNetworkError(message) -> "network"
        else -> "other"
    }
    return errorResult(kind, message.takeIf { kind == "other" })
}

private fun errorResult(kind: String, message: String?): dynamic {
    val err: dynamic = js("({})")
    err.ok = false
    val errorObj: dynamic = js("({})")
    errorObj.kind = kind
    if (message != null && kind == "other") {
        errorObj.message = message
    }
    err.error = errorObj
    return err
}
