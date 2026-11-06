package org.tenshu.sync.common

import org.tenshu.core.security.SyncCryptoManager

/**
 * The per-realm sync state a device needs to participate in one realm — bundled
 * into a single carrier so the emit/ingest path can be made realm-aware without
 * reaching into a single-valued `SyncManager`.
 *
 * Today a `SyncManager` holds exactly one realm's state (the device's personal
 * realm). This type is the **seam** for the multi-realm-per-device runtime
 * ("SR-M", `docs/proposals/sync-multi-realm-runtime.md`): SR-M.1 will pool one
 * `RealmContext` per realm (personal + shared) in a registry, and route each
 * `SyncBlock` to its realm's keys structurally (one pipeline per context) rather
 * than via a per-block key lookup in the crypto hot path. This slice (SR-M.0)
 * only introduces the carrier + [SyncManager.currentRealmContext] — a pure
 * read-model over the existing single-realm state, no behaviour change.
 *
 * - [ownerId] identifies the realm (the relay keys mailboxes / content / JWT
 *   `iss` on it).
 * - [derivedKeys] are the realm's symmetric keys: `sharedIdentityKey` encrypts
 *   block envelopes, `sharedVolumeKey` AEAD-encrypts file content chunks.
 * - [deviceSigningKeyPair] is the device's own Ed25519 key (A4) — block + JWT
 *   signatures are the *member's* key, not the realm master key, so attribution
 *   holds across members of a shared realm.
 * - [masterKeyPair] (A1) is **null on a joiner** (a paired device that holds
 *   `derivedKeys` + `ownerId` but never the recovery-derived master keypair) and
 *   on every shared realm a device merely *belongs* to. It is non-null only on
 *   the realm's host/recovery device. Consumers that only sync never need it;
 *   consumers that sign master delegations (pairing/recovery) require it and must
 *   handle the null.
 * - [historicalDerivedKeys] is the **retained keyring** (GR-7,
 *   `shared-realm-membership-grant.md` §13.4): the `DerivedKeys` of *retired* realm
 *   keys, derived from the [org.tenshu.identity.RetainedRealmKey] rows for this
 *   realm. Removing a member rotates `Kr` → `Kr′` ([derivedKeys] holds the current
 *   `Kr′`); unchanged content stays encrypted under the old `Kr` (ADR-0017, no bulk
 *   re-encryption), so the decrypt path tries [derivedKeys] first and falls back
 *   across this list on AEAD failure. **Always empty for the personal realm** (it
 *   never rotates) and for a freshly-created/joined shared realm; populated only
 *   after a rotation re-registers the realm.
 * - [retiredKeys] carries the same keyring **with each key's retirement time**, which the
 *   ingest's retired-key rule needs (`sync-envelope-authentication.md` §3.5): a block that opens
 *   only under a retired key is accepted only if its HLC predates that retirement.
 */
class RealmContext(
    val ownerId: String,
    val deviceId: String,
    val derivedKeys: SyncCryptoManager.DerivedKeys,
    val deviceSigningKeyPair: SyncCryptoManager.KeyPair,
    val masterKeyPair: SyncCryptoManager.KeyPair?,
    val retiredKeys: List<RetiredRealmKeys> = emptyList(),
) {
    /** The retired keys alone, newest-retired first — for paths with no HLC to judge by (content). */
    val historicalDerivedKeys: List<SyncCryptoManager.DerivedKeys>
        get() = retiredKeys.map { it.keys }
}

/**
 * One retired realm key (GR-7) and when it was retired, in wall-clock ms
 * (`RetainedRealmKey.retiredAt`).
 */
class RetiredRealmKeys(
    val keys: SyncCryptoManager.DerivedKeys,
    val retiredAtMs: Long,
)
