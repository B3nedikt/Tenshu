package app.tenshu.control.di

import org.tenshu.core.common.app.proxy.PeerAttestationProbe
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's answer to "was this client's identity *attested*?"
 * (`control-app-catalogue.md` C4, the twin of desktop's `UnixSocketServer.isAttested`).
 *
 * ### Why Android can answer yes at all
 *
 * A Binder caller cannot state its own name. `ProxyService` derives the client id from
 * `Binder.getCallingUid()` and asks the `PackageManager` which package owns that uid — kernel
 * bookkeeping the caller has no way to influence — and it **errors rather than falling back**
 * when the lookup fails, so no unattested id can enter through that door. That is a stronger
 * guarantee than desktop's, which had to be built (code-signature attestation, P0–P3); Android
 * has had it all along and simply never said so, leaving `AppRegistration.verified` hardcoded
 * `false` everywhere.
 *
 * ### Why record rather than answer `true` unconditionally
 *
 * Not every client id reaching the proxy comes over Binder: Control's own in-process client
 * uses `platformPackageName` directly. Today that one never registers (it is not in the
 * allowlist), so a blanket `true` would be *accidentally* correct — right for a reason that
 * lives in a different file and could change without anyone noticing here. Recording the ids
 * that actually arrived through the attested path keeps the claim tied to the evidence.
 *
 * ⚠ **The uid is attested; the package *name* has one soft edge.** `getPackagesForUid` returns
 * every package sharing that uid and `ProxyService` takes the first. Apps sharing a uid
 * (`android:sharedUserId`, long deprecated) are mutually trusting by definition and no
 * first-party Tenshu app uses it — but for such a caller the chosen name is arbitrary among
 * that trust group, not forged from outside it.
 */
class BinderAttestedClients : PeerAttestationProbe {

    private val attested = ConcurrentHashMap.newKeySet<String>()

    /**
     * Records that [clientId] was derived from the calling uid. Called on the binder thread for
     * every transaction, so it must stay cheap and allocation-free on the hot path — a set
     * membership check plus, once per client, one insert.
     */
    fun record(clientId: String) {
        attested.add(clientId)
    }

    override fun isAttested(clientId: String): Boolean = attested.contains(clientId)
}
