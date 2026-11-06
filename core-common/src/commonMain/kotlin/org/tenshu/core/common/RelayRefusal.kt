package org.tenshu.core.common

/**
 * Stable, machine-matchable reasons the relay puts at the **front of a refusal body** (`403`s, and
 * the pairing response's `409` [PAIRING_RESPONSE_CONSUMED]).
 *
 * **Why this exists.** Two different `403`s are reachable on the pairing-response routes and they
 * mean opposite things to the client:
 *  - [ADMISSION_REVOKED] — the **approver's** A33 capability was revoked. The device being added is
 *    fine; the one doing the adding is not.
 *  - [DEVICE_REMOVED] — F2′: the **joiner** was removed from this realm and cannot rejoin under the
 *    same identity. Terminal for that identity, and no retry of any kind will change it.
 *
 * The status code cannot separate them, so a client that only sees `403` must either guess or
 * treat a permanent refusal as a transient one. `drive-web-parity.md` W10b named that gap from the
 * browser side — the `401` has `UNAUTHORIZED_REJECTION_MARKER` and the `402` its own, and F2′ had
 * nothing.
 *
 * ⚠⚠ **These are constants in `:core-common`, not prose in two places, and that is the whole
 * point.** `docs/tech-debt.md` records a `401` detector that matched the literal
 * `"401 Unauthorized"` and was **completely inert in production**, because HTTP/2 carries no reason
 * phrase. The lesson generalises: never key client logic on text one side spells and the other
 * re-spells. `:core-common` is the lowest module both `:sync-relay-server` and every client already
 * depend on, so both sides compile against **one** definition and drift is unrepresentable.
 *
 * ⚠ **No `RELAY_PROTOCOL_VERSION` bump.** No status code, route meaning or envelope changes — the
 * body gains a prefix. A client built before this reads the body exactly as it did (the human
 * sentence still follows), so the two directions cannot misunderstand each other; see
 * [RELAY_PROTOCOL_VERSION]'s normative list.
 */
object RelayRefusal {

    /** F2′ — this identity was removed from the realm and can never rejoin as itself. */
    const val DEVICE_REMOVED: String = "device-removed"

    /** The admitting device's A33 admission capability has been revoked. */
    const val ADMISSION_REVOKED: String = "admission-revoked"

    /**
     * F7 — the `409` of `POST /sync/pairing/response`: the joiner already acknowledged a response
     * under this `pairingSecretHash`, so the hash is retired. Distinguishable from every other
     * `409` because a host **retrying its own post** must read it as delivered, not as failure
     * (`ApprovePairingRequestImplementation`): the first post landed, its reply was lost, and the
     * joiner acked before the retry.
     */
    const val PAIRING_RESPONSE_CONSUMED: String = "pairing-response-consumed"

    /**
     * Workstream B — the `400` of every route that registers a device, when the claimed `deviceId` is
     * not `deviceIdForPublicKey(key)` of the key presented with it. Terminal for that body: a client
     * that sends it is mis-deriving its id (a build from before the derivation), not racing anything.
     */
    const val DEVICE_ID_NOT_KEY_DERIVED: String = "device-id-not-key-derived"

    /**
     * Body text for a refusal: the machine token, then the sentence a human reading a log needs.
     * ⚠ The token must stay **first** — consumers match a prefix so a later sentence edit (or a
     * localisation of it, which is where this is heading) cannot break the match.
     */
    fun body(reason: String, detail: String): String = "$reason: $detail"

    /** True when [body] was produced with [reason]. The only supported way to read one. */
    fun matches(body: String?, reason: String): Boolean = body?.trimStart()?.startsWith(reason) == true
}
