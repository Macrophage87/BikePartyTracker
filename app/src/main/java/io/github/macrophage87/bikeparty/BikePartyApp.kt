package io.github.macrophage87.bikeparty

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration

class BikePartyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // osmdroid tile cache + required user agent for the OSM tile servers
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCIDENTS,
                getString(R.string.channel_incidents),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_INCIDENTS = "incidents"
    }
}
