package io.github.macrophage87.bikeparty.ride

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the HiveMQ MQTT client. Reconnects automatically and
 * re-subscribes to the ride's topic tree on every (re)connect, so riders
 * recover transparently from cell-network dropouts.
 */
class MqttTransport(
    host: String,
    port: Int,
    useTls: Boolean,
    clientId: String,
    private val subscribeFilter: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {

    private val client: Mqtt3AsyncClient

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

    fun connect() {
        client.connectWith()
            .cleanSession(true)
            .keepAlive(30)
            .send()
            .exceptionally { null } // automatic reconnect keeps retrying
    }

    private fun subscribe() {
        client.subscribeWith()
            .topicFilter(subscribeFilter)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish -> onMessage(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        client.publishWith()
            .topic(topic)
            .payload(payload)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .send()
    }

    fun disconnect() {
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
    }
}
