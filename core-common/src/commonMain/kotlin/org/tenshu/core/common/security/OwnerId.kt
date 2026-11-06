package org.tenshu.core.common.security

import org.tenshu.core.common.util.sha256

/**
 * Derive an owner identifier deterministically from the realm's master public key.
 *
 * The proposal in `sync-relay-api.md` §5.3 specifies `base32(blake2b-256(masterPublicKey))`.
 * The current stub uses `hex(sha256(masterPublicKey))` for KMP-portability while a real
 * blake2b implementation is missing from `:core-common`. The crucial property — that any
 * client can derive the same `ownerId` from the master public key without coordinating
 * with the relay — holds either way.
 *
 * TODO(crypto): swap to `base32(blake2b-256(...))` once a KMP blake2b is available. The
 *  swap is a one-line change here plus tests; the wire shape changes (longer hex →
 *  shorter base32) — at that point the relay's `RealmStore` is rebuilt and the migration
 *  is just "everyone re-bootstraps".
 */
fun ownerIdForMasterPublicKey(masterPublicKey: ByteArray): String =
    sha256(masterPublicKey).toHexString()
