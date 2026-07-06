package io.github.macrophage87.bikeparty

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Date

class BikePartyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashCapture()

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

    /**
     * Writes any uncaught exception to a file so the next launch can show it
     * and let the user share it — this app has no Play-Store crash reporting.
     */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile(this).writeText(
                    "Bike Party Tracker crash — ${Date()}\n" +
                        "Thread: ${thread.name}\n\n" +
                        Log.getStackTraceString(throwable)
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(2)
        }
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_INCIDENTS = "incidents"

        fun crashFile(app: Application): File = File(app.filesDir, "last_crash.txt")
    }
}
