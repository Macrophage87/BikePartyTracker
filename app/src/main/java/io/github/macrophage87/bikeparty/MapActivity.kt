package io.github.macrophage87.bikeparty

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import io.github.macrophage87.bikeparty.model.Incident
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ride.RideSession
import io.github.macrophage87.bikeparty.service.LocationSharingService
import io.github.macrophage87.bikeparty.ui.MarkerFactory
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapActivity : AppCompatActivity(), RideSession.Listener {

    private lateinit var map: MapView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnRole: Button

    private var routeOverlay: Polyline? = null
    private var selfMarker: Marker? = null
    private val riderMarkers = HashMap<String, Marker>()
    private val incidentMarkers = HashMap<String, Marker>()
    private val iconCache = HashMap<String, Drawable>()
    private var centeredOnce = false

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            updateRiderMarkers()
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RideSession.active) {
            finish()
            return
        }
        setContentView(R.layout.activity_map)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.ride_code_title, RideSession.config?.rideCode ?: "")
        toolbar.subtitle = getString(R.string.connecting)
        toolbar.inflateMenu(R.menu.menu_map)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_leave) {
                confirmLeave()
                true
            } else {
                false
            }
        }

        btnRole = findViewById(R.id.btn_role)
        updateRoleButton()
        btnRole.setOnClickListener { showRolePicker() }
        findViewById<Button>(R.id.btn_report).setOnClickListener { showReportDialog() }
        findViewById<Button>(R.id.btn_incidents).setOnClickListener { showIncidentList() }
        findViewById<FloatingActionButton>(R.id.fab_center).setOnClickListener {
            RideSession.ownLocation?.let {
                map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            } ?: Snackbar.make(map, R.string.waiting_gps, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!RideSession.active) {
            finish()
            return
        }
        RideSession.addListener(this)
        drawRoute()
        updateRiderMarkers()
        updateIncidentMarkers()
        onConnectionChanged(RideSession.connected)
        refreshHandler.post(refreshTick)
    }

    override fun onStop() {
        RideSession.removeListener(this)
        refreshHandler.removeCallbacks(refreshTick)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    // ---- RideSession.Listener (called on the main thread) ----

    override fun onRidersChanged() = updateRiderMarkers()

    override fun onRouteChanged() = drawRoute()

    override fun onIncidentsChanged() = updateIncidentMarkers()

    override fun onNewIncident(incident: Incident) {
        Snackbar.make(
            map,
            "${incident.type.emoji} ${incident.type.label} — ${incident.riderName}",
            Snackbar.LENGTH_LONG
        ).setAction(R.string.show_on_map) {
            map.controller.animateTo(GeoPoint(incident.lat, incident.lon))
        }.show()
    }

    override fun onConnectionChanged(connected: Boolean) {
        toolbar.subtitle = getString(if (connected) R.string.connected else R.string.connecting)
    }

    // ---- map drawing ----

    private fun drawRoute() {
        routeOverlay?.let { map.overlays.remove(it) }
        routeOverlay = null
        val points = RideSession.routePoints
        if (points.isEmpty()) {
            map.invalidate()
            return
        }
        val line = Polyline(map).apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = 0xE01565C0.toInt()
            outlinePaint.strokeWidth = 9f
        }
        routeOverlay = line
        map.overlays.add(0, line)
        if (!centeredOnce && RideSession.ownLocation == null) {
            map.controller.setCenter(GeoPoint(points.first().lat, points.first().lon))
        }
        map.invalidate()
    }

    private fun updateRiderMarkers() {
        // own position
        RideSession.ownLocation?.let { loc ->
            val point = GeoPoint(loc.latitude, loc.longitude)
            val marker = selfMarker ?: Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(this)
                selfMarker = this
            }
            marker.position = point
            marker.icon = roleIcon(RideSession.currentRole, isSelf = true, stale = false)
            marker.title = getString(R.string.you_label, RideSession.currentRole.label)
            if (!centeredOnce) {
                centeredOnce = true
                map.controller.setCenter(point)
            }
        }

        val now = System.currentTimeMillis()
        val live = RideSession.riders.values.associateBy { it.id }

        (riderMarkers.keys - live.keys).forEach { gone ->
            riderMarkers.remove(gone)?.let { map.overlays.remove(it) }
        }
        live.values.forEach { rider ->
            val stale = now - rider.updatedAt > STALE_AFTER_MS
            val marker = riderMarkers.getOrPut(rider.id) {
                Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(this)
                }
            }
            marker.position = GeoPoint(rider.lat, rider.lon)
            marker.icon = roleIcon(rider.role, isSelf = false, stale = stale)
            marker.title = "${rider.name} (${rider.role.label})"
            val kmh = rider.speedMps * 3.6f
            marker.snippet = String.format("%.1f km/h · %s", kmh, agoText(rider.updatedAt))
        }
        map.invalidate()
    }

    private fun updateIncidentMarkers() {
        val live = RideSession.incidents
        (incidentMarkers.keys - live.keys).forEach { gone ->
            incidentMarkers.remove(gone)?.let { map.overlays.remove(it) }
        }
        live.values.forEach { incident ->
            val marker = incidentMarkers.getOrPut(incident.id) {
                Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = MarkerFactory.incidentMarker(this@MapActivity, incident.type)
                    map.overlays.add(this)
                }
            }
            marker.position = GeoPoint(incident.lat, incident.lon)
            marker.title = "${incident.type.emoji} ${incident.type.label}"
            marker.snippet = buildString {
                append(incident.riderName).append(" · ").append(agoText(incident.timestamp))
                incident.note?.let { append("\n").append(it) }
            }
            marker.setOnMarkerClickListener { _, _ ->
                showIncidentDialog(incident.id)
                true
            }
        }
        map.invalidate()
    }

    private fun roleIcon(role: RiderRole, isSelf: Boolean, stale: Boolean): Drawable =
        iconCache.getOrPut("${role.id}|$isSelf|$stale") {
            MarkerFactory.riderMarker(this, role, isSelf, stale)
        }

    // ---- dialogs ----

    private fun showReportDialog() {
        val types = IncidentType.entries
        val labels = types.map { "${it.emoji}  ${it.label}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_incident)
            .setItems(labels) { _, which ->
                val incident = RideSession.reportIncident(types[which], null)
                if (incident == null) {
                    Snackbar.make(map, R.string.waiting_gps, Snackbar.LENGTH_LONG).show()
                } else {
                    Snackbar.make(
                        map,
                        getString(R.string.incident_shared, incident.type.label),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIncidentList() {
        val incidents = RideSession.incidents.values.sortedByDescending { it.timestamp }
        if (incidents.isEmpty()) {
            Snackbar.make(map, R.string.no_incidents, Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = incidents.map {
            "${it.type.emoji} ${it.type.label} — ${it.riderName} (${agoText(it.timestamp)})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.incidents)
            .setItems(labels) { _, which -> showIncidentDialog(incidents[which].id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIncidentDialog(id: String) {
        val incident = RideSession.incidents[id] ?: return
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("${incident.type.emoji} ${incident.type.label}")
            .setMessage(buildString {
                append(incident.riderName).append(" · ").append(agoText(incident.timestamp))
                incident.note?.let { append("\n").append(it) }
            })
            .setPositiveButton(R.string.show_on_map) { _, _ ->
                map.controller.animateTo(GeoPoint(incident.lat, incident.lon))
            }
            .setNegativeButton(R.string.cancel, null)
        if (RideSession.canClear(incident)) {
            builder.setNeutralButton(R.string.clear_incident) { _, _ ->
                RideSession.clearIncident(incident.id)
            }
        }
        builder.show()
    }

    private fun showRolePicker() {
        val roles = RiderRole.entries
        val labels = roles.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.role)
            .setItems(labels) { _, which ->
                RideSession.setRole(roles[which])
                updateRoleButton()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateRoleButton() {
        btnRole.text = RideSession.currentRole.label
    }

    private fun confirmLeave() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_ride)
            .setMessage(R.string.leave_confirm)
            .setPositiveButton(R.string.leave) { _, _ ->
                RideSession.stop()
                LocationSharingService.stop(this)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun agoText(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp) / 1000).coerceAtLeast(0)
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
    }

    companion object {
        private const val REFRESH_MS = 5000L
        private const val STALE_AFTER_MS = 30_000L
    }
}
