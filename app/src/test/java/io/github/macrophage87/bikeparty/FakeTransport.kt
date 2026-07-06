package io.github.macrophage87.bikeparty

import io.github.macrophage87.bikeparty.ride.RideSession
import io.github.macrophage87.bikeparty.ride.RideTransport

/**
 * In-memory transport for tests: no sockets, no netty threads (a real MQTT
 * client with auto-reconnect keeps the test JVM alive and hangs Gradle).
 */
class FakeTransport(
    private val onConnectionChanged: (Boolean) -> Unit
) : RideTransport {

    data class Published(val topic: String, val payload: ByteArray, val retain: Boolean)

    val published = mutableListOf<Published>()

    override fun connect() = onConnectionChanged(true)

    override fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        published.add(Published(topic, payload, retain))
    }

    override fun disconnect() = onConnectionChanged(false)

    companion object {
        /** Installs the fake into RideSession; returns an accessor for the instance. */
        fun install(): () -> FakeTransport? {
            var current: FakeTransport? = null
            RideSession.transportFactory = { _, _, onConnection ->
                FakeTransport(onConnection).also { current = it }
            }
            return { current }
        }

        fun uninstall() {
            RideSession.transportFactory = null
        }
    }
}
