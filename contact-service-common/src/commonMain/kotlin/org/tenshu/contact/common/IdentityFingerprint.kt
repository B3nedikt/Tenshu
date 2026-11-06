package org.tenshu.contact.common

import org.tenshu.core.common.util.sha256

/**
 * Canonical fingerprint of an identity public key — `TenshuIdentity`'s key
 * field (`contact-service-identity-model.md` §4.2): full SHA-256 of the raw
 * key bytes, lowercase hex. Display surfaces shorten it to the pairing
 * `XXXXX-YYYYY-ZZZZZ` style; the stored key stays full-length so it can
 * never collide.
 */
fun identityFingerprint(publicKey: ByteArray): String = sha256(publicKey).toHexString()
