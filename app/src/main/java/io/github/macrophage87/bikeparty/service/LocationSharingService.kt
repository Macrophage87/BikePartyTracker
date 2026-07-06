package io.github.macrophage87.bikeparty.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.macrophage87.bikeparty.BikePartyApp
import io.github.macrophage87.bikeparty.MapActivity
import io.github.macrophage87.bikeparty.R
import io.github.macrophage87.bikeparty.model.Incident
import io.github.macrophage87.bikeparty.ride.RideSession

/**
 * Foreground service that keeps location sharing (and the group connection)
 * alive while the app is minimized or the screen is off. Also raises
 * heads-up notifications when another rider shares an incident.
 */
class LocationSharingService : Service() {

    private lateinit var fused: FusedLocationProviderClient

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { RideSession.onOwnLocation(it) }
        }
    }

    private val sessionListener = object : RideSession.Listener {
        override fun onNewIncident(incident: Incident) {
            postIncidentNotification(incident)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        RideSession.addListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RideSession.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildTrackingNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun buildTrackingNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BikePartyApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_bike)
            .setContentTitle(getString(R.string.notif_sharing_title))
            .setContentText(
                getString(R.string.notif_sharing_text, RideSession.config?.rideCode ?: "")
            )
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postIncidentNotification(incident: Incident) {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        var text = getString(R.string.notif_incident_text, incident.riderName)
        RideSession.ownLocation?.let { own ->
            val dist = FloatArray(1)
            Location.distanceBetween(own.latitude, own.longitude, incident.lat, incident.lon, dist)
            text += " · " + formatDistance(dist[0])
        }
        val tapIntent = PendingIntent.getActivity(
            this, incident.id.hashCode(),
            Intent(this, MapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, BikePartyApp.CHANNEL_INCIDENTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("${incident.type.emoji} ${incident.type.label}")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        NotificationManagerCompat.from(this)
            .notify(INCIDENT_NOTIFICATION_BASE + (incident.id.hashCode() and 0xFFFF), notification)
    }

    private fun formatDistance(meters: Float): String =
        if (meters >= 1000f) String.format("%.1f km", meters / 1000f)
        else String.format("%.0f m", meters)

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        RideSession.removeListener(sessionListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val INCIDENT_NOTIFICATION_BASE = 1000
        private const val UPDATE_INTERVAL_MS = 4000L
        private const val MIN_INTERVAL_MS = 2000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, LocationSharingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
    }
}
