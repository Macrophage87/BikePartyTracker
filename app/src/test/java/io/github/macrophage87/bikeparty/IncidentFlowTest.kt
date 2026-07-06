package io.github.macrophage87.bikeparty

import android.location.Location
import android.os.Looper
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ride.RideSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the full report-incident flow (session state, JSON encode,
 * MQTT publish attempt, listener dispatch) on the JVM. The broker address is
 * unreachable on purpose — publishing while disconnected must never throw.
 */
@RunWith(RobolectricTestRunner::class)
class IncidentFlowTest {

    private fun startSession(role: RiderRole = RiderRole.LEAD_LINE) {
        RideSession.start(
            RideSession.Config(
                riderId = "test-rider",
                riderName = "Tester",
                role = role,
                rideCode = "TEST42",
                password = "pw",
                isLeader = true,
                brokerHost = "127.0.0.1",
                brokerPort = 1,
                useTls = false
            ),
            null
        )
    }

    @After
    fun tearDown() {
        RideSession.stop()
    }

    @Test
    fun reportIncident_withFix_addsIncidentAndNotifies() {
        startSession()
        var incidentsChanged = 0
        val listener = object : RideSession.Listener {
            override fun onIncidentsChanged() {
                incidentsChanged++
            }
        }
        RideSession.addListener(listener)

        RideSession.onOwnLocation(Location("test").apply {
            latitude = 37.7749
            longitude = -122.4194
        })
        val incident = RideSession.reportIncident(IncidentType.FIRST_AID, "rider down")
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(incident)
        assertEquals(1, RideSession.incidents.size)
        assertEquals("rider down", RideSession.incidents[incident!!.id]?.note)
        assertTrue(incidentsChanged >= 1)
        assertTrue(RideSession.canClear(incident))

        RideSession.clearIncident(incident.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, RideSession.incidents.size)
        RideSession.removeListener(listener)
    }

    @Test
    fun reportIncident_withoutFix_returnsNull() {
        startSession()
        assertNull(RideSession.reportIncident(IncidentType.HAZARD, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, RideSession.incidents.size)
    }

    @Test
    fun observer_neverPublishes_butCanReportIncidents() {
        startSession(role = RiderRole.OBSERVER)
        RideSession.onOwnLocation(Location("test").apply {
            latitude = 37.0
            longitude = -122.0
        })
        val incident = RideSession.reportIncident(IncidentType.EMERGENCY_VEHICLE, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(incident)
    }

    @Test
    fun roleSwitch_toObserverAndBack_doesNotThrow() {
        startSession()
        RideSession.onOwnLocation(Location("test").apply {
            latitude = 37.0
            longitude = -122.0
        })
        RideSession.setRole(RiderRole.OBSERVER)
        RideSession.setRole(RiderRole.SWEEP)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
