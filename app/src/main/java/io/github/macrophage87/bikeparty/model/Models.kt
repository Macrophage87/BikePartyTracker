package io.github.macrophage87.bikeparty.model

/**
 * Role of a rider in the group. Each role gets a distinct marker color and
 * badge letter on the map.
 */
enum class RiderRole(val id: String, val label: String, val badge: String, val colorHex: String) {
    LEAD_LINE("lead", "Lead line", "L", "#1E88E5"),
    CORKER("corker", "Corker", "C", "#F4511E"),
    SWEEP("sweep", "Sweep", "S", "#43A047"),
    SOUNDBIKE("sound", "Soundbike", "♪", "#8E24AA"),
    PARTICIPANT("rider", "Participant", "P", "#546E7A"),
    OBSERVER("observer", "Observer", "O", "#6D4C41");

    companion object {
        fun fromId(id: String?): RiderRole = entries.firstOrNull { it.id == id } ?: PARTICIPANT
        fun fromLabel(label: String?): RiderRole = entries.firstOrNull { it.label == label } ?: PARTICIPANT
    }
}

/** Quick-share incident categories. */
enum class IncidentType(val id: String, val label: String, val emoji: String) {
    FIRST_AID("first_aid", "First aid needed", "🩹"),
    EMERGENCY_VEHICLE("emergency_vehicle", "Emergency vehicle", "🚨"),
    DETOUR("detour", "Detour required", "↪"),
    MECHANICAL("mechanical", "Mechanical problem", "🔧"),
    HAZARD("hazard", "Road hazard", "⚠"),
    REGROUP("regroup", "Stop and regroup", "🛑");

    companion object {
        fun fromId(id: String?): IncidentType = entries.firstOrNull { it.id == id } ?: HAZARD
    }
}

/** Last known state of another rider in the ride. */
data class RiderState(
    val id: String,
    val name: String,
    val role: RiderRole,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val headingDeg: Float,
    val updatedAt: Long
)

/** An active incident shared with the group. */
data class Incident(
    val id: String,
    val riderId: String,
    val riderName: String,
    val type: IncidentType,
    val lat: Double,
    val lon: Double,
    val note: String?,
    val timestamp: Long
)

/** A single point of the shared route. */
data class RoutePoint(val lat: Double, val lon: Double)
