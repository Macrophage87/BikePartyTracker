package io.github.macrophage87.bikeparty

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class BikePartyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashCapture()
        installFreezeWatchdog()

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

    /**
     * ANRs (frozen main thread) never reach the uncaught-exception handler,
     * so a sampler thread records where the main thread is stuck instead.
     * The report lands in the same file the crash dialog offers to share.
     */
    private fun installFreezeWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val responded = AtomicBoolean(false)
                mainHandler.post { responded.set(true) }
                try {
                    Thread.sleep(FREEZE_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!responded.get()) {
                    try {
                        val stack = Looper.getMainLooper().thread.stackTrace
                            .joinToString("\n") { "  at $it" }
                        crashFile(this).writeText(
                            "App froze (main thread blocked > ${FREEZE_TIMEOUT_MS / 1000}s)" +
                                " — ${Date()}\n\nMain thread was stuck at:\n$stack"
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }, "freeze-watchdog").apply { isDaemon = true }.start()
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_INCIDENTS = "incidents"
        private const val FREEZE_TIMEOUT_MS = 5000L

        fun crashFile(app: Application): File = File(app.filesDir, "last_crash.txt")
    }
}
