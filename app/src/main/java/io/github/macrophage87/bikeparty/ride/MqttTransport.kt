package io.github.macrophage87.bikeparty.ride

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Transport abstraction so tests can run the ride logic without networking. */
interface RideTransport {
    fun connect()
    fun publish(topic: String, payload: ByteArray, retain: Boolean)
    fun disconnect()
}

/**
 * Thin wrapper around the HiveMQ MQTT client. Reconnects automatically and
 * re-subscribes to the ride's topic tree on every (re)connect, so riders
 * recover transparently from cell-network dropouts.
 *
 * IMPORTANT: despite its name, the HiveMQ *async* client's publish() BLOCKS
 * the calling thread while the client is (re)connecting — calling it from the
 * UI thread froze the app (ANR) whenever an incident was reported before the
 * connection was up. Every client call therefore runs on [worker]; publishes
 * queue there and flush once the connection is established.
 */
class MqttTransport(
    host: String,
    port: Int,
    useTls: Boolean,
    clientId: String,
    private val subscribeFilter: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) : RideTransport {

    private val client: Mqtt3AsyncClient

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mqtt-worker").apply { isDaemon = true }
    }

    init {
        var builder = MqttClient.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            .automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            .maxDelay(30, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener {
                subscribe()
                onConnectionChanged(true)
            }
            .addDisconnectedListener { onConnectionChanged(false) }
        if (useTls) {
            builder = builder.sslWithDefaultConfig()
        }
        client = builder.useMqttVersion3().buildAsync()
    }

    override fun connect() {
        worker.execute {
            try {
                client.connectWith()
                    .cleanSession(true)
                    .keepAlive(30)
                    .send()
                    .exceptionally { null } // automatic reconnect keeps retrying
            } catch (_: Exception) {
            }
        }
    }

    private fun subscribe() {
        client.subscribeWith()
            .topicFilter(subscribeFilter)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish -> onMessage(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
    }

    override fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        worker.execute {
            try {
                client.publishWith()
                    .topic(topic)
                    .payload(payload)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(retain)
                    .send()
            } catch (_: Exception) {
            }
        }
    }

    override fun disconnect() {
        // Let already-queued publishes (e.g. the retained "remove my marker"
        // message) flush briefly, then interrupt anything still blocked on a
        // dead connection and tear the client down off the calling thread.
        worker.shutdown()
        Thread({
            try {
                if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                    worker.shutdownNow()
                }
            } catch (_: InterruptedException) {
            }
            try {
                client.disconnect()
            } catch (_: Exception) {
            }
        }, "mqtt-disconnect").apply { isDaemon = true }.start()
    }
}
