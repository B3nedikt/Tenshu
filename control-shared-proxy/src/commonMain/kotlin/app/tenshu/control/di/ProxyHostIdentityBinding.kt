package app.tenshu.control.di

import org.tenshu.core.common.schema.ProxyHostIdentity
import org.tenshu.sync.RuntimeIdentity

/**
 * The [ProxyHostIdentity] every Control proxy module binds — one helper so the three platforms
 * cannot disagree about what "unknown" looks like on the wire.
 *
 * ⚠⚠ [RuntimeIdentity.UNKNOWN_VERSION] (the literal `"unknown"`) is mapped to `""`. The announce
 * reply's contract is that an **empty** identity means unknown and must never be rendered; the
 * `RuntimeIdentity` sentinel is a word, and a client would print "Update Control (unknown)".
 * Desktop always has no version, and Android/iOS fall back to the same sentinel on failure.
 */
fun controlProxyHostIdentity(hostAppId: String?, hostAppVersion: String): ProxyHostIdentity =
    ProxyHostIdentity(
        appId = hostAppId?.takeIf { it.isNotBlank() } ?: RuntimeIdentity.CONTROL_APP_ID,
        appVersion = hostAppVersion.trim().takeUnless { it == RuntimeIdentity.UNKNOWN_VERSION }.orEmpty(),
    )
