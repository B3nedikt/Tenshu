package app.tenshu.hub

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage as PahoMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Production [MqttClient] over Eclipse Paho v3 — chosen over HiveMQ's client
 * because it is dependency-free (HiveMQ drags Netty into a runtime that has
 * none) and unremarkable on ARM; the seam above it makes the choice cheap to
 * revisit.
 *
 * Deliberately **dumb**: no automatic reconnect (`isAutomaticReconnect` off),
 * no persistence on disk ([MemoryPersistence]) and no retry policy. Reconnect
 * with backoff plus re-subscription is [Zigbee2MqttBridge]'s job, where it is
 * observable and unit-tested; a second, invisible retry loop inside the
 * library would race it.
 *
 * ⚠ Posture (House Rule #1 / hub proposal §11): the hub is an MQTT **client**,
 * outbound-only even on the LAN. It opens a TCP connection to the broker and
 * never listens. The recommended deployment binds mosquitto to `127.0.0.1`
 * (see `docs/how-to/hub-pi-deploy.md`), so this connection does not leave the
 * node at all.
 */
class PahoMqttClient(
    private val serverUri: String,
    private val clientId: String = "tenshu-hub",
    private val username: String? = null,
    private val password: String? = null,
    /** MemoryPersistence keeps zero MQTT state on disk (we re-derive everything). */
    private val keepAliveSeconds: Int = 30,
) : MqttClient {

    private val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())

    // ⚠ `replay` is load-bearing, not a buffer-size guess: retained publishes
    // land the instant a subscription is acknowledged, which can be *before*
    // the bridge's collector has attached. With replay = 0 that inventory
    // would be dropped on the floor and the hub would sit there with no
    // devices until something changed. A small replay closes the window.
    private val inbound = MutableSharedFlow<MqttMessage>(
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val connectedState = MutableStateFlow(false)

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, uri: String?) {
                connectedState.value = true
            }

            override fun connectionLost(cause: Throwable?) {
                connectedState.value = false
            }

            override fun messageArrived(topic: String, message: PahoMessage) {
                // tryEmit + DROP_OLDEST: a broker flood must never block the
                // Paho callback thread (that would stall keep-alives).
                inbound.tryEmit(
                    MqttMessage(
                        topic = topic,
                        payload = String(message.payload, Charsets.UTF_8),
                        retained = message.isRetained,
                    ),
                )
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    override fun messages(): Flow<MqttMessage> = inbound.asSharedFlow()

    override fun connected(): StateFlow<Boolean> = connectedState.asStateFlow()

    override suspend fun connect() {
        if (client.isConnected) {
            connectedState.value = true
            return
        }
        val options = MqttConnectOptions().apply {
            isCleanSession = true // we re-derive all state from retained topics
            isAutomaticReconnect = false // the bridge owns backoff (class KDoc)
            keepAliveInterval = keepAliveSeconds
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            this@PahoMqttClient.username?.let { userName = it }
            this@PahoMqttClient.password?.let { password = it.toCharArray() }
        }
        awaitToken { listener -> client.connect(options, null, listener) }
        connectedState.value = true
    }

    override suspend fun subscribe(topicFilter: String) {
        awaitToken { listener -> client.subscribe(topicFilter, QOS_AT_LEAST_ONCE, null, listener) }
    }

    override suspend fun publish(topic: String, payload: String, retained: Boolean) {
        val message = PahoMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            qos = QOS_AT_LEAST_ONCE
            isRetained = retained
        }
        awaitToken { listener -> client.publish(topic, message, null, listener) }
    }

    override suspend fun close() {
        connectedState.value = false
        runCatching { if (client.isConnected) client.disconnect().waitForCompletion(2_000) }
        runCatching { client.close(true) }
    }

    private suspend fun awaitToken(start: (IMqttActionListener) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            val listener = object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onFailure(token: IMqttToken?, cause: Throwable?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            cause ?: IllegalStateException("MQTT operation failed"),
                        )
                    }
                }
            }
            try {
                start(listener)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }

    companion object {
        /** QoS 1: a lost `/set` is a light that did not turn on; at-least-once is right. */
        const val QOS_AT_LEAST_ONCE = 1

        const val CONNECT_TIMEOUT_SECONDS = 10

        /** `tcp://127.0.0.1:1883` — the loopback broker the deploy how-to installs. */
        const val DEFAULT_BROKER_URL = "tcp://127.0.0.1:1883"

        /**
         * A stable per-hub client id: MQTT brokers kick an existing session
         * when a second client connects with the same id, so it must be
         * unique per hub but stable across restarts.
         */
        fun clientIdFor(deviceName: String): String =
            "tenshu-hub-" + Zigbee2Mqtt.sanitiseText(deviceName, 24)
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .ifBlank { "node" }
    }
}
