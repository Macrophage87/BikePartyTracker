package io.github.macrophage87.bikeparty

import android.location.Location
import android.os.Looper
import android.widget.Button
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ride.RideSession
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Boots the real MapActivity and drives the report-incident path end to end:
 * opening the report dialog, reporting, and rendering the incident marker.
 */
@RunWith(RobolectricTestRunner::class)
class MapActivityIncidentTest {

    @After
    fun tearDown() {
        RideSession.stop()
    }

    @Test
    fun reportIncident_rendersMarker_withoutCrashing() {
        RideSession.start(
            RideSession.Config(
                riderId = "test-rider",
                riderName = "Tester",
                role = RiderRole.CORKER,
                rideCode = "TEST42",
                password = "pw",
                isLeader = true,
                brokerHost = "127.0.0.1",
                brokerPort = 1,
                useTls = false
            ),
            null
        )
        RideSession.onOwnLocation(Location("test").apply {
            latitude = 37.7749
            longitude = -122.4194
        })
        shadowOf(Looper.getMainLooper()).idle()

        val controller = Robolectric.buildActivity(MapActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        // Open the report dialog (inflation must not crash) ...
        activity.findViewById<Button>(R.id.btn_report).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // ... then report and let the listener render the incident marker.
        val incident = RideSession.reportIncident(IncidentType.MECHANICAL, null)
        assertNotNull(incident)
        shadowOf(Looper.getMainLooper()).idle()

        // Incident list dialog must also render.
        activity.findViewById<Button>(R.id.btn_incidents).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        controller.pause().stop().destroy()
    }
}
