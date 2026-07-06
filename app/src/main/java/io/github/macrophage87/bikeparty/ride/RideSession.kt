package io.github.macrophage87.bikeparty.ride

import android.location.Location
import android.os.Handler
import android.os.Looper
import io.github.macrophage87.bikeparty.model.Incident
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.model.RiderState
import io.github.macrophage87.bikeparty.model.RoutePoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Holds the state of the active ride and synchronizes it with the group over
 * MQTT. Locations and incidents are published as *retained* messages so
 * riders joining late immediately see the current picture; the leader's route
 * is retained the same way, which is how "leader loads the map, everyone gets
 * it" works.
 */
object RideSession {

    data class Config(
        val riderId: String,
        val riderName: String,
        val role: RiderRole,
        val rideCode: String,
        val password: String,
        val isLeader: Boolean,
        val brokerHost: String,
        val brokerPort: Int,
        val useTls: Boolean
    )

    interface Listener {
        fun onRidersChanged() {}
        fun onRouteChanged() {}
        fun onIncidentsChanged() {}
        fun onNewIncident(incident: Incident) {}
        fun onConnectionChanged(connected: Boolean) {}
    }

    @Volatile
    var active = false
        private set

    @Volatile
    var connected = false
        private set

    var config: Config? = null
        private set

    val riders = ConcurrentHashMap<String, RiderState>()
    val incidents = ConcurrentHashMap<String, Incident>()

    @Volatile
    var routeName: String? = null
        private set

    @Volatile
    var routePoints: List<RoutePoint> = emptyList()
        private set

    @Volatile
    var ownLocation: Location? = null
        private set

    @Volatile
    var currentRole: RiderRole = RiderRole.PARTICIPANT
        private set

    private var crypto: CryptoBox? = null
    private var transport: RideTransport? = null
    private var topicRoot: String = ""

    /** Test hook: replaces the real MQTT transport so tests never open sockets. */
    @Volatile
    internal var transportFactory: ((
        subscribeFilter: String,
        onMessage: (String, ByteArray) -> Unit,
        onConnectionChanged: (Boolean) -> Unit
    ) -> RideTransport)? = null
    private var pendingRoute: Pair<String?, List<RoutePoint>>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners(block: (Listener) -> Unit) {
        mainHandler.post {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach(block)
        }
    }

    fun start(cfg: Config, route: Pair<String?, List<RoutePoint>>?) {
        stop()
        config = cfg
        currentRole = cfg.role
        crypto = CryptoBox(cfg.rideCode, cfg.password)
        topicRoot = CryptoBox.topicRoot(cfg.rideCode, cfg.password)
        pendingRoute = if (cfg.isLeader) route else null
        routeName = if (cfg.isLeader) route?.first else null
        routePoints = if (cfg.isLeader) route?.second.orEmpty() else emptyList()
        riders.clear()
        incidents.clear()
        active = true

        val onConnection: (Boolean) -> Unit = { isUp ->
            connected = isUp
            if (isUp) publishPending()
            notifyListeners { it.onConnectionChanged(isUp) }
        }
        transport = (transportFactory?.invoke("$topicRoot/#", ::handleMessage, onConnection)
            ?: MqttTransport(
                host = cfg.brokerHost,
                port = cfg.brokerPort,
                useTls = cfg.useTls,
                clientId = "bpt-" + UUID.randomUUID().toString().replace("-", "").take(20),
                subscribeFilter = "$topicRoot/#",
                onMessage = ::handleMessage,
                onConnectionChanged = onConnection
            )).also { it.connect() }
    }

    fun stop() {
        val cfg = config
        if (active && cfg != null) {
            // Retained empty payload removes our marker from everyone's map.
            try {
                transport?.publish("$topicRoot/loc/${cfg.riderId}", ByteArray(0), retain = true)
            } catch (_: Exception) {
            }
        }
        transport?.disconnect()
        transport = null
        crypto = null
        active = false
        connected = false
        config = null
        riders.clear()
        incidents.clear()
        routeName = null
        routePoints = emptyList()
        pendingRoute = null
        ownLocation = null
    }

    /** Called by the foreground location service on every fix. */
    fun onOwnLocation(location: Location) {
        ownLocation = location
        // Observers are never broadcast to the group.
        if (active && connected && currentRole != RiderRole.OBSERVER) publishLocation(location)
        notifyListeners { it.onRidersChanged() }
    }

    fun setRole(role: RiderRole) {
        currentRole = role
        if (role == RiderRole.OBSERVER) {
            clearOwnRetainedLocation()
        } else {
            ownLocation?.let { if (active && connected) publishLocation(it) }
        }
        notifyListeners { it.onRidersChanged() }
    }

    /** Removes our retained position so our marker disappears from everyone's map. */
    private fun clearOwnRetainedLocation() {
        val cfg = config ?: return
        try {
            transport?.publish("$topicRoot/loc/${cfg.riderId}", ByteArray(0), retain = true)
        } catch (_: Exception) {
        }
    }

    /** Leader only: broadcast (and retain) the route for the whole group. */
    fun publishRoute(name: String?, points: List<RoutePoint>) {
        val cfg = config ?: return
        if (!cfg.isLeader) return
        routeName = name
        routePoints = points
        pendingRoute = name to points
        notifyListeners { it.onRouteChanged() }

        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONArray().put(round5(p.lat)).put(round5(p.lon)))
        }
        encryptAndPublish(
            "$topicRoot/route",
            JSONObject().put("name", name ?: "").put("pts", arr),
            retain = true
        )
    }

    /** Share an incident at the rider's current position. Returns null if we have no fix yet. */
    fun reportIncident(type: IncidentType, note: String?): Incident? {
        val cfg = config ?: return null
        val loc = ownLocation ?: return null
        // Some providers emit non-finite values; JSONObject.put(double) throws on them.
        if (!loc.latitude.isFinite() || !loc.longitude.isFinite()) return null
        val id = UUID.randomUUID().toString().take(8)
        val incident = Incident(
            id = id,
            riderId = cfg.riderId,
            riderName = cfg.riderName,
            type = type,
            lat = loc.latitude,
            lon = loc.longitude,
            note = note?.trim()?.ifEmpty { null },
            timestamp = System.currentTimeMillis()
        )
        incidents[id] = incident
        notifyListeners { it.onIncidentsChanged() }

        val json = JSONObject()
            .put("id", incident.riderId)
            .put("n", incident.riderName)
            .put("k", incident.type.id)
            .put("la", incident.lat)
            .put("lo", incident.lon)
            .put("ts", incident.timestamp)
        incident.note?.let { json.put("txt", it) }
        encryptAndPublish("$topicRoot/incident/$id", json, retain = true)
        return incident
    }

    /** Own incidents (or any incident, if leader) can be cleared for everyone. */
    fun clearIncident(id: String) {
        incidents.remove(id)
        notifyListeners { it.onIncidentsChanged() }
        try {
            transport?.publish("$topicRoot/incident/$id", ByteArray(0), retain = true)
        } catch (_: Exception) {
        }
    }

    fun canClear(incident: Incident): Boolean {
        val cfg = config ?: return false
        return cfg.isLeader || incident.riderId == cfg.riderId
    }

    private fun publishPending() {
        pendingRoute?.let { (name, pts) -> publishRoute(name, pts) }
        if (currentRole != RiderRole.OBSERVER) {
            ownLocation?.let { publishLocation(it) }
        }
    }

    private fun publishLocation(location: Location) {
        val cfg = config ?: return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        val speed = location.speed.toDouble().takeIf { it.isFinite() } ?: 0.0
        val bearing = location.bearing.toDouble().takeIf { it.isFinite() } ?: 0.0
        val json = JSONObject()
            .put("id", cfg.riderId)
            .put("n", cfg.riderName)
            .put("r", currentRole.id)
            .put("la", location.latitude)
            .put("lo", location.longitude)
            .put("s", speed)
            .put("h", bearing)
            .put("ts", System.currentTimeMillis())
        encryptAndPublish("$topicRoot/loc/${cfg.riderId}", json, retain = true)
    }

    private fun encryptAndPublish(topic: String, json: JSONObject, retain: Boolean) {
        val c = crypto ?: return
        val t = transport ?: return
        try {
            t.publish(topic, c.encrypt(json.toString().toByteArray(Charsets.UTF_8)), retain)
        } catch (_: Exception) {
        }
    }

    private fun handleMessage(topic: String, payload: ByteArray) {
        if (!topic.startsWith("$topicRoot/")) return
        val sub = topic.removePrefix("$topicRoot/")
        val cfg = config ?: return

        // Retained-message deletions arrive as empty payloads.
        if (payload.isEmpty()) {
            when {
                sub.startsWith("loc/") ->
                    riders.remove(sub.removePrefix("loc/"))
                        ?.also { notifyListeners { l -> l.onRidersChanged() } }
                sub.startsWith("incident/") ->
                    incidents.remove(sub.removePrefix("incident/"))
                        ?.also { notifyListeners { l -> l.onIncidentsChanged() } }
                sub == "route" -> {
                    routeName = null
                    routePoints = emptyList()
                    notifyListeners { it.onRouteChanged() }
                }
            }
            return
        }

        val plain = crypto?.decrypt(payload) ?: return
        try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            when {
                sub == "route" -> {
                    routeName = json.optString("name").ifEmpty { null }
                    val arr = json.getJSONArray("pts")
                    routePoints = (0 until arr.length()).map { i ->
                        val p = arr.getJSONArray(i)
                        RoutePoint(p.getDouble(0), p.getDouble(1))
                    }
                    notifyListeners { it.onRouteChanged() }
                }

                sub.startsWith("loc/") -> {
                    val id = json.getString("id")
                    if (id == cfg.riderId) return
                    val role = RiderRole.fromId(json.optString("r"))
                    if (role == RiderRole.OBSERVER) {
                        // Observers are hidden — drop any position we may still
                        // have (e.g. from before they switched role).
                        riders.remove(id)
                    } else {
                        riders[id] = RiderState(
                            id = id,
                            name = json.optString("n", "?"),
                            role = role,
                            lat = json.getDouble("la"),
                            lon = json.getDouble("lo"),
                            speedMps = json.optDouble("s", 0.0).toFloat(),
                            headingDeg = json.optDouble("h", 0.0).toFloat(),
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    notifyListeners { it.onRidersChanged() }
                }

                sub.startsWith("incident/") -> {
                    val id = sub.removePrefix("incident/")
                    val incident = Incident(
                        id = id,
                        riderId = json.optString("id"),
                        riderName = json.optString("n", "?"),
                        type = IncidentType.fromId(json.optString("k")),
                        lat = json.getDouble("la"),
                        lon = json.getDouble("lo"),
                        note = json.optString("txt").ifEmpty { null },
                        timestamp = json.optLong("ts", System.currentTimeMillis())
                    )
                    val isNew = incidents.put(id, incident) == null
                    notifyListeners { it.onIncidentsChanged() }
                    if (isNew && incident.riderId != cfg.riderId) {
                        notifyListeners { it.onNewIncident(incident) }
                    }
                }
            }
        } catch (_: Exception) {
            // Malformed message from a peer; ignore.
        }
    }

    private fun round5(v: Double) = (v * 100000.0).roundToInt() / 100000.0
}
