package app.tenshu.hub

import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-memory broker good enough to test every [Zigbee2MqttBridge]
 * behaviour that matters — retained-message replay on subscribe, unsolicited
 * disconnects, connect failures, and what the bridge publishes — with no
 * broker process, no Docker and no testcontainers. This is the reason the
 * [MqttClient] seam exists.
 */
class FakeMqttClient(
    /** The first N [connect] calls throw, as a broker that is not up yet would. */
    private val failConnectTimes: Int = 0,
) : MqttClient {

    /** Topics the broker has stored a retained publish for; replayed on subscribe. */
    val retained = LinkedHashMap<String, String>()

    /** Everything the bridge published, in order. */
    val published = mutableListOf<Pair<String, String>>()

    val subscriptions = mutableListOf<String>()

    @Volatile
    var connectCount = 0
        private set

    // Replay like the production client (see MqttClient.messages): retained
    // publishes must survive the window before the collector attaches.
    private val inbound = MutableSharedFlow<MqttMessage>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val connectedState = MutableStateFlow(false)

    override fun messages(): Flow<MqttMessage> = inbound

    override fun connected(): StateFlow<Boolean> = connectedState

    override suspend fun connect() {
        connectCount += 1
        if (connectCount <= failConnectTimes) throw IOException("fake broker refused connection")
        connectedState.value = true
    }

    override suspend fun subscribe(topicFilter: String) {
        subscriptions += topicFilter
        // Real brokers deliver retained publishes the moment a matching
        // subscription lands — the mechanism a hub that starts *after* the
        // zigbee2mqtt sidecar relies on for its whole inventory.
        // ⚠ Each retained topic is a *separate* message, and nothing orders a consumer's
        // observation of one against another — a test that waits for one and asserts on
        // another is under-synchronised even though it passes locally. Inserting a
        // `delay(300)` here makes that failure deterministic; it is how the
        // `retainedInventoryReplaysOnSubscribeSoAHubMayStartLast` flake was pinned, and it is
        // the cheapest way to re-check any future test that consumes two retained topics.
        retained.toList().forEach { (topic, payload) ->
            inbound.emit(MqttMessage(topic, payload, retained = true))
        }
    }

    override suspend fun publish(topic: String, payload: String, retained: Boolean) {
        published += topic to payload
        if (retained) this.retained[topic] = payload
    }

    override suspend fun close() {
        connectedState.value = false
    }

    /** Push a live (non-retained) publish at the bridge. */
    suspend fun deliver(topic: String, payload: String) {
        inbound.emit(MqttMessage(topic, payload, retained = false))
    }

    /** Simulate the broker (or the sidecar's host) going away. */
    fun dropConnection() {
        connectedState.value = false
    }

    fun publishedTo(topic: String): List<String> =
        published.filter { it.first == topic }.map { it.second }
}
