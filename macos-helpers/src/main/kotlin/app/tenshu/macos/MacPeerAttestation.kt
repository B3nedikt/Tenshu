package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Which Apple Developer Team IDs may vouch for a given code-signing identifier.
 *
 * ⚠ **The binding is `identifier → teams`, deliberately not a bare set of trusted teams.**
 * A bare set says "anyone we trust may claim any identifier", which is harmless while we are
 * the only trusted team and unsafe the moment a third-party publisher is added — they could
 * sign a binary claiming `app.tenshu.drive` and inherit Drive's standing. Keying by
 * identifier keeps each publisher confined to their own ids
 * (`desktop-ipc-client-identity.md` §5).
 *
 * An empty result means "no team may vouch for this identifier" ⇒ the peer is rejected. That
 * is also the correct answer for an identifier we simply do not know.
 *
 * ⚠ This policy must never be sourced from a runtime environment variable or a plaintext
 * config file: the gate exists to stop a hostile local process, and such a process could
 * then add its own team. The first-party anchor is compiled in; third-party publishers ride
 * user-consented, integrity-protected records.
 */
fun interface PeerTeamPolicy {
    fun acceptedTeamsFor(identifier: String): Set<String>
}

/** The outcome of attesting one connecting peer. */
sealed interface PeerAttestation {
    /**
     * The peer's running code satisfied an Apple-anchored requirement naming this identifier,
     * and its signature ties it to [teamId] — either the leaf certificate's `subject.OU` (we
     * signed it) or, for a Mac App Store / TestFlight copy Apple re-signed, the signed team
     * identifier. So [identifier] is *attested*, not merely claimed.
     */
    data class Attested(val identifier: String, val teamId: String) : PeerAttestation

    /** No identity could be established. [reason] is for the log, never for a decision. */
    data class Unattested(val reason: String) : PeerAttestation
}

/**
 * Attests the process on the other end of a Unix-domain socket by checking its **running
 * code** against an Apple code-signing *requirement*.
 *
 * ### ⚠⚠ Reading an identifier is not authentication
 *
 * macOS refuses to execute an unsigned binary, so every peer carries *a* signature — but
 * ad-hoc signing is self-service and the identifier is a free-text argument. A three-line
 * binary can be signed as `app.tenshu.drive`, report that identifier, and satisfy its own
 * designated requirement (`desktop-ipc-client-identity.md` §4.1, reproduced through this very
 * gate in §6.2). Any check that ends in a string comparison is therefore bypassed outright.
 *
 * What an attacker *cannot* self-assert is a certificate chain anchored at Apple and issued
 * to our team. So the decision is always `SecCodeCheckValidity` against a requirement pinning
 * `anchor apple generic and certificate leaf[subject.OU] = "<TEAM>"` — the identifier rides
 * along inside that requirement rather than being compared separately.
 *
 * ⚠ **Apple re-signs store copies.** A Mac App Store or TestFlight install has Apple's own leaf
 * certificate (`OU=TESTFLIGHT`, or Apple Mac OS Application Signing), so the `subject.OU` pin
 * rejects our own apps there. Those pass instead through an Apple-store-marker requirement *plus*
 * the team identifier in the Apple-covered signature — never the marker alone ([decide]).
 *
 * ### Hint, then verify
 *
 * The requirement names the identifier, so the identifier has to be known before the
 * requirement can be built. The order is therefore: read it as an **untrusted hint**, build a
 * team-pinned requirement around it, and only then check validity. That is O(1) instead of
 * testing every allowlisted requirement in turn, and it is safe precisely because the hint is
 * never itself the decision — it only selects which requirement must hold.
 *
 * This class owns the cryptography and nothing else; *policy* is [PeerTeamPolicy], supplied
 * by the caller. That keeps this module free of any Tenshu type and makes the whole path
 * testable with a fake policy.
 */
object MacPeerAttestation {

    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()
    private val handles = ConcurrentHashMap<String, MethodHandle>()

    private val ptr = ValueLayout.ADDRESS
    private val i32 = ValueLayout.JAVA_INT
    private val i64 = ValueLayout.JAVA_LONG

    /**
     * Resolve the identity of [channel]'s peer, or explain why it could not be resolved.
     *
     * Never throws: this runs on the accept path, where an exception would take down a
     * connection handler. Every failure degrades to [PeerAttestation.Unattested].
     */
    fun attest(channel: SocketChannel, policy: PeerTeamPolicy): PeerAttestation = try {
        if (!ForeignSupport.isAvailable()) {
            PeerAttestation.Unattested("foreign function access unavailable on this runtime")
        } else {
            Arena.ofConfined().use { arena -> attestWithin(arena, channel, policy) }
        }
    } catch (e: Throwable) {
        PeerAttestation.Unattested("attestation failed: ${e::class.simpleName}: ${e.message}")
    }

    private fun attestWithin(
        arena: Arena,
        channel: SocketChannel,
        policy: PeerTeamPolicy,
    ): PeerAttestation {
        val token = when (val result = PeerAuditToken.read(channel, arena)) {
            is PeerAuditToken.Result.Unavailable -> return PeerAttestation.Unattested(result.reason)
            is PeerAuditToken.Result.Token -> result.bytes
        }

        val code = guestFromAuditToken(arena, token)
            ?: return PeerAttestation.Unattested("no running code matched the peer's audit token")

        try {
            val hint = identifierOf(arena, code)
                ?: return PeerAttestation.Unattested("peer code carries no signing identifier")
            return decide(
                hint = hint,
                teams = policy.acceptedTeamsFor(hint),
                satisfies = { requirement -> satisfies(arena, code, requirement) },
                signedTeamId = { teamIdentifierOf(arena, code) },
            )
        } finally {
            release(code)
        }
    }

    /**
     * The team identifier [channel]'s peer is signed for — **unattested**, never a decision.
     * Exists so a test can prove [teamIdentifierOf]'s native plumbing against a real signed
     * peer, which [decide]'s fakes cannot.
     */
    internal fun signedTeamIdOf(channel: SocketChannel): String? = Arena.ofConfined().use { arena ->
        val token = (PeerAuditToken.read(channel, arena) as? PeerAuditToken.Result.Token)?.bytes
            ?: return null
        val code = guestFromAuditToken(arena, token) ?: return null
        try {
            teamIdentifierOf(arena, code)
        } finally {
            release(code)
        }
    }

    /**
     * The whole admission decision, with the two Security.framework calls passed in so it can be
     * tested without a signed peer.
     *
     * Two routes admit, and both pin our team:
     *  1. **We signed it** (Developer ID / Apple Development): the leaf certificate's
     *     `subject.OU` is our team, checked inside [requirementFor].
     *  2. **Apple re-signed it for the Mac App Store or TestFlight**: the leaf is Apple's own
     *     certificate (`OU=TESTFLIGHT` / Apple Mac OS Application Signing), so route 1 can never
     *     pass. [storeRequirementFor] proves the chain is Apple's store signature, and
     *     [signedTeamId] — the CodeDirectory's team identifier, which that signature covers —
     *     must then be one of [teams].
     *
     * ⚠⚠ Route 2 without the team check would admit **any** store app: the requirement language
     * has no team clause, and the identifiers of nested code (`tray-agent`) are not registered
     * with Apple, so another developer's store app could ship a helper signed with that
     * identifier (`desktop-ipc-client-identity.md` §10).
     *
     * [signedTeamId] is only consulted after the store requirement passed, so an ad-hoc binary
     * never reaches it — ad-hoc signing sets no team, and a self-asserted one would mean nothing.
     */
    internal fun decide(
        hint: String,
        teams: Set<String>,
        satisfies: (requirement: String) -> Boolean,
        signedTeamId: () -> String?,
    ): PeerAttestation {
        if (teams.isEmpty()) {
            // Deliberately echoes the hint: it is the only clue to *which* unknown app
            // connected, and at this point it is still untrusted text — hence the wording.
            return PeerAttestation.Unattested("no team may vouch for claimed identifier '$hint'")
        }

        teams.forEach { team ->
            if (satisfies(requirementFor(team, hint))) {
                return PeerAttestation.Attested(identifier = hint, teamId = team)
            }
        }

        if (satisfies(storeRequirementFor(hint))) {
            val signed = signedTeamId()
            if (signed != null && signed in teams) {
                return PeerAttestation.Attested(identifier = hint, teamId = signed)
            }
            return PeerAttestation.Unattested(
                "peer claimed '$hint' under an Apple store signature for team " +
                    "${signed ?: "<none>"}, not ${teams.joinToString("/")}",
            )
        }

        return PeerAttestation.Unattested(
            "peer claimed '$hint' but is not signed by ${teams.joinToString("/")}",
        )
    }

    /**
     * The requirement a peer must satisfy. `anchor apple generic` pins the chain to Apple,
     * `subject.OU` pins it to our team (the part an attacker cannot forge without our signing
     * key), and `identifier` binds the whole thing to the app it claims to be.
     */
    internal fun requirementFor(teamId: String, identifier: String): String =
        "anchor apple generic and certificate leaf[subject.OU] = \"$teamId\" " +
            "and identifier \"$identifier\""

    /** Leaf-certificate marker Apple puts on **Mac App Store** production signatures. */
    internal const val MAC_APP_STORE_MARKER = "1.2.840.113635.100.6.1.9"

    /** Leaf-certificate marker Apple puts on **TestFlight** beta signatures. */
    internal const val TESTFLIGHT_MARKER = "1.2.840.113635.100.6.1.25.1"

    /**
     * The requirement for a peer **Apple re-signed** for the Mac App Store or TestFlight: Apple's
     * chain, one of the two store markers, and the identifier.
     *
     * ⚠⚠ Never sufficient on its own — it names no team, so every store app passes the first
     * two clauses. [decide] pairs it with the signed team identifier.
     */
    internal fun storeRequirementFor(identifier: String): String =
        "anchor apple generic and (certificate leaf[field.$MAC_APP_STORE_MARKER] exists " +
            "or certificate leaf[field.$TESTFLIGHT_MARKER] exists) " +
            "and identifier \"$identifier\""

    // ── Security.framework ────────────────────────────────────────────────────────

    /**
     * `SecCodeCopyGuestWithAttributes(NULL, {kSecGuestAttributeAudit: <token>}, …)` — the
     * public route from an audit token to a handle on the **running** process.
     *
     * ⚠ Not `SecCodeCreateWithAuditToken`, which is not public API.
     */
    private fun guestFromAuditToken(arena: Arena, token: ByteArray): MemorySegment? {
        val dataCreate = handle("CoreFoundation", "CFDataCreate", FunctionDescriptor.of(ptr, ptr, ptr, i64))
            ?: return null
        val dictCreate = handle(
            "CoreFoundation", "CFDictionaryCreate",
            FunctionDescriptor.of(ptr, ptr, ptr, ptr, i64, ptr, ptr),
        ) ?: return null
        val copyGuest = handle(
            "Security", "SecCodeCopyGuestWithAttributes",
            FunctionDescriptor.of(i32, ptr, ptr, i32, ptr),
        ) ?: return null

        val key = ObjC.frameworkConstant("Security", "kSecGuestAttributeAudit")
        if (ObjC.isNull(key)) return null
        // The callbacks are *structs*; the dictionary needs their address, not their value —
        // unlike kSecGuestAttributeAudit above, which is a pointer-valued constant to deref.
        val keyCallbacks = symbol("CoreFoundation", "kCFTypeDictionaryKeyCallBacks") ?: return null
        val valueCallbacks = symbol("CoreFoundation", "kCFTypeDictionaryValueCallBacks") ?: return null

        val raw = arena.allocate(PeerAuditToken.SIZE.toLong())
        raw.copyFrom(MemorySegment.ofArray(token))
        val data = dataCreate.invokeExact(MemorySegment.NULL, raw, PeerAuditToken.SIZE.toLong()) as MemorySegment
        if (ObjC.isNull(data)) return null

        try {
            val keys = arena.allocate(ptr).also { it.set(ptr, 0, key) }
            val values = arena.allocate(ptr).also { it.set(ptr, 0, data) }
            val attributes = dictCreate.invokeExact(
                MemorySegment.NULL, keys, values, 1L, keyCallbacks, valueCallbacks,
            ) as MemorySegment
            if (ObjC.isNull(attributes)) return null

            try {
                val out = arena.allocate(ptr)
                val status = copyGuest.invokeExact(MemorySegment.NULL, attributes, 0, out) as Int
                if (status != 0) return null
                return out.get(ptr, 0).takeIf { !ObjC.isNull(it) }
            } finally {
                release(attributes)
            }
        } finally {
            release(data)
        }
    }

    /**
     * The identifier the peer's signature *claims*. Untrusted until
     * [satisfies] confirms a team-pinned requirement naming it.
     */
    private fun identifierOf(arena: Arena, code: MemorySegment): String? {
        val copyInfo = handle(
            "Security", "SecCodeCopySigningInformation", FunctionDescriptor.of(i32, ptr, i32, ptr),
        ) ?: return null
        val getValue = handle(
            "CoreFoundation", "CFDictionaryGetValue", FunctionDescriptor.of(ptr, ptr, ptr),
        ) ?: return null

        val out = arena.allocate(ptr)
        if (copyInfo.invokeExact(code, 0, out) as Int != 0) return null
        val info = out.get(ptr, 0)
        if (ObjC.isNull(info)) return null

        try {
            // Read the key from the framework rather than reconstructing "identifier" —
            // the dictionary compares CFString identity for these constants.
            val key = ObjC.frameworkConstant("Security", "kSecCodeInfoIdentifier")
            if (ObjC.isNull(key)) return null
            val value = getValue.invokeExact(info, key) as MemorySegment
            if (ObjC.isNull(value)) return null
            // CFString and NSString are toll-free bridged, so the ObjC bridge reads it.
            return ObjC.kotlinString(value).takeIf { it.isNotBlank() }
        } finally {
            release(info)
        }
    }

    /**
     * The team identifier in the peer's CodeDirectory (`kSecCodeInfoTeamIdentifier`).
     *
     * ⚠ Meaningful only after [satisfies] passed an Apple-anchored requirement on this same
     * code: that signature covers the CodeDirectory, which is what makes the value Apple's
     * statement rather than the peer's. Needs `kSecCSSigningInformation`; with flags 0 the key
     * is absent.
     */
    private fun teamIdentifierOf(arena: Arena, code: MemorySegment): String? {
        val copyInfo = handle(
            "Security", "SecCodeCopySigningInformation", FunctionDescriptor.of(i32, ptr, i32, ptr),
        ) ?: return null
        val getValue = handle(
            "CoreFoundation", "CFDictionaryGetValue", FunctionDescriptor.of(ptr, ptr, ptr),
        ) ?: return null

        val out = arena.allocate(ptr)
        if (copyInfo.invokeExact(code, K_SEC_CS_SIGNING_INFORMATION, out) as Int != 0) return null
        val info = out.get(ptr, 0)
        if (ObjC.isNull(info)) return null

        try {
            val key = ObjC.frameworkConstant("Security", "kSecCodeInfoTeamIdentifier")
            if (ObjC.isNull(key)) return null
            val value = getValue.invokeExact(info, key) as MemorySegment
            if (ObjC.isNull(value)) return null
            return ObjC.kotlinString(value).takeIf { it.isNotBlank() }
        } finally {
            release(info)
        }
    }

    /** `kSecCSSigningInformation` from `SecCode.h`. */
    private const val K_SEC_CS_SIGNING_INFORMATION = 1 shl 1

    /** `SecCodeCheckValidity(code, 0, requirement)` — the only step that decides anything. */
    private fun satisfies(arena: Arena, code: MemorySegment, requirement: String): Boolean {
        val createRequirement = handle(
            "Security", "SecRequirementCreateWithString", FunctionDescriptor.of(i32, ptr, i32, ptr),
        ) ?: return false
        val checkValidity = handle(
            "Security", "SecCodeCheckValidity", FunctionDescriptor.of(i32, ptr, i32, ptr),
        ) ?: return false

        val text = ObjC.nsString(requirement)
        if (ObjC.isNull(text)) return false
        val out = arena.allocate(ptr)
        if (createRequirement.invokeExact(text, 0, out) as Int != 0) return false
        val parsed = out.get(ptr, 0)
        if (ObjC.isNull(parsed)) return false

        return try {
            checkValidity.invokeExact(code, 0, parsed) as Int == 0
        } finally {
            release(parsed)
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────

    private fun handle(
        framework: String,
        symbol: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle? {
        handles[symbol]?.let { return it }
        val l = linker ?: return null
        val address = ObjC.framework(framework)?.find(symbol)?.orElse(null) ?: return null
        return l.downcallHandle(address, descriptor).also { handles[symbol] = it }
    }

    /** The *address of* an exported symbol, for constants that are structs rather than pointers. */
    private fun symbol(framework: String, name: String): MemorySegment? =
        ObjC.framework(framework)?.find(name)?.orElse(null)

    private fun release(cfObject: MemorySegment) {
        if (ObjC.isNull(cfObject)) return
        handle("CoreFoundation", "CFRelease", FunctionDescriptor.ofVoid(ptr))
            ?.invokeExact(cfObject)
    }
}
