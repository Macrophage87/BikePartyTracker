package io.github.macrophage87.bikeparty

import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole
import io.github.macrophage87.bikeparty.ui.MarkerFactory
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Every marker icon (all roles/incident types, incl. emoji) must draw. */
@RunWith(RobolectricTestRunner::class)
class MarkerFactoryTest {

    @Test
    fun allRiderMarkersDraw() {
        val context = RuntimeEnvironment.getApplication()
        RiderRole.entries.forEach { role ->
            listOf(true, false).forEach { isSelf ->
                listOf(true, false).forEach { stale ->
                    assertNotNull(MarkerFactory.riderMarker(context, role, isSelf, stale))
                }
            }
        }
    }

    @Test
    fun allIncidentMarkersDraw() {
        val context = RuntimeEnvironment.getApplication()
        IncidentType.entries.forEach { type ->
            assertNotNull(MarkerFactory.incidentMarker(context, type))
        }
    }
}
