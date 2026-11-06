package org.tenshu.sync.common

import org.tenshu.core.time.HybridLogicalClock

/**
 * Canonical bytes signed by a device when publishing its HLC tip on
 * `POST /sync/changes` (per `docs/proposals/sync-backend-relay-plan.md` §5.1 and
 * `docs/sync/threat-model.md` §11.3).
 *
 * `LP("tenshu-hlc-tip-v1") ‖ LP(ownerId) ‖ LP(deviceId) ‖ LP(physicalTime) ‖ LP(logicalCounter)
 * ‖ LP(hlc.deviceId)` — [lengthPrefixedSignedMessage], tagged so an A4 signature over a tip can
 * never double as any other A4-signed message (B1, 2026-10-28).
 *
 * Both the relay (which verifies the publisher's signature when accepting the tip)
 * and the receiver clients (which verify peer-tip signatures returned in the response)
 * must produce the same byte sequence — the function lives here in
 * `:sync-service-common` so the two sides cannot drift.
 *
 * The HLC's three components are serialised explicitly rather than via a Composite
 * round-trip because a binary-serialised Composite would carry framing bytes the
 * signature scheme doesn't need and that could change if the Composite layout evolves.
 * Pinning to the three primitives keeps the signed-bytes contract stable across schema
 * revisions.
 */
fun hlcTipCanonicalMessage(
    ownerId: String,
    deviceId: String,
    hlc: HybridLogicalClock
): ByteArray = lengthPrefixedSignedMessage(
    HLC_TIP_TAG,
    signedField(ownerId),
    signedField(deviceId),
    signedField(hlc.physicalTime.get()),
    signedField(hlc.logicalCounter.get()),
    signedField(hlc.deviceId.get()),
)
