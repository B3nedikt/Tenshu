package org.tenshu.sdk.ios

import org.tenshu.epc.rendezvous.EpcRendezvousChannel

/**
 * Owns the live EPC channel for the app session (Tier 2 — channel lifecycle,
 * `ios-drive-live-client-handover.md` §4). [activate] installs the real client across all three
 * surfaces (files + contacts + membership, via [DriveReadSurface.installRealClient]) and retains the
 * channel; [teardown] cancels the channel's reader and restores the fakes.
 *
 * **Lifetime is the app session, and nothing is persisted (the re-handshake-vs-persist decision,
 * handover §4.2).** The channel's session keys are ephemeral (X25519 ECDH per handshake) and the
 * `/sync/epc` rendezvous mailbox is one-shot, so there is nothing resumable to store; persisting the
 * pairing secret to silently re-handshake would be a bearer-secret-at-rest regression *and* would fail
 * against a torn-down rendezvous. So:
 *  - the **dev-harness path** ([EpcPairingView]) re-pairs each launch;
 *  - the **shipping path** (proposal §11.6) does not use the relay at all — it rides a Control-provided
 *    *local* channel through the shared Keychain / App Group (Phase 6e), and would call [activate] with
 *    that local transport instead.
 *
 * Server-push needs no separate consume scope: `RemoteBinaryTaskExecutor` (assembled inside
 * [EpcDriveClient.assembleClient]) binds the channel's `onCallback`, so request/response and proxy
 * pushes both ride the one retained channel. Teardown therefore reduces to `channel.unbind()`.
 */
object LiveClientSession {

    private var channel: EpcRendezvousChannel? = null

    /** True while a live client is installed. [RootView] treats this as "connected" in the dev harness. */
    val isActive: Boolean get() = channel != null

    /**
     * Install the real client over [pairing]'s established channel and retain it for teardown. Replaces
     * any prior live channel (tears it down first), so re-pairing is idempotent.
     */
    fun activate(pairing: EpcRelayPairing.Channel) {
        teardown()
        channel = pairing.channel
        DriveReadSurface.installRealClient(pairing.channel)
    }

    /** Cancel the live channel's reader scope and restore the default fakes. No-op when inactive. */
    fun teardown() {
        channel?.unbind()
        channel = null
        DriveReadSurface.installFakes()
    }
}

/**
 * Install this channel as the app's live client once the SAS is confirmed (Tier 2) — swaps the
 * whole app (files + sharing) off the fakes. Delegates to [LiveClientSession.activate].
 *
 * ⚠ An extension rather than a member of [EpcRelayPairing.Channel] (`ios-per-app-frameworks.md`
 * §5.3): the channel is in every app's framework and this installs Drive's surfaces. Swift still
 * spells it `channel.activate()` — Kotlin/Native exports an extension on a class as an Obj-C
 * category method.
 */
fun EpcRelayPairing.Channel.activate() = LiveClientSession.activate(this)
