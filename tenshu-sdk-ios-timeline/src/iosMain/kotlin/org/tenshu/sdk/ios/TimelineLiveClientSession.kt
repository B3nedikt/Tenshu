package org.tenshu.sdk.ios

import org.tenshu.epc.rendezvous.EpcRendezvousChannel

/**
 * Owns the live EPC channel for the Timeline app session — the Timeline twin of [FitLiveClientSession]
 * / [LiveClientSession]. [activate] installs the real Timeline client over the established channel (via
 * [TimelineSurface.installRealClient]) and retains it; [teardown] cancels the channel's reader and
 * restores [app.tenshu.timeline.data.MockTimelineRepository].
 *
 * **Lifetime is the app session, and nothing is persisted** (the same re-handshake-vs-persist decision
 * as Drive, `LiveClientSession` KDoc): the channel's session keys are ephemeral (X25519 ECDH per
 * handshake) and the `/sync/epc` rendezvous mailbox is one-shot, so there is nothing resumable to
 * store. The shipping path rides a Control-provided *local* channel through the shared Keychain / App
 * Group and calls [activate] with that transport instead of the relay.
 *
 * Server-push needs no separate consume scope: `RemoteBinaryTaskExecutor` (assembled inside
 * [EpcTimelineClient.assembleClient]) binds the channel's `onCallback`, so request/response and proxy
 * pushes both ride the one retained channel — teardown reduces to `channel.unbind()`.
 */
object TimelineLiveClientSession {

    private var channel: EpcRendezvousChannel? = null

    /** True while a live client is installed. */
    val isActive: Boolean get() = channel != null

    /**
     * Install the real Timeline client over [pairing]'s established channel and retain it for teardown.
     * Replaces any prior live channel (tears it down first), so re-pairing is idempotent.
     */
    fun activate(pairing: EpcRelayPairing.Channel) {
        teardown()
        channel = pairing.channel
        TimelineSurface.installRealClient(pairing.channel)
    }

    /**
     * Cancel the live channel's reader and restore the honest not-connected surface (empty reads +
     * the "Can't reach Tenshu Control" banner, never fabricated mock data). No-op when inactive.
     */
    fun teardown() {
        channel?.unbind()
        channel = null
        TimelineSurface.installNotConnected()
    }
}
