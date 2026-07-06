package io.github.macrophage87.bikeparty

import android.location.Location
import android.os.Looper
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ride.CryptoBox
import io.github.macrophage87.bikeparty.ride.RideSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the full report-incident flow (session state, JSON encode,
 * encryption, publish, listener dispatch) with an in-memory transport.
 */
@RunWith(RobolectricTestRunner::class)
class IncidentFlowTest {

    private lateinit var fake: () -> FakeTransport?

    @Before
    fun setUp() {
        fake = FakeTransport.install()
    }

    @After
    fun tearDown() {
        RideSession.stop()
        FakeTransport.uninstall()
    }

    private fun startSession(role: RiderRole = RiderRole.LEAD_LINE) {
        RideSession.start(
            RideSession.Config(
                riderId = "test-rider",
                riderName = "Tester",
                role = role,
                rideCode = "TEST42",
                password = "pw",
                isLeader = true,
                brokerHost = "unused",
                brokerPort = 1,
                useTls = false
            ),
            null
        )
    }

    private fun fix(lat: Double = 37.7749, lon: Double = -122.4194) =
        Location("test").apply {
            latitude = lat
            longitude = lon
        }

    @Test
    fun reportIncident_withFix_publishesEncryptedIncident() {
        startSession()
        var incidentsChanged = 0
        val listener = object : RideSession.Listener {
            override fun onIncidentsChanged() {
                incidentsChanged++
            }
        }
        RideSession.addListener(listener)

        RideSession.onOwnLocation(fix())
        val incident = RideSession.reportIncident(IncidentType.FIRST_AID, "rider down")
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(incident)
        assertEquals(1, RideSession.incidents.size)
        assertTrue(incidentsChanged >= 1)
        assertTrue(RideSession.canClear(incident!!))

        // The published payload must decrypt with the ride's secrets.
        val publish = fake()!!.published.last { it.topic.contains("/incident/") }
        assertTrue(publish.retain)
        val plain = CryptoBox("TEST42", "pw").decrypt(publish.payload)
        assertNotNull(plain)
        val json = JSONObject(String(plain!!, Charsets.UTF_8))
        assertEquals("first_aid", json.getString("k"))
        assertEquals("rider down", json.getString("txt"))
        assertEquals(37.7749, json.getDouble("la"), 1e-6)

        RideSession.clearIncident(incident.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, RideSession.incidents.size)
        // Clearing publishes a retained empty payload (retained-message delete).
        val clear = fake()!!.published.last { it.topic.contains("/incident/") }
        assertEquals(0, clear.payload.size)
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
    fun reportIncident_withNonFiniteCoordinates_returnsNullInsteadOfCrashing() {
        startSession()
        RideSession.onOwnLocation(fix(lat = Double.NaN))
        assertNull(RideSession.reportIncident(IncidentType.FIRST_AID, null))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // SDK 31+ Location setters reject NaN, but parceled locations from real
    // providers can still carry it — run on an SDK whose setters allow it.
    @Config(sdk = [28])
    @Test
    fun locationWithNonFiniteSpeed_isSanitized_notCrashing() {
        startSession()
        RideSession.onOwnLocation(fix().apply {
            speed = Float.NaN
            bearing = Float.NaN
        })
        shadowOf(Looper.getMainLooper()).idle()
        val publish = fake()!!.published.last { it.topic.contains("/loc/") }
        val json = JSONObject(
            String(CryptoBox("TEST42", "pw").decrypt(publish.payload)!!, Charsets.UTF_8)
        )
        assertEquals(0.0, json.getDouble("s"), 0.0)
        assertEquals(0.0, json.getDouble("h"), 0.0)
    }

    @Test
    fun observer_neverPublishesLocation_butCanReportIncidents() {
        startSession(role = RiderRole.OBSERVER)
        RideSession.onOwnLocation(fix())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fake()!!.published.none { it.topic.contains("/loc/") && it.payload.isNotEmpty() })

        val incident = RideSession.reportIncident(IncidentType.EMERGENCY_VEHICLE, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(incident)
    }

    @Test
    fun switchingToObserver_deletesRetainedLocation() {
        startSession(role = RiderRole.SWEEP)
        RideSession.onOwnLocation(fix())
        RideSession.setRole(RiderRole.OBSERVER)
        shadowOf(Looper.getMainLooper()).idle()
        val last = fake()!!.published.last { it.topic.contains("/loc/") }
        assertEquals(0, last.payload.size)
        RideSession.setRole(RiderRole.SWEEP)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fake()!!.published.last { it.topic.contains("/loc/") }.payload.isNotEmpty())
    }
}
