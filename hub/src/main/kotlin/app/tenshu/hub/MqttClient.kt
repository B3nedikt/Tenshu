package app.tenshu.hub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The hub's thin MQTT seam — deliberately the *whole* surface
 * [Zigbee2MqttBridge] needs (connect / subscribe / publish / inbound
 * messages / liveness), so all bridge logic (inventory derivation, rename
 * tracking, reconnect-with-backoff, malformed-payload tolerance) is testable
 * against [FakeMqttClient]-shaped doubles with no broker, no Docker and no
 * testcontainers.
 *
 * Production implementation: [PahoMqttClient] (Eclipse Paho v3 — pure JVM,
 * dependency-free, ARM-friendly). Nothing above this interface knows the
 * library exists; swapping it is a one-file change.
 *
 * Contract:
 *  - [messages] is **hot** and shared: collect it before [connect] so retained
 *    publishes that arrive during subscription are not missed.
 *  - [connected] is a state flow so a reconnect supervisor can await
 *    transitions without polling.
 *  - [connect] and [subscribe] **throw** on failure; the caller owns the
 *    retry/backoff policy (it is bridge behaviour, and thus tested).
 */
interface MqttClient {

    /**
     * Every inbound message on every active subscription. Hot, shared, never
     * completes. ⚠ Implementations MUST replay a short tail: a broker
     * delivers retained publishes the instant a subscription is acknowledged,
     * which can precede the collector attaching.
     */
    fun messages(): Flow<MqttMessage>

    /** Broker liveness — flips to false on an unsolicited disconnect. */
    fun connected(): StateFlow<Boolean>

    /** Establish the session. Throws on failure. Idempotent when already connected. */
    suspend fun connect()

    /** Subscribe to [topicFilter] (MQTT wildcards allowed). Throws on failure. */
    suspend fun subscribe(topicFilter: String)

    /** Publish [payload] to [topic]. Throws on failure. */
    suspend fun publish(topic: String, payload: String, retained: Boolean = false)

    /** Disconnect + release resources. Idempotent. */
    suspend fun close()
}

/**
 * One inbound MQTT message. [retained] marks the broker's stored
 * last-known-good publish — zigbee2mqtt retains `bridge/devices` and
 * `bridge/state`, which is how a hub that starts *after* the sidecar still
 * derives the full inventory (see [Zigbee2MqttBridge]).
 */
data class MqttMessage(
    val topic: String,
    val payload: String,
    val retained: Boolean = false,
)
